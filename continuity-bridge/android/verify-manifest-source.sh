#!/bin/sh
set -eu
MANIFEST=${1:-continuity-bridge/android/app/src/main/AndroidManifest.xml}
PYTHON_BIN=${PYTHON3:-/usr/bin/python3}

command -v "$PYTHON_BIN" >/dev/null 2>&1 || { echo 'MANIFEST_XML_PARSER_MISSING' >&2; exit 1; }

"$PYTHON_BIN" - "$MANIFEST" <<'PY'
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

ANDROID_NS = "http://schemas.android.com/apk/res/android"
MAX_MANIFEST_BYTES = 1_048_576
EXPECTED_PERMISSIONS = (
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_REMOTE_MESSAGING",
    "android.permission.INTERNET",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.QUERY_ALL_PACKAGES",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.RECEIVE_BOOT_COMPLETED",
    "android.permission.WRITE_SECURE_SETTINGS",
)
EXPECTED_ACTIVITIES = (".MainActivity", ".NotificationAppsActivity", ".HelperSetupActivity")
EXPECTED_SERVICES = (".BridgeService", ".NotificationMirrorService")
NLS_PERMISSION = "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
NLS_ACTION = "android.service.notification.NotificationListenerService"
MAIN_ACTION = "android.intent.action.MAIN"
LAUNCHER_CATEGORY = "android.intent.category.LAUNCHER"


def fail(marker):
    print(marker, file=sys.stderr)
    raise SystemExit(1)


def android_attr(element, name):
    return element.attrib.get(f"{{{ANDROID_NS}}}{name}")


def direct_children(element, name):
    return [child for child in list(element) if child.tag == name]


def require_children(element, allowed, marker):
    children = list(element)
    for child in children:
        if child.tag not in allowed:
            fail(f"{marker}={child.tag}")
    return children


def require_no_children(element, marker):
    children = list(element)
    if children:
        fail(f"{marker}={children[0].tag}")


def exact_values(
    values,
    expected,
    duplicate_marker,
    missing_marker,
    unexpected_marker,
):
    counts = Counter(values)
    for value, count in counts.items():
        if value is not None and count > 1:
            fail(f"{duplicate_marker}={value}")
    expected_set = set(expected)
    actual_set = {value for value in values if value is not None}
    missing = sorted(expected_set - actual_set)
    if missing:
        fail(f"{missing_marker}={missing[0]}")
    unexpected = sorted(actual_set - expected_set)
    if unexpected:
        fail(f"{unexpected_marker}={unexpected[0]}")
    if len(values) != len(expected):
        fail(f"{unexpected_marker}=count")


manifest_path = Path(sys.argv[1])
try:
    manifest_bytes = manifest_path.read_bytes()
except OSError as error:
    fail(f"MANIFEST_XML_READ_ERROR={error.__class__.__name__}")

if len(manifest_bytes) > MAX_MANIFEST_BYTES:
    fail("MANIFEST_XML_TOO_LARGE")

upper_manifest = manifest_bytes.upper()
if b"<!DOCTYPE" in upper_manifest:
    fail("MANIFEST_XML_DOCTYPE_FORBIDDEN")
if b"<!ENTITY" in upper_manifest:
    fail("MANIFEST_XML_ENTITY_FORBIDDEN")

try:
    root = ET.fromstring(manifest_bytes)
except ET.ParseError as error:
    fail(f"MANIFEST_XML_PARSE_ERROR={error.__class__.__name__}")

for element in root.iter():
    if not isinstance(element.tag, str):
        fail("MANIFEST_ELEMENT_TAG_INVALID")
    if element.tag.startswith("{"):
        fail(f"MANIFEST_ELEMENT_NAMESPACE_FORBIDDEN={element.tag}")

if root.tag != "manifest":
    fail("MANIFEST_ROOT_MISSING")
if root.attrib.get("package") != "com.froglike6.continuitybridge":
    fail("MANIFEST_PACKAGE_MISMATCH")

require_children(root, {"uses-permission", "application"}, "MANIFEST_ROOT_CHILD_UNEXPECTED")
applications = direct_children(root, "application")
if len(applications) != 1:
    fail("MANIFEST_APPLICATION_COUNT_MISMATCH")
application = applications[0]
require_children(application, {"activity", "service", "provider", "receiver"}, "MANIFEST_APPLICATION_CHILD_UNEXPECTED")

for attribute, expected, marker in (
    ("allowBackup", "false", "MANIFEST_APPLICATION_ALLOW_BACKUP"),
    ("usesCleartextTraffic", "false", "MANIFEST_APPLICATION_CLEARTEXT_TRAFFIC"),
    ("label", "연속성 브리지", "MANIFEST_APPLICATION_LABEL"),
    ("theme", "@android:style/Theme.Material.Settings", "MANIFEST_APPLICATION_THEME"),
):
    actual = android_attr(application, attribute)
    if actual != expected:
        fail(f"{marker}_MISSING" if actual is None else f"{marker}_MISMATCH")

for element in root.iter():
    class_name = android_attr(element, "name") or ""
    if "AccessibilityService" in class_name:
        fail("MANIFEST_FORBIDDEN_ACCESSIBILITY_SERVICE")
    if "InputMethodService" in class_name:
        fail("MANIFEST_FORBIDDEN_INPUT_METHOD_SERVICE")

providers = direct_children(application, "provider")
if len(providers) != 2:
    fail("MANIFEST_PROVIDER_COUNT_MISMATCH")
expected_providers = {
    ".ClipboardImageProvider": {
        "name": ".ClipboardImageProvider",
        "authorities": "com.froglike6.continuitybridge.clipboard.images",
        "exported": "false",
        "grantUriPermissions": "true",
    },
    ".ClipboardHelperProvider": {
        "name": ".ClipboardHelperProvider",
        "authorities": "com.froglike6.continuitybridge.clipboard.helper",
        "exported": "true",
        "enabled": "true",
        "multiprocess": "false",
        "permission": "android.permission.INTERACT_ACROSS_USERS_FULL",
    },
}
if Counter(android_attr(provider, "name") for provider in providers) != Counter(expected_providers.keys()):
    fail("MANIFEST_PROVIDER_NAMES_MISMATCH")
for provider in providers:
    provider_name = android_attr(provider, "name")
    expected_attributes = {f"{{{ANDROID_NS}}}{key}": value for key, value in expected_providers[provider_name].items()}
    if provider.attrib != expected_attributes:
        fail(f"MANIFEST_PROVIDER_ATTRIBUTES_MISMATCH={provider_name}")
    require_no_children(provider, "MANIFEST_PROVIDER_CHILD_UNEXPECTED")

receivers = direct_children(application, "receiver")
if len(receivers) != 1 or android_attr(receivers[0], "name") != ".HelperBootReceiver":
    fail("MANIFEST_BOOT_RECEIVER_MISMATCH")
if android_attr(receivers[0], "exported") != "false":
    fail("MANIFEST_BOOT_RECEIVER_EXPORTED")
require_children(receivers[0], {"intent-filter"}, "MANIFEST_BOOT_RECEIVER_CHILD_UNEXPECTED")
boot_filters = direct_children(receivers[0], "intent-filter")
if len(boot_filters) != 1:
    fail("MANIFEST_BOOT_FILTER_MISMATCH")
require_children(boot_filters[0], {"action"}, "MANIFEST_BOOT_FILTER_CHILD_UNEXPECTED")
if Counter(android_attr(action, "name") for action in direct_children(boot_filters[0], "action")) != Counter([
    "android.intent.action.BOOT_COMPLETED", "android.intent.action.MY_PACKAGE_REPLACED"
]):
    fail("MANIFEST_BOOT_ACTION_MISMATCH")

permissions = [android_attr(child, "name") for child in direct_children(root, "uses-permission")]
exact_values(
    permissions,
    EXPECTED_PERMISSIONS,
    "MANIFEST_DUPLICATE_PERMISSION",
    "MANIFEST_PERMISSION_MISSING",
    "MANIFEST_PERMISSION_UNEXPECTED",
)

activities = [android_attr(child, "name") for child in direct_children(application, "activity")]
exact_values(
    activities,
    EXPECTED_ACTIVITIES,
    "MANIFEST_DUPLICATE_ACTIVITY",
    "MANIFEST_ACTIVITY_MISSING",
    "MANIFEST_ACTIVITY_UNEXPECTED",
)

service_elements = direct_children(application, "service")
services = [android_attr(child, "name") for child in service_elements]
exact_values(
    services,
    EXPECTED_SERVICES,
    "MANIFEST_DUPLICATE_SERVICE",
    "MANIFEST_SERVICE_MISSING",
    "MANIFEST_SERVICE_UNEXPECTED",
)

services_by_name = {android_attr(service, "name"): service for service in service_elements}
bridge_service = services_by_name[".BridgeService"]
mirror_service = services_by_name[".NotificationMirrorService"]

for service in service_elements:
    service_name = android_attr(service, "name")
    if android_attr(service, "foregroundServiceType") == "remoteMessaging" and service_name != ".BridgeService":
        fail("MANIFEST_REMOTE_MESSAGING_FGS_WRONG_SERVICE")
    if android_attr(service, "permission") == NLS_PERMISSION and service_name != ".NotificationMirrorService":
        fail("MANIFEST_NOTIFICATION_LISTENER_PERMISSION_WRONG_SERVICE")

fgs_type = android_attr(bridge_service, "foregroundServiceType")
if fgs_type != "remoteMessaging":
    fail("MANIFEST_REMOTE_MESSAGING_FGS_MISSING" if fgs_type is None else "MANIFEST_REMOTE_MESSAGING_FGS_MISMATCH")
require_no_children(bridge_service, "MANIFEST_BRIDGE_SERVICE_CHILD_UNEXPECTED")

if android_attr(mirror_service, "permission") != NLS_PERMISSION:
    fail("MANIFEST_NOTIFICATION_LISTENER_PERMISSION_MISSING")

for activity in direct_children(application, "activity"):
    activity_name = android_attr(activity, "name")
    if activity_name in (".NotificationAppsActivity", ".HelperSetupActivity"):
        require_no_children(activity, "MANIFEST_NOTIFICATION_APPS_CHILD_UNEXPECTED")
        if android_attr(activity, "exported") != "false":
            fail("MANIFEST_NOTIFICATION_APPS_EXPORTED")
    elif activity_name == ".MainActivity":
        require_children(activity, {"intent-filter"}, "MANIFEST_MAIN_ACTIVITY_CHILD_UNEXPECTED")
        filters = direct_children(activity, "intent-filter")
        if len(filters) != 1:
            fail("MANIFEST_MAIN_LAUNCHER_FILTER_COUNT_MISMATCH")
        main_filter = filters[0]
        require_children(main_filter, {"action", "category"}, "MANIFEST_MAIN_FILTER_CHILD_UNEXPECTED")
        actions = [android_attr(action, "name") for action in direct_children(main_filter, "action")]
        categories = [android_attr(category, "name") for category in direct_children(main_filter, "category")]
        if Counter(actions) != Counter([MAIN_ACTION]):
            fail("MANIFEST_MAIN_ACTION_MISMATCH")
        if Counter(categories) != Counter([LAUNCHER_CATEGORY]):
            fail("MANIFEST_MAIN_CATEGORY_MISMATCH")

require_children(mirror_service, {"intent-filter"}, "MANIFEST_NOTIFICATION_LISTENER_SERVICE_CHILD_UNEXPECTED")
mirror_filters = direct_children(mirror_service, "intent-filter")
if len(mirror_filters) != 1:
    fail("MANIFEST_NOTIFICATION_LISTENER_FILTER_COUNT_MISMATCH")
mirror_filter = mirror_filters[0]
require_children(mirror_filter, {"action"}, "MANIFEST_NOTIFICATION_LISTENER_FILTER_CHILD_UNEXPECTED")
mirror_actions = [android_attr(action, "name") for action in direct_children(mirror_filter, "action")]

if Counter(mirror_actions) == Counter([NLS_ACTION]):
    pass
elif mirror_actions.count(NLS_ACTION) > 1:
    fail("MANIFEST_NOTIFICATION_LISTENER_ACTION_DUPLICATE")
elif NLS_ACTION not in mirror_actions:
    fail("MANIFEST_NOTIFICATION_LISTENER_ACTION_MISSING")
else:
    unexpected_actions = sorted(action for action in mirror_actions if action != NLS_ACTION)
    fail(f"MANIFEST_NOTIFICATION_LISTENER_ACTION_UNEXPECTED={unexpected_actions[0]}")

parent_by_id = {id(child): parent for parent in root.iter() for child in list(parent)}
for action in root.iter():
    if action.tag != "action" or android_attr(action, "name") != NLS_ACTION:
        continue
    parent = parent_by_id.get(id(action))
    grandparent = parent_by_id.get(id(parent)) if parent is not None else None
    if (
        parent is None
        or parent.tag != "intent-filter"
        or grandparent is not mirror_service
    ):
        fail("MANIFEST_NOTIFICATION_LISTENER_ACTION_WRONG_NODE")

print("MANIFEST_SOURCE_OK permissions=8 activities=3 services=2 image_provider=1 helper_provider=1 boot_receiver=1 overlay=0 notification_listener=1")
PY
