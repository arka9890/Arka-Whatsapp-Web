#!/bin/sh
# Build exe Windows + installer wizard NSIS. Jalan di Linux, macOS, maupun Git Bash.
#   ./build.sh 1.0.0
set -e
cd "$(dirname "$0")"
VERSION="${1:?pakai: ./build.sh 1.0.0}"
mkdir -p dist

GOOS=windows GOARCH=amd64 CGO_ENABLED=0 \
  go build -trimpath -ldflags "-s -w -X main.version=$VERSION" -o dist/WhatsAppWebClient.exe .

command -v makensis >/dev/null 2>&1 || {
  echo "makensis tidak ada. Pasang NSIS dulu (Linux: apt-get install nsis; macOS: brew install nsis)." >&2
  exit 1
}
makensis -DAPP_VERSION="$VERSION" installer/installer.nsi
ls -la dist
