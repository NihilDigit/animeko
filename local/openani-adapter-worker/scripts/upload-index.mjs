import * as fs from "node:fs/promises";
import path from "node:path";
import { spawn } from "node:child_process";

const root = path.resolve(import.meta.dirname, "..");
const indexDir = path.join(root, "dist-index");
const uploadDir = path.join(indexDir, "kv-upload");
const binding = optionValue("--binding") || "OPENANI_INDEX";
const dryRun = process.argv.includes("--dry-run");
const includeKeyword = process.argv.includes("--include-keyword");
const local = process.argv.includes("--local");
const wranglerArgs = local ? [] : ["--remote"];

const manifest = await readJson(path.join(indexDir, "manifest.json"));
const version = manifest.version;
if (!version) throw new Error("dist-index/manifest.json has no version");

await fs.mkdir(uploadDir, { recursive: true });

const bulkEntries = [];
await addShards("search", manifest.searchShards || [], manifest.kv.searchKeyPattern);
await addSeasonTables(manifest.seasons || [], manifest.kv.seasonKeyPattern);
if (includeKeyword) {
  await addShards("keyword", manifest.keywordShards || [], manifest.kv.keywordKeyPattern);
}

const bulkFile = path.join(uploadDir, `openani-v1-${version}${includeKeyword ? "-with-keyword" : ""}.bulk.json`);
const manifestFile = path.join(uploadDir, `openani-v1-${version}.manifest.json`);
await fs.writeFile(bulkFile, `${JSON.stringify(bulkEntries, null, 2)}\n`, "utf8");
await fs.writeFile(manifestFile, `${JSON.stringify(manifest, null, 2)}\n`, "utf8");

console.log(JSON.stringify({
  version,
  binding,
  mode: local ? "local" : "remote",
  dryRun,
  includeKeyword,
  dataKeys: bulkEntries.length,
  manifestKey: manifest.kv.manifestKey,
  totalWrites: bulkEntries.length + 1,
  bulkFile: path.relative(root, bulkFile),
  manifestFile: path.relative(root, manifestFile),
}, null, 2));

if (dryRun) {
  console.log(`bunx wrangler kv bulk put ${shellQuote(bulkFile)} --binding ${shellQuote(binding)} ${wranglerArgs.join(" ")}`.trim());
  console.log(`bunx wrangler kv key put ${shellQuote(manifest.kv.manifestKey)} --path ${shellQuote(manifestFile)} --binding ${shellQuote(binding)} ${wranglerArgs.join(" ")}`.trim());
  process.exit(0);
}

await run("bunx", [
  "wrangler",
  "kv",
  "bulk",
  "put",
  bulkFile,
  "--binding",
  binding,
  ...wranglerArgs,
]);
await run("bunx", [
  "wrangler",
  "kv",
  "key",
  "put",
  manifest.kv.manifestKey,
  "--path",
  manifestFile,
  "--binding",
  binding,
  ...wranglerArgs,
]);

console.error(`Uploaded ${bulkEntries.length} data keys and switched ${manifest.kv.manifestKey} to ${version}.`);

async function addShards(kind, shards, keyPattern) {
  for (const shard of shards) {
    const file = path.join(indexDir, kind, `${shard}.json`);
    const key = keyPattern.replace("<hashPrefix>", shard);
    await addBulkEntry(key, file);
  }
}

async function addSeasonTables(seasons, keyPattern) {
  for (const season of seasons) {
    const file = path.join(indexDir, "season", `${season}.json`);
    const key = keyPattern.replace("<season>", season);
    await addBulkEntry(key, file);
  }
}

async function addBulkEntry(key, file) {
  const value = await fs.readFile(file, "utf8");
  bulkEntries.push({ key, value });
}

async function readJson(file) {
  try {
    return JSON.parse(await fs.readFile(file, "utf8"));
  } catch (error) {
    throw new Error(`Cannot read ${path.relative(root, file)}: ${error.message}`);
  }
}

function optionValue(name) {
  const index = process.argv.indexOf(name);
  if (index === -1) return "";
  return process.argv[index + 1] || "";
}

function shellQuote(value) {
  return `'${String(value).replaceAll("'", "'\\''")}'`;
}

function run(command, args) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, {
      cwd: root,
      stdio: "inherit",
    });
    child.on("error", reject);
    child.on("exit", (code, signal) => {
      if (code === 0) return resolve();
      reject(new Error(`${command} ${args.join(" ")} failed with ${signal || code}`));
    });
  });
}
