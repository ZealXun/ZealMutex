#!/usr/bin/env sh
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

if [ -d "$PROJECT_DIR/.tools/jdk" ]; then
    export JAVA_HOME="$PROJECT_DIR/.tools/jdk"
fi

if [ -d "$PROJECT_DIR/.tools/android-sdk" ]; then
    export ANDROID_SDK_ROOT="$PROJECT_DIR/.tools/android-sdk"
fi

VARIANT=${1:-dev}

case "$VARIANT" in
    dev|debug)
        exec "$PROJECT_DIR/gradlew" :app:assembleDebug
        ;;
    release)
        if [ ! -f "$PROJECT_DIR/keystore.properties" ]; then
            echo "缺少 keystore.properties，不能构建正式发行版。" >&2
            exit 1
        fi
        exec "$PROJECT_DIR/gradlew" :app:assembleRelease
        ;;
    *)
        echo "用法: ./build-apk.sh [dev|release]" >&2
        exit 2
        ;;
esac
