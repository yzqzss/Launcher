#!/usr/bin/env bash
# Installs the Android SDK needed to build this project (e.g. in a fresh cloud container).
# Idempotent: re-running skips what is already installed.
#
# Usage: scripts/setup-android-sdk.sh            (from anywhere inside the repo)
# Env:   ANDROID_HOME (default /opt/android-sdk)
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
CMDLINE_TOOLS_ZIP="commandlinetools-linux-13114758_latest.zip"
PACKAGES=("platforms;android-36" "build-tools;36.0.0" "platform-tools")

SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

if [ ! -x "$SDKMANAGER" ]; then
    echo "Installing Android command-line tools to $ANDROID_HOME"
    mkdir -p "$ANDROID_HOME/cmdline-tools"
    tmp="$(mktemp -d)"
    curl -fsSL -o "$tmp/clt.zip" "https://dl.google.com/android/repository/$CMDLINE_TOOLS_ZIP"
    unzip -q "$tmp/clt.zip" -d "$tmp"
    rm -rf "$ANDROID_HOME/cmdline-tools/latest"
    mv "$tmp/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
    rm -rf "$tmp"
fi

yes | "$SDKMANAGER" --sdk_root="$ANDROID_HOME" --licenses >/dev/null 2>&1 || true
"$SDKMANAGER" --sdk_root="$ANDROID_HOME" "${PACKAGES[@]}" | grep -v '^\[' || true

# Point Gradle at the SDK (local.properties is git-ignored).
repo_root="$(git -C "$(dirname "$0")" rev-parse --show-toplevel 2>/dev/null || true)"
if [ -n "$repo_root" ]; then
    echo "sdk.dir=$ANDROID_HOME" > "$repo_root/local.properties"
fi

# Maven Central often answers 429 (Too Many Requests) to shared cloud IPs.
# Redirect it to Google's mirror of Maven Central for all local Gradle builds.
mkdir -p "$HOME/.gradle/init.d"
cat > "$HOME/.gradle/init.d/maven-central-mirror.gradle" <<'EOF'
def M = 'https://maven-central.storage-download.googleapis.com/maven2/'
def fix = { RepositoryHandler r -> r.withType(MavenArtifactRepository).configureEach { if (it.url.toString().contains('repo.maven.apache.org')) it.url = M } }
settingsEvaluated { s -> fix(s.pluginManagement.repositories); fix(s.dependencyResolutionManagement.repositories) }
allprojects { fix(buildscript.repositories); fix(repositories) }
EOF

echo "Android SDK ready at $ANDROID_HOME"
echo "Build with: ANDROID_HOME=$ANDROID_HOME ./gradlew assembleDefaultDebug"
