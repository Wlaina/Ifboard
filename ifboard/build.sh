#!/bin/bash
# Build the single Ifboard APK (arm64). Signing key comes from SIGN_ENV (never committed).
set -e
cd "$(dirname "$0")/.."
SIGN_ENV="${SIGN_ENV:-/d/Android/keys/sign.env}"
set -a; . "$SIGN_ENV"; set +a
export BUILD_ABI=arm64-v8a ANDROID_HOME="${ANDROID_HOME:-D:\Android\sdk}" ANDROID_SDK_ROOT="${ANDROID_HOME:-D:\Android\sdk}"
export ECM_DIR="${ECM_DIR:-D:/Android/tools/ecm/share/ECM/cmake}"
export PATH="/d/Android/tools/gettext/bin:$PATH"
unset _JAVA_OPTIONS JAVA_TOOL_OPTIONS
export BUILD_VERSION_NAME="${IFB_VERSION:-1.0.1}"
./gradlew --no-daemon --console=plain :plugin:rime:assembleRelease
bash ifboard/stage-data.sh
./gradlew --no-daemon --console=plain :app:assembleRelease "$@"
ls -la app/build/outputs/apk/release/*.apk
