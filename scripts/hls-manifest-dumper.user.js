// ==UserScript==
// @name         Animeko HLS Manifest Dumper
// @namespace    https://github.com/open-ani/animeko
// @version      0.2.1
// @description  Capture HLS m3u8 manifests, preview individual TS segments, and export manual labels.
// @license      MIT
// @match        *://*/*
// @run-at       document-start
// @all-frames   true
// @grant        unsafeWindow
// @grant        GM_xmlhttpRequest
// @grant        GM_setClipboard
// @grant        GM_registerMenuCommand
// @require      https://cdn.jsdelivr.net/npm/hls.js@1/dist/hls.min.js
// @connect      *
// ==/UserScript==

(function () {
  "use strict";

  const PAGE = typeof unsafeWindow === "object" && unsafeWindow ? unsafeWindow : window;
  const SCRIPT_ID = "animeko-hls-manifest-dumper";
  const MAX_TEXT_CHARS = 2_000_000;
  const REQUEST_TIMEOUT_MS = 25_000;
  const state = {
    frameId: `${Date.now()}-${Math.random().toString(16).slice(2)}`,
    seen: new Map(),
    order: [],
    childReports: new Map(),
    panelOpen: true,
    enabled: true,
    scanTimer: 0,
    selectedRecordKey: "",
    selectedGroupByRecord: {},
    showSuspectGroupsOnly: true,
    previewHls: null,
    previewManifestUrl: "",
    previewResourceUrls: [],
    previewRecordKey: "",
    previewSegmentIndex: -1,
  };

  function isTopFrame() {
    return window.self === window.top;
  }

  function nowIso() {
    return new Date().toISOString();
  }

  function log(...args) {
    console.log("[Animeko HLS Dumper]", ...args);
  }

  function isM3u8Url(value) {
    return typeof value === "string" && /\.m3u8(?:[?#]|$)/i.test(value);
  }

  function decodeLoose(value) {
    let out = String(value || "");
    for (let i = 0; i < 3; i += 1) {
      try {
        const decoded = decodeURIComponent(out);
        if (decoded === out) break;
        out = decoded;
      } catch {
        break;
      }
    }
    return out;
  }

  function embeddedM3u8Urls(value) {
    const text = decodeLoose(value);
    const urls = new Set();
    try {
      const parsed = new URL(text, PAGE.location?.href || location.href);
      for (const queryValue of parsed.searchParams.values()) {
        for (const nested of embeddedM3u8Urls(queryValue)) urls.add(nested);
      }
    } catch {}
    if (urls.size) return Array.from(urls);

    const pattern = /https?:\/\/[^\s"'<>]+?\.m3u8(?:[^\s"'<>]*)?/gi;
    for (const match of text.matchAll(pattern)) {
      urls.add(match[0].replace(/[),;\]]+$/g, ""));
    }
    return Array.from(urls);
  }

  function absolutize(url) {
    return absolutizeFrom(PAGE.location?.href || location.href, url);
  }

  function absolutizeFrom(baseUrl, url) {
    try {
      return new URL(url, baseUrl).href;
    } catch {
      return String(url || "");
    }
  }

  function shortUrl(url) {
    try {
      const u = new URL(url);
      const parts = u.pathname.split("/").filter(Boolean).slice(-3).join("/");
      return `${u.host}/${parts}${u.search ? "?" : ""}`;
    } catch {
      return String(url).slice(0, 100);
    }
  }

  function sanitizeFilePart(value) {
    return String(value || "hls")
      .replace(/^https?:\/\//i, "")
      .replace(/[^a-z0-9._-]+/gi, "_")
      .replace(/^_+|_+$/g, "")
      .slice(0, 120) || "hls";
  }

  function httpGetText(url) {
    return new Promise((resolve, reject) => {
      GM_xmlhttpRequest({
        method: "GET",
        url,
        timeout: REQUEST_TIMEOUT_MS,
        responseType: "text",
        headers: {
          Accept: "application/vnd.apple.mpegurl, application/x-mpegURL, text/plain, */*",
        },
        onload: (res) => {
          const text = String(res.responseText || "");
          resolve({
            finalUrl: res.finalUrl || url,
            status: res.status,
            statusText: res.statusText || "",
            headers: res.responseHeaders || "",
            text: text.length > MAX_TEXT_CHARS ? text.slice(0, MAX_TEXT_CHARS) : text,
            truncated: text.length > MAX_TEXT_CHARS,
          });
        },
        ontimeout: () => reject(new Error(`timeout after ${REQUEST_TIMEOUT_MS}ms`)),
        onerror: () => reject(new Error("request failed")),
      });
    });
  }

  function httpGetArrayBuffer(url) {
    return new Promise((resolve, reject) => {
      GM_xmlhttpRequest({
        method: "GET",
        url,
        timeout: REQUEST_TIMEOUT_MS,
        responseType: "arraybuffer",
        headers: {
          Accept: "*/*",
        },
        onload: (res) => {
          if (res.status >= 200 && res.status < 400) {
            resolve(res.response);
          } else {
            reject(new Error(`HTTP ${res.status} ${url}`));
          }
        },
        ontimeout: () => reject(new Error(`timeout after ${REQUEST_TIMEOUT_MS}ms`)),
        onerror: () => reject(new Error(`request failed ${url}`)),
      });
    });
  }

  function parseManifestKind(text) {
    if (!text.includes("#EXTM3U")) return "not-m3u8";
    if (text.includes("#EXT-X-STREAM-INF")) return "master";
    if (text.includes("#EXTINF:")) return "media";
    return "unknown-m3u8";
  }

  function collectStats(text) {
    const lines = text.split(/\r?\n/);
    let segmentCount = 0;
    let discontinuityCount = 0;
    let keyCount = 0;
    let streamCount = 0;
    let totalDuration = 0;
    const variants = [];

    for (let i = 0; i < lines.length; i += 1) {
      const line = lines[i].trim();
      if (line.startsWith("#EXTINF:")) {
        segmentCount += 1;
        const duration = Number.parseFloat(line.slice("#EXTINF:".length).split(",", 1)[0]);
        if (Number.isFinite(duration)) totalDuration += duration;
      } else if (line === "#EXT-X-DISCONTINUITY") {
        discontinuityCount += 1;
      } else if (line.startsWith("#EXT-X-KEY")) {
        keyCount += 1;
      } else if (line.startsWith("#EXT-X-STREAM-INF")) {
        streamCount += 1;
        for (let j = i + 1; j < lines.length; j += 1) {
          const next = lines[j].trim();
          if (!next || next.startsWith("#")) continue;
          variants.push(next);
          break;
        }
      }
    }

    return {
      kind: parseManifestKind(text),
      lineCount: lines.length,
      segmentCount,
      discontinuityCount,
      keyCount,
      streamCount,
      variantCount: variants.length,
      totalDuration: Number(totalDuration.toFixed(3)),
      hasEndlist: text.includes("#EXT-X-ENDLIST"),
      hasProgramDateTime: text.includes("#EXT-X-PROGRAM-DATE-TIME"),
      hasCue: /#EXT-X-CUE-|#EXT-OATCLS-SCTE35|#EXT-X-DATERANGE/.test(text),
      variants,
    };
  }

  function parseExtinf(line) {
    const value = line.includes(":") ? line.split(":", 2)[1] : "";
    const [durationText, title = ""] = value.split(",", 2);
    const duration = Number.parseFloat(durationText.trim());
    return {
      duration: Number.isFinite(duration) ? duration : 0,
      title: title.trim(),
    };
  }

  function rewriteUriAttributes(line, manifestUrl) {
    return String(line).replace(/URI="([^"]+)"/g, (_match, value) => {
      return `URI="${absolutizeFrom(manifestUrl, value)}"`;
    });
  }

  function parseMediaSegments(text, manifestUrl) {
    if (parseManifestKind(text) !== "media") return [];

    const segments = [];
    const stateLines = new Map();
    let pendingLines = [];
    let pendingDuration = null;
    let pendingTitle = "";
    let pendingDiscontinuity = false;
    let groupIndex = 0;

    for (const raw of text.split(/\r?\n/)) {
      const line = raw.trim();
      if (!line) continue;

      if (line === "#EXT-X-DISCONTINUITY") {
        pendingDiscontinuity = true;
        continue;
      }
      if (line.startsWith("#EXT-X-KEY") || line.startsWith("#EXT-X-MAP")) {
        stateLines.set(line.split(":", 1)[0], rewriteUriAttributes(raw, manifestUrl));
        continue;
      }
      if (
        line.startsWith("#EXT-X-BYTERANGE") ||
        line.startsWith("#EXT-X-PROGRAM-DATE-TIME") ||
        line.startsWith("#EXT-X-DATERANGE") ||
        line.startsWith("#EXT-X-CUE-") ||
        line.startsWith("#EXT-OATCLS-SCTE35")
      ) {
        pendingLines.push(rewriteUriAttributes(raw, manifestUrl));
        continue;
      }
      if (line.startsWith("#EXTINF:")) {
        const extinf = parseExtinf(line);
        pendingDuration = extinf.duration;
        pendingTitle = extinf.title;
        pendingLines.push(raw);
        continue;
      }
      if (line.startsWith("#")) continue;
      if (pendingDuration == null) continue;

      if (pendingDiscontinuity && segments.length > 0) groupIndex += 1;
      const uri = absolutizeFrom(manifestUrl, line);
      pendingLines.push(uri);
      segments.push({
        index: segments.length,
        groupIndex,
        duration: pendingDuration,
        title: pendingTitle,
        uri,
        fileName: shortUrl(uri).split("/").pop() || uri,
        hasDiscontinuity: pendingDiscontinuity,
        stateLines: Array.from(stateLines.values()),
        rawLines: pendingLines.slice(),
      });
      pendingLines = [];
      pendingDuration = null;
      pendingTitle = "";
      pendingDiscontinuity = false;
    }

    return segments;
  }

  function buildSingleSegmentManifest(segment) {
    const targetDuration = Math.max(1, Math.ceil(segment.duration || 1));
    const out = [
      "#EXTM3U",
      "#EXT-X-VERSION:3",
      "#EXT-X-PLAYLIST-TYPE:VOD",
      `#EXT-X-TARGETDURATION:${targetDuration}`,
      "#EXT-X-MEDIA-SEQUENCE:0",
    ];
    for (const line of segment.stateLines || []) out.push(line);
    for (const line of segment.rawLines || []) {
      const trimmed = String(line).trim();
      if (
        trimmed === "#EXT-X-DISCONTINUITY" ||
        trimmed.startsWith("#EXT-X-KEY") ||
        trimmed.startsWith("#EXT-X-MAP")
      ) {
        continue;
      }
      out.push(line);
    }
    out.push("#EXT-X-ENDLIST");
    return out.join("\n") + "\n";
  }

  function guessSegmentMime(url) {
    const lower = String(url || "").split("?", 1)[0].toLowerCase();
    if (lower.endsWith(".m4s") || lower.endsWith(".mp4") || lower.endsWith(".m4v")) return "video/mp4";
    if (lower.endsWith(".aac")) return "audio/aac";
    if (lower.endsWith(".mp3")) return "audio/mpeg";
    return "video/mp2t";
  }

  function firstUriAttribute(line) {
    const match = String(line || "").match(/URI="([^"]+)"/);
    return match ? match[1] : "";
  }

  async function blobUrlFor(url, type) {
    const bytes = await httpGetArrayBuffer(url);
    const blobUrl = URL.createObjectURL(new Blob([bytes], { type }));
    state.previewResourceUrls.push(blobUrl);
    return blobUrl;
  }

  async function rewriteStateLineToBlob(line) {
    const uri = firstUriAttribute(line);
    if (!uri) return line;
    const blobUrl = await blobUrlFor(uri, "application/octet-stream");
    return String(line).replace(/URI="([^"]+)"/, `URI="${blobUrl}"`);
  }

  async function buildBlobBackedSegmentManifest(segment) {
    const targetDuration = Math.max(1, Math.ceil(segment.duration || 1));
    const out = [
      "#EXTM3U",
      "#EXT-X-VERSION:3",
      "#EXT-X-PLAYLIST-TYPE:VOD",
      `#EXT-X-TARGETDURATION:${targetDuration}`,
      "#EXT-X-MEDIA-SEQUENCE:0",
    ];
    for (const line of segment.stateLines || []) out.push(await rewriteStateLineToBlob(line));

    const segmentBlobUrl = await blobUrlFor(segment.uri, guessSegmentMime(segment.uri));
    for (const line of segment.rawLines || []) {
      const trimmed = String(line).trim();
      if (
        trimmed === "#EXT-X-DISCONTINUITY" ||
        trimmed.startsWith("#EXT-X-KEY") ||
        trimmed.startsWith("#EXT-X-MAP")
      ) {
        continue;
      }
      out.push(trimmed === segment.uri ? segmentBlobUrl : line);
    }
    out.push("#EXT-X-ENDLIST");
    return out.join("\n") + "\n";
  }

  function revokePreviewUrls() {
    if (state.previewHls) {
      state.previewHls.destroy();
      state.previewHls = null;
    }
    if (state.previewManifestUrl) {
      URL.revokeObjectURL(state.previewManifestUrl);
      state.previewManifestUrl = "";
    }
    for (const url of state.previewResourceUrls) URL.revokeObjectURL(url);
    state.previewResourceUrls = [];
  }

  function makeRecord(url, reason) {
    const absolute = absolutize(url);
    const existing = state.seen.get(absolute);
    if (existing) {
      existing.reasons.add(reason);
      existing.lastSeenAt = nowIso();
      publishReport();
      renderPanel();
      return existing;
    }

    const record = {
      id: state.order.length + 1,
      frameId: state.frameId,
      pageUrl: location.href,
      pageTitle: document.title || "",
      referrer: document.referrer || "",
      url: absolute,
      finalUrl: "",
      reasons: new Set([reason]),
      firstSeenAt: nowIso(),
      lastSeenAt: nowIso(),
      status: "queued",
      httpStatus: 0,
      httpStatusText: "",
      responseHeaders: "",
      error: "",
      text: "",
      truncated: false,
      stats: null,
      segments: [],
      labels: {},
    };
    state.seen.set(absolute, record);
    state.order.push(record);
    fetchManifest(record);
    publishReport();
    renderPanel();
    return record;
  }

  async function fetchManifest(record) {
    record.status = "fetching";
    publishReport();
    renderPanel();
    try {
      const res = await httpGetText(record.url);
      record.finalUrl = res.finalUrl;
      record.httpStatus = res.status;
      record.httpStatusText = res.statusText;
      record.responseHeaders = res.headers;
      record.text = res.text;
      record.truncated = res.truncated;
      record.stats = collectStats(res.text);
      record.segments = parseMediaSegments(res.text, record.finalUrl || record.url);
      record.status = res.status >= 200 && res.status < 400 ? "ok" : "http-error";
      if (record.stats?.kind === "master") {
        for (const variant of record.stats.variants || []) {
          enqueueCandidate(absolutizeFrom(record.finalUrl || record.url, variant), `master.variant#${record.id}`);
        }
      }
      log("captured", record.id, record.status, record.stats, record.url);
    } catch (error) {
      record.status = "fetch-error";
      record.error = String(error && error.message ? error.message : error);
      log("fetch failed", record.id, record.url, record.error);
    }
    publishReport();
    renderPanel();
  }

  function enqueueCandidate(url, reason) {
    if (!state.enabled || typeof url !== "string") return;
    if (String(url).startsWith("blob:")) return;
    const nestedUrls = embeddedM3u8Urls(url);
    if (nestedUrls.length) {
      for (const nested of nestedUrls) makeRecord(nested, `${reason}.embedded`);
      return;
    }
    if (!isM3u8Url(url)) return;
    makeRecord(url, reason);
  }

  function recordToJson(record, includeText) {
    return {
      id: record.id,
      frameId: record.frameId,
      pageUrl: record.pageUrl,
      pageTitle: record.pageTitle,
      referrer: record.referrer,
      url: record.url,
      finalUrl: record.finalUrl,
      reasons: Array.from(record.reasons),
      firstSeenAt: record.firstSeenAt,
      lastSeenAt: record.lastSeenAt,
      status: record.status,
      httpStatus: record.httpStatus,
      httpStatusText: record.httpStatusText,
      responseHeaders: record.responseHeaders,
      error: record.error,
      truncated: record.truncated,
      stats: record.stats,
      segments: record.segments,
      labels: record.labels,
      text: includeText ? record.text : undefined,
    };
  }

  function recordKey(record) {
    return `${record.frameId}:${record.id}`;
  }

  function currentRecords() {
    return state.order.map((record) => recordToJson(record, true));
  }

  function allRecords() {
    const local = currentRecords().map((record) => ({ ...record, captureFrame: "local" }));
    if (!isTopFrame()) return local;
    const childRecords = [];
    for (const report of state.childReports.values()) {
      for (const record of report.records || []) {
        childRecords.push({
          ...record,
          captureFrame: "iframe",
          captureFramePageUrl: report.pageUrl,
          captureFramePageTitle: report.pageTitle,
        });
      }
    }
    return local.concat(childRecords);
  }

  function exportJson() {
    return {
      exportedAt: nowIso(),
      frameId: state.frameId,
      pageUrl: location.href,
      pageTitle: document.title || "",
      topFrame: isTopFrame(),
      records: allRecords(),
      childReports: isTopFrame() ? Array.from(state.childReports.values()) : [],
    };
  }

  function exportJsonl() {
    return allRecords().map((record) => JSON.stringify(record)).join("\n") + "\n";
  }

  function exportDirectoryText() {
    const files = [];
    const records = allRecords();
    const summary = records.map((record) => ({ ...record, text: undefined }));
    files.push({
      name: "summary.json",
      text: JSON.stringify({
        exportedAt: nowIso(),
        pageUrl: location.href,
        pageTitle: document.title || "",
        records: summary,
      }, null, 2),
    });
    for (const record of records) {
      const framePart = sanitizeFilePart(record.frameId || record.captureFrame || "frame").slice(0, 18);
      const base = `${framePart}-${String(record.id).padStart(3, "0")}-${record.stats?.kind || "unknown"}-${sanitizeFilePart(shortUrl(record.url))}`;
      files.push({ name: `${base}.m3u8`, text: record.text || "" });
      files.push({ name: `${base}.json`, text: JSON.stringify({ ...record, text: undefined }, null, 2) });
    }
    return files.map((file) => `===== ${file.name} =====\n${file.text}`).join("\n\n");
  }

  function downloadText(filename, text, type) {
    const url = URL.createObjectURL(new Blob([text], { type }));
    const a = document.createElement("a");
    a.href = url;
    a.download = filename;
    document.documentElement.appendChild(a);
    a.click();
    a.remove();
    window.setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  function downloadJson() {
    downloadText(`hls-manifests-${Date.now()}.json`, JSON.stringify(exportJson(), null, 2), "application/json");
  }

  function downloadJsonl() {
    downloadText(`hls-manifests-${Date.now()}.jsonl`, exportJsonl(), "application/x-ndjson");
  }

  function downloadBundleText() {
    downloadText(`hls-manifests-${Date.now()}.txt`, exportDirectoryText(), "text/plain");
  }

  async function copyJsonl() {
    const text = exportJsonl();
    try {
      if (typeof GM_setClipboard === "function") GM_setClipboard(text, "text");
      else await navigator.clipboard.writeText(text);
      renderPanel("JSONL copied");
    } catch (error) {
      console.error("[Animeko HLS Dumper] copy failed", error, text);
      window.prompt("Copy JSONL", text);
    }
  }

  function findRecordByKey(key) {
    for (const record of state.order) {
      if (recordKey(record) === key) return record;
    }
    for (const report of state.childReports.values()) {
      for (const record of report.records || []) {
        if (`${record.frameId}:${record.id}` === key) return record;
      }
    }
    return null;
  }

  function mediaRecords() {
    return allRecords().filter((record) => record.stats?.kind === "media" && record.segments?.length);
  }

  function selectedRecord() {
    const records = mediaRecords();
    if (!records.length) return null;
    if (state.selectedRecordKey) {
      const selected = records.find((record) => `${record.frameId}:${record.id}` === state.selectedRecordKey);
      if (selected) return selected;
    }
    return records[records.length - 1];
  }

  function segmentGroups(record) {
    const groups = [];
    for (const segment of record?.segments || []) {
      let group = groups.find((item) => item.index === segment.groupIndex);
      if (!group) {
        group = {
          index: segment.groupIndex,
          segments: [],
          duration: 0,
          start: segment.index,
          end: segment.index,
          hosts: new Set(),
          pathRoots: new Set(),
        };
        groups.push(group);
      }
      group.segments.push(segment);
      group.duration += segment.duration || 0;
      group.end = segment.index;
      try {
        const url = new URL(segment.uri);
        group.hosts.add(url.host);
        group.pathRoots.add(url.pathname.split("/").slice(0, -1).join("/"));
      } catch {}
    }
    return groups.map((group) => ({
      ...group,
      count: group.segments.length,
      duration: Number(group.duration.toFixed(3)),
      hosts: Array.from(group.hosts),
      pathRoots: Array.from(group.pathRoots),
    }));
  }

  function groupFileSignature(group) {
    return group.segments
      .map((segment) => String(segment.fileName || "").split("?", 1)[0])
      .join("|");
  }

  function numericModel(segments) {
    const values = [];
    for (const segment of segments || []) {
      const name = String(segment.fileName || "").split("?", 1)[0];
      const match = name.match(/^(.*?)(\d{1,7})\.ts$/);
      if (match) values.push({ prefix: match[1], number: Number(match[2]) });
    }
    if (!values.length) return "";

    const prefixCounts = new Map();
    for (const value of values) {
      prefixCounts.set(value.prefix, (prefixCounts.get(value.prefix) || 0) + 1);
    }
    let prefix = "";
    let prefixCount = 0;
    for (const [candidate, count] of prefixCounts) {
      if (count > prefixCount) {
        prefix = candidate;
        prefixCount = count;
      }
    }
    if (prefixCount / segments.length < 0.8) return "";

    const numbers = values.filter((value) => value.prefix === prefix).map((value) => value.number);
    const deltaCounts = new Map();
    for (let index = 1; index < numbers.length; index += 1) {
      const delta = numbers[index] - numbers[index - 1];
      deltaCounts.set(delta, (deltaCounts.get(delta) || 0) + 1);
    }
    let bestDelta = null;
    let bestDeltaCount = 0;
    for (const [delta, count] of deltaCounts) {
      if (count > bestDeltaCount) {
        bestDelta = delta;
        bestDeltaCount = count;
      }
    }
    if (bestDelta !== 1 || bestDeltaCount / Math.max(1, numbers.length - 1) < 0.5) return "";
    return prefix;
  }

  function trailingNum(name, prefix) {
    if (!prefix) return null;
    const clean = String(name || "").split("?", 1)[0];
    if (!clean.startsWith(prefix) || !clean.endsWith(".ts")) return null;
    const suffix = clean.slice(prefix.length, -3);
    return /^\d+$/.test(suffix) ? Number(suffix) : null;
  }

  function groupLabelCounts(record, group) {
    const labels = record.labels || {};
    return group.segments.reduce((acc, segment) => {
      const value = labels[String(segment.index)];
      const label = typeof value === "string" ? value : value?.label;
      if (label) acc[label] = (acc[label] || 0) + 1;
      return acc;
    }, {});
  }

  function suspiciousGroupReasons(record, group, groups, signatureCounts) {
    const reasons = [];
    const groupPosition = groups.indexOf(group);
    const dense = groups.length >= 20 || Boolean(record?.segments?.length && groups.length / record.segments.length > 0.08);
    const repeatShort = (signatureCounts.get(groupFileSignature(group)) || 0) > 1 && group.count <= 12 && group.duration <= 45;
    const short = group.count <= 12 || group.duration <= 45;
    const paths = group.segments.map((segment) => {
      try {
        return new URL(segment.uri).pathname.toLowerCase();
      } catch {
        return String(segment.uri || "").toLowerCase();
      }
    }).join(" ");
    const strongPath = ["adjump", "/ad/", "/ads/", "advert"].some((token) => paths.includes(token));
    const prev = groups[groupPosition - 1];
    const next = groups[groupPosition + 1];
    const sandwiched = Boolean(
      prev &&
      next &&
      prev.duration >= 60 &&
      next.duration >= 60 &&
      group.duration <= 45 &&
      group.count >= 2
    );
    const lowDensityShort = !dense && short && group.count >= 2;
    const denseTiny = dense && (group.count <= 3 || group.duration <= 12) && prev && next;

    let sequenceIsland = false;
    const seqPrefix = dense ? numericModel(record?.segments || []) : "";
    if (dense && seqPrefix) {
      const names = group.segments.map((segment) => String(segment.fileName || "").split("?", 1)[0]);
      const numbers = names.map((name) => trailingNum(name, seqPrefix));
      const first = numbers[0];
      const last = numbers[numbers.length - 1];
      const previous = group.start > 0
        ? trailingNum(record.segments[group.start - 1]?.fileName, seqPrefix)
        : null;
      const following = group.end + 1 < (record.segments || []).length
        ? trailingNum(record.segments[group.end + 1]?.fileName, seqPrefix)
        : null;
      const linearIsland = numbers.every((number) => number != null) &&
        numbers.every((number, index) => index === 0 || number === numbers[index - 1] + 1);
      sequenceIsland = previous != null &&
        following != null &&
        first != null &&
        last != null &&
        linearIsland &&
        following === previous + 1 &&
        Math.abs(first - previous) > 1000 &&
        Math.abs(last - following) > 1000 &&
        group.count >= 2 &&
        short;
    }

    if (strongPath) reasons.push("strong_path");
    if (repeatShort) reasons.push("repeat_short");
    if (sandwiched) reasons.push("sandwiched_short");
    if (lowDensityShort) reasons.push("low_density_short");
    if (sequenceIsland) reasons.push("sequence_island");
    if (denseTiny) reasons.push("dense_tiny");
    return Array.from(new Set(reasons));
  }

  function setSegmentLabel(key, segmentIndex, label) {
    const record = findRecordByKey(key);
    if (!record) return;
    if (!record.labels || typeof record.labels !== "object") record.labels = {};
    record.labels[String(segmentIndex)] = {
      label,
      labeledAt: nowIso(),
    };
    publishReport();
    renderPanel();
  }

  function hlsConstructor() {
    try {
      if (typeof Hls !== "undefined") return Hls;
    } catch {}
    return PAGE.Hls || window.Hls || globalThis.Hls || null;
  }

  function ensurePreviewPlayer() {
    let box = document.getElementById(`${SCRIPT_ID}-preview`);
    if (box) return box.querySelector("video");

    box = document.createElement("div");
    box.id = `${SCRIPT_ID}-preview`;
    box.style.cssText = [
      "position:fixed",
      "left:12px",
      "bottom:12px",
      "z-index:2147483647",
      "width:420px",
      "background:#111",
      "color:#fff",
      "border:1px solid rgba(255,255,255,.25)",
      "box-shadow:0 10px 30px rgba(0,0,0,.4)",
      "padding:8px",
      "font:12px/1.4 system-ui,sans-serif",
    ].join(";");

    const title = document.createElement("div");
    title.id = `${SCRIPT_ID}-preview-title`;
    title.textContent = "TS preview";
    title.style.cssText = "margin-bottom:6px;font-weight:700";
    box.appendChild(title);

    const video = document.createElement("video");
    video.controls = true;
    video.autoplay = true;
    video.muted = false;
    video.style.cssText = "display:block;width:100%;max-height:240px;background:#000";
    box.appendChild(video);

    const close = document.createElement("button");
    close.textContent = "close preview";
    close.style.cssText = "margin-top:6px;font:12px system-ui,sans-serif;padding:4px 6px;cursor:pointer";
    close.addEventListener("click", () => {
      revokePreviewUrls();
      box.remove();
    });
    box.appendChild(close);
    document.documentElement.appendChild(box);
    return video;
  }

  async function playSegment(key, segmentIndex) {
    const record = findRecordByKey(key);
    const segment = record?.segments?.[segmentIndex];
    if (!record || !segment) return;

    const video = ensurePreviewPlayer();
    const title = document.getElementById(`${SCRIPT_ID}-preview-title`);
    if (title) {
      title.textContent = `#${record.id} seg ${segment.index} group ${segment.groupIndex} ${segment.duration.toFixed(3)}s`;
    }

    revokePreviewUrls();

    renderPanel(`loading segment ${segment.index}`);
    const manifest = await buildBlobBackedSegmentManifest(segment);
    state.previewManifestUrl = URL.createObjectURL(new Blob([manifest], { type: "application/vnd.apple.mpegurl" }));
    state.previewRecordKey = key;
    state.previewSegmentIndex = segmentIndex;

    const HlsClass = hlsConstructor();
    if (HlsClass && typeof HlsClass.isSupported === "function" && HlsClass.isSupported()) {
      state.previewHls = new HlsClass({ enableWorker: false });
      state.previewHls.loadSource(state.previewManifestUrl);
      state.previewHls.attachMedia(video);
      video.play?.().catch(() => {});
      renderPanel(`playing segment ${segment.index}`);
      return;
    }

    if (video.canPlayType("application/vnd.apple.mpegurl")) {
      video.src = state.previewManifestUrl;
    } else {
      video.src = segment.uri;
    }
    video.play?.().catch(() => {});
    renderPanel(`playing segment ${segment.index}`);
  }

  function publishReport() {
    if (isTopFrame()) return;
    const report = {
      frameId: state.frameId,
      pageUrl: location.href,
      pageTitle: document.title || "",
      count: state.order.length,
      okCount: state.order.filter((r) => r.status === "ok").length,
      mediaCount: state.order.filter((r) => r.stats?.kind === "media").length,
      masterCount: state.order.filter((r) => r.stats?.kind === "master").length,
      last: state.order.length ? recordToJson(state.order[state.order.length - 1], false) : null,
      records: currentRecords(),
    };
    try {
      window.top.postMessage({ type: `${SCRIPT_ID}:report`, report }, "*");
    } catch {}
  }

  function installFrameMessaging() {
    window.addEventListener("message", (event) => {
      const data = event.data;
      if (!data || typeof data !== "object") return;
      if (isTopFrame() && data.type === `${SCRIPT_ID}:report` && data.report) {
        const existing = state.childReports.get(data.report.frameId);
        if (existing?.records?.length && data.report.records?.length) {
          const oldByKey = new Map(existing.records.map((record) => [`${record.frameId}:${record.id}`, record]));
          for (const record of data.report.records) {
            const old = oldByKey.get(`${record.frameId}:${record.id}`);
            if (old?.labels && Object.keys(old.labels).length) {
              record.labels = { ...record.labels, ...old.labels };
            }
          }
        }
        state.childReports.set(data.report.frameId, data.report);
        renderPanel();
      }
    });
  }

  function scanKnownPlaces() {
    if (!state.enabled) return;
    try {
      for (const entry of PAGE.performance?.getEntriesByType?.("resource") || []) {
        enqueueCandidate(entry?.name, "performance");
      }
    } catch {}
    try {
      for (const video of PAGE.document?.querySelectorAll?.("video") || []) {
        enqueueCandidate(video.currentSrc, "video.currentSrc");
        enqueueCandidate(video.src, "video.src");
      }
    } catch {}
    try {
      for (const iframe of PAGE.document?.querySelectorAll?.("iframe") || []) {
        enqueueCandidate(iframe.src, "iframe.src");
      }
    } catch {}
    for (const key of ["player_aaaa", "player_data", "MacPlayer", "__PLAYER__", "player", "Player"]){
      try {
        const value = PAGE[key];
        if (!value) continue;
        scanObjectForUrls(value, key, 0);
      } catch {}
    }
  }

  function scanObjectForUrls(value, reason, depth) {
    if (depth > 3 || value == null) return;
    if (typeof value === "string") {
      enqueueCandidate(value, reason);
      return;
    }
    if (typeof value !== "object") return;
    if (Array.isArray(value)) {
      for (let i = 0; i < Math.min(value.length, 50); i += 1) scanObjectForUrls(value[i], `${reason}[${i}]`, depth + 1);
      return;
    }
    for (const key of Object.keys(value).slice(0, 80)) {
      scanObjectForUrls(value[key], `${reason}.${key}`, depth + 1);
    }
  }

  function installHlsHook() {
    const timer = window.setInterval(() => {
      const Hls = PAGE.Hls;
      if (!Hls || Hls.__animekoHlsDumperHooked) return;
      Hls.__animekoHlsDumperHooked = true;
      const originalLoadSource = Hls.prototype.loadSource;
      Hls.prototype.loadSource = function (url) {
        enqueueCandidate(url, "hls.loadSource");
        return originalLoadSource.apply(this, arguments);
      };
      window.clearInterval(timer);
      log("hooked hls.js");
    }, 200);
  }

  function installVideoHook() {
    const ElementPrototype = PAGE.Element?.prototype || Element.prototype;
    const HTMLMediaElementPrototype = PAGE.HTMLMediaElement?.prototype || HTMLMediaElement.prototype;
    const HTMLVideoElementClass = PAGE.HTMLVideoElement || HTMLVideoElement;

    const originalSetAttribute = ElementPrototype.setAttribute;
    ElementPrototype.setAttribute = function (name, value) {
      if (this instanceof HTMLVideoElementClass && String(name).toLowerCase() === "src") {
        enqueueCandidate(value, "video.setAttribute");
      }
      return originalSetAttribute.apply(this, arguments);
    };

    const descriptor = Object.getOwnPropertyDescriptor(HTMLMediaElementPrototype, "src");
    if (descriptor?.set && descriptor?.get) {
      Object.defineProperty(HTMLMediaElementPrototype, "src", {
        configurable: true,
        enumerable: descriptor.enumerable,
        get: descriptor.get,
        set(value) {
          if (this instanceof HTMLVideoElementClass) enqueueCandidate(value, "video.src.setter");
          return descriptor.set.call(this, value);
        },
      });
    }
  }

  function installNetworkHooks() {
    if (PAGE.__animekoHlsDumperNetworkHooked) return;
    PAGE.__animekoHlsDumperNetworkHooked = true;

    const originalFetch = PAGE.fetch;
    if (typeof originalFetch === "function") {
      PAGE.fetch = function (input) {
        const url = typeof input === "string" ? input : input?.url;
        enqueueCandidate(url, "fetch");
        return originalFetch.apply(this, arguments).then((response) => {
          enqueueCandidate(response?.url || url, "fetch.response");
          return response;
        });
      };
    }

    const xhrPrototype = PAGE.XMLHttpRequest?.prototype;
    if (xhrPrototype) {
      const originalOpen = xhrPrototype.open;
      xhrPrototype.open = function (_method, url) {
        this.__animekoHlsDumperUrl = url;
        enqueueCandidate(url, "xhr.open");
        return originalOpen.apply(this, arguments);
      };
      const originalSend = xhrPrototype.send;
      xhrPrototype.send = function () {
        this.addEventListener("load", () => enqueueCandidate(this.responseURL || this.__animekoHlsDumperUrl, "xhr.load"));
        return originalSend.apply(this, arguments);
      };
    }
  }

  function installPerformanceObserver() {
    try {
      const observer = new PAGE.PerformanceObserver((list) => {
        for (const entry of list.getEntries()) enqueueCandidate(entry?.name, "performance-observer");
      });
      observer.observe({ type: "resource", buffered: true });
    } catch {}
    state.scanTimer = window.setInterval(scanKnownPlaces, 2000);
  }

  function renderPanel(statusText) {
    if (!isTopFrame() || !state.panelOpen) return;
    const old = document.getElementById(`${SCRIPT_ID}-panel`);
    if (old) old.remove();

    const localOk = state.order.filter((r) => r.status === "ok").length;
    const localMedia = state.order.filter((r) => r.stats?.kind === "media").length;
    const child = Array.from(state.childReports.values());
    const childCount = child.reduce((sum, item) => sum + (item.count || 0), 0);

    const panel = document.createElement("div");
    panel.id = `${SCRIPT_ID}-panel`;
    panel.style.cssText = [
      "position:fixed",
      "right:12px",
      "bottom:12px",
      "z-index:2147483647",
      "width:560px",
      "max-height:70vh",
      "overflow:auto",
      "background:rgba(18,18,18,.95)",
      "color:#f5f5f5",
      "font:12px/1.45 system-ui,-apple-system,BlinkMacSystemFont,sans-serif",
      "border:1px solid rgba(255,255,255,.25)",
      "box-shadow:0 10px 30px rgba(0,0,0,.4)",
      "padding:10px",
    ].join(";");

    const title = document.createElement("div");
    title.style.cssText = "font-weight:700;margin-bottom:6px";
    title.textContent = "Animeko HLS Manifest Dumper";
    panel.appendChild(title);

    const summary = document.createElement("div");
    summary.textContent = statusText || `local ${state.order.length} (${localOk} ok, ${localMedia} media), iframes ${childCount}`;
    panel.appendChild(summary);

    const buttons = document.createElement("div");
    buttons.style.cssText = "display:flex;flex-wrap:wrap;gap:6px;margin:8px 0";
    for (const [label, action] of [
      [state.enabled ? "capture ON" : "capture OFF", () => {
        state.enabled = !state.enabled;
        if (state.enabled) scanKnownPlaces();
        renderPanel();
      }],
      ["scan", scanKnownPlaces],
      ["download JSON", downloadJson],
      ["download JSONL", downloadJsonl],
      ["download bundle", downloadBundleText],
      ["copy JSONL", copyJsonl],
      ["hide", () => {
        state.panelOpen = false;
        panel.remove();
      }],
    ]) {
      const btn = document.createElement("button");
      btn.textContent = label;
      btn.style.cssText = "font:12px system-ui,sans-serif;padding:4px 6px;cursor:pointer";
      btn.addEventListener("click", action);
      buttons.appendChild(btn);
    }
    panel.appendChild(buttons);

    const records = mediaRecords();
    const selected = selectedRecord();
    if (selected) state.selectedRecordKey = `${selected.frameId}:${selected.id}`;

    const playlistTitle = document.createElement("div");
    playlistTitle.style.cssText = "margin:8px 0 4px;font-weight:700";
    playlistTitle.textContent = `media playlists: ${records.length}`;
    panel.appendChild(playlistTitle);

    const playlistRows = document.createElement("div");
    playlistRows.style.cssText = "display:grid;gap:4px;margin-bottom:8px";
    for (const record of records.slice(-8).reverse()) {
      const key = `${record.frameId}:${record.id}`;
      const row = document.createElement("div");
      row.style.cssText = [
        "display:grid",
        "grid-template-columns:auto 1fr auto",
        "gap:6px",
        "align-items:center",
        "border:1px solid rgba(255,255,255,.14)",
        "padding:4px",
        key === state.selectedRecordKey ? "background:#26344d" : "background:#191919",
      ].join(";");

      const select = document.createElement("button");
      select.textContent = "select";
      select.style.cssText = "font:12px system-ui,sans-serif;padding:3px 6px;cursor:pointer";
      select.addEventListener("click", () => {
        state.selectedRecordKey = key;
        renderPanel();
      });
      row.appendChild(select);

      const info = document.createElement("div");
      info.style.cssText = "overflow:hidden;text-overflow:ellipsis;white-space:nowrap";
      info.title = record.url;
      info.textContent = `#${record.id} seg=${record.segments.length} dis=${record.stats.discontinuityCount} dur=${record.stats.totalDuration}s ${shortUrl(record.url)}`;
      row.appendChild(info);

      const labels = record.labels || {};
      const counts = Object.values(labels).reduce((acc, item) => {
        const label = typeof item === "string" ? item : item?.label;
        if (label) acc[label] = (acc[label] || 0) + 1;
        return acc;
      }, {});
      const labelInfo = document.createElement("div");
      labelInfo.textContent = `ad ${counts.ad || 0} / ok ${counts.content || 0}`;
      row.appendChild(labelInfo);
      playlistRows.appendChild(row);
    }
    panel.appendChild(playlistRows);

    if (selected) {
      const key = `${selected.frameId}:${selected.id}`;
      const groups = segmentGroups(selected);
      const signatureCounts = groups.reduce((acc, group) => {
        const signature = groupFileSignature(group);
        acc.set(signature, (acc.get(signature) || 0) + 1);
        return acc;
      }, new Map());
      const groupsWithReasons = groups.map((group) => ({
        ...group,
        reasons: suspiciousGroupReasons(selected, group, groups, signatureCounts),
        labels: groupLabelCounts(selected, group),
      }));
      const suspectGroups = groupsWithReasons.filter((group) => group.reasons.length);
      const visibleGroups = state.showSuspectGroupsOnly && suspectGroups.length ? suspectGroups : groupsWithReasons;
      if (!visibleGroups.some((group) => group.index === state.selectedGroupByRecord[key])) {
        state.selectedGroupByRecord[key] = (visibleGroups[0] || groupsWithReasons[0])?.index ?? 0;
      }
      const selectedGroup = groupsWithReasons.find((group) => group.index === state.selectedGroupByRecord[key])
        || visibleGroups[0]
        || groupsWithReasons[0];

      const segmentTitle = document.createElement("div");
      segmentTitle.style.cssText = "margin:8px 0 4px;font-weight:700";
      segmentTitle.textContent = `groups for #${selected.id}`;
      panel.appendChild(segmentTitle);

      const groupToolbar = document.createElement("div");
      groupToolbar.style.cssText = "display:flex;flex-wrap:wrap;gap:6px;margin:6px 0";
      const groupPosition = visibleGroups.findIndex((group) => group.index === selectedGroup?.index);
      for (const [label, action] of [
        [state.showSuspectGroupsOnly ? "show all groups" : "suspects only", () => {
          state.showSuspectGroupsOnly = !state.showSuspectGroupsOnly;
          renderPanel();
        }],
        ["prev group", () => {
          const nextIndex = Math.max(0, groupPosition - 1);
          state.selectedGroupByRecord[key] = visibleGroups[nextIndex]?.index ?? selectedGroup?.index ?? 0;
          renderPanel();
        }],
        ["next group", () => {
          const nextIndex = Math.min(visibleGroups.length - 1, groupPosition + 1);
          state.selectedGroupByRecord[key] = visibleGroups[nextIndex]?.index ?? selectedGroup?.index ?? 0;
          renderPanel();
        }],
      ]) {
        const btn = document.createElement("button");
        btn.textContent = label;
        btn.style.cssText = "font:12px system-ui,sans-serif;padding:3px 6px;cursor:pointer";
        btn.addEventListener("click", action);
        groupToolbar.appendChild(btn);
      }
      panel.appendChild(groupToolbar);

      const groupList = document.createElement("div");
      groupList.style.cssText = "display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:4px;margin-bottom:8px";
      for (const group of visibleGroups) {
        const btn = document.createElement("button");
        const reasons = group.reasons.length ? ` · ${group.reasons.join("+")}` : "";
        const labels = Object.entries(group.labels).map(([label, count]) => `${label}:${count}`).join(" ");
        btn.textContent = `g${group.index} #${group.start}-${group.end} ${group.count}seg ${group.duration.toFixed(1)}s${reasons}${labels ? ` · ${labels}` : ""}`;
        btn.title = [
          `hosts: ${group.hosts.join(", ")}`,
          `roots: ${group.pathRoots.join(", ")}`,
          `first: ${group.segments[0]?.fileName || ""}`,
          `last: ${group.segments[group.segments.length - 1]?.fileName || ""}`,
        ].join("\n");
        btn.style.cssText = [
          "font:12px system-ui,sans-serif",
          "padding:5px 6px",
          "cursor:pointer",
          "overflow:hidden",
          "text-overflow:ellipsis",
          "white-space:nowrap",
          "text-align:left",
          group.index === selectedGroup?.index ? "background:#31516f;color:#fff;border:1px solid #7fb1df" : "",
          group.reasons.length ? "box-shadow:inset 3px 0 #ffb020" : "",
        ].join(";");
        btn.addEventListener("click", () => {
          state.selectedGroupByRecord[key] = group.index;
          renderPanel();
        });
        groupList.appendChild(btn);
      }
      panel.appendChild(groupList);

      const selectedGroupTitle = document.createElement("div");
      selectedGroupTitle.style.cssText = "margin:8px 0 4px;font-weight:700";
      selectedGroupTitle.textContent = selectedGroup
        ? `segments in g${selectedGroup.index}: #${selectedGroup.start}-${selectedGroup.end}, ${selectedGroup.count} segment(s), ${selectedGroup.duration.toFixed(3)}s`
        : "segments";
      panel.appendChild(selectedGroupTitle);

      const segmentList = document.createElement("div");
      segmentList.style.cssText = "display:grid;gap:3px";
      for (const segment of selectedGroup?.segments || []) {
        const labelValue = selected.labels?.[String(segment.index)];
        const label = typeof labelValue === "string" ? labelValue : labelValue?.label || "";
        const row = document.createElement("div");
        row.style.cssText = [
          "display:grid",
          "grid-template-columns:42px 58px 48px 1fr auto auto auto auto",
          "gap:4px",
          "align-items:center",
          "border-bottom:1px solid rgba(255,255,255,.08)",
          "padding:2px 0",
          state.previewRecordKey === key && state.previewSegmentIndex === segment.index ? "background:#332b16" : "",
        ].join(";");

        const index = document.createElement("div");
        index.textContent = `#${segment.index}`;
        row.appendChild(index);

        const duration = document.createElement("div");
        duration.textContent = `${segment.duration.toFixed(3)}s`;
        row.appendChild(duration);

        const group = document.createElement("div");
        group.textContent = `g${segment.groupIndex}`;
        row.appendChild(group);

        const name = document.createElement("div");
        name.style.cssText = "overflow:hidden;text-overflow:ellipsis;white-space:nowrap";
        name.title = segment.uri;
        name.textContent = segment.fileName;
        row.appendChild(name);

        const play = document.createElement("button");
        play.textContent = "play";
        play.style.cssText = "font:12px system-ui,sans-serif;padding:2px 5px;cursor:pointer";
        play.addEventListener("click", () => {
          playSegment(key, segment.index).catch((error) => {
            console.error("[Animeko HLS Dumper] segment preview failed", error);
            renderPanel(`segment ${segment.index} failed: ${error?.message || error}`);
          });
        });
        row.appendChild(play);

        for (const [nextLabel, text] of [["ad", "ad"], ["content", "ok"], ["unknown", "?"]]) {
          const btn = document.createElement("button");
          btn.textContent = text;
          btn.title = `mark ${nextLabel}`;
          btn.style.cssText = [
            "font:12px system-ui,sans-serif",
            "padding:2px 5px",
            "cursor:pointer",
            label === nextLabel ? "background:#2f7dff;color:#fff;border:1px solid #8cb8ff" : "",
          ].join(";");
          btn.addEventListener("click", () => setSegmentLabel(key, segment.index, nextLabel));
          row.appendChild(btn);
        }
        segmentList.appendChild(row);
      }
      panel.appendChild(segmentList);
    } else {
      const list = document.createElement("pre");
      list.style.cssText = "white-space:pre-wrap;margin:0;color:#ddd";
      const rows = state.order.slice(-8).map((record) => {
        const stats = record.stats;
        const detail = stats
          ? `${stats.kind} seg=${stats.segmentCount} dis=${stats.discontinuityCount} dur=${stats.totalDuration}s`
          : record.error || record.status;
        return `#${record.id} ${record.status} ${detail}\n${shortUrl(record.url)}`;
      });
      const childRows = child.slice(-4).map((item) => `iframe ${item.count} ok=${item.okCount} media=${item.mediaCount}\n${shortUrl(item.pageUrl)}`);
      list.textContent = rows.concat(childRows).join("\n\n");
      panel.appendChild(list);
    }

    document.documentElement.appendChild(panel);
  }

  function installMenu() {
    if (typeof GM_registerMenuCommand !== "function") return;
    GM_registerMenuCommand("Animeko HLS: show panel", () => {
      state.panelOpen = true;
      renderPanel();
    });
    GM_registerMenuCommand("Animeko HLS: download JSONL", downloadJsonl);
    GM_registerMenuCommand("Animeko HLS: copy JSONL", copyJsonl);
  }

  function exposeDebugApi() {
    PAGE.__animekoHlsDumper = {
      add(url, reason = "manual.add") {
        return enqueueCandidate(url, reason);
      },
      scan: scanKnownPlaces,
      show() {
        state.panelOpen = true;
        renderPanel();
      },
      exportJson,
      exportJsonl,
    };
  }

  function boot() {
    exposeDebugApi();
    installMenu();
    installFrameMessaging();
    installNetworkHooks();
    installHlsHook();
    installVideoHook();
    installPerformanceObserver();
    window.setTimeout(scanKnownPlaces, 1000);
    if (document.readyState === "loading") {
      document.addEventListener("DOMContentLoaded", () => {
        scanKnownPlaces();
        renderPanel();
      }, { once: true });
    } else {
      renderPanel();
    }
  }

  boot();
})();
