#!/bin/sh
# Decode ANDROID_KEYSTORE_BASE64 into a keystore file.
#
# A perfectly good keystore arrives as something `base64 -d` refuses in more
# ways than is obvious, and every one of them looks identical from the outside:
# the workflow says the secret is not valid base64, and the operator has just
# pasted what `base64 -w0 release.jks` printed. The recoverable shapes are:
#
#   - wrapped lines (`base64` without -w0, which is the default on Linux)
#   - CRLF endings, from a value pasted through a Windows clipboard
#   - a UTF-8 BOM, from a value saved by Notepad
#   - surrounding single or double quotes
#   - PEM-style banners, from `openssl base64` output
#   - the URL-safe alphabet (-_ rather than +/) with padding stripped
#
# What is left after all of that is a secret which genuinely does not hold a
# keystore, and this says so in those words rather than as a decode error.
#
# Usage: decode-keystore.sh <output-path>
# Reads KEYSTORE_BASE64 from the environment.
set -eu

out="${1:?usage: decode-keystore.sh <output-path>}"

if [ -z "${KEYSTORE_BASE64:-}" ]; then
	echo "::error::KEYSTORE_BASE64 is empty." >&2
	exit 1
fi

printf '%s' "$KEYSTORE_BASE64" \
	| sed -e '1s/^\xEF\xBB\xBF//' \
	      -e 's/\r$//' \
	      -e '/^-----BEGIN/d' \
	      -e '/^-----END/d' \
	| tr -d '\n\r \t"'"'" \
	| tr '_-' '/+' \
	> /tmp/ks.b64

# Restore padding the URL-safe alphabet usually drops. base64 wants the length
# to be a multiple of four.
len=$(wc -c < /tmp/ks.b64 | tr -d ' ')
case $((len % 4)) in
	2) printf '==' >> /tmp/ks.b64 ;;
	3) printf '='  >> /tmp/ks.b64 ;;
	1) echo "::error::KEYSTORE_BASE64 has a length that cannot be base64 (${len} chars, remainder 1)." >&2; exit 1 ;;
esac

if ! base64 -d < /tmp/ks.b64 > "$out" 2>/dev/null; then
	echo "::error::KEYSTORE_BASE64 is not base64, even after normalising whitespace, quotes, PEM banners and the URL-safe alphabet. Regenerate it with: base64 -w0 release.jks" >&2
	rm -f /tmp/ks.b64 "$out"
	exit 1
fi
rm -f /tmp/ks.b64

# A JKS begins 0xFEEDFEED, a PKCS#12 begins 0x30. Anything else decoded to
# something that is not a keystore, which is worth catching here rather than as
# a confusing keytool error.
magic=$(od -An -tx1 -N4 "$out" | tr -d ' \n')
case "$magic" in
	feedfeed*) : ;;
	30*)       : ;;
	*)
		echo "::error::The secret decoded, but the result is not a JKS or PKCS#12 keystore (starts ${magic})." >&2
		rm -f "$out"
		exit 1
		;;
esac

echo "Keystore decoded to $out ($(wc -c < "$out" | tr -d ' ') bytes)."
