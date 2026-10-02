#!/usr/bin/env bash
# بناء FastType APK على سيرفر Ubuntu/Debian (VPS) بدون Android Studio
# شغّله من داخل مجلد المشروع:  sudo bash build-on-vps.sh
set -euo pipefail

SDK_ROOT="${SDK_ROOT:-/opt/android-sdk}"
GRADLE_VER="${GRADLE_VER:-8.9}"

# إذا طلع 404 بالتنزيل، روح لـ developer.android.com/studio#command-line-tools-only
# وانسخ رابط "Linux" وحطه هنا:
CMDLINE_URL="${CMDLINE_URL:-https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip}"

echo "==> تنصيب المتطلبات"
apt-get update -qq
apt-get install -y -qq openjdk-17-jdk-headless unzip curl

echo "==> تنزيل Android command-line tools"
mkdir -p "$SDK_ROOT/cmdline-tools"
if [ ! -d "$SDK_ROOT/cmdline-tools/latest" ]; then
  curl -fL "$CMDLINE_URL" -o /tmp/cmdline-tools.zip
  unzip -q -o /tmp/cmdline-tools.zip -d /tmp/cltools
  mv /tmp/cltools/cmdline-tools "$SDK_ROOT/cmdline-tools/latest"
  rm -rf /tmp/cmdline-tools.zip /tmp/cltools
fi

export ANDROID_HOME="$SDK_ROOT"
export ANDROID_SDK_ROOT="$SDK_ROOT"
export PATH="$PATH:$SDK_ROOT/cmdline-tools/latest/bin"

echo "==> قبول التراخيص وتنزيل مكوّنات SDK"
yes | sdkmanager --licenses > /dev/null 2>&1 || true
sdkmanager --install "platform-tools" "platforms;android-35" "build-tools;35.0.0"

echo "==> تنصيب Gradle $GRADLE_VER"
if [ ! -d "/opt/gradle-$GRADLE_VER" ]; then
  curl -fL "https://services.gradle.org/distributions/gradle-$GRADLE_VER-bin.zip" -o /tmp/gradle.zip
  unzip -q -o /tmp/gradle.zip -d /opt
  rm /tmp/gradle.zip
fi
export PATH="$PATH:/opt/gradle-$GRADLE_VER/bin"

echo "==> البناء"
echo "sdk.dir=$SDK_ROOT" > local.properties
gradle assembleDebug --no-daemon --stacktrace

APK="app/build/outputs/apk/debug/app-debug.apk"
echo
echo "================================================"
echo "  تم البناء:"
echo "  $(pwd)/$APK"
echo "  الحجم: $(du -h "$APK" | cut -f1)"
echo "================================================"
