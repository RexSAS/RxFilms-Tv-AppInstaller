#!/bin/sh
# Builds dist/RxFilms-<version>.wgt for Samsung Tizen TVs.
# Seerr address and key come from ~/.gradle/gradle.properties, like the Fire TV build, and only end up in the package.
# The .wgt is unsigned: Samsung TVs only take packages signed with a certificate for that TV, so it is signed at install
# (Tizen Studio / `tizen package -t wgt -s <profile>`, see README).
set -e
cd "$(dirname "$0")"

props="$HOME/.gradle/gradle.properties"
prop() { sed -n "s/^$1=//p" "$props" 2>/dev/null | tail -n 1 | sed 's/\\//g'; }
printf 'window.RXFILMS_CONFIG = { seerrUrl: "%s", seerrKey: "%s" };\n' "$(prop rxfilms.seerr.url)" "$(prop rxfilms.seerr.key)" > app/config.js

node --test test/*.test.js
version=$(sed -n 's/.*<widget[^>]* version="\([^"]*\)".*/\1/p' app/config.xml)
mkdir -p dist
rm -f "dist/RxFilms-$version.wgt"
(cd app && zip -qr "../dist/RxFilms-$version.wgt" . -x '.*')
ls dist
