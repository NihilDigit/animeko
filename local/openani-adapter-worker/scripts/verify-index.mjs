import * as fs from "node:fs/promises";
import path from "node:path";
import crypto from "node:crypto";
import * as OpenCC from "opencc-js";

const toSimplified = OpenCC.Converter({ from: "tw", to: "cn" });

const root = path.resolve(import.meta.dirname, "..");
const indexDir = path.join(root, "dist-index");
const reportsDir = path.join(root, "dist-index", "reports");

const summary = await readJson(path.join(reportsDir, "summary.json"));
const review = await readJson(path.join(reportsDir, "review.json"));

const failures = [];
const seasonTables = new Map();

for (const season of summary.seasons || []) {
  const total = (season.matched || 0) + (season.excluded || 0) + (season.unmatched || 0) + (season.ambiguous || 0);
  if (total !== season.aniSubjectCount) {
    failures.push(`${season.season}: coverage ${total} != aniSubjectCount ${season.aniSubjectCount}`);
  }
  if (season.unmatched) failures.push(`${season.season}: unmatched=${season.unmatched}`);
  if (season.ambiguous) failures.push(`${season.season}: ambiguous=${season.ambiguous}`);

  const table = await readJson(path.join(indexDir, "season", `${season.season}.json`));
  seasonTables.set(season.season, table.subjects || {});
  if (Object.keys(table.subjects || {}).length !== season.aniSubjectCount) {
    failures.push(`${season.season}: season table subjects ${Object.keys(table.subjects || {}).length} != aniSubjectCount ${season.aniSubjectCount}`);
  }
}

const stats = summary.manifest?.stats || {};
if (stats.unmatchedSubjects) failures.push(`manifest: unmatchedSubjects=${stats.unmatchedSubjects}`);
if (stats.ambiguousSubjects) failures.push(`manifest: ambiguousSubjects=${stats.ambiguousSubjects}`);

const reviewStats = {
  riskyMatches: review.riskyMatches?.length || 0,
  duplicateBgmSubjects: review.duplicateBgmSubjects?.length || 0,
  excluded: review.excluded?.length || 0,
};

await verifyReports();
await verifyShardDirectory("search");
await verifyShardDirectory("keyword");

console.log(JSON.stringify({
  stats,
  review: reviewStats,
}, null, 2));

if (failures.length) {
  console.error(`Index verification failed:\n${failures.map((item) => `- ${item}`).join("\n")}`);
  process.exit(1);
}

console.error("Index verification passed.");

async function readJson(file) {
  try {
    return JSON.parse(await fs.readFile(file, "utf8"));
  } catch (error) {
    throw new Error(`Cannot read ${path.relative(root, file)}: ${error.message}`);
  }
}

async function verifyReports() {
  for (const season of summary.seasons || []) {
    const report = await readJson(path.join(reportsDir, `${season.season}.json`));
    const subjects = seasonTables.get(season.season) || {};

    for (const item of [...(report.matched || []), ...(report.excluded || []), ...(report.unmatched || []), ...(report.ambiguous || [])]) {
      const subject = subjects[item.titleKey];
      if (!subject) {
        failures.push(`${season.season}: report item missing from season table: ${item.aniTitle} (${item.titleKey})`);
        continue;
      }
      if (subject.aniTitle !== item.aniTitle) {
        failures.push(`${season.season}: season table title mismatch for ${item.titleKey}: ${subject.aniTitle} != ${item.aniTitle}`);
      }
    }

    for (const item of report.matched || []) {
      const subject = subjects[item.titleKey];
      if (!subject?.bgm?.id) {
        failures.push(`${season.season}: matched item has no season-table bgm: ${item.aniTitle}`);
        continue;
      }
      if (subject.bgm.id !== item.bgm.id) {
        failures.push(`${season.season}: matched bgm mismatch for ${item.aniTitle}: ${subject.bgm.id} != ${item.bgm.id}`);
      }
      const bgmKey = item.bgm.nameCn ? normalizeKey(item.bgm.nameCn) : "";
      if (bgmKey) {
        await verifySearchEntry(bgmKey, season.season, item.titleKey, ["bgmNameCn"]);
      }
      await verifySearchEntry(
        item.titleKey,
        season.season,
        item.titleKey,
        bgmKey === item.titleKey ? ["aniTitle", "bgmNameCn"] : ["aniTitle"],
      );
    }

    for (const item of [...(report.excluded || []), ...(report.unmatched || [])]) {
      const subject = subjects[item.titleKey];
      if (subject?.bgm) {
        failures.push(`${season.season}: non-matched item has bgm in season table: ${item.aniTitle}`);
      }
      await verifySearchEntry(item.titleKey, season.season, item.titleKey, ["aniTitle"]);
    }
  }
}

async function verifySearchEntry(searchKey, season, titleKey, allowedReasons) {
  const shard = await readShard("search", searchKey);
  const entries = shard[searchKey] || [];
  if (!entries.some((entry) => entry.season === season && entry.titleKey === titleKey && allowedReasons.includes(entry.reason))) {
    failures.push(`${season}: missing search entry ${allowedReasons.join("|")} ${searchKey} -> ${titleKey}`);
  }
}

async function verifyShardDirectory(kind) {
  const dir = path.join(indexDir, kind);
  const files = await fs.readdir(dir);
  for (const file of files) {
    if (!file.endsWith(".json")) continue;
    const shard = await readJson(path.join(dir, file));
    for (const [key, entries] of Object.entries(shard)) {
      const expected = `${hash(key).slice(0, summary.manifest?.kv?.searchShardPrefixLength || 1)}.json`;
      if (file !== expected) {
        failures.push(`${kind}/${file}: key ${key} belongs in ${expected}`);
      }
      if (!Array.isArray(entries)) {
        failures.push(`${kind}/${file}: key ${key} is not an array`);
        continue;
      }
      for (const entry of entries) {
        const subject = seasonTables.get(entry.season)?.[entry.titleKey];
        if (!subject) {
          failures.push(`${kind}/${file}: dangling entry ${entry.season}/${entry.titleKey} for key ${key}`);
        }
      }
    }
  }
}

async function readShard(kind, key) {
  const prefixLength = summary.manifest?.kv?.searchShardPrefixLength || 1;
  return readJson(path.join(indexDir, kind, `${hash(key).slice(0, prefixLength)}.json`));
}

function normalizeKey(value) {
  return toSimplified(String(value || "").normalize("NFKC"))
    .toLowerCase()
    .replace(/\p{Cf}/gu, "")
    .replace(/幺/g, "么")
    .replace(/坯/g, "坏")
    .replace(/砲/g, "炮")
    .replace(/智慧型/g, "智能")
    .replace(/钢弹/g, "高达")
    .replace(/编/g, "篇")
    .replace(/裤裤/g, "胖次")
    .replace(/嫌恶/g, "嫌弃")
    .replace(/疗愈/g, "治愈")
    .replace(/&/g, "and")
    .replace(/[×＊]/g, "x")
    .replace(/[＋+]/g, "plus")
    .replace(/[$＄￥¥]/g, "")
    .replace(/[／/]/g, "")
    .replace(/[\s_\-:：;；·・!！?？,，.。()[\]【】「」『』《》〈〉~～†☆★♪♡△—―‐‑‧"'`´’‘’ʼ“”]/g, "");
}

function hash(value) {
  return crypto.createHash("sha1").update(value).digest("hex");
}
