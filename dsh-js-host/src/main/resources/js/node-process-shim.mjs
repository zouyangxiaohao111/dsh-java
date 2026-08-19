// node:process shim — re-export the already-initialized global process object,
// lazily (getters) so importing it does not touch the process streams.
const p = globalThis.process
function lazy(name) {
  return { get: () => p[name] }
}
export default p
export const argv = p.argv
export const env = p.env
export const platform = p.platform
export const arch = p.arch
export const version = p.version
export const versions = p.versions
export const pid = p.pid
export const execPath = p.execPath
export const execArgv = p.execArgv
export const title = p.title
export const cwd = () => p.cwd()
export const nextTick = (...a) => p.nextTick(...a)
export const chdir = (d) => p.chdir(d)
export const exit = (c) => p.exit(c)
export const kill = (pid, sig) => p.kill(pid, sig)
export const on = (...a) => p.on(...a)
export const off = (...a) => (p.off ? p.off(...a) : undefined)
export const binding = (n) => p.binding(n)
