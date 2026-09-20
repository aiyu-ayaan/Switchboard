// electron-builder afterPack hook: makes the Linux AppImage start on systems
// that restrict unprivileged user namespaces.
//
// Chromium needs either user namespaces or a root-owned setuid chrome-sandbox.
// Ubuntu 24.04 and its derivatives (Mint 22, Pop!_OS 24.04) set
// kernel.apparmor_restrict_unprivileged_userns=1, and inside an AppImage's FUSE
// mount chrome-sandbox cannot be root-owned, so the app aborts with "The SUID
// sandbox helper binary was found, but is not configured correctly".
//
// The real binary is renamed and a launcher takes its name. The launcher drops
// the sandbox only when running from an AppImage ($APPIMAGE is set) AND the
// kernel restricts user namespaces, so the .deb -- whose post-install makes
// chrome-sandbox setuid -- and every unrestricted system keep the sandbox.
const fs = require('fs');
const path = require('path');

module.exports = async function afterPack(context) {
  if (context.electronPlatformName !== 'linux') return;

  const name = context.packager.executableName;
  const real = path.join(context.appOutDir, name);
  const moved = `${real}.bin`;
  fs.renameSync(real, moved);

  const launcher = `#!/bin/sh
here="$(dirname "$(readlink -f "$0")")"
if [ -n "\${APPIMAGE:-}" ]; then
	restricted=no
	[ "$(cat /proc/sys/kernel/apparmor_restrict_unprivileged_userns 2>/dev/null)" = 1 ] && restricted=yes
	[ "$(cat /proc/sys/kernel/unprivileged_userns_clone 2>/dev/null)" = 0 ] && restricted=yes
	[ "$(cat /proc/sys/user/max_user_namespaces 2>/dev/null)" = 0 ] && restricted=yes
	if [ "$restricted" = yes ]; then
		exec "$here/${name}.bin" --no-sandbox "$@"
	fi
fi
exec "$here/${name}.bin" "$@"
`;
  fs.writeFileSync(real, launcher, { mode: 0o755 });
};
