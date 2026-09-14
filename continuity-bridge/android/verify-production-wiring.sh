#!/bin/sh
set -eu
CLASSES=${1:-continuity-bridge/android/build/shizuku/classes}
ANDROID_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$ANDROID_DIR/toolchain.sh"
continuity_java
SERVICE=com.froglike6.continuitybridge.BridgeService
DUMP=$("$JAVAP" -classpath "$CLASSES" -c -p "$SERVICE")
for required in 'BridgeEngine.step' 'BridgeEngine.cancel' 'BridgeRepository.get' 'AndroidClipboardApplier."<init>"' 'ClipboardCaptureController.start' 'ConnectionOwner.start' 'RelayTransport."<init>"'; do
    printf '%s\n' "$DUMP" | grep -Fq "$required" || { echo "SERVICE_WIRING_MISSING=$required" >&2; exit 1; }
done
if printf '%s\n' "$DUMP" | grep -Eq 'RelayTransport\.(poll|publish|acknowledge)'; then
    echo 'SERVICE_BYPASSES_ENGINE' >&2; exit 1
fi
APPLIER=$("$JAVAP" -classpath "$CLASSES" -c -p com.froglike6.continuitybridge.AndroidClipboardApplier)
printf '%s\n' "$APPLIER" | grep -Fq 'ClipboardManager.setPrimaryClip' || { echo 'APPLIER_WIRING_MISSING=setPrimaryClip' >&2; exit 1; }
printf '%s\n' "$APPLIER" | grep -Fq 'ClipboardApplyTransaction.apply' || { echo 'APPLIER_WIRING_MISSING=ClipboardApplyTransaction.apply' >&2; exit 1; }
printf '%s\n' "$APPLIER" | grep -Fq 'ShizukuClipboardClient.read' || { echo 'APPLIER_WIRING_MISSING=ShizukuClipboardClient.read' >&2; exit 1; }
CAPTURE=$("$JAVAP" -classpath "$CLASSES" -c -p com.froglike6.continuitybridge.ClipboardCaptureController)
printf '%s\n' "$CAPTURE" | grep -Fq 'ShizukuClipboardClient.start' || { echo 'CAPTURE_WIRING_MISSING=ShizukuClipboardClient.start' >&2; exit 1; }
printf '%s\n' "$CAPTURE" | grep -Fq 'ShizukuClipboardClient.stop' || { echo 'CAPTURE_WIRING_MISSING=ShizukuClipboardClient.stop' >&2; exit 1; }
for adapter in AndroidClipboardApplier ClipboardCaptureController ShizukuClipboard; do
    find "$CLASSES/com/froglike6/continuitybridge" -name "$adapter*.class" -print | while IFS= read -r class_file; do
        class_name=$(basename "$class_file" .class)
        ADAPTER_DUMP=$("$JAVAP" -classpath "$CLASSES" -c -p "com.froglike6.continuitybridge.$class_name")
        if printf '%s\n' "$ADAPTER_DUMP" | grep -Eq 'ClipboardOverlayActivity|android/view/WindowManager|\.startActivity|java/lang/ProcessBuilder|java/lang/Runtime.exec|String logcat'; then
            echo "CLIPBOARD_FORBIDDEN_BYTECODE=$class_name" >&2; exit 1
        fi
    done
done
TRANSACTION=$("$JAVAP" -classpath "$CLASSES" -c -p com.froglike6.continuitybridge.ClipboardApplyTransaction)
printf '%s\n' "$TRANSACTION" | grep -Fq 'BridgeState.markRemoteApply' || { echo 'APPLIER_WIRING_MISSING=markRemoteApply' >&2; exit 1; }
MIRROR=$("$JAVAP" -classpath "$CLASSES" -c -p com.froglike6.continuitybridge.NotificationMirrorService)
printf '%s\n' "$MIRROR" | grep -Fq 'DurableOutbox.enqueueNotification' || { echo 'MIRROR_WIRING_MISSING=enqueueNotification' >&2; exit 1; }
REPOSITORY=$("$JAVAP" -classpath "$CLASSES" -c -p com.froglike6.continuitybridge.BridgeRepository)
printf '%s\n' "$REPOSITORY" | grep -Fq 'AndroidKeystoreStateCipher."<init>"' || { echo 'STATE_CIPHER_WIRING_MISSING=AndroidKeystoreStateCipher' >&2; exit 1; }
printf '%s\n' "$REPOSITORY" | grep -Fq 'FileBridgeStateStore.create' || { echo 'STATE_STORE_WIRING_MISSING=FileBridgeStateStore.create' >&2; exit 1; }
ATOMIC=$("$JAVAP" -classpath "$CLASSES" -s -p com.froglike6.continuitybridge.AtomicStateFile)
printf '%s\n' "$ATOMIC" | grep -Fq '(Ljava/nio/file/Path;Lcom/froglike6/continuitybridge/StateCipher;)V' || { echo 'STATE_CIPHER_CONSTRUCTOR_MISSING' >&2; exit 1; }
if printf '%s\n' "$ATOMIC" | grep -Fq 'AtomicStateFile(java.nio.file.Path);'; then
    echo 'PLAINTEXT_STATE_CONSTRUCTOR_PRESENT' >&2; exit 1
fi
echo 'PRODUCTION_WIRING_OK service=BridgeService engine=BridgeEngine durable=BridgeRepository clipboard=ShizukuClipboardClient applier=AndroidClipboardApplier notification=NotificationMirrorService cancellation=BridgeEngine.cancel focus_fallback=absent'
