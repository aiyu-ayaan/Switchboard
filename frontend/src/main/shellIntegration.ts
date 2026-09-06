// Windows Explorer integration: a "Send to Switchboard" verb on every file.
//
// A registry verb rather than an IExplorerCommand handler. The modern Windows
// 11 top-level menu is only open to a shell extension shipped in a sparse MSIX
// package, and Windows loads one of those only when it is signed by a
// certificate trusted on the *target* machine. This project ships unsigned, so
// a sparse package would register a menu item that never appears for anyone.
// The verb below is what actually works, on Windows 10 and 11 alike — on 11 it
// sits under "Show more options".
//
// ponytail: registry verb only. Add the sparse package + IExplorerCommand DLL
// when the installer is code-signed, to lift the item onto the Win11 top-level
// menu. Nothing here has to change for that: both can coexist.
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';

const run = promisify(execFile);

/** Per-user, so registering never needs elevation. */
// String.raw, because a lone backslash in this path is one careless edit away
// from being read as an escape and collapsing the key name to nonsense.
const VERB_KEY = String.raw`HKCU\Software\Classes\*\shell\SwitchboardSend`;
const COMMAND_KEY = String.raw`${VERB_KEY}\command`;

export const SEND_FLAG = '--send';

/**
 * The file paths Explorer handed us, from a cold start or a second instance.
 *
 * Everything after the flag is taken rather than a single `%1`: with the
 * Player multi-select model Explorer passes the whole selection to one
 * invocation, and the caller batches the one-per-file case anyway.
 */
export function pathsFromArgv(argv: string[]): string[] {
  const flag = argv.indexOf(SEND_FLAG);
  if (flag < 0) return [];
  return argv.slice(flag + 1).filter((arg) => arg.length > 0 && !arg.startsWith('--'));
}

const reg = (args: string[]) => run('reg', args, { windowsHide: true });

/**
 * Registers the context-menu verb, overwriting whatever is there.
 *
 * Rewritten on every launch rather than once: the command line embeds the
 * path the app is running from, and a user who moves, reinstalls, or switches
 * between a packaged build and a checkout would otherwise be left with a menu
 * entry pointing at nothing.
 *
 * `appPath` is what makes this work unpackaged. There, `execPath` is
 * electron.exe, which cannot launch anything on its own — but handed the app
 * directory as its first argument it can, so the verb is testable from a dev
 * checkout instead of only after packaging. Pass null when packaged, where the
 * executable is the whole command.
 */
export async function installExplorerVerb(
  exePath: string,
  appPath: string | null = null,
  iconPath?: string
): Promise<void> {
  if (process.platform !== 'win32') return;
  const target = appPath ? `"${exePath}" "${appPath}"` : `"${exePath}"`;
  // The branded .ico rather than the executable's own icon: unpackaged the
  // executable is electron.exe, and the menu would carry Electron's atom.
  const icon = iconPath ?? `${exePath},0`;
  try {
    await reg(['add', VERB_KEY, '/ve', '/d', 'Send to Switchboard', '/f']);
    await reg(['add', VERB_KEY, '/v', 'Icon', '/d', icon, '/f']);
    // Player: Explorer builds one command line for the whole selection instead
    // of launching the app once per selected file.
    await reg(['add', VERB_KEY, '/v', 'MultiSelectModel', '/d', 'Player', '/f']);
    await reg(['add', COMMAND_KEY, '/ve', '/d', `${target} ${SEND_FLAG} "%1"`, '/f']);
  } catch (err) {
    // A missing menu entry is not worth failing a launch over.
    console.warn('[switchboard] could not register the Explorer verb:', err);
  }
}
