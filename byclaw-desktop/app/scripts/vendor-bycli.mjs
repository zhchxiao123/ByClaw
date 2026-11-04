#!/usr/bin/env node
// Put byCLI (`@sovovs/bycli`, Apache-2.0) with its runtime dependencies into vendor/bycli, which
// electron-builder copies to Contents/Resources/bycli as plain files (the daemon runs from there and
// the Chrome extension is copied out of it; neither may live inside app.asar).
//
//   node scripts/vendor-bycli.mjs            # download the pinned version from the npm registry
//   node scripts/vendor-bycli.mjs --from <bycli-<version>.tgz>   # offline, from a tarball
//
// The tarball must match the pinned sha512; npm verifies the dependencies against the registry.
// Install scripts are skipped: byCLI's postinstall only writes shell completions into $HOME and
// fetches site adapters, neither of which the desktop client uses.
import { execFileSync } from 'node:child_process'
import crypto from 'node:crypto'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { parseArgs } from 'node:util'

/** Keep in step with BYCLI_VERSION in node-runtime/src/bycli.ts. */
const VERSION = '2.1.78'
const INTEGRITY = 'sha512-/FD2Urpecuw0M6A4vjUfJgQz3YodmvoEXI0SOqjP7GMLKH5yehtZG8agd4wy+qJzgv1hx1eYv8lj95ndzcIYvQ=='

const APP = path.join(path.dirname(fileURLToPath(import.meta.url)), '..')
const OUT = path.join(APP, 'vendor', 'bycli')
const npm = process.platform === 'win32' ? 'npm.cmd' : 'npm'
const { values } = parseArgs({ options: { from: { type: 'string' }, force: { type: 'boolean', default: false } } })

const installed = path.join(OUT, 'node_modules', '@sovovs', 'bycli', 'package.json')
if (!values.force && fs.existsSync(installed) && JSON.parse(fs.readFileSync(installed, 'utf8')).version === VERSION) {
  console.log(`byCLI ${VERSION} already in ${path.relative(APP, OUT)}`)
  process.exit(0)
}

const work = fs.mkdtempSync(path.join(os.tmpdir(), 'byclaw-vendor-bycli-'))
let tarball = values.from && path.resolve(values.from)
if (!tarball) {
  execFileSync(npm, ['pack', `@sovovs/bycli@${VERSION}`, '--pack-destination', work, '--silent'], { stdio: ['ignore', 'ignore', 'inherit'] })
  tarball = path.join(work, `sovovs-bycli-${VERSION}.tgz`)
}
const digest = `sha512-${crypto.createHash('sha512').update(fs.readFileSync(tarball)).digest('base64')}`
if (digest !== INTEGRITY) {
  console.error(`byCLI tarball ${tarball} does not match the pinned ${VERSION} integrity\n  expected ${INTEGRITY}\n  got      ${digest}`)
  process.exit(1)
}

fs.rmSync(OUT, { recursive: true, force: true })
fs.mkdirSync(OUT, { recursive: true })
fs.writeFileSync(path.join(OUT, 'package.json'), `${JSON.stringify({ name: 'byclaw-vendored-bycli', private: true }, null, 2)}\n`)
execFileSync(npm, ['install', '--omit=dev', '--omit=optional', '--ignore-scripts', '--no-audit', '--no-fund', '--no-package-lock', '--install-strategy=hoisted', tarball], { cwd: OUT, stdio: 'inherit' })
fs.rmSync(path.join(OUT, 'package.json'))
const version = JSON.parse(fs.readFileSync(installed, 'utf8')).version
if (version !== VERSION) {
  console.error(`installed byCLI is ${version}, expected ${VERSION}`)
  process.exit(1)
}
for (const required of ['dist/src/main.js', 'dist/src/daemon.js', 'extension/manifest.json', 'LICENSE']) {
  if (!fs.existsSync(path.join(OUT, 'node_modules', '@sovovs', 'bycli', required))) {
    console.error(`byCLI ${VERSION} is missing ${required}`)
    process.exit(1)
  }
}
fs.rmSync(work, { recursive: true, force: true })
console.log(`byCLI ${VERSION} vendored into ${path.relative(APP, OUT)}`)
