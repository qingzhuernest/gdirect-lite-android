#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
xcodebuild -project GDirectLite.xcodeproj -scheme GDirectCoreTests \
  -destination "platform=macOS,arch=$(uname -m)" \
  -derivedDataPath "${TMPDIR:-/tmp}/GDirectLite-core-verification" \
  CODE_SIGNING_ALLOWED=NO test
xcodebuild -project GDirectLite.xcodeproj -scheme GDirectLite \
  -configuration Debug -sdk iphoneos -destination 'generic/platform=iOS' \
  -derivedDataPath "${TMPDIR:-/tmp}/GDirectLite-ios-verification" \
  CODE_SIGNING_ALLOWED=NO build
