// link-web-profile.mjs — M8:link every @deepseek-ai workspace package from the
// vendor/dsh submodule into profiles/web/node_modules so bare-module resolution
// (HostSelector walk + Node's parent-walk) finds the web-app bundle plugins.
//
// Why junctions: the loader's bare-module resolver walks up from the profile dir
// checking node_modules/<pkg>, and the worker resolves plugin entry files by the
// same layout. Real dsh maintains $DSH_HOME/profiles/node_modules as a flat
// fallback with one symlink per dependency-closure package; this script is the
// dsh-java equivalent for the shipped web profile. Idempotent: existing
// junctions are kept; only missing ones are created.
//
// Windows: Node's fs.symlinkSync(target, link, 'junction') creates a directory
// junction without admin rights (mklink /J equivalent).
//
// Usage: node scripts/link-web-profile.mjs [profileDir]
import { readFileSync, readdirSync, symlinkSync, existsSync, lstatSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const repo = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const vendor = join(repo, 'vendor', 'dsh')
const profileDir = resolve(process.argv[2] ?? join(repo, 'profiles', 'web'))
const scopedNm = join(profileDir, 'node_modules', '@deepseek-ai')

/** Collect {@code <name> → packageDir} for every {@code @deepseek-ai/*} workspace package. */
function inventory() {
  const pkgs = new Map()
  const addPkgDir = (dir) => {
    const pkgJson = join(dir, 'package.json')
    if (!existsSync(pkgJson)) return
    let manifest
    try {
      manifest = JSON.parse(readFileSync(pkgJson, 'utf8'))
    } catch {
      return
    }
    if (typeof manifest?.name === 'string' && manifest.name.startsWith('@deepseek-ai/')) {
      pkgs.set(manifest.name, dir)
    }
  }
  // vendor/dsh/packages/<area>/<pkg>
  const areas = join(vendor, 'packages')
  for (const area of readdirSync(areas, { withFileTypes: true })) {
    if (!area.isDirectory()) continue
    for (const pkg of readdirSync(join(areas, area.name), { withFileTypes: true })) {
      if (pkg.isDirectory()) addPkgDir(join(areas, area.name, pkg.name))
    }
  }
  // vendor/dsh/vendor/<pkg> (vendored cordis, schemastery, ...)
  for (const pkg of readdirSync(join(vendor, 'vendor'), { withFileTypes: true })) {
    if (pkg.isDirectory()) addPkgDir(join(vendor, 'vendor', pkg.name))
  }
  return pkgs
}

function linkDir(link, target) {
  try {
    const st = lstatSync(link)
    if (st.isSymbolicLink() || st.isDirectory()) return 'keep'
    return 'conflict'
  } catch {
    // missing → create below
  }
  try {
    symlinkSync(target, link, 'junction')
    return 'created'
  } catch (error) {
    if (error?.code === 'EEXIST') return 'keep'   // concurrent creation
    throw error
  }
}

let created = 0
let kept = 0
let conflicts = 0
for (const [name, target] of inventory()) {
  const link = join(scopedNm, name.slice('@deepseek-ai/'.length))
  const result = linkDir(link, resolve(target))
  if (result === 'created') created += 1
  else if (result === 'keep') kept += 1
  else conflicts += 1
}
console.log(`link-web-profile: ${kept} kept, ${created} created, ${conflicts} conflicts under ${scopedNm}`)
process.exitCode = conflicts > 0 ? 1 : 0
