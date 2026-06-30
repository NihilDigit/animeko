# ANi Open Adapter Notes

This directory contains a Cloudflare Worker data-source adapter for Animeko.
It serves an Animeko `web-selector` subscription and small HTML pages that Animeko
parses through configured CSS selectors.

## Current Goal

Build an MVP ANi Open data source that is fast enough for Animeko FastSelect.

The core issue is not ANi Open response speed. Animeko first searches with its
own subject names, often Simplified Chinese names, while ANi Open uses Traditional
Chinese/Bahamut-facing titles. Searching ANi with the Animeko first query is often
asking the wrong index, and can miss the FastSelect window.

## Supported ANi Scope

The builder discovers available season directories from the ANi Open root
listing instead of assuming a complete quarterly sequence. Current local index
scope starts at `2019-1`; `2019-4` is not present in the root listing and should
not be synthesized.

The builder also treats `/ANi/` as a legacy archive bucket. Archive entries do
not have season context, so they may use Bangumi search as an offline fallback
and must still verify candidates locally before writing `name_cn` search keys.

ANi has used three observed filename schemas in this index:

```text
[ANi]<番名>[<集數>][<解像度>][<來源>][<獲取方法>].<副檔名>
```

This schema appears in early season folders around 2019-2022.

ANi announced the EMBY-friendly filename format from the 2022-04 season:

```text
[TeamName] <番名(中文)> - <集數> [<解像度>][<來源>][<獲取方法>][<音頻格式> <影像格式>][<字幕語言>].<副檔名>
```

The same EMBY-friendly schema also appears in the `/ANi/` archive. Some older
season entries add intermediate folders such as `日文配音` or `中文配音`; folder
recursion should preserve the original subject folder title and should not treat
language folder names as subject titles.

ANi season directories are JSON file lists returned by POSTing
`{"password": null}`.

There are two observed shapes:

- Modern flat lists, such as `/2026-4/`, may contain video files directly.
  Group episodes by the parsed subject title from each file name.
- Older or mixed season lists, such as `/2024-7/`, may contain Google Drive
  folder entries whose names are already ANi/Bahamut subject titles. Enter those
  folders with another POST to collect episodes.

The builder must support both shapes. Do not assume every `2022-4+` season is
flat.

## Architecture

Use ANi directory data as the source of truth. Use Bangumi only to map ANi titles
to Animeko's first-search title, normally Bangumi `name_cn`.

The preferred architecture is:

1. A local builder script runs on the user's machine.
2. The builder fetches the ANi root directory, discovers real season folders
   from `2019-1` onward, and optionally includes the `/ANi/` archive bucket.
3. The builder supports both ANi folder entries and flat video files:
   - For folders, the folder name is the ANi title and the folder contents are
     the episode list.
   - For flat video files, parse the stable ANi filename format and group files
     by parsed subject title.
4. For each ANi season, the builder fetches Bangumi subjects for the matching
   broadcast season with `GET /v0/subjects?type=2&sort=date&year=...&month=...`
   and pagination.
5. The builder constructs a local reverse alias table from each Bangumi subject:
   `name`, `name_cn`, `infobox["中文名"]`, and `infobox["别名"]`.
6. The builder first matches each ANi title against that reverse alias table
   using exact normalized-key matching.
7. If exact matching fails, the builder may use a season-local keyword index:
   normalize title text, generate strong tokens, weight them with season-local
   IDF, and accept only candidates with enough margin and compatible episode or
   season signals.
8. Treat season markers such as `第二季` or `Season2` as alignment signals, not
   primary IR evidence. They should not dominate keyword score or retrieval.
9. Similarity fallback should stay narrow and diagnostic. Do not use broad
   substring similarity to force long-tail titles into Bangumi subjects. The
   accepted fallback should be limited to cases with strong episode-count fit
   and either high text margin or explicit season alignment.
10. On match, use the Bangumi subject's `name_cn` as the primary Animeko search
   key and keep the ANi title as a fallback key.
11. Titles that cannot be safely matched should be moved to an `excluded`
    report with a reason, not hard-matched to weak candidates.
12. The builder writes versioned JSON index artifacts.
13. The builder uploads those artifacts to Cloudflare KV with Wrangler.
14. The Worker runtime only reads KV and renders Animeko-compatible HTML.

Do not make the normal builder path query Bangumi once per ANi title. Bangumi's
search ranking can produce wrong first results for titles such as `K-ON!`,
`戰姬絕唱`, and `命運石之門`. Prefer season browsing plus local alias reverse
lookup.

`POST /v0/search/subjects` is allowed for `/ANi/` archive matching and for
unmatched or diagnostic cases. Query both the original ANi title and its
Simplified Chinese form when using search, because Bangumi search ranking is
less reliable for Traditional Chinese archive titles. Search results must be
locally verified before writing a search key.

Do not make normal `/search` requests depend on live Bangumi calls.
Do not make normal `/search` requests depend on ANi global search.

## KV Shape

Prefer versioned, static shards instead of one KV key per search alias.

Suggested key shape:

```text
openani:v1:manifest
openani:v1:search:<version>:<hashPrefix>
openani:v1:keyword:<version>:<hashPrefix>
openani:v1:season:<version>:<season>
```

The manifest should point to the active version. Publish a new version by writing
all season tables and search shards first, then switching the manifest last.

Use hash-prefix search shards so key distribution is not biased by Chinese,
Japanese, or English first characters.

`search` shards are for exact normalized keys. `keyword` shards are for fallback
candidate retrieval; the Worker should only score the small candidate set
returned by query tokens, never scan all season subjects.

Cloudflare Workers can run lightweight IR scoring, but request CPU budget is not
large enough to justify scanning every indexed subject on the hot path. Exact
lookup should be the normal path. Keyword fallback should load only the token
shards for the normalized query, union those entries into a small candidate set,
then score that set with the same simple IDF/coverage model used by the builder.

## Static Index Read Model

The generated static index has three runtime-facing layers:

- `manifest.json`: active version, available seasons, and shard prefix length.
- `search/<hashPrefix>.json`: exact normalized search-key shard. Each key maps
  to entries containing `season`, `titleKey`, `aniTitle`, and `reason`.
- `season/<season>.json`: subject table. `subjects[titleKey]` contains the ANi
  title, optional Bangumi summary, and direct episode links.
- `keyword/<hashPrefix>.json`: fallback inverted index from strong token to
  candidate entries. This is for small-candidate recovery only.

The Worker fast path should be:

1. Normalize Animeko's query with the same key normalization used by the builder.
2. Hash the normalized key, load one `search` shard, and read exact entries.
3. For each entry, load its `season` table and render the corresponding
   `subjects[titleKey]` as Animeko subject results.
4. `/subject` should use `season` plus `titleKey` to render the stored episode
   list directly. It should not call ANi when the static row exists.
5. Only if exact lookup misses, optionally run keyword fallback by loading token
   shards and scoring the unioned candidates. Do not scan all `season` tables.

MVP runtime should implement exact `search` + `season` lookup first. Do not wire
`keyword` shards into Worker request handling until the exact-index path is
deployed and measured.

For example, Animeko searching `莉可丽丝` normalizes to the search key
`莉可丽丝`, which points to `2022-7/lycorisrecoil莉可丽丝`; the season table then
provides the ANi title `Lycoris Recoil 莉可麗絲` and all episode URLs.

Generated reports should include:

- `summary.json`: matched, excluded, unmatched, ambiguous counts.
- `review.json`: risky accepted matches, duplicate Bangumi mappings, and
  excluded/manual-review items.

## Runtime Contract

Keep serving the existing Animeko `web-selector` shape:

- `/sub.json` returns `exportedMediaSourceDataList.mediaSources[]`.
- `/search?wd=...` returns HTML containing `.subject-list a`.
- `/subject?...` returns HTML containing `.episode-list a`.
- Episode links may point directly to ANi video URLs.

Animeko's configured selectors should continue to work:

```text
.subject-list a
.episode-list a
```

The Worker may keep a direct ANi directory fallback for debugging or KV-miss
survival, but the intended fast path is static KV index lookup.

## Update Strategy

Initial bootstrap can be run locally and uploaded to KV.

Upload the MVP exact index with:

```bash
bun run upload:index
```

This writes `search` shards and `season` tables first, then switches
`openani:v1:manifest` last. Use `bun run upload:index -- --dry-run` to print the
planned writes. `keyword` shards are excluded by default; include them only when
runtime fallback is implemented.

Daily automation should use the same publish order:

1. Fetch the ANi root listing and discover season folders.
2. Rebuild the latest discovered season, the previous season, and the next season
   if it already exists in the root listing.
3. Rebuild `/ANi/` only on a slower cadence, such as weekly, or when the root
   listing shows archive changes.
4. Run `bun run verify:index`.
5. Upload versioned season tables and search shards.
6. Switch `openani:v1:manifest` last.

The full rebuild remains the recovery path. Incremental automation should never
mutate the active manifest until verification passes for the candidate index.

## Data Safety

Do not commit downloaded media files or real media payloads.
Commit code and small generated schemas/docs only. Generated index JSON should be
treated as deploy artifact unless explicitly requested otherwise.

Do not commit local Wrangler state, `node_modules`, or backup files.
