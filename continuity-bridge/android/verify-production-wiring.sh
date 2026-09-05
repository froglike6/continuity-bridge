#!/bin/sh
set -eu
CLASSES=${1:-continuity-bridge/android/build/android/classes}
JAVAP="/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/javap"
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
echo 'PRODUCTION_WIRING_OK service=BridgeService engine=BridgeEngine durable=BridgeRepository clipboard=ClipboardCaptureController applier=AndroidClipboardApplier notification=NotificationMirrorService cancellation=BridgeEngine.cancel'
