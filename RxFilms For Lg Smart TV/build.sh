#!/bin/sh
# Builds dist/org.rxfilms.tv_<version>_all.ipk for LG webOS TVs.
# Seerr address and key come from ~/.gradle/gradle.properties, like the Fire TV build, and only end up in the package.
set -e
cd "$(dirname "$0")"

props="$HOME/.gradle/gradle.properties"
prop() { sed -n "s/^$1=//p" "$props" 2>/dev/null | tail -n 1 | sed 's/\\//g'; }
printf 'window.RXFILMS_CONFIG = { seerrUrl: "%s", seerrKey: "%s" };\n' "$(prop rxfilms.seerr.url)" "$(prop rxfilms.seerr.key)" > app/config.js

node --test test/*.test.js
mkdir -p dist
npx --yes -p @webos-tools/cli ares-package app service --outdir dist --no-minify
ls dist
