#!/bin/sh
continuity_tool_error() { echo "ANDROID_TOOLCHAIN_ERROR: $*" >&2; exit 1; }

continuity_java() {
    if test -z "${JAVA_HOME:-}"; then
        if test -x '/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/javac'; then
            JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
        elif test -x /usr/libexec/java_home; then
            JAVA_HOME=$(/usr/libexec/java_home 2>/dev/null) || continuity_tool_error 'Set JAVA_HOME to a JDK (17 or newer).'
        else
            continuity_tool_error 'Set JAVA_HOME to a JDK (17 or newer).'
        fi
    fi
    for continuity_java_tool in java javac javap keytool; do
        test -x "$JAVA_HOME/bin/$continuity_java_tool" || continuity_tool_error "Missing JDK tool: $JAVA_HOME/bin/$continuity_java_tool"
    done
    JAVAP="$JAVA_HOME/bin/javap"
    export JAVA_HOME
}

continuity_android_sdk() {
    CONTINUITY_SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    if test -z "$CONTINUITY_SDK"; then
        for continuity_sdk_candidate in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" \
            /opt/homebrew/share/android-commandlinetools /usr/local/share/android-commandlinetools; do
            if test -d "$continuity_sdk_candidate"; then
                CONTINUITY_SDK=$continuity_sdk_candidate
                break
            fi
        done
    fi
    test -d "$CONTINUITY_SDK" || continuity_tool_error 'Set ANDROID_HOME (or ANDROID_SDK_ROOT) to an installed Android SDK.'
    PLATFORM_DIR="${CONTINUITY_ANDROID_PLATFORM_DIR:-$CONTINUITY_SDK/platforms/android-35}"
    PLATFORM_JAR="$PLATFORM_DIR/android.jar"
    test -f "$PLATFORM_JAR" || continuity_tool_error "Missing Android API 35: $PLATFORM_JAR; install platforms;android-35."
}

continuity_android_tools() {
    continuity_java
    continuity_android_sdk
    TOOLS_DIR="${CONTINUITY_ANDROID_BUILD_TOOLS:-$CONTINUITY_SDK/build-tools/35.0.0}"
    for continuity_android_tool in aapt2 aidl d8 zipalign apksigner; do
        test -x "$TOOLS_DIR/$continuity_android_tool" || continuity_tool_error "Missing build tool: $TOOLS_DIR/$continuity_android_tool; install build-tools;35.0.0."
    done
    for continuity_android_file in "$TOOLS_DIR/core-lambda-stubs.jar" "$PLATFORM_DIR/framework.aidl"; do
        test -f "$continuity_android_file" || continuity_tool_error "Missing SDK file: $continuity_android_file"
    done
    APK_ANALYZER="${CONTINUITY_APKANALYZER:-$CONTINUITY_SDK/cmdline-tools/latest/bin/apkanalyzer}"
    test -x "$APK_ANALYZER" || continuity_tool_error "Missing apkanalyzer: $APK_ANALYZER; install cmdline-tools;latest or set CONTINUITY_APKANALYZER."
    AAPT2="$TOOLS_DIR/aapt2"
}

continuity_debug_keystore() {
    KEYSTORE="${CONTINUITY_DEBUG_KEYSTORE:-${KEYSTORE:-$HOME/.android/debug.keystore}}"
    if test ! -e "$KEYSTORE"; then
        mkdir -p "$(dirname "$KEYSTORE")"
        (umask 077; "$JAVA_HOME/bin/keytool" -genkeypair -noprompt -keystore "$KEYSTORE" \
            -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 \
            -validity 10000 -dname 'CN=Android Debug,O=Android,C=US')
    fi
    test -f "$KEYSTORE" || continuity_tool_error "Debug keystore is not a regular file: $KEYSTORE"
    "$JAVA_HOME/bin/keytool" -list -keystore "$KEYSTORE" -storepass android -alias androiddebugkey \
        >/dev/null 2>&1 || continuity_tool_error 'Debug keystore must contain androiddebugkey with password android.'
    export KEYSTORE
}
