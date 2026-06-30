import * as OpenCC from "opencc-js";

const OPENANI_ORIGIN = "https://openani.an-i.workers.dev";
const FOLDER_MIME = "application/vnd.google-apps.folder";
const CACHE_TTL_SECONDS = 30 * 60;
const CACHE_VERSION = "20260630-legacy-index-exact";
const toTraditional = OpenCC.Converter({ from: "cn", to: "tw" });
const toSimplified = OpenCC.Converter({ from: "tw", to: "cn" });

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    try {
      if (request.method === "OPTIONS") return cors(new Response(null, { status: 204 }));
      if (request.method !== "GET" && request.method !== "HEAD") return text("Method not allowed", 405);

      switch (url.pathname) {
        case "/":
          return Response.redirect(`${url.origin}/sub.json`, 302);
        case "/favicon.ico":
          return favicon();
        case "/sub.json":
          return json(subscription(url.origin));
        case "/search":
          return cached(request, ctx, () => handleSearch(url, env));
        case "/subject":
          return cached(request, ctx, () => handleSubject(url, env));
        case "/play":
          return handlePlay(url);
        default:
          return text("Not found", 404);
      }
    } catch (error) {
      return text(`Adapter error: ${error?.message || error}`, 500);
    }
  },
};

async function handleSearch(url, env) {
  const keyword = (url.searchParams.get("wd") || url.searchParams.get("keyword") || "").trim();
  if (!keyword) return html(renderSearch(url.origin, keyword, []));

  const indexedItems = await indexedSearchItems(url.origin, keyword, env);
  if (indexedItems.length) return html(renderSearch(url.origin, keyword, indexedItems));

  const directItems = await directSearchItems(url.origin, keyword);
  if (directItems.length) return html(renderSearch(url.origin, keyword, directItems));
  if (hasCjk(keyword)) return html(renderSearch(url.origin, keyword, []));

  const items = [];
  try {
    for (const query of queryVariants(keyword)) {
      const result = await openaniPost("/", { inputvalue: query });
      const folderNames = Array.isArray(result.foldername) ? result.foldername : [];
      items.push(...(Array.isArray(result.files) ? result.files : []).map((file, index) => {
        const base = folderNames[index] || "/";
        return toSearchItem(url.origin, base, file, query);
      }).filter(Boolean));
      if (items.length) break;
    }
  } catch {
    return html(renderSearch(url.origin, keyword, []));
  }

  return html(renderSearch(url.origin, keyword, dedupeBy(items, (item) => item.href)));
}

async function directSearchItems(origin, keyword) {
  const titles = directSubjectTitles(keyword);
  const files = await currentSeasonFiles();
  const matchedTitles = titles.filter((title) => seasonFilesForTitle(files, title).length);
  return matchedTitles.map((title) => ({
      name: title,
      href: `${origin}/subject?search=${encodeURIComponent(title)}&q=${encodeURIComponent(title)}`,
      kind: "direct",
    }));
}

async function handleSubject(url, env) {
  const season = (url.searchParams.get("season") || "").trim();
  const titleKey = (url.searchParams.get("titleKey") || "").trim();
  if (season && titleKey) {
    const subject = await indexedSubject(season, titleKey, env);
    if (!subject) return html(renderSubject(url.origin, titleKey, []));
    return html(renderSubject(url.origin, subject.aniTitle || titleKey, subject.episodes || []));
  }

  const search = (url.searchParams.get("search") || "").trim();
  const subjectPath = normalizeFolderPath(url.searchParams.get("path") || "");
  const filter = (url.searchParams.get("q") || "").trim();
  if (search) return handleSearchSubject(url, search, filter);
  if (!subjectPath) return text("Missing subject path", 400);

  const result = await openaniPost(subjectPath, { password: null });
  const files = Array.isArray(result.files) ? result.files : [];
  const episodes = [];

  for (const file of files) {
    if (file.mimeType === FOLDER_MIME) {
      const childPath = normalizeFolderPath(subjectPath + file.name + "/");
      if (filter && !matchesQuery(file.name, filter)) continue;
      const child = await openaniPost(childPath, { password: null });
      for (const childFile of Array.isArray(child.files) ? child.files : []) {
        const item = toEpisode(url.origin, childPath, childFile, filter);
        if (item) episodes.push(item);
      }
      continue;
    }
    const item = toEpisode(url.origin, subjectPath, file, filter);
    if (item) episodes.push(item);
  }

  episodes.sort(compareEpisode);
  return html(renderSubject(url.origin, subjectPath, episodes));
}

async function handleSearchSubject(url, search, filter) {
  const directEpisodes = await episodesFromSeasonListing(filter || search, search);
  if (directEpisodes.length) return html(renderSubject(url.origin, filter || search, directEpisodes));

  let result;
  try {
    result = await openaniPost("/", { inputvalue: search });
  } catch {
    return html(renderSubject(url.origin, filter || search, []));
  }
  const files = Array.isArray(result.files) ? result.files : [];
  const folderNames = Array.isArray(result.foldername) ? result.foldername : [];
  const episodes = [];

  files.forEach((file, index) => {
    const base = normalizeFolderPath(folderNames[index] || "/");
    const item = toEpisode(url.origin, base, file, filter);
    if (item) episodes.push(item);
  });

  episodes.sort(compareEpisode);
  return html(renderSubject(url.origin, filter || search, dedupeBy(episodes, (episode) => episode.href)));
}

async function episodesFromSeasonListing(primaryTitle, fallbackTitle = "") {
  const titles = directSubjectTitles(primaryTitle, fallbackTitle);
  if (!titles.length) return [];
  const files = await currentSeasonFiles();
  for (const title of titles) {
    const episodes = seasonFilesForTitle(files, title);
    if (episodes.length) return episodes;
  }
  return [];
}

function directSubjectTitles(primaryTitle, fallbackTitle) {
  const candidates = [];
  for (const value of [primaryTitle, fallbackTitle]) {
    if (!value) continue;
    candidates.push(toTraditionalLite(cleanupKeyword(value)));
    candidates.push(...queryVariants(value));
  }
  return uniqueStrings(candidates)
    .filter((title) => /[\p{Script=Han}\p{Script=Hiragana}\p{Script=Katakana}]/u.test(title))
    .slice(0, 4);
}

function directSeasonDirs(date) {
  const year = date.getUTCFullYear();
  const month = date.getUTCMonth() + 1;
  const seasonMonth = month >= 10 ? 10 : month >= 7 ? 7 : month >= 4 ? 4 : 1;
  return [`${year}-${seasonMonth}`];
}

async function currentSeasonFiles() {
  const files = [];
  for (const dir of directSeasonDirs(new Date())) {
    try {
      const result = await openaniPost(`/${dir}/`, { password: null });
      for (const file of Array.isArray(result.files) ? result.files : []) {
        if (!file || !file.name || file.mimeType === FOLDER_MIME) continue;
        files.push({ ...file, folderPath: `/${dir}/` });
      }
    } catch {
      // Search fallback handles openani directory misses.
    }
  }
  return files;
}

function seasonFilesForTitle(files, title) {
  const titleKey = normalizeSearchText(title);
  const episodes = [];
  for (const file of files) {
    const parsed = parseAniFilename(file.name);
    if (!parsed.subject || normalizeSearchText(parsed.subject) !== titleKey) continue;
    const item = toEpisode("", file.folderPath, file);
    if (item) episodes.push(item);
  }
  return dedupeBy(episodes.sort(compareEpisode), (episode) => episode.href);
}

function handlePlay(url) {
  const path = normalizeFilePath(url.searchParams.get("path") || "");
  if (!path || !isVideoPath(path)) return text("Missing or invalid video path", 400);

  const videoUrl = OPENANI_ORIGIN + encodeOpenPath(path);
  return html(renderPlay(videoUrl, path), {
    "Cache-Control": "public, max-age=300",
  });
}

async function openaniPost(path, body) {
  const target = OPENANI_ORIGIN + encodeOpenPath(path);
  const response = await fetch(target, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "user-agent": "Animeko-OpenANI-Adapter/1.0",
    },
    body: JSON.stringify(body),
  });

  if (!response.ok) {
    throw new Error(`openani ${response.status} for ${path}`);
  }
  return response.json();
}

function toSearchItem(origin, base, file, query) {
  if (!file || !file.name) return null;
  const path = normalizePath(base + file.name + (file.mimeType === FOLDER_MIME ? "/" : ""));
  if (file.mimeType === FOLDER_MIME) {
    return {
      name: cleanupSubject(file.name),
      href: `${origin}/subject?path=${encodeURIComponent(path)}&q=${encodeURIComponent(query)}`,
      kind: "folder",
    };
  }
  if (isVideoPath(path)) {
    const parsed = parseAniFilename(file.name);
    return {
      name: parsed.subject || cleanupSubject(file.name),
      href: `${origin}/subject?search=${encodeURIComponent(query)}&q=${encodeURIComponent(parsed.subject || query)}`,
      kind: "file",
    };
  }
  return null;
}

function toEpisode(origin, folderPath, file, filter = "") {
  if (!file || !file.name || file.mimeType === FOLDER_MIME) return null;
  const path = normalizeFilePath(folderPath + file.name);
  if (!isVideoPath(path)) return null;
  if (filter && !matchesQuery(file.name, filter)) return null;

  const parsed = parseAniFilename(file.name);
  const label = [
    parsed.episode ? `第 ${parsed.episode} 集` : cleanupSubject(file.name),
    parsed.resolution,
    parsed.subtitle,
  ].filter(Boolean).join(" ");

  return {
    label,
    episode: parsed.episode,
    href: OPENANI_ORIGIN + encodeOpenPath(path),
    title: cleanupSubject(file.name),
    size: file.size || "",
    createdTime: file.createdTime || "",
  };
}

function parseAniFilename(name) {
  const base = name.replace(/\.[^.]+$/, "");
  const spaced = base.match(/^\[[^\]]+\]\s*(?<subject>.+?)\s*-\s*(?<episode>\d+(?:\.\d+)?|[A-Za-z]+\d*)\s*(?<tags>(?:\[[^\]]+\])*)/);
  const compact = base.match(/^\[[^\]]+\]\s*(?<subject>.+?)\[(?<episode>\d+(?:\.\d+)?|[A-Za-z]+\d*)\](?<tags>(?:\[[^\]]+\])*)/);
  const match = spaced || compact;
  const tags = match?.groups?.tags || "";
  return {
    subject: cleanupSubject(match?.groups?.subject || ""),
    episode: match?.groups?.episode || "",
    resolution: tags.match(/\[(?<value>\d{3,4}P|[248]K)\]/i)?.groups?.value?.toUpperCase() || "",
    subtitle: tags.match(/\[(?<value>CHT|CHS|BIG5|GB|繁|简)\]/i)?.groups?.value?.toUpperCase() || "",
  };
}

function renderHome(origin) {
  return page("ANi Open Adapter", `
    <h1>ANi Open Adapter</h1>
    <p>Animeko subscription: <a href="${origin}/sub.json">${origin}/sub.json</a></p>
    <form action="/search">
      <input name="wd" placeholder="keyword">
      <button type="submit">Search</button>
    </form>
  `);
}

function renderSearch(origin, keyword, items) {
  const body = items.length
    ? `<ul class="subject-list">${items.map((item) => `
        <li><a href="${escAttr(item.href)}">${esc(item.name)}</a></li>
      `).join("")}</ul>`
    : `<p class="empty">No results for ${esc(keyword)}</p>`;
  return page(`Search ${keyword}`, `<h1>Search</h1>${body}`);
}

function renderSubject(origin, subjectPath, episodes) {
  const title = cleanupSubject(subjectPath.split("/").filter(Boolean).at(-1) || subjectPath);
  const body = episodes.length
    ? `<ul class="episode-list">${episodes.map((episode) => `
        <li>
          <a href="${escAttr(episode.href)}" title="${escAttr(episode.title)}">${esc(episode.label)}</a>
        </li>
      `).join("")}</ul>`
    : `<p class="empty">No video files found.</p>`;
  return page(title, `<h1>${esc(title)}</h1>${body}`);
}

function renderPlay(videoUrl, path) {
  const title = cleanupSubject(path.split("/").pop() || path);
  return page(title, `
    <h1>${esc(title)}</h1>
    <video src="${escAttr(videoUrl)}" controls preload="metadata"></video>
    <p><a class="video-link" href="${escAttr(videoUrl)}">${esc(videoUrl)}</a></p>
  `);
}

function page(title, body) {
  return `<!doctype html>
<html lang="zh-Hant">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>${esc(title)}</title>
  <style>
    body { font-family: system-ui, sans-serif; margin: 24px; line-height: 1.5; }
    a { color: #0757a8; }
    input { font: inherit; padding: 6px 8px; }
    button { font: inherit; padding: 6px 10px; }
    video { width: min(100%, 960px); background: #111; }
  </style>
</head>
<body>${body}</body>
</html>`;
}

function subscription(origin) {
  return {
    exportedMediaSourceDataList: {
      mediaSources: [
        {
          factoryId: "web-selector",
          version: 2,
          arguments: {
            name: "ANi Open",
            description: "收录2019-01之后的新番及部分旧番归档，繁中翻译",
            iconUrl: `${origin}/favicon.ico`,
            searchConfig: {
              searchUrl: `${origin}/search?wd={keyword}`,
              searchUseOnlyFirstWord: false,
              searchRemoveSpecial: false,
              searchUseSubjectNamesCount: 1,
              rawBaseUrl: origin,
              requestInterval: 1000,
              subjectFormatId: "a",
              selectorSubjectFormatA: {
                selectLists: ".subject-list a",
                preferShorterName: true,
              },
              channelFormatId: "no-channel",
              selectorChannelFormatNoChannel: {
                selectEpisodes: ".episode-list a",
                selectEpisodeLinks: "",
                matchEpisodeSortFromName: "第\\s*(?<ep>\\d+(?:\\.\\d+)?)\\s*集",
              },
              defaultResolution: "1080P",
              defaultSubtitleLanguage: "CHT",
              onlySupportsPlayers: [],
              filterByEpisodeSort: true,
              filterBySubjectName: true,
              selectMedia: {
                distinguishSubjectName: true,
                distinguishChannelName: false,
              },
              matchVideo: {
                enableNestedUrl: false,
                matchNestedUrl: "$^",
                matchVideoUrl: "(^https?:\\/\\/.+\\.(mp4|webm|mkv|ts)(\\?.*)?$)",
                cookies: "",
                addHeadersToVideo: {
                  referer: "",
                },
              },
            },
            tier: 2,
          },
        },
      ],
    },
  };
}

async function indexedSearchItems(origin, keyword, env) {
  const manifest = await indexManifest(env);
  if (!manifest) return [];

  const searchKey = normalizeIndexKey(keyword);
  if (!searchKey) return [];

  const searchShard = await indexJson(env, manifest.kv.searchKeyPattern.replace("<hashPrefix>", await hashPrefix(searchKey, manifest)));
  const entries = searchShard?.[searchKey] || [];
  if (!entries.length) return [];

  const seasons = new Map();
  const items = [];
  for (const entry of entries) {
    const seasonTable = seasons.get(entry.season) ||
      await indexJson(env, manifest.kv.seasonKeyPattern.replace("<season>", entry.season));
    seasons.set(entry.season, seasonTable);

    const subject = seasonTable?.subjects?.[entry.titleKey];
    if (!subject?.episodes?.length) continue;
    items.push({
      name: subject.bgm?.nameCn || subject.aniTitle || entry.aniTitle || entry.titleKey,
      href: `${origin}/subject?season=${encodeURIComponent(entry.season)}&titleKey=${encodeURIComponent(entry.titleKey)}`,
      kind: "index",
    });
  }

  return dedupeBy(items, (item) => item.href);
}

async function indexedSubject(season, titleKey, env) {
  const manifest = await indexManifest(env);
  if (!manifest) return null;
  const seasonTable = await indexJson(env, manifest.kv.seasonKeyPattern.replace("<season>", season));
  return seasonTable?.subjects?.[titleKey] || null;
}

async function indexManifest(env) {
  return indexJson(env, "openani:v1:manifest");
}

async function indexJson(env, key) {
  const namespace = env?.OPENANI_INDEX;
  if (!namespace || !key) return null;
  try {
    return await namespace.get(key, { type: "json" });
  } catch {}
  try {
    return await namespace.get(key, "json");
  } catch {
    return null;
  }
}

async function hashPrefix(value, manifest) {
  const prefixLength = manifest?.kv?.searchShardPrefixLength || 1;
  return (await sha1Hex(value)).slice(0, prefixLength);
}

async function sha1Hex(value) {
  const bytes = new TextEncoder().encode(value);
  const digest = await crypto.subtle.digest("SHA-1", bytes);
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

async function cached(request, ctx, producer) {
  const cache = caches.default;
  const cacheUrl = new URL(request.url);
  cacheUrl.searchParams.set("__adapter_cache", CACHE_VERSION);
  const cacheKey = new Request(cacheUrl.toString(), request);
  const cachedResponse = await cache.match(cacheKey);
  if (cachedResponse) return cachedResponse;

  const response = await producer();
  const cacheable = new Response(response.body, response);
  cacheable.headers.set("Cache-Control", `public, max-age=${CACHE_TTL_SECONDS}`);
  ctx.waitUntil(cache.put(cacheKey, cacheable.clone()));
  return cacheable;
}

function json(value, init = {}) {
  return cors(new Response(JSON.stringify(value, null, 2), {
    ...init,
    headers: {
      "content-type": "application/json; charset=utf-8",
      ...(init.headers || {}),
    },
  }));
}

function html(value, headers = {}) {
  return cors(new Response(value, {
    headers: {
      "content-type": "text/html; charset=utf-8",
      ...headers,
    },
  }));
}

function text(value, status = 200) {
  return cors(new Response(value, {
    status,
    headers: { "content-type": "text/plain; charset=utf-8" },
  }));
}

function favicon() {
  return new Response(`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">
  <rect width="64" height="64" rx="12" fill="#101827"/>
  <path d="M14 44 27 12h10l13 32h-9l-2.4-6.7H25.2L22.8 44H14Zm13.7-14h8.6L32 18.1 27.7 30Z" fill="#f8fafc"/>
  <path d="M17 52h30" stroke="#38bdf8" stroke-width="5" stroke-linecap="round"/>
</svg>`, {
    headers: {
      "content-type": "image/svg+xml; charset=utf-8",
      "cache-control": "public, max-age=604800",
    },
  });
}

function cors(response) {
  response.headers.set("Access-Control-Allow-Origin", "*");
  response.headers.set("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS");
  response.headers.set("Access-Control-Allow-Headers", "content-type");
  return response;
}

function normalizeFolderPath(path) {
  const normalized = normalizePath(path);
  if (!normalized || normalized === "/") return "";
  return normalized.endsWith("/") ? normalized : `${normalized}/`;
}

function normalizeFilePath(path) {
  const normalized = normalizePath(path);
  return normalized.endsWith("/") ? normalized.slice(0, -1) : normalized;
}

function normalizePath(path) {
  const decoded = safeDecode(path || "").replaceAll("\\", "/");
  const parts = decoded.split("/").filter((part) => part && part !== "." && part !== "..");
  return `/${parts.join("/")}`;
}

function encodeOpenPath(path) {
  const normalized = normalizePath(path);
  if (normalized === "/") return "/";
  const encoded = normalized.split("/").map((part, index) => index === 0 ? "" : encodeURIComponent(part)).join("/");
  return String(path || "").endsWith("/") ? `${encoded}/` : encoded;
}

function isVideoPath(path) {
  return /\.(mp4|webm|mkv|ts)$/i.test(path);
}

function cleanupSubject(value) {
  return safeDecode(value)
    .replace(/\.[^.]+$/, "")
    .replace(/^\[[^\]]+\]\s*/, "")
    .replace(/\s*\[(?:\d{3,4}P|[248]K|Baha|WEB-DL|AAC|AVC|CHT|CHS|BIG5|GB)[^\]]*\]/gi, "")
    .trim();
}

function queryVariants(keyword) {
  const candidates = [];
  for (const base of expandKeyword(keyword)) {
    addSearchForms(candidates, base);
  }
  return uniqueLiteralStrings(candidates).slice(0, 12);
}

function matchesQuery(value, query) {
  const normalizedValue = normalizeSearchText(value);
  return queryVariants(query).some((candidate) => {
    const normalizedCandidate = normalizeSearchText(candidate);
    return normalizedCandidate && normalizedValue.includes(normalizedCandidate);
  });
}

function normalizeSearchText(value) {
  return toTraditionalLite(cleanupSubject(value))
    .toLowerCase()
    .replace(/[\s_\-:：·・!！?？,，.。()[\]【】「」『』《》〈〉]/g, "");
}

function normalizeIndexKey(value) {
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

function expandKeyword(keyword) {
  const cleaned = cleanupKeyword(keyword);
  const withoutParens = removeParenthetical(cleaned);
  const candidates = [cleaned, withoutParens];

  for (const value of [cleaned, withoutParens]) {
    for (const segment of cjkSegments(value)) {
      candidates.push(segment);
    }
    const cjkTail = value.match(/[\p{Script=Han}][\p{Script=Han}\p{Script=Hiragana}\p{Script=Katakana}ー々〆〤\s·・!！?？:：-]*$/u)?.[0];
    if (cjkTail) candidates.push(cjkTail);
  }

  return uniqueLiteralStrings(candidates)
    .map((item) => item.trim())
    .filter((item) => item.length >= 2);
}

function addSearchForms(output, keyword) {
  const cleaned = cleanupKeyword(keyword);
  if (!cleaned) return;
  const traditional = toTraditionalLite(cleaned);
  const compact = cleaned.replace(/\s+/g, "");
  const compactTraditional = toTraditionalLite(compact);

  output.push(traditional, compactTraditional);
}

function cleanupKeyword(value) {
  return safeDecode(value || "")
    .replace(/\.[^.]+$/, "")
    .replace(/^\[[^\]]+\]\s*/, "")
    .replace(/\s+/g, " ")
    .trim();
}

function removeParenthetical(value) {
  return value
    .replace(/（[^（）]*）/g, "")
    .replace(/\([^()]*\)/g, "")
    .replace(/【[^【】]*】/g, "")
    .replace(/\[[^\[\]]*\]/g, "")
    .trim();
}

function cjkSegments(value) {
  return [...value.matchAll(/[\p{Script=Han}\p{Script=Hiragana}\p{Script=Katakana}ー々〆〤]+/gu)]
    .map((match) => match[0])
    .filter((segment) => segment.length >= 3 && !isRegionMarker(segment));
}

function hasCjk(value) {
  return /[\p{Script=Han}\p{Script=Hiragana}\p{Script=Katakana}]/u.test(value);
}

function isRegionMarker(value) {
  return /^(港|台|臺|港台|港澳台|港澳臺|僅限港澳台地區|仅限港澳台地区)$/.test(value);
}

function uniqueStrings(items) {
  const seen = new Set();
  const result = [];
  for (const item of items) {
    const value = item.trim();
    const key = normalizeSearchText(value);
    if (!value || seen.has(key)) continue;
    seen.add(key);
    result.push(value);
  }
  return result;
}

function uniqueLiteralStrings(items) {
  const seen = new Set();
  const result = [];
  for (const item of items) {
    const value = item.trim();
    const key = value.toLowerCase().replace(/\s+/g, " ");
    if (!value || seen.has(key)) continue;
    seen.add(key);
    result.push(value);
  }
  return result;
}

function toTraditionalLite(value) {
  return toTraditional(value);
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

function safeDecode(value) {
  try {
    return decodeURIComponent(value);
  } catch {
    return value;
  }
}

function esc(value) {
  return String(value).replace(/[&<>"']/g, (char) => ({
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    '"': "&quot;",
    "'": "&#39;",
  })[char]);
}

function escAttr(value) {
  return esc(value);
}
