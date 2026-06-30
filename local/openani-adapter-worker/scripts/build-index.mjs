import * as fs from "node:fs/promises";
import path from "node:path";
import crypto from "node:crypto";
import * as OpenCC from "opencc-js";

const OPENANI_ORIGIN = "https://openani.an-i.workers.dev";
const BGM_API = "https://api.bgm.tv";
const BGM_USER_AGENT = "spencer/animeko-openani-adapter-builder/0.1 (https://github.com/open-ani/ani)";
const FOLDER_MIME = "application/vnd.google-apps.folder";
const VERSION = process.env.OPENANI_INDEX_VERSION || new Date().toISOString().replace(/[-:.TZ]/g, "").slice(0, 12);
const START_SEASON = "2022-4";
const END_SEASON = "2026-4";
const SEARCH_SHARD_PREFIX_LENGTH = 1;
const ANI_FOLDER_CONCURRENCY = 6;
const SIMILARITY_EPISODE_STRONG_SCORE = 0.23;
const SIMILARITY_EPISODE_STRONG_MARGIN = 0.16;
const SIMILARITY_SEASON_EPISODE_SCORE = 0.22;
const SIMILARITY_STRONG_EPISODE_FIT = 0.9;
const KEYWORD_ACCEPT_SCORE = 0.42;
const KEYWORD_ACCEPT_MARGIN = 0.08;
const KEYWORD_HIGH_CONFIDENCE_SCORE = 0.7;
const KEYWORD_HIGH_CONFIDENCE_MARGIN = 0.5;
const KEYWORD_HIGH_CONFIDENCE_COVERAGE = 0.8;
const KEYWORD_ALIAS_SUBSET_SCORE = 0.7;
const KEYWORD_ALIAS_SUBSET_MARGIN = 0.4;
const KEYWORD_ALIAS_SUBSET_QUERY_COVERAGE = 0.9;
const KEYWORD_MIN_EPISODE_FIT = 0.75;
const TOKEN_STOPWORDS = new Set([
  "动画", "动漫", "电影", "剧场", "剧场版", "特别", "特别篇", "特別篇", "中文", "配音",
  "年龄", "年龄限", "龄限制", "限制版", "第季", "第二", "第三", "第四", "第五", "最终", "最终季", "完结", "篇", "季",
  "the", "and", "with", "season", "movie", "special", "ova", "ona", "web", "tv",
]);
const toSimplified = OpenCC.Converter({ from: "tw", to: "cn" });

const root = path.resolve(import.meta.dirname, "..");
const cacheDir = path.join(root, "cache", "build-index");
const outDir = path.join(root, "dist-index");

const seasons = listSeasons(START_SEASON, END_SEASON);
await fs.mkdir(cacheDir, { recursive: true });
await fs.rm(outDir, { recursive: true, force: true });
await fs.mkdir(path.join(outDir, "season"), { recursive: true });
await fs.mkdir(path.join(outDir, "search"), { recursive: true });
await fs.mkdir(path.join(outDir, "keyword"), { recursive: true });
await fs.mkdir(path.join(outDir, "reports"), { recursive: true });

const allSearchEntries = new Map();
const allKeywordEntries = new Map();
const reports = [];
const stats = {
  seasons: seasons.length,
  aniSubjects: 0,
  matchedSubjects: 0,
  unmatchedSubjects: 0,
  excludedSubjects: 0,
  ambiguousSubjects: 0,
  episodes: 0,
  bgmSubjects: 0,
};
const bgmSubjectsBySeason = new Map();
const globalBgmSubjectsById = new Map();
const keywordProfileCache = new Map();

for (const season of seasons) {
  const bgmSubjects = await fetchBgmSeasonSubjects(season);
  bgmSubjectsBySeason.set(season, bgmSubjects);
  for (const subject of bgmSubjects) {
    if (subject?.id) globalBgmSubjectsById.set(subject.id, subject);
  }
}

const globalAliasMap = buildBgmAliasMap([...globalBgmSubjectsById.values()]);

for (const season of seasons) {
  console.error(`[season] ${season}`);
  const aniSubjects = await fetchAniSeasonSubjects(season);
  const bgmSubjects = bgmSubjectsBySeason.get(season) || [];
  const aliasMap = buildBgmAliasMap(bgmSubjects);
  const keywordStats = buildKeywordStats(bgmSubjects);

  const seasonSubjects = {};
  const seasonReport = {
    season,
    aniSubjectCount: aniSubjects.length,
    bgmSubjectCount: bgmSubjects.length,
    matched: [],
    unmatched: [],
    excluded: [],
    ambiguous: [],
  };

  for (const subject of aniSubjects) {
    const titleKey = normalizeKey(subject.aniTitle);
    const match = matchBgmCandidates(subject.aniTitle, aliasMap, bgmSubjects, globalAliasMap, subject.episodes.length, keywordStats);
    const candidates = match.candidates;
    stats.aniSubjects += 1;
    stats.episodes += subject.episodes.length;

    if (candidates.length !== 1) {
      const item = {
        aniTitle: subject.aniTitle,
        titleKey,
        episodeCount: subject.episodes.length,
        candidates: candidates.map(toBgmSummary),
        match: match.diagnostics,
      };
      if (candidates.length > 1) {
        stats.ambiguousSubjects += 1;
        seasonReport.ambiguous.push(item);
      } else {
        const exclusion = classifyUnmatched(item);
        if (exclusion) {
          stats.excludedSubjects += 1;
          seasonReport.excluded.push({ ...item, exclusion });
        } else {
          stats.unmatchedSubjects += 1;
          seasonReport.unmatched.push(item);
        }
      }
      seasonSubjects[titleKey] = toSeasonSubject(subject, null);
      addSearchEntry(allSearchEntries, titleKey, season, titleKey, subject.aniTitle, "aniTitle");
      addKeywordEntries(allKeywordEntries, subject.aniTitle, season, titleKey, subject.aniTitle, null, "aniTitle");
      continue;
    }

    const bgm = candidates[0];
    stats.matchedSubjects += 1;
    seasonReport.matched.push({
      aniTitle: subject.aniTitle,
      titleKey,
      bgm: toBgmSummary(bgm),
      searchKey: bgm.name_cn || subject.aniTitle,
      match: match.diagnostics,
    });
    seasonSubjects[titleKey] = toSeasonSubject(subject, bgm);

    if (bgm.name_cn) {
      addSearchEntry(allSearchEntries, normalizeKey(bgm.name_cn), season, titleKey, subject.aniTitle, "bgmNameCn");
      addKeywordEntries(allKeywordEntries, bgm.name_cn, season, titleKey, subject.aniTitle, bgm, "bgmNameCn");
    }
    addSearchEntry(allSearchEntries, titleKey, season, titleKey, subject.aniTitle, "aniTitle");
    addKeywordEntries(allKeywordEntries, subject.aniTitle, season, titleKey, subject.aniTitle, bgm, "aniTitle");
  }

  reports.push(seasonReport);
  await writeJson(path.join(outDir, "season", `${season}.json`), {
    version: VERSION,
    season,
    generatedAt: new Date().toISOString(),
    subjects: seasonSubjects,
  });
  await writeJson(path.join(outDir, "reports", `${season}.json`), seasonReport);
  console.error(
    `[season] ${season}: ani=${seasonReport.aniSubjectCount} bgm=${seasonReport.bgmSubjectCount} ` +
      `matched=${seasonReport.matched.length} excluded=${seasonReport.excluded.length} ` +
      `unmatched=${seasonReport.unmatched.length} ambiguous=${seasonReport.ambiguous.length}`,
  );
}

const shards = shardSearchEntries(allSearchEntries);
for (const [prefix, entries] of [...shards.entries()].sort(([a], [b]) => a.localeCompare(b))) {
  await writeJson(path.join(outDir, "search", `${prefix}.json`), entries);
}

const keywordShards = shardSearchEntries(allKeywordEntries);
for (const [prefix, entries] of [...keywordShards.entries()].sort(([a], [b]) => a.localeCompare(b))) {
  await writeJson(path.join(outDir, "keyword", `${prefix}.json`), entries);
}

const manifest = {
  version: VERSION,
  generatedAt: new Date().toISOString(),
  source: {
    aniOrigin: OPENANI_ORIGIN,
    startSeason: START_SEASON,
    endSeason: END_SEASON,
  },
  kv: {
    manifestKey: "openani:v1:manifest",
    searchKeyPattern: `openani:v1:search:${VERSION}:<hashPrefix>`,
    keywordKeyPattern: `openani:v1:keyword:${VERSION}:<hashPrefix>`,
    seasonKeyPattern: `openani:v1:season:${VERSION}:<season>`,
    searchShardPrefixLength: SEARCH_SHARD_PREFIX_LENGTH,
  },
  seasons,
  searchShards: [...shards.keys()].sort(),
  keywordShards: [...keywordShards.keys()].sort(),
  stats: {
    ...stats,
    bgmSubjects: reports.reduce((sum, report) => sum + report.bgmSubjectCount, 0),
    searchKeys: allSearchEntries.size,
    searchShards: shards.size,
    keywordTokens: allKeywordEntries.size,
    keywordShards: keywordShards.size,
  },
};

await writeJson(path.join(outDir, "manifest.json"), manifest);
await writeJson(path.join(outDir, "reports", "summary.json"), {
  manifest,
  seasons: reports.map((report) => ({
    season: report.season,
    aniSubjectCount: report.aniSubjectCount,
    bgmSubjectCount: report.bgmSubjectCount,
      matched: report.matched.length,
      unmatched: report.unmatched.length,
      excluded: report.excluded.length,
    ambiguous: report.ambiguous.length,
  })),
});
await writeJson(path.join(outDir, "reports", "review.json"), buildReviewReport(reports));

console.error(`[done] wrote ${path.relative(root, outDir)}`);
console.log(JSON.stringify(manifest.stats, null, 2));

async function fetchAniSeasonSubjects(season) {
  const listing = await cachedJson(`ani-${season}.json`, () => openaniPost(`/${season}/`));
  const groups = new Map();
  const folders = [];

  for (const file of listing.files || []) {
    if (!file?.name) continue;
    if (file.mimeType === FOLDER_MIME) {
      const folderTitle = cleanupTitle(file.name);
      const childPath = `/${season}/${file.name}/`;
      folders.push({ folderTitle, childPath, cacheName: `ani-${season}-${hash(file.name)}.json` });
      continue;
    }

    const parsed = parseAniFilename(file.name);
    if (!parsed.subject) continue;
    addEpisode(groups, parsed.subject, season, `/${season}/${file.name}`, file, parsed);
  }

  await mapLimit(folders, ANI_FOLDER_CONCURRENCY, async ({ folderTitle, childPath, cacheName }) => {
    const child = await cachedJson(cacheName, () => openaniPost(childPath));
    for (const childFile of child.files || []) {
      addEpisode(groups, folderTitle, season, `${childPath}${childFile.name}`, childFile);
    }
  });

  return [...groups.values()]
    .map((subject) => ({
      ...subject,
      episodes: dedupeBy(subject.episodes, (episode) => episode.href)
        .sort(compareEpisode),
    }))
    .filter((subject) => subject.episodes.length)
    .sort((a, b) => a.aniTitle.localeCompare(b.aniTitle));
}

async function fetchBgmSeasonSubjects(season) {
  const months = bgmMonthsForSeason(season);
  const byId = new Map();
  for (const { year, month } of months) {
    let offset = 0;
    while (true) {
      const cacheName = `bgm-${year}-${month}-${offset}.json`;
      const page = await cachedJson(cacheName, () => bgmGetSubjects(year, month, offset));
      for (const subject of page.data || []) {
        if (subject?.id) byId.set(subject.id, subject);
      }
      offset += page.limit || 50;
      if (offset >= (page.total || 0) || !(page.data || []).length) break;
      await sleep(300);
    }
  }
  return [...byId.values()];
}

function buildBgmAliasMap(subjects) {
  const map = new Map();
  for (const subject of subjects) {
    for (const alias of bgmAliases(subject)) {
      for (const key of titleMatchKeys(alias, { loose: false })) {
        if (!key) continue;
        const list = map.get(key) || [];
        if (!list.some((item) => item.id === subject.id)) list.push(subject);
        map.set(key, list);
      }
    }
  }
  return map;
}

function bgmAliases(subject) {
  const values = [subject.name, subject.name_cn];
  for (const item of subject.infobox || []) {
    if (item?.key === "中文名" && typeof item.value === "string") values.push(item.value);
    if (item?.key === "别名" || item?.key === "別名") {
      if (typeof item.value === "string") values.push(item.value);
      if (Array.isArray(item.value)) {
        for (const value of item.value) values.push(typeof value === "string" ? value : value?.v);
      }
    }
  }
  return unique(values.map((value) => String(value || "").trim()).filter(Boolean));
}

function matchBgmCandidates(aniTitle, aliasMap, bgmSubjects, globalAliasMap, episodeCount, keywordStats) {
  const strong = uniqueCandidates(titleMatchKeys(aniTitle, { loose: false }).flatMap((key) => aliasMap.get(key) || []));
  if (strong.length === 1) return { candidates: strong, diagnostics: { strategy: "strong" } };
  if (strong.length > 1) return { candidates: strong, diagnostics: { strategy: "strongAmbiguous" } };

  const strongIds = new Set(strong.map((candidate) => candidate.id));
  const loose = uniqueCandidates(titleMatchKeys(aniTitle, { loose: true })
    .flatMap((key) => aliasMap.get(key) || [])
    .filter((candidate) => !strongIds.has(candidate.id)));
  if (loose.length) return { candidates: loose, diagnostics: { strategy: "loose" } };

  const globalStrong = uniqueCandidates(titleMatchKeys(aniTitle, { loose: false }).flatMap((key) => globalAliasMap.get(key) || []));
  if (globalStrong.length === 1) return { candidates: globalStrong, diagnostics: { strategy: "globalStrong" } };
  if (globalStrong.length > 1) return { candidates: [], diagnostics: { strategy: "globalStrongRejected", candidates: globalStrong.map(toBgmSummary) } };

  const keyword = keywordCandidates(aniTitle, bgmSubjects, episodeCount, keywordStats);
  if (keyword.candidates.length) return keyword;

  const similarity = similarityCandidates(aniTitle, bgmSubjects, episodeCount);
  if (similarity.diagnostics) {
    similarity.diagnostics.keyword = keyword.diagnostics;
  }
  return similarity;
}

async function openaniPost(pathname) {
  const response = await fetch(OPENANI_ORIGIN + encodeOpenPath(pathname), {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ password: null }),
  });
  if (!response.ok) throw new Error(`ANi ${response.status} ${pathname}`);
  return response.json();
}

async function bgmGetSubjects(year, month, offset) {
  const url = new URL("/v0/subjects", BGM_API);
  url.searchParams.set("type", "2");
  url.searchParams.set("sort", "date");
  url.searchParams.set("year", String(year));
  url.searchParams.set("month", String(month));
  url.searchParams.set("limit", "50");
  url.searchParams.set("offset", String(offset));
  const response = await fetch(url, {
    headers: { "user-agent": BGM_USER_AGENT },
  });
  if (!response.ok) throw new Error(`Bangumi ${response.status} ${url}`);
  return response.json();
}

async function cachedJson(name, producer) {
  const file = path.join(cacheDir, name);
  try {
    return JSON.parse(await fs.readFile(file, "utf8"));
  } catch {}
  const value = await producer();
  await writeJson(file, value);
  return value;
}

function addEpisode(groups, aniTitle, season, openPath, file, parsed = parseAniFilename(file?.name || "")) {
  if (!file?.name || !isVideoPath(file.name)) return;
  const title = cleanupTitle(aniTitle);
  if (!title) return;
  const key = normalizeKey(title);
  const group = groups.get(key) || { aniTitle: title, episodes: [] };
  group.episodes.push({
    label: [
      parsed.episode ? `第 ${parsed.episode} 集` : cleanupTitle(file.name),
      parsed.resolution,
      parsed.subtitle,
    ].filter(Boolean).join(" "),
    episode: parsed.episode || "",
    title: cleanupFileName(file.name),
    href: OPENANI_ORIGIN + encodeOpenPath(openPath),
    size: file.size || "",
    createdTime: file.createdTime || "",
  });
  groups.set(key, group);
}

function parseAniFilename(name) {
  const base = removeMediaExtension(String(name || ""));
  const spaced = base.match(/^\[[^\]]+\]\s*(?<subject>.+?)\s*-\s*(?<episode>\d+(?:\.\d+)?|[A-Za-z]+\d*)\s*(?<tags>(?:\[[^\]]+\])*)/);
  const compact = base.match(/^\[[^\]]+\]\s*(?<subject>.+?)\[(?<episode>\d+(?:\.\d+)?|[A-Za-z]+\d*)\](?<tags>(?:\[[^\]]+\])*)/);
  const match = spaced || compact;
  const tags = match?.groups?.tags || "";
  return {
    subject: cleanupTitle(match?.groups?.subject || ""),
    episode: match?.groups?.episode || "",
    resolution: tags.match(/\[(?<value>\d{3,4}P|[248]K)\]/i)?.groups?.value?.toUpperCase() || "",
    subtitle: tags.match(/\[(?<value>CHT|CHS|BIG5|GB|繁|简)\]/i)?.groups?.value?.toUpperCase() || "",
  };
}

function toSeasonSubject(subject, bgm) {
  return {
    aniTitle: subject.aniTitle,
    bgm: bgm ? toBgmSummary(bgm) : null,
    episodes: subject.episodes,
  };
}

function toBgmSummary(subject) {
  return {
    id: subject.id,
    name: subject.name || "",
    nameCn: subject.name_cn || "",
    date: subject.date || "",
    platform: subject.platform || "",
  };
}

function uniqueCandidates(candidates) {
  const seen = new Set();
  const result = [];
  for (const candidate of candidates) {
    if (!candidate?.id || seen.has(candidate.id)) continue;
    seen.add(candidate.id);
    result.push(candidate);
  }
  return result;
}

function buildReviewReport(reports) {
  const riskyMatches = [];
  const duplicateBgmSubjects = [];
  const excluded = [];

  for (const report of reports) {
    const byBgm = new Map();
    const stronglyMatchedBgmIds = new Set(report.matched
      .filter((item) => ["strong", "loose", "globalStrong", "keyword"].includes(item.match?.strategy || ""))
      .map((item) => item.bgm?.id)
      .filter(Boolean));

    for (const item of report.matched) {
      const strategy = item.match?.strategy || "";
      const margin = Number(item.match?.margin ?? 999);
      if (strategy.startsWith("similarity") || strategy === "keyword" && margin < 0.3) {
        riskyMatches.push({
          season: report.season,
          aniTitle: item.aniTitle,
          bgm: item.bgm,
          strategy,
          score: item.match?.score ?? null,
          margin: item.match?.margin ?? null,
          episodeFit: item.match?.episodeFit ?? null,
          secondEpisodeFit: item.match?.secondEpisodeFit ?? null,
          alias: item.match?.alias ?? "",
          hits: item.match?.hits || [],
          reviewHint: reviewHintForMatch(item, stronglyMatchedBgmIds),
        });
      }

      if (item.bgm?.id) {
        const key = `${report.season}:${item.bgm.id}`;
        const list = byBgm.get(key) || [];
        list.push({
          aniTitle: item.aniTitle,
          titleKey: item.titleKey,
          strategy,
        });
        byBgm.set(key, list);
      }
    }

    for (const [key, values] of byBgm) {
      if (values.length <= 1) continue;
      const [, bgmId] = key.split(":");
      duplicateBgmSubjects.push({
        season: report.season,
        bgmId: Number(bgmId),
        reviewKind: classifyDuplicateGroup(values),
        items: values,
      });
    }

    for (const item of report.excluded || []) {
      excluded.push({
        season: report.season,
        aniTitle: item.aniTitle,
        episodeCount: item.episodeCount,
        exclusion: item.exclusion,
      });
    }
  }

  return {
    riskyMatches: riskyMatches.sort((a, b) => String(a.season).localeCompare(String(b.season)) || a.aniTitle.localeCompare(b.aniTitle)),
    duplicateBgmSubjects: duplicateBgmSubjects.sort((a, b) => String(a.season).localeCompare(String(b.season)) || a.bgmId - b.bgmId),
    excluded: excluded.sort((a, b) => String(a.season).localeCompare(String(b.season)) || a.aniTitle.localeCompare(b.aniTitle)),
  };
}

function reviewHintForMatch(item, stronglyMatchedBgmIds) {
  const strategy = item.match?.strategy || "";
  if (stronglyMatchedBgmIds.has(item.bgm?.id) && strategy.startsWith("similarity")) {
    return "sameBgmHasStrongerSeasonMatch";
  }
  if (item.match?.episodeFit && item.match.episodeFit >= SIMILARITY_STRONG_EPISODE_FIT) {
    return "episodeCountAligned";
  }
  if (item.match?.arcExact) return "arcTokenAligned";
  return "needsManualReview";
}

function classifyDuplicateGroup(items) {
  const titles = items.map((item) => item.aniTitle);
  if (titles.some((title) => /[\[【](?:特別篇|特别篇|中文配音|年齡限制版|年龄限制版)[\]】]/u.test(title))) {
    return "labelVariant";
  }
  const normalized = new Set(titles.map((title) => normalizeKey(title)));
  if (normalized.size === 1) return "unicodeOrPunctuationVariant";
  const strategies = new Set(items.map((item) => item.strategy));
  if (strategies.has("strong") || strategies.has("keyword")) return "titleAlias";
  return "needsManualReview";
}

function classifyUnmatched(item) {
  const keyword = item.match?.keyword?.best;
  const similarity = item.match?.best;
  const best = keyword || similarity;
  if (!best) {
    return {
      reason: "noRetrievalSignal",
      detail: "No usable keyword or similarity candidate was found in the season window.",
    };
  }

  if (item.episodeCount <= 2 && Number(best.episodeFit || 0) < KEYWORD_MIN_EPISODE_FIT) {
    return {
      reason: "shortSpecialOrDuplicate",
      detail: "ANi has only a very short group and the closest BGM candidate has incompatible episode count.",
      candidate: best.alias || "",
    };
  }

  if (isGenericAliasRisk(item, similarity)) {
    return {
      reason: "genericAliasRisk",
      detail: "The closest alias is a generic subset of the ANi title, so accepting it would likely map a more specific work to a broader franchise alias.",
      candidate: similarity.alias || "",
    };
  }

  if (Number(similarity?.score || 0) >= 0.95 && Number(similarity?.margin || 0) === 0 && Number(similarity?.episodeFit || 0) < KEYWORD_MIN_EPISODE_FIT) {
    return {
      reason: "multiSubjectSplit",
      detail: "The title strongly matches multiple low-episode BGM subjects, likely representing a split movie/chapter release rather than one season subject.",
      candidate: similarity.alias || "",
    };
  }

  if (Number(best.episodeFit || 0) < KEYWORD_MIN_EPISODE_FIT && Number(best.score || 0) < KEYWORD_ACCEPT_SCORE) {
    return {
      reason: "weakCandidate",
      detail: "The best candidate is both textually weak and episode-count incompatible.",
      candidate: best.alias || "",
    };
  }

  return {
    reason: "manualReviewRequired",
    detail: "No rule accepted this item without risking a false positive.",
    candidate: best.alias || "",
  };
}

function isGenericAliasRisk(item, match) {
  if (!match?.queryKey || !match?.aliasKey) return false;
  if (hasAlignedSeasonNumber(item.aniTitle, match)) return false;
  if (!match.queryKey.includes(match.aliasKey)) return false;
  return [...match.queryKey].length - [...match.aliasKey].length >= 2;
}

function buildKeywordStats(subjects) {
  const documentFrequency = new Map();
  let documents = 0;
  for (const subject of subjects) {
    const tokens = new Set();
    for (const alias of bgmAliases(subject)) {
      for (const token of keywordProfile(alias).tokens) tokens.add(token);
    }
    if (!tokens.size) continue;
    documents += 1;
    for (const token of tokens) {
      documentFrequency.set(token, (documentFrequency.get(token) || 0) + 1);
    }
  }
  const idf = new Map();
  for (const [token, frequency] of documentFrequency) {
    idf.set(token, Math.log(1 + (documents - frequency + 0.5) / (frequency + 0.5)));
  }
  return { documents, idf };
}

function tokenWeight(tokens, keywordStats) {
  let total = 0;
  for (const token of tokens) {
    total += keywordStats?.idf?.get(token) ?? 1;
  }
  return total;
}

function keywordCandidates(aniTitle, bgmSubjects, episodeCount, keywordStats) {
  const queryProfile = keywordProfile(aniTitle);
  if (!queryProfile.tokens.size) return { candidates: [], diagnostics: { strategy: "keywordRejected" } };

  const ranked = bgmSubjects
    .map((subject) => ({
      subject,
      match: bestKeywordMatch(queryProfile, subject, keywordStats),
      episodeFit: episodeFit(subject, episodeCount),
    }))
    .filter((item) => item.match)
    .sort((a, b) => b.match.score - a.match.score || b.episodeFit - a.episodeFit);

  const best = ranked[0];
  if (!best) return { candidates: [], diagnostics: { strategy: "keywordRejected" } };
  const second = ranked.find((item) => item.subject.id !== best.subject.id);
  const margin = best.match.score - (second?.match?.score || 0);
  const seasonAligned = hasAlignedSeasonNumber(aniTitle, {
    alias: best.match.alias,
    aliasKey: best.match.aliasKey,
    queryKey: best.match.queryKey,
  });
  const acceptableEpisode = best.episodeFit >= KEYWORD_MIN_EPISODE_FIT || seasonAligned || best.match.arcExact;

  if (best.match.score >= KEYWORD_ACCEPT_SCORE && margin >= KEYWORD_ACCEPT_MARGIN && acceptableEpisode) {
    return {
      candidates: [best.subject],
      diagnostics: {
        strategy: "keyword",
        ...keywordSummary(best, second),
      },
    };
  }
  if (
    best.match.score >= KEYWORD_HIGH_CONFIDENCE_SCORE &&
    margin >= KEYWORD_HIGH_CONFIDENCE_MARGIN &&
    best.match.queryCoverage >= KEYWORD_HIGH_CONFIDENCE_COVERAGE &&
    best.match.aliasCoverage >= KEYWORD_HIGH_CONFIDENCE_COVERAGE &&
    !best.match.arcConflict
  ) {
    return {
      candidates: [best.subject],
      diagnostics: {
        strategy: "keywordHighConfidence",
        ...keywordSummary(best, second),
      },
    };
  }
  if (
    best.match.score >= KEYWORD_ALIAS_SUBSET_SCORE &&
    margin >= KEYWORD_ALIAS_SUBSET_MARGIN &&
    best.match.queryCoverage >= KEYWORD_ALIAS_SUBSET_QUERY_COVERAGE &&
    best.match.hits.length >= 4 &&
    !best.match.arcConflict &&
    !best.match.latinMissing
  ) {
    return {
      candidates: [best.subject],
      diagnostics: {
        strategy: "keywordAliasSubset",
        ...keywordSummary(best, second),
      },
    };
  }
  return {
    candidates: [],
    diagnostics: {
      strategy: "keywordRejected",
      best: keywordSummary(best, second),
    },
  };
}

function bestKeywordMatch(queryProfile, subject, keywordStats) {
  let best = null;
  for (const alias of bgmAliases(subject)) {
    const aliasProfile = keywordProfile(alias);
    if (!aliasProfile.tokens.size) continue;
    const match = keywordScore(queryProfile, aliasProfile, keywordStats);
    if (!match) continue;
    if (!best || match.score > best.score) {
      best = {
        ...match,
        queryKey: queryProfile.key,
        aliasKey: aliasProfile.key,
        alias,
      };
    }
  }
  return best;
}

function keywordScore(query, alias, keywordStats) {
  const hits = intersection(query.tokens, alias.tokens);
  if (!hits.size) return null;

  const strongHits = intersection(query.strongTokens, alias.strongTokens);
  const hitWeight = tokenWeight(hits, keywordStats);
  const queryWeight = tokenWeight(query.tokens, keywordStats);
  const aliasWeight = tokenWeight(alias.tokens, keywordStats);
  const strongHitWeight = tokenWeight(strongHits, keywordStats);
  const strongQueryWeight = tokenWeight(query.strongTokens, keywordStats);
  const queryCoverage = hitWeight / Math.max(queryWeight, 1);
  const aliasCoverage = hitWeight / Math.max(aliasWeight, 1);
  const strongCoverage = strongHitWeight / Math.max(strongQueryWeight, 1);
  const arcExact = query.arcTokens.size > 0 && intersects(query.arcTokens, alias.arcTokens);
  const arcConflict = query.arcTokens.size > 0 && alias.arcTokens.size > 0 && !arcExact;
  const seasonExact = query.seasonTokens.size > 0 && intersects(query.seasonTokens, alias.seasonTokens);
  const latinMissing = query.latinTokens.size > 0 && !intersects(query.latinTokens, alias.latinTokens);
  const longTokenExact = query.longTokens.size > 0 && intersects(query.longTokens, alias.longTokens);

  let score = 0.5 * queryCoverage + 0.2 * aliasCoverage + 0.2 * strongCoverage;
  if (arcExact) score += 0.18;
  if (seasonExact) score += 0.08;
  if (longTokenExact) score += 0.08;
  if (arcConflict) score -= 0.28;
  if (latinMissing) score -= 0.18;

  return {
    score,
    queryCoverage,
    aliasCoverage,
    strongCoverage,
    arcExact,
    arcConflict,
    seasonExact,
    latinMissing,
    longTokenExact,
    hits: [...hits].sort(),
  };
}

function keywordProfile(value) {
  const cleaned = cleanupKeywordTitle(value);
  const cacheKey = cleaned;
  const cached = keywordProfileCache.get(cacheKey);
  if (cached) return cached;
  const key = normalizeKey(cleaned);
  const tokens = new Set();
  const strongTokens = new Set();
  const latinTokens = new Set();
  const longTokens = new Set();
  const arcTokens = titleArcTokens(cleaned);
  const seasonTokens = new Set([...seasonNumbers(cleaned)].map((number) => `season:${number}`));

  for (const token of arcTokens) {
    tokens.add(token);
    strongTokens.add(token);
  }

  for (const latin of key.match(/[a-z][a-z0-9]{2,}/g) || []) {
    if (TOKEN_STOPWORDS.has(latin)) continue;
    if (/^season\d+$/i.test(latin)) continue;
    tokens.add(latin);
    strongTokens.add(latin);
    latinTokens.add(latin);
    if (latin.length >= 6) longTokens.add(latin);
  }

  const han = [...key].filter((char) => /\p{Script=Han}/u.test(char)).join("");
  for (const token of [...charNgrams(han, 2), ...charNgrams(han, 3)]) {
    if (!isKeywordToken(token)) continue;
    tokens.add(token);
    if (token.length >= 3 || !TOKEN_STOPWORDS.has(token)) strongTokens.add(token);
  }
  for (const token of han.match(/[\p{Script=Han}]{4,}/gu) || []) {
    if (isKeywordToken(token)) {
      tokens.add(token);
      strongTokens.add(token);
      longTokens.add(token);
    }
  }

  const profile = { key, tokens, strongTokens, latinTokens, longTokens, arcTokens, seasonTokens };
  keywordProfileCache.set(cacheKey, profile);
  return profile;
}

function titleArcTokens(value) {
  const text = toSimplified(String(value || "").normalize("NFKC"))
    .replace(/編/g, "篇")
    .replace(/[【】\[\]()（）「」『』《》〈〉]/g, " ");
  const result = new Set();
  for (const match of text.matchAll(/([\p{Script=Han}]{2,8}(?:篇|季|期|部|部分))/gu)) {
    const token = normalizeKey(match[1]);
    if (!token || TOKEN_STOPWORDS.has(token) || /^第?\d+季$/u.test(token)) continue;
    result.add(token);
  }
  return result;
}

function isKeywordToken(token) {
  if (!token || token.length < 2) return false;
  if (TOKEN_STOPWORDS.has(token)) return false;
  if (/^\d+$/u.test(token)) return false;
  if (/^第?\d?季$/u.test(token)) return false;
  return true;
}

function keywordSummary(item, second) {
  const secondScore = second?.match?.score || 0;
  return {
    score: Number(item.match.score.toFixed(3)),
    secondScore: Number(secondScore.toFixed(3)),
    margin: Number((item.match.score - secondScore).toFixed(3)),
    episodeFit: Number(item.episodeFit.toFixed(3)),
    secondEpisodeFit: Number((second?.episodeFit || 0).toFixed(3)),
    queryCoverage: Number(item.match.queryCoverage.toFixed(3)),
    aliasCoverage: Number(item.match.aliasCoverage.toFixed(3)),
    strongCoverage: Number(item.match.strongCoverage.toFixed(3)),
    arcExact: item.match.arcExact,
    arcConflict: item.match.arcConflict,
    seasonExact: item.match.seasonExact,
    latinMissing: item.match.latinMissing,
    longTokenExact: item.match.longTokenExact,
    hits: item.match.hits.slice(0, 20),
    queryKey: item.match.queryKey,
    aliasKey: item.match.aliasKey,
    alias: item.match.alias,
  };
}

function similarityCandidates(aniTitle, bgmSubjects, episodeCount) {
  const queryKeys = titleSimilarityKeys(aniTitle);
  if (!queryKeys.length) return { candidates: [], diagnostics: { strategy: "none" } };

  const ranked = bgmSubjects
    .map((subject) => ({
      subject,
      match: bestSimilarityMatch(queryKeys, subject),
      episodeFit: episodeFit(subject, episodeCount),
    }))
    .filter((item) => item.match)
    .sort((a, b) => b.match.score - a.match.score || b.episodeFit - a.episodeFit);

  const best = ranked[0];
  if (!best) return { candidates: [], diagnostics: { strategy: "none" } };
  const second = ranked.find((item) => item.subject.id !== best.subject.id);
  const secondScore = second?.match.score || 0;
  if (hasMissingSpecificity(aniTitle, best.match)) return { candidates: [], diagnostics: { strategy: "similarityRejected", best: similaritySummary(best, second) } };
  if (
    best.episodeFit >= SIMILARITY_STRONG_EPISODE_FIT &&
    best.match.score >= SIMILARITY_EPISODE_STRONG_SCORE &&
    best.match.score - secondScore >= SIMILARITY_EPISODE_STRONG_MARGIN
  ) {
    return {
      candidates: [best.subject],
      diagnostics: {
        strategy: "similarityEpisodeStrong",
        ...similaritySummary(best, second),
      },
    };
  }
  if (
    hasAlignedSeasonNumber(aniTitle, best.match) &&
    best.episodeFit >= SIMILARITY_STRONG_EPISODE_FIT &&
    best.match.score >= SIMILARITY_SEASON_EPISODE_SCORE
  ) {
    return {
      candidates: [best.subject],
      diagnostics: {
        strategy: "similaritySeasonEpisode",
        ...similaritySummary(best, second),
      },
    };
  }
  return { candidates: [], diagnostics: { strategy: "similarityRejected", best: similaritySummary(best, second) } };
}

function bestSimilarityMatch(queryKeys, subject) {
  let best = null;
  for (const alias of bgmAliases(subject)) {
    for (const aliasKey of titleSimilarityKeys(alias)) {
      for (const queryKey of queryKeys) {
        const score = similarityScore(queryKey, aliasKey);
        if (!best || score.score > best.score) {
          best = {
            ...score,
            queryKey,
            aliasKey,
            alias,
          };
        }
      }
    }
  }
  return best;
}

function similaritySummary(item, second) {
  const secondScore = second?.match?.score || 0;
  return {
    score: Number(item.match.score.toFixed(3)),
    secondScore: Number(secondScore.toFixed(3)),
    margin: Number((item.match.score - secondScore).toFixed(3)),
    episodeFit: Number(item.episodeFit.toFixed(3)),
    secondEpisodeFit: Number((second?.episodeFit || 0).toFixed(3)),
    episodeFitMargin: Number((item.episodeFit - (second?.episodeFit || 0)).toFixed(3)),
    queryKey: item.match.queryKey,
    aliasKey: item.match.aliasKey,
    alias: item.match.alias,
  };
}

function episodeFit(subject, aniEpisodeCount) {
  const bgmCount = bgmEpisodeCount(subject);
  if (!bgmCount || !aniEpisodeCount) return 0;
  return Math.min(bgmCount, aniEpisodeCount) / Math.max(bgmCount, aniEpisodeCount);
}

function bgmEpisodeCount(subject) {
  const direct = Number(subject?.eps || subject?.total_episodes || 0);
  if (direct > 0) return direct;
  for (const item of subject?.infobox || []) {
    if (!/^(?:话数|話數|集数|集數)$/u.test(item?.key || "")) continue;
    const value = typeof item.value === "string" ? item.value : item.value?.v;
    const parsed = Number.parseInt(String(value || "").match(/\d+/)?.[0] || "", 10);
    if (parsed > 0) return parsed;
  }
  return 0;
}

function titleSimilarityKeys(value) {
  return unique(titleMatchKeys(value, { loose: true }).filter(isSimilarityKey));
}

function isSimilarityKey(key) {
  if (!key) return false;
  if (/第$/u.test(key)) return false;
  if (/^(?:season\d*|s\d+|\d+(?:st|nd|rd|th)season|第?\d+季|第?[一二三四五六七八九十]+季|第?\d+期|第?[一二三四五六七八九十]+期|第?\d+部|第?\d+部分|finalseason|最终季)$/i.test(key)) return false;
  if (/^(?:ova|web|tv|sp|tvsp|movie|剧场版|特别篇|特別篇)$/i.test(key)) return false;
  const hanCount = [...key].filter((char) => /\p{Script=Han}/u.test(char)).length;
  const latinCount = [...key].filter((char) => /[a-z0-9]/i.test(char)).length;
  return hanCount >= 4 || latinCount >= 6 || (hanCount >= 3 && latinCount >= 2);
}

function hasAlignedSeasonNumber(aniTitle, match) {
  const sourceNumbers = seasonNumbers(`${aniTitle} ${match.queryKey}`);
  if (!sourceNumbers.size) return false;
  const targetNumbers = seasonNumbers(`${match.alias} ${match.aliasKey}`);
  for (const number of sourceNumbers) {
    if (targetNumbers.has(number)) return true;
  }
  return false;
}

function hasMissingSpecificity(aniTitle, match) {
  if (hasAlignedSeasonNumber(aniTitle, match)) return false;
  const queryKey = match.queryKey || normalizeKey(aniTitle);
  const aliasKey = match.aliasKey || "";
  if (!queryKey || !aliasKey || queryKey === aliasKey) return false;
  if (!/[\p{Script=Han}]/u.test(queryKey) || !/[\p{Script=Han}]/u.test(aliasKey)) return false;
  if (!queryKey.includes(aliasKey)) return false;
  return [...queryKey].length - [...aliasKey].length >= 2;
}

function seasonNumbers(value) {
  const text = toSimplified(String(value || "").normalize("NFKC")).toLowerCase();
  const result = new Set();
  for (const match of text.matchAll(/(?:season|s)\s*(\d+)/gi)) result.add(Number(match[1]));
  for (const match of text.matchAll(/第\s*(\d+)\s*(?:季|期|部|部分)/g)) result.add(Number(match[1]));
  for (const match of text.matchAll(/第\s*([一二三四五六七八九十]+)\s*(?:季|期|部|部分)/g)) result.add(Number(chineseNumberToNumber(match[1])));
  for (const match of text.matchAll(/\b(II|III|IV|V)\b/gi)) result.add(Number(romanToNumber(match[1])));
  return result;
}

function similarityScore(a, b) {
  const bigram = jaccard(charNgrams(a, 2), charNgrams(b, 2));
  const trigram = jaccard(charNgrams(a, 3), charNgrams(b, 3));
  const lcs = lcsLength(a, b) / Math.max([...a].length, [...b].length, 1);
  const contain = a.includes(b) || b.includes(a)
    ? Math.min([...a].length, [...b].length) / Math.max([...a].length, [...b].length)
    : 0;
  return {
    score: 0.25 * bigram + 0.2 * trigram + 0.25 * lcs + 0.3 * contain,
    bigram,
    trigram,
    lcs,
    contain,
  };
}

function charNgrams(value, size) {
  const chars = [...value];
  if (chars.length <= size) return new Set([value]);
  const result = new Set();
  for (let index = 0; index <= chars.length - size; index += 1) {
    result.add(chars.slice(index, index + size).join(""));
  }
  return result;
}

function jaccard(a, b) {
  if (!a.size && !b.size) return 1;
  let intersection = 0;
  for (const item of a) {
    if (b.has(item)) intersection += 1;
  }
  return intersection / (a.size + b.size - intersection || 1);
}

function intersection(a, b) {
  const result = new Set();
  for (const item of a) {
    if (b.has(item)) result.add(item);
  }
  return result;
}

function intersects(a, b) {
  for (const item of a) {
    if (b.has(item)) return true;
  }
  return false;
}

function lcsLength(a, b) {
  const left = [...a];
  const right = [...b];
  const row = Array(right.length + 1).fill(0);
  for (const leftChar of left) {
    let previous = 0;
    for (let index = 0; index < right.length; index += 1) {
      const current = row[index + 1];
      row[index + 1] = leftChar === right[index] ? previous + 1 : Math.max(row[index + 1], row[index]);
      previous = current;
    }
  }
  return row[right.length];
}

function addSearchEntry(entries, searchKey, season, titleKey, aniTitle, reason) {
  if (!searchKey) return;
  const list = entries.get(searchKey) || [];
  if (!list.some((item) => item.season === season && item.titleKey === titleKey)) {
    list.push({ season, titleKey, aniTitle, reason });
  }
  entries.set(searchKey, list);
}

function addKeywordEntries(entries, title, season, titleKey, aniTitle, bgm, reason) {
  const profile = keywordProfile(title);
  for (const token of profile.strongTokens) {
    if (!isIndexKeywordToken(token)) continue;
    const list = entries.get(token) || [];
    if (!list.some((item) => item.season === season && item.titleKey === titleKey)) {
      list.push({
        season,
        titleKey,
        aniTitle,
        searchTitle: title,
        bgmId: bgm?.id || null,
        reason,
      });
    }
    entries.set(token, list);
  }
}

function isIndexKeywordToken(token) {
  if (!token || TOKEN_STOPWORDS.has(token)) return false;
  if (token.startsWith("season:")) return false;
  if (/^season\d+$/i.test(token)) return false;
  if (/^第?[一二三四五六七八九十\d]+季$/u.test(token)) return false;
  if (token.length < 3) return false;
  return true;
}

function shardSearchEntries(entries) {
  const shards = new Map();
  for (const [searchKey, value] of entries) {
    const prefix = hash(searchKey).slice(0, SEARCH_SHARD_PREFIX_LENGTH);
    const shard = shards.get(prefix) || {};
    shard[searchKey] = value;
    shards.set(prefix, shard);
  }
  return shards;
}

function listSeasons(start, end) {
  const [startYear, startMonth] = start.split("-").map(Number);
  const [endYear, endMonth] = end.split("-").map(Number);
  const result = [];
  for (let year = startYear; year <= endYear; year++) {
    for (const month of [1, 4, 7, 10]) {
      if (year === startYear && month < startMonth) continue;
      if (year === endYear && month > endMonth) continue;
      result.push(`${year}-${month}`);
    }
  }
  return result;
}

function bgmMonthsForSeason(season) {
  const [year, month] = season.split("-").map(Number);
  return [-3, -2, -1, 0, 1, 2].map((offset) => shiftMonth(year, month, offset));
}

function shiftMonth(year, month, offset) {
  const date = new Date(Date.UTC(year, month - 1 + offset, 1));
  return { year: date.getUTCFullYear(), month: date.getUTCMonth() + 1 };
}

function normalizeKey(value) {
  return toSimplified(String(value || "").normalize("NFKC"))
    .toLowerCase()
    .replace(/\p{Cf}/gu, "")
    .replace(/幺/g, "么")
    .replace(/坯/g, "坏")
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

function titleMatchKeys(value, { loose = true } = {}) {
  const raw = cleanupTitle(value);
  const variants = new Set([raw]);

  const withoutSquareLabels = raw
    .replace(/\s*[\[【](?:年齡限制版|年龄限制版|特別篇|特别篇|中文配音|WEB|TV|OVA|劇場版|剧场版)[\]】]\s*/gi, " ")
    .trim();
  variants.add(withoutSquareLabels);

  for (const item of [...variants]) {
    variants.add(item.replace(/^\s*(?:電影|电影|劇場版|剧场版)\s+/, ""));
    variants.add(item.replace(/\s+(?:Season|S)\s*(\d+)\b/gi, " 第$1季"));
    variants.add(item.replace(/\s+(\d+)(?:st|nd|rd|th)\s+Season\b/gi, " 第$1季"));
    variants.add(item.replace(/\s+Season\s+(II|III|IV|V)\b/gi, (_, roman) => ` 第${romanToNumber(roman)}季`));
    variants.add(item.replace(/\s+(II|III|IV|V)\b/gi, (_, roman) => ` 第${romanToNumber(roman)}季`));
    variants.add(item.replace(/\s+FINAL\s+SEASON\b/gi, " 最终季"));
    variants.add(item.replace(/\s+第\s*(\d+)\s*季\b/gi, " Season $1"));
    variants.add(item.replace(/\s+第\s*([一二三四五六七八九十]+)\s*季\b/g, (_, value) => ` 第${chineseNumberToNumber(value)}季`));
    variants.add(item.replace(/\s+第\s*(\d+)\s*季\b/g, (_, value) => ` 第${numberToChineseNumber(value)}季`));
    variants.add(item.replace(/\s+第\s*(\d+)\s*期\b/gi, " 第$1季"));
    variants.add(item.replace(/\s+第\s*([一二三四五六七八九十]+)\s*期\b/g, " 第$1季"));
    variants.add(item.replace(/\s+(\d+)\s*年級篇/gi, " $1年级篇"));
    variants.add(item.replace(/\s+(\d+)\s*$/, " 第$1季"));
    variants.add(item.replace(/\s+2nd\s+Attack\b/gi, " 第二季"));
    variants.add(item.replace(/\s+2nd\s+STAGE\b/gi, " 第二季"));
    variants.add(item.replace(/[的之]/g, ""));
    variants.add(item.replace(/\s+/g, " "));
  }

  if (loose) {
    for (const item of [...variants]) {
      addMixedScriptVariants(variants, item);
      addLooseSuffixVariants(variants, item);
    }
  }

  return unique([...variants].map(normalizeKey).filter(Boolean));
}

function cleanupTitle(value) {
  return safeDecode(value)
    .replace(/^\[[^\]]+\]\s*/, "")
    .replace(/\s+-\s*電影(?:\[WEB)?$/i, "")
    .replace(/\s+-\s*电影(?:\[WEB)?$/i, "")
    .replace(/\s*\[(?:\d{3,4}P|[248]K|Baha|WEB-DL|AAC|AVC|CHT|CHS|BIG5|GB)[^\]]*\]/gi, "")
    .trim();
}

function cleanupKeywordTitle(value) {
  return cleanupTitle(value)
    .replace(/\s*[\[【](?:年齡限制版|年龄限制版|特別篇|特别篇|中文配音|WEB|TV|OVA|劇場版|剧场版)[\]】]\s*/gi, " ")
    .trim();
}

function addMixedScriptVariants(variants, value) {
  const text = value.trim();
  if (!text) return;

  const cjkParts = text.match(/[\p{Script=Han}][\p{Script=Han}\s「」『』：:，,。.!！?？、~～－—―-]*/gu) || [];
  for (const part of cjkParts) {
    const cleaned = part.replace(/[「」『』：:，,。.!！?？、~～－—―-]/g, " ").trim();
    if ([...cleaned].filter((char) => /\p{Script=Han}/u.test(char)).length >= 2) variants.add(cleaned);
  }

  const latinParts = text.match(/[A-Za-z][A-Za-z0-9 .:'’!+×＊/-]*/g) || [];
  for (const part of latinParts) {
    const cleaned = part.trim();
    if (normalizeKey(cleaned).length >= 4) variants.add(cleaned);
  }
}

function addLooseSuffixVariants(variants, value) {
  const suffixPatterns = [
    /\s+第\s*\d+\s*季\b.*$/i,
    /\s+第\s*[一二三四五六七八九十]+\s*季\b.*$/i,
    /\s+Season\s*\d+\b.*$/i,
    /\s+\d+(?:st|nd|rd|th)\s+Season\b.*$/i,
    /\s+FINAL\s+SEASON\b.*$/i,
    /\s+第\s*\d+\s*期\b.*$/i,
    /\s+第\s*[一二三四五六七八九十]+\s*期\b.*$/i,
  ];
  for (const pattern of suffixPatterns) {
    const stripped = value.replace(pattern, "").trim();
    if (stripped && stripped !== value && normalizeKey(stripped).length >= 4) variants.add(stripped);
  }
}

function romanToNumber(value) {
  return { II: 2, III: 3, IV: 4, V: 5 }[String(value).toUpperCase()] || value;
}

function chineseNumberToNumber(value) {
  const text = String(value);
  const digits = { 一: 1, 二: 2, 三: 3, 四: 4, 五: 5, 六: 6, 七: 7, 八: 8, 九: 9 };
  if (text === "十") return 10;
  if (text.startsWith("十")) return 10 + (digits[text[1]] || 0);
  if (text.includes("十")) {
    const [tens, ones] = text.split("十");
    return (digits[tens] || 1) * 10 + (digits[ones] || 0);
  }
  return digits[text] || value;
}

function numberToChineseNumber(value) {
  const number = Number(value);
  const digits = ["", "一", "二", "三", "四", "五", "六", "七", "八", "九"];
  if (!Number.isInteger(number) || number <= 0 || number >= 100) return value;
  if (number < 10) return digits[number];
  if (number === 10) return "十";
  if (number < 20) return `十${digits[number - 10]}`;
  const tens = Math.floor(number / 10);
  const ones = number % 10;
  return `${digits[tens]}十${digits[ones] || ""}`;
}

function cleanupFileName(value) {
  return cleanupTitle(removeMediaExtension(value));
}

function removeMediaExtension(value) {
  return String(value || "").replace(/\.(?:mp4|mkv|webm|ts)$/i, "");
}

function encodeOpenPath(value) {
  const decoded = safeDecode(value || "").replaceAll("\\", "/");
  const parts = decoded.split("/").filter((part) => part && part !== "." && part !== "..");
  return "/" + parts.map(encodeURIComponent).join("/") + (decoded.endsWith("/") ? "/" : "");
}

function isVideoPath(value) {
  return /\.(mp4|webm|mkv|ts)$/i.test(value || "");
}

function compareEpisode(a, b) {
  const av = Number.parseFloat(a.episode || "99999");
  const bv = Number.parseFloat(b.episode || "99999");
  if (Number.isFinite(av) && Number.isFinite(bv) && av !== bv) return av - bv;
  return a.title.localeCompare(b.title);
}

function dedupeBy(items, keyFn) {
  const seen = new Set();
  return items.filter((item) => {
    const key = keyFn(item);
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

function unique(items) {
  return [...new Set(items)];
}

function hash(value) {
  return crypto.createHash("sha1").update(value).digest("hex");
}

function safeDecode(value) {
  try {
    return decodeURIComponent(value);
  } catch {
    return String(value || "");
  }
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function mapLimit(items, limit, mapper) {
  const results = [];
  let next = 0;
  const workers = Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (next < items.length) {
      const index = next++;
      results[index] = await mapper(items[index], index);
    }
  });
  await Promise.all(workers);
  return results;
}

async function writeJson(file, value) {
  await fs.mkdir(path.dirname(file), { recursive: true });
  await fs.writeFile(file, `${JSON.stringify(value, null, 2)}\n`);
}
