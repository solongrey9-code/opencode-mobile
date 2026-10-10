import { createServer } from "node:http"
import { timingSafeEqual } from "node:crypto"
import { readFile } from "node:fs/promises"
import { existsSync } from "node:fs"
import { networkInterfaces } from "node:os"
import { dirname, join } from "node:path"
import { fileURLToPath } from "node:url"

const repositoryMarker = join(dirname(fileURLToPath(import.meta.url)), "shared-integration.retired")
const hostMarker = process.env.LOCALAPPDATA
  ? join(process.env.LOCALAPPDATA, "OpenCodeMobile", "integration-retired.json")
  : undefined
if (existsSync(repositoryMarker) || (hostMarker && existsSync(hostMarker))) {
  throw new Error("The Android/Desktop shared integration is retired; the Desktop bridge is disabled. See docs/RETIREMENT.md.")
}

const args = new Map()
for (let index = 2; index < process.argv.length; index += 2) {
  args.set(process.argv[index], process.argv[index + 1])
}

const hostname = args.get("--hostname") ?? "127.0.0.1"
const port = Number(args.get("--port") ?? "4097")
const serverPort = Number(args.get("--server-port") ?? "4096")
const sharedServerUrl = normalizeUrl(args.get("--server-url") ?? `http://127.0.0.1:${serverPort}`)
const userData = args.get("--user-data")
const desktopVersion = args.get("--desktop-version") ?? ""
const bridgeToken = process.env.OPENCODE_MOBILE_BRIDGE_TOKEN || ""
const supportedDesktopVersions = new Set(["1.18.25", "1.18.26", "1.18.27"])

if (!userData) throw new Error("--user-data is required")
if (!bridgeToken) throw new Error("OPENCODE_MOBILE_BRIDGE_TOKEN is required")
if (process.env.OPENCODE_SERVER_PASSWORD || process.env.OPENCODE_SERVER_USERNAME) {
  throw new Error("OpenCode Basic credentials must not be inherited by the Desktop bridge")
}
if (!supportedDesktopVersions.has(desktopVersion)) {
  throw new Error(`Unsupported OpenCode Desktop version: ${desktopVersion || "unknown"}`)
}

function normalizeUrl(value) {
  return value?.trim().replace(/\/+$/, "").toLowerCase()
}

function authorized(request) {
  const expected = Buffer.from(bridgeToken)
  const header = request.headers.authorization ?? ""
  if (!header.startsWith("Bearer ")) return false
  const supplied = Buffer.from(header.slice(7))
  return supplied.length === expected.length && timingSafeEqual(supplied, expected)
}

async function readJson(path) {
  return JSON.parse(await readFile(path, "utf8"))
}

function parseStoredJson(value, fallback) {
  if (typeof value !== "string") return fallback
  try {
    return JSON.parse(value)
  } catch {
    return fallback
  }
}

function parseRecent(value) {
  const key = parseStoredJson(value, {}).key
  if (typeof key !== "string") return {}
  return {
    server: key.split("\n", 1)[0],
    sessionId: key.match(/\/session\/(ses_[A-Za-z0-9_-]+)/)?.[1],
  }
}

function lanUrls(targetPort) {
  const result = []
  for (const [name, entries] of Object.entries(networkInterfaces())) {
    if (/loopback|vethernet|wsl|docker|vmware|virtualbox|tailscale|vgate|utun|sstap|vpn/i.test(name)) continue
    for (const entry of entries ?? []) {
      if (entry.family !== "IPv4" || entry.internal || entry.address.startsWith("169.254.")) continue
      result.push(`http://${entry.address}:${targetPort}`)
    }
  }
  return [...new Set(result)]
}

async function state() {
  const settings = await readJson(join(userData, "opencode.settings")).catch(() => ({}))
  const global = await readJson(join(userData, "opencode.global.dat")).catch(() => ({}))
  const model = parseStoredJson(global.model, { user: [], recent: [] })
  const windows = []
  const privateOpenSessionIds = new Set()
  const sharedOpenSessionIds = new Set()
  let privateRecentSessionId
  let sharedRecentSessionId

  for (const windowID of settings.windowIds ?? []) {
    const store = await readJson(join(userData, `opencode.window.${windowID}.dat`)).catch(() => undefined)
    if (!store) continue
    const tabs = parseStoredJson(store.tabs, [])
    const recent = parseRecent(store["tabs.recent"])
    const sessions = []
    for (const tab of tabs) {
      if (tab?.type !== "session" || typeof tab.sessionId !== "string") continue
      const localTab = tab.server === "sidecar"
      const sharedTab = normalizeUrl(tab.server) === sharedServerUrl
      if (!localTab && !sharedTab) continue
      sessions.push({ id: tab.sessionId, server: tab.server })
      if (sharedTab) sharedOpenSessionIds.add(tab.sessionId)
      else privateOpenSessionIds.add(tab.sessionId)
    }
    const recentLocal = recent.server === "sidecar" || normalizeUrl(recent.server) === sharedServerUrl
    if (recentLocal && recent.sessionId) {
      if (recent.server === "sidecar") privateRecentSessionId = recent.sessionId
      if (normalizeUrl(recent.server) === sharedServerUrl) sharedRecentSessionId = recent.sessionId
    }
    windows.push({
      id: windowID,
      sessions,
      recentSessionId: recentLocal ? recent.sessionId : undefined,
      recentServer: recentLocal ? recent.server : undefined,
    })
  }

  const defaultServerUrl = typeof settings.defaultServerUrl === "string" ? settings.defaultServerUrl : undefined
  const desktopUsesSharedServer = normalizeUrl(defaultServerUrl) === sharedServerUrl

  return {
    version: 1,
    desktopVersion,
    timestamp: Date.now(),
    desktop: {
      defaultServerUrl,
      desktopUsesSharedServer,
      privateOpenSessionIds: [...privateOpenSessionIds],
      sharedOpenSessionIds: [...sharedOpenSessionIds],
      privateRecentSessionId,
      sharedRecentSessionId,
      windows,
    },
    network: {
      serverUrls: hostname === "127.0.0.1" ? [] : lanUrls(serverPort),
      bridgeUrls: hostname === "127.0.0.1" ? [] : lanUrls(port),
    },
    models: {
      user: Array.isArray(model.user) ? model.user : [],
      recent: Array.isArray(model.recent) ? model.recent : [],
    },
  }
}

const server = createServer(async (request, response) => {
  if (!authorized(request)) {
    response.writeHead(401)
    response.end("Unauthorized")
    return
  }
  if (request.method !== "GET" || !["/state", "/health"].includes(request.url)) {
    response.writeHead(404)
    response.end("Not found")
    return
  }
  try {
    const body = request.url === "/health" ? { healthy: true, version: 1, desktopVersion } : await state()
    response.writeHead(200, {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
      "X-Content-Type-Options": "nosniff",
    })
    response.end(JSON.stringify(body))
  } catch (error) {
    response.writeHead(500, { "Content-Type": "application/json; charset=utf-8" })
    response.end(JSON.stringify({ error: error instanceof Error ? error.message : String(error) }))
  }
})

server.listen(port, hostname, () => {
  console.log(`OpenCode Mobile bridge listening on http://${hostname}:${port}`)
})

for (const signal of ["SIGINT", "SIGTERM"]) {
  process.on(signal, () => server.close(() => process.exit(0)))
}
