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
const VERB_KEY = 'HKCU\Software\Classes\*\shell\SwitchboardSend';

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
 * install path, and a user who moves or reinstalls the app would otherwise be
 * left with a menu entry pointing at nothing.
 */
export async function installExplorerVerb(exePath: string): Promise<void> {
  if (process.platform !== 'win32') return;
  try {
    await reg(['add', VERB_KEY, '/ve', '/d', 'Send to Switchboard', '/f']);
    await reg(['add', VERB_KEY, '/v', 'Icon', '/d', `${exePath},0`, '/f']);
    // Player: Explorer builds one command line for the whole selection instead
    // of launching the app once per selected file.
    await reg(['add', VERB_KEY, '/v', 'MultiSelectModel', '/d', 'Player', '/f']);
    await reg(['add', `${VERB_KEY}\command`, '/ve', '/d', `"${exePath}" ${SEND_FLAG} "%1"`, '/f']);
  } catch (err) {
    // A missing menu entry is not worth failing a launch over.
    console.warn('[switchboard] could not register the Explorer verb:', err);
  }
}
