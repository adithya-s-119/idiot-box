#!/usr/bin/env bash
# What CI runs on every pull request (.github/workflows/check.yml). Run it before you push: the Fire TV build and the
# translation catalogues.
#
# One difference: here ./build.sh uses the newest SDK and JDK you have, while CI uses build-tools 34, android-34 and
# JDK 17, as the README says. A call newer than Android 34 can pass here and fail on the pull request.
set -euo pipefail
cd "$(dirname "$0")/.."
./build.sh
python3 tools/translate.py --check
echo "All checks passed."
