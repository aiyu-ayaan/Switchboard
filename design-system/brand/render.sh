#!/bin/sh
# Re-render PNG icons from the SVG masters. Needs google-chrome.
cd "$(dirname "$0")/../.."
r() { # svg size out
  google-chrome --headless --disable-gpu --hide-scrollbars --default-background-color=00000000 \
    --window-size=$2,$2 --screenshot="$3" "file://$PWD/$1" >/dev/null 2>&1
}
B=design-system/brand
mkdir -p frontend/build frontend/public
r $B/app-icon.svg 512 frontend/build/icon.png
r $B/app-icon-dev.svg 512 frontend/build/icon-dev.png
# Tray: the small cut (no knob, it closes at 22 px), 64 px, light glyph for dark panels.
sed 's/currentColor/#E0E0FF/g' $B/logo-mark-small.svg > $B/.tray.svg
r $B/.tray.svg 64 frontend/build/tray.png
rm -f $B/.tray.svg
cp $B/app-icon.svg frontend/public/favicon.svg
