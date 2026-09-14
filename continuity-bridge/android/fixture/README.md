# Notification and clipboard fixtures

`../build-fixture.sh` builds two separate test apps. Both APKs are debuggable and contain fixture code only.

| APK in `outputs/` | Package | App identity |
| --- | --- | --- |
| `continuity-fixture-debug.apk` | `com.froglike6.continuityfixture` | Bridge Test Chat, gold chat icon |
| `continuity-download-fixture-debug.apk` | `com.froglike6.continuityfixture.downloads` | Bridge Test Downloads, blue download icon |

The chat app also provides the image clipboard fixture. The download app does not register its image provider or image activity.

## Image provider timeout scenario

`ClipboardImageFixtureActivity` accepts `image_action=copy` and `image_format=png`, `jpeg`, `oversize`, `invalid`, `dimensions`, or `stall`. The `stall` case creates a valid 256×128 PNG with readable MIME type and size metadata, then delays every read-only `openFile` call for 30 seconds before returning the file. It tests transfer timeout and recovery separately from invalid image validation. Logcat emits the synthetic `stall_open_started` and `stall_open_finished` phases under `ContinuityImageFixture`; the provider does not log image data. The provider remains unexported and requires a granted content URI.

```sh
adb -s emulator-5554 shell am start \
  -n com.froglike6.continuityfixture/.ClipboardImageFixtureActivity \
  --es image_action copy --es image_format stall
```

After the consumer times out, copy a fresh text token or another valid image to verify recovery. The stalled provider call still finishes after its fixed delay; the fixture does not claim that cancellation interrupts a remote provider. Use a new activity instance for each intent-driven image trial.

Use an explicitly selected AVD. Grant `android.permission.POST_NOTIFICATIONS` to each installed fixture package before posting. The exported notification receiver has no intent filter and is invoked by its exact component. Posting works without opening an activity, including while the AVD screen is locked after boot.

```sh
adb -s emulator-5554 shell am broadcast \
  -n com.froglike6.continuityfixture/com.froglike6.continuityfixture.NotificationFixtureReceiver \
  -a com.froglike6.continuityfixture.POST_NOTIFICATION \
  --es mode messaging --ei id 7401 --es token CHAT_TRIAL_1

adb -s emulator-5554 shell am broadcast \
  -n com.froglike6.continuityfixture.downloads/com.froglike6.continuityfixture.NotificationFixtureReceiver \
  -a com.froglike6.continuityfixture.POST_NOTIFICATION \
  --es mode progress --ei progress 37 --ei id 7402 --es token DOWNLOAD_TRIAL_1
```

The notification tag is `notification-fixture`. Repeat the same package and `id` to update one notification. Use distinct IDs for independent notices.

| `mode` | Delivered source notification |
| --- | --- |
| `ordinary` | Ordinary title and text |
| `private` | Readable body with `VISIBILITY_PRIVATE` |
| `messaging` | Group `MessagingStyle`, two named senders |
| `inbox` | Two `EXTRA_TEXT_LINES` |
| `empty-bigtext` | Meaningful normal body, empty expanded body |
| `indeterminate` | Ongoing indeterminate progress |
| `progress` | Ongoing progress, `--ei progress` in 0–100, default 37 |
| `progress-text` | Ongoing text containing 37%, without a progress bar |
| `complete` | Progress 100/100, ongoing cleared |
| `failure` | Explicit `CATEGORY_ERROR`, ongoing cleared |
| `redacted` | Framework localized `redacted_notification_message` body |
| `redacted-messaging` | Framework marker as one empty-sender message |
| `remove` | Cancel only the chosen fixture tag/ID |

`redacted` modes are synthetic delivered-data fixtures. They verify recognition of the framework marker; they do not prove that Android or an OEM actually redacted a source notification. A framework without that resource returns a cancelled broadcast result instead of inventing a marker. Optional `token` prefixes non-redacted body text for correlation. Logcat records only the scenario and notification ID.

For progress replacement, send `indeterminate`, `progress` at several values, then `complete` using the same package and ID. Run a separate ID through `progress` then `failure`. Run `remove` separately; removal alone is not evidence that work succeeded.

## Quiet and persistent notification scenarios

These additional modes use stable channels separate from the original `notification_capture_scenarios` channel. All earlier modes retain their original channel and behavior. Channel importance is assigned through `NotificationChannel`, so the notification listener receives Android's real ranking importance.

| `mode` | Delivered source notification | Channel ID | Initial importance |
| --- | --- | --- | --- |
| `charging-percent` | Ongoing `CATEGORY_STATUS`; charging text with a percentage and no progress extras | `notification_charging_status_scenarios` | DEFAULT (3) |
| `charging-progress` | Same charging state with an explicit progress bar | `notification_charging_status_scenarios` | DEFAULT (3) |
| `background-service` | Ongoing `CATEGORY_SERVICE`; persistent service text | `notification_background_status_scenarios` | DEFAULT (3) |
| `background-status` | Ongoing `CATEGORY_STATUS`; persistent connection text | `notification_background_status_scenarios` | DEFAULT (3) |
| `quiet-low` | Ordinary non-ongoing notice without a category or progress | `notification_quiet_low_scenarios` | LOW (2) |
| `quiet-minimal` | Non-ongoing `CATEGORY_STATUS`; passive status text | `notification_quiet_minimal_scenarios` | MIN (1) |
| `download-low-progress` | Ongoing `CATEGORY_PROGRESS` with explicit progress, default 37/100 | `notification_download_low_scenarios` | LOW (2) |
| `download-low-complete` | `CATEGORY_PROGRESS`, 100/100, ongoing cleared | `notification_download_low_scenarios` | LOW (2) |
| `download-low-failure` | `CATEGORY_ERROR`, no progress, ongoing cleared | `notification_download_low_scenarios` | LOW (2) |

The charging modes accept `--ei progress` in 0–100, default 68. `download-low-progress` accepts the same extra, default 37. All modes use the existing `id` and optional `token` arguments. Charging and persistent-service cases are synthetic notifications; they do not start a foreground service or change battery state. Android retains user changes to existing channels, so record the delivered ranking importance when evaluating filtering.

Use a fresh ID for charging or a quiet notice. For the LOW download lifecycle, send `download-low-progress` at several values followed by `download-low-complete` with the same package, ID, and token. Use another ID for `download-low-progress` followed by `download-low-failure`. These lifecycle modes share a LOW channel and replace the same notification key.

```sh
adb -s emulator-5554 shell am broadcast \
  -n com.froglike6.continuityfixture/com.froglike6.continuityfixture.NotificationFixtureReceiver \
  -a com.froglike6.continuityfixture.POST_NOTIFICATION \
  --es mode charging-percent --ei progress 68 --ei id 7501 --es token QUIET_TRIAL_1

adb -s emulator-5554 shell am broadcast \
  -n com.froglike6.continuityfixture.downloads/com.froglike6.continuityfixture.NotificationFixtureReceiver \
  -a com.froglike6.continuityfixture.POST_NOTIFICATION \
  --es mode download-low-progress --ei progress 37 --ei id 7502 --es token LOW_DOWNLOAD_TRIAL_1

adb -s emulator-5554 shell am broadcast \
  -n com.froglike6.continuityfixture.downloads/com.froglike6.continuityfixture.NotificationFixtureReceiver \
  -a com.froglike6.continuityfixture.POST_NOTIFICATION \
  --es mode download-low-complete --ei id 7502 --es token LOW_DOWNLOAD_TRIAL_1
```

Extractor regression coverage lives in `../instrumentation-test/com/froglike6/continuitybridge/NotificationExtractionInstrumentationTest.java`: English/Korean charging percentages and charging progress extras must not become task progress, while text-only and explicit download progress must survive extraction. Build production Android classes before `../build-tokenstore-test.sh`, install the production and instrumentation APKs on the selected AVD, then run:

```sh
adb -s emulator-5554 shell am instrument -w \
  -e class com.froglike6.continuitybridge.NotificationExtractionInstrumentationTest \
  com.froglike6.continuitybridge.tokenstoretest/android.test.InstrumentationTestRunner
```

The full class includes framework-redaction tests that require Android's `redacted_notification_message` resource; select a QA AVD that supplies it. Extractor tests construct notifications directly; channel filtering requires posting the fixtures through an enabled notification listener connected to the isolated relay.
