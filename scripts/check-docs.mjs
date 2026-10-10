import { existsSync, readFileSync, readdirSync } from "node:fs"
import { dirname, join, resolve } from "node:path"
import { fileURLToPath } from "node:url"

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..")
const docs = join(root, "docs")
const required = [
  "START_HERE.md",
  "BASELINE.md",
  "ARCHITECTURE.md",
  "INDEX.md",
  "CODE_MAP.md",
  "PROTOCOL_COMPATIBILITY.md",
  "DESKTOP_INTEGRATION.md",
  "SECURITY.md",
  "OPERATIONS.md",
  "TROUBLESHOOTING.md",
  "TESTING.md",
  "UI_MODEL_PARITY.md",
  "DECISIONS.md",
  "DEVELOPMENT_ARCHIVE_2026-09-01.md",
  "STATE_SNAPSHOT_2026-09-01.md",
]

const failures = []
for (const name of required) {
  if (!existsSync(join(docs, name))) failures.push(`Missing required document: docs/${name}`)
}

function projectFiles(directory, prefix = "") {
  const result = []
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    if ([".git", ".gradle", ".idea", "build"].includes(entry.name)) continue
    const relative = prefix ? `${prefix}/${entry.name}` : entry.name
    if (entry.isDirectory()) result.push(...projectFiles(join(directory, entry.name), relative))
    else result.push(relative.replaceAll("\\", "/"))
  }
  return result
}

const repositoryFiles = projectFiles(root)
const markdown = ["AGENTS.md", "README.md", ...readdirSync(docs).filter((name) => name.endsWith(".md")).map((name) => `docs/${name}`)]
for (const relative of markdown) {
  const file = join(root, relative)
  const text = readFileSync(file, "utf8")
  for (const match of text.matchAll(/\[[^\]]*\]\(([^)]+)\)/g)) {
    const target = match[1].trim().split("#", 1)[0]
    if (!target || /^(https?:|mailto:)/i.test(target)) continue
    const resolved = resolve(dirname(file), target)
    if (!existsSync(resolved)) failures.push(`${relative}: missing Markdown target ${match[1]}`)
  }
  for (const match of text.matchAll(/`([^`\r\n]+\.(?:md|ps1|cmd|mjs|kt|kts|xml|apk))`/g)) {
    const target = match[1].replaceAll("\\", "/")
    if (/[*<>%$]/.test(target) || target.includes("...")) continue
    if (/\.(?:ps1|cmd)\/\.(?:ps1|cmd)$/.test(target)) {
      const [first, secondExtension] = target.split("/.")
      const second = first.replace(/\.[^.]+$/, `.${secondExtension}`)
      for (const candidate of [first, second]) {
        if (!repositoryFiles.some((path) => path === candidate || path.endsWith(`/${candidate}`))) {
          failures.push(`${relative}: missing documented path ${candidate}`)
        }
      }
      continue
    }
    const candidates = target.endsWith(".md") && !target.includes("/")
      ? [`docs/${target}`]
      : repositoryFiles.filter((path) => path === target || path.endsWith(`/${target}`))
    if (candidates.length === 0) failures.push(`${relative}: missing documented path ${match[1]}`)
  }
}

if (failures.length) {
  console.error(failures.join("\n"))
  process.exit(1)
}
console.log(`Documentation checks passed (${markdown.length} Markdown files)`)
