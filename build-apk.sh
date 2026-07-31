#!/usr/bin/env sh
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

if [ -d "$PROJECT_DIR/.tools/jdk" ]; then
    export JAVA_HOME="$PROJECT_DIR/.tools/jdk"
fi

if [ -d "$PROJECT_DIR/.tools/android-sdk" ]; then
    export ANDROID_SDK_ROOT="$PROJECT_DIR/.tools/android-sdk"
fi

if [ -f "$PROJECT_DIR/keystore.properties" ]; then
    exec "$PROJECT_DIR/gradlew" :app:assembleRelease
fi

echo "未配置正式签名，正在构建可安装的 debug APK。" >&2
exec "$PROJECT_DIR/gradlew" :app:assembleDebug
