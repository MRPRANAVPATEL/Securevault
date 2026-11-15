#!/usr/bin/env bash
set -euo pipefail

sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ ! -d "$sdk_root" || ! -w "$sdk_root" ]]; then
  sdk_root="$RUNNER_TEMP/securevault-android-sdk"
fi
mkdir -p "$sdk_root"
export ANDROID_HOME="$sdk_root"
export ANDROID_SDK_ROOT="$sdk_root"

packages=()
if [[ ! -s "$sdk_root/platforms/android-34/android.jar" ]]; then
  packages+=('platforms;android-34')
fi
build_tools="$sdk_root/build-tools/34.0.0"
if [[ ! -x "$build_tools/aapt2" || ! -x "$build_tools/apksigner" || ! -x "$build_tools/zipalign" ]]; then
  packages+=('build-tools;34.0.0')
fi
if [[ ! -x "$sdk_root/platform-tools/adb" ]]; then
  packages+=('platform-tools')
fi

if (( ${#packages[@]} > 0 )); then
  temp_dir=$(mktemp -d "$RUNNER_TEMP/securevault-sdk-tools.XXXXXX")
  trap 'rm -rf -- "$temp_dir"' EXIT
  archive="$temp_dir/commandline-tools.zip"
  curl --fail --silent --show-error --location --retry 3 --connect-timeout 20 \
    'https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip' \
    --output "$archive"
  printf '%s  %s\n' '2d2d50857e4eb553af5a6dc3ad507a17adf43d115264b1afc116f95c92e5e258' "$archive" \
    | sha256sum --check
  unzip -q "$archive" -d "$temp_dir"
  sdkmanager="$temp_dir/cmdline-tools/bin/sdkmanager"
  "$sdkmanager" --licenses "--sdk_root=$sdk_root" < <(yes) >/dev/null
  "$sdkmanager" --install "--sdk_root=$sdk_root" "${packages[@]}"
fi

printf 'ANDROID_HOME=%s\nANDROID_SDK_ROOT=%s\n' "$sdk_root" "$sdk_root" >> "$GITHUB_ENV"
printf 'Android SDK selected at %s\n' "$sdk_root"
