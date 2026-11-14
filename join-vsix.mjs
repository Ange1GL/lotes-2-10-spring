#!/usr/bin/env node
// Usage: node join-vsix.mjs [--out <dir>] [--install]
// Rebuilds the .vsix files from the lote-XX folders, verifies SHA-256 against manifest.json
// and, with --install, installs them in dependency order using the `code` CLI.
import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = dirname(fileURLToPath(import.meta.url))
const MANIFEST_PATH = join(ROOT, 'manifest.json')
const LOTE_DIR_PATTERN = /^lote-\d+$/
const PART_PATTERN = /^(.+\.vsix)\.part(\d+)$/

// Packs (extensionPack) go last so their members are already installed.
const INSTALL_ORDER = [
  'redhat.java',
  'vscjava.vscode-java-debug',
  'vscjava.vscode-java-test',
  'vscjava.vscode-maven',
  'vscjava.vscode-gradle',
  'vscjava.vscode-java-dependency',
  'vmware.vscode-spring-boot',
  'vscjava.vscode-spring-initializr',
  'vscjava.vscode-spring-boot-dashboard',
  'vscjava.vscode-java-pack',
  'vmware.vscode-boot-dev-pack',
]

function fail(message) {
  console.error(`ERROR: ${message}`)
  process.exit(1)
}

function parseArgs(argv) {
  const outIndex = argv.indexOf('--out')
  return {
    outDir: resolve(outIndex >= 0 ? argv[outIndex + 1] : join(ROOT, 'vsix')),
    install: argv.includes('--install'),
  }
}

function collectLoteFiles() {
  return readdirSync(ROOT)
    .filter((name) => LOTE_DIR_PATTERN.test(name) && statSync(join(ROOT, name)).isDirectory())
    .sort()
    .flatMap((dir) => readdirSync(join(ROOT, dir)).map((file) => join(ROOT, dir, file)))
}

function groupFiles(paths) {
  const whole = new Map()
  const parts = new Map()
  for (const path of paths) {
    const fileName = path.split(/[\\/]/).pop()
    const match = PART_PATTERN.exec(fileName)
    if (match) {
      const list = parts.get(match[1]) ?? []
      list.push({ index: Number(match[2]), path })
      parts.set(match[1], list)
    } else if (fileName.endsWith('.vsix')) {
      whole.set(fileName, path)
    }
  }
  return { whole, parts }
}

const sha256 = (buffer) => createHash('sha256').update(buffer).digest('hex')

function buildVsix(name, whole, parts) {
  if (whole.has(name)) return readFileSync(whole.get(name))
  const list = parts.get(name)
  if (!list) fail(`Missing file or parts for ${name}`)
  list.sort((a, b) => a.index - b.index)
  list.forEach((part, i) => {
    if (part.index !== i + 1) fail(`Missing part ${i + 1} of ${name}`)
  })
  return Buffer.concat(list.map((part) => readFileSync(part.path)))
}

function installAll(outDir) {
  const files = readdirSync(outDir).filter((name) => name.endsWith('.vsix'))
  for (const id of INSTALL_ORDER) {
    const file = files.find((name) => name.startsWith(`${id}-`))
    if (!file) fail(`No .vsix found for ${id}`)
    console.log(`Installing ${file}`)
    execFileSync('code', ['--install-extension', join(outDir, file)], {
      stdio: 'inherit',
      shell: process.platform === 'win32', // `code` is code.cmd on Windows
    })
  }
}

function main() {
  const { outDir, install } = parseArgs(process.argv.slice(2))
  if (!existsSync(MANIFEST_PATH)) fail('manifest.json not found next to this script')
  const manifest = JSON.parse(readFileSync(MANIFEST_PATH, 'utf8'))
  const { whole, parts } = groupFiles(collectLoteFiles())

  mkdirSync(outDir, { recursive: true })
  for (const entry of manifest.files) {
    const buffer = buildVsix(entry.name, whole, parts)
    if (sha256(buffer) !== entry.sha256) fail(`Checksum mismatch for ${entry.name} (corrupt or incomplete upload)`)
    writeFileSync(join(outDir, entry.name), buffer)
    console.log(`OK  ${entry.name}`)
  }
  console.log(`\n${manifest.files.length} extensions ready in ${outDir}`)
  if (install) installAll(outDir)
}

main()
