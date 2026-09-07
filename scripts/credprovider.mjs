// Builds the Windows unlock credential provider.
//
// One DLL from five translation units, so this drives cl.exe directly rather
// than adding CMake to a repo that has never needed it. vswhere is how you
// locate MSVC without hardcoding a version; it ships with every Visual Studio
// and Build Tools install at a fixed path.

import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, readdirSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const src = join(root, 'native', 'credprovider')
const out = join(root, 'bin')

const VSWHERE = 'C:\\Program Files (x86)\\Microsoft Visual Studio\\Installer\\vswhere.exe'

function fail(message) {
  console.error(message)
  process.exit(1)
}

if (process.platform !== 'win32') fail('The credential provider is a Windows component.')
if (!existsSync(VSWHERE)) {
  fail(
    'Visual Studio Build Tools not found. Install the C++ workload:\n' +
      '  winget install --id Microsoft.VisualStudio.2022.BuildTools --override ' +
      '"--quiet --add Microsoft.VisualStudio.Workload.VCTools --includeRecommended"'
  )
}

const vsPath = execFileSync(VSWHERE, [
  '-latest',
  '-products', '*',
  '-requires', 'Microsoft.VisualStudio.Component.VC.Tools.x86.x64',
  '-property', 'installationPath'
]).toString().trim()

if (!vsPath) fail('No Visual Studio install with the C++ tools. Add the "Desktop development with C++" workload.')

// vcvars sets up dozens of environment variables; running the build inside a
// cmd that sources it first is the supported way to get them, and far less
// brittle than reconstructing INCLUDE and LIB by hand.
const vcvars = join(vsPath, 'VC', 'Auxiliary', 'Build', 'vcvars64.bat')
if (!existsSync(vcvars)) fail(`vcvars64.bat missing under ${vsPath}`)

mkdirSync(out, { recursive: true })

const sources = readdirSync(src).filter((f) => f.endsWith('.cpp')).sort()
const objDir = join(out, 'credprovider-obj')
mkdirSync(objDir, { recursive: true })

const compile = [
  'cl.exe',
  '/nologo',
  '/c',
  '/EHsc',
  '/W4',
  '/WX',
  '/O2',
  '/MT', // static CRT: LogonUI is not going to have our redistributable
  '/DUNICODE',
  '/D_UNICODE',
  `/Fo"${objDir}\\"`,
  ...sources.map((f) => `"${join(src, f)}"`)
].join(' ')

const link = [
  'link.exe',
  '/nologo',
  '/DLL',
  `/DEF:"${join(src, 'switchboard_cp.def')}"`,
  `/OUT:"${join(out, 'switchboard_cp.dll')}"`,
  `"${objDir}\\*.obj"`,
  'ole32.lib',
  'oleaut32.lib',
  'advapi32.lib',
  'crypt32.lib',
  'secur32.lib',
  'wtsapi32.lib',
  'shlwapi.lib',
  'shell32.lib',
  'user32.lib',
  'kernel32.lib'
].join(' ')

const script = `call "${vcvars}" >nul && ${compile} && ${link}`

try {
  execFileSync('cmd.exe', ['/d', '/s', '/c', script], { stdio: 'inherit' })
} catch {
  fail('\nBuild failed.')
}

console.log(`\nBuilt ${join(out, 'switchboard_cp.dll')}`)
