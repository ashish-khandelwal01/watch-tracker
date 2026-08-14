# History importers

All history sources produce the same type: **`WatchEvent`**.

## Contract

A raw event (before normalization) must provide at least:

| Field | Meaning |
|-------|---------|
| `rawTitle` | Platform-supplied title string |
| `watchedAt` | When it was watched |
| `platform` | Source label (`NETFLIX`, `TRAKT`, …) |

`WatchEvent.raw(title, watchedAt, platform)` is the usual factory. Ids are assigned
stably by the model. Normalization fills `canonicalShow`, `season`, `episode`,
`episodeTitle`, and marks the event parsed.

After import, events are normalized with `TitleNormalizer`, then passed to
`ShowBuilderService` and enrichment. **Importers must not call providers or build shows.**

## Netflix (`parser.NetflixCsvParser`)

- Input: official viewing-history CSV (`Title`, `Date`).
- Platform: `NETFLIX`.
- Role: **complete past** for Netflix accounts that can export history.
- Dates use Netflix’s `M/d/uu` style and are parsed into `LocalDateTime`.

This is the primary importer for personal use.

## Trakt (`importer.TraktHistoryImporter`)

- Input: live `GET /users/me/history` (OAuth) or a saved JSON array of the same shape.
- Platform: `TRAKT`.
- Role: **optional** plays from other apps/OTTs once the user connects Trakt — not a
  replacement for the Netflix backfill, and **not** a metadata catalogue.

Episode rows are shaped for the existing normalizer, for example:

```text
Dark: Season 1: Chapter Three
Show Name: Season 2: Episode 5
```

Movies use the plain movie title.

### Configuration (CLI)

| Flag / env | Purpose |
|------------|---------|
| `--trakt-history <file>` | Offline merge from a JSON dump |
| `--trakt` | Live fetch |
| `TRAKT_CLIENT_ID` | Trakt API app client id |
| `TRAKT_ACCESS_TOKEN` | User OAuth access token |

Trakt does not replace TMDb/TVMaze. Enrichment still runs against catalogue providers.

## Adding another source

1. Add a class under `parser` or `importer` that returns `List<WatchEvent>`.
2. Wire it in the CLI (or a future API) next to Netflix/Trakt merge.
3. Reuse normalizer → show builder → enrichment unchanged.

Prefer a clear `platform` string so diagnostics and future merges can tell sources apart.
