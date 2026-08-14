# Watch Tracker

Watch Tracker imports personal Netflix viewing-history exports into an immutable,
platform-neutral domain model. It deliberately makes no assumptions about whether a
series is complete or whether a title is a film.

**Netflix** is the primary history source (full past export). **Trakt** is an optional
add-on for plays from other apps/OTTs going forward. **TMDb / TVMaze** supply catalogue
metadata — not Trakt.

## Architecture

| Module | Role |
|--------|------|
| `watch-tracker-core` | Parsing, normalization, grouping, enrichment, export |
| `watch-tracker-cli` | Thin adapter: args → core → files / exit codes |
| `watch-tracker-testdata` | Fixtures and golden JSON for tests |
| `watch-tracker-web` | Reserved for a future UI (empty on purpose) |

Pipeline:

```
Netflix CSV  (+ optional Trakt history)
    → TitleNormalizer
    → ShowBuilder  (can consolidate via overrides)
    → MetadataEnrichmentService  (TMDb / TVMaze)
         → ProgressResolver
    → shows.json
    → review-queue.json
    → override_data.json
```

## Build and test

Java 21 required.

```bash
./gradlew build
./gradlew spotlessCheck    # or spotlessApply
```

## Quick start

Offline import only (no metadata):

```bash
./gradlew :watch-tracker-cli:run --args="NetflixViewingHistory.csv shows.json"
```

With metadata enrichment:

```bash
export TMDB_API_READ_ACCESS_TOKEN='your-token'
./gradlew :watch-tracker-cli:run --args="NetflixViewingHistory.csv shows.json --metadata"
```

Default cache directory: `.watch-tracker/metadata-cache`. Override with `--cache <dir>`.

### CLI flags

```text
watch-tracker <netflix.csv> [shows.json|shows.csv]
  --metadata              enrich via TMDb / TVMaze
  --cache <directory>     metadata response cache
  --overrides <file>      human-edited overrides (default: overrides.json next to output)
  --revoked <file>        drop sticky manual overrides (default: revoked-overrides.json)
  --trakt                 fetch Trakt history (needs TRAKT_CLIENT_ID + TRAKT_ACCESS_TOKEN)
  --trakt-history <file>  merge Trakt history from a saved JSON dump
  --diagnostics [dir]     per-show pipeline diagnostics (default dir: build/diagnostics)
```

## Netflix import

The Netflix parser expects the standard two-column viewing-history CSV (`Title`, `Date`).
Each row becomes a raw `WatchEvent`. Normalization is separate: it recognizes generic
season / episode / part / chapter / volume patterns without a catalogue of show names.
Grouping then produces one `Show` per canonical title.

## Metadata enrichment (TMDb / TVMaze)

Enrichment is opt-in (`--metadata`). It adds type, genres, posters, release dates,
provider ids, match confidence, and resolved watch progress where possible.

Select the provider strategy with the JVM property `watchtracker.metadata.provider`:

| Value | Behavior |
|-------|----------|
| `TMDB_WITH_TVMAZE_FALLBACK` | Default: TMDb first when a token is set, else TVMaze |
| `TMDB` | TMDb only (token required) |
| `TVMAZE` | TVMaze only (no API key) |

```bash
export TMDB_API_READ_ACCESS_TOKEN='your-token'
./gradlew :watch-tracker-cli:run --args="history.csv shows.json --metadata"

./gradlew -Dwatchtracker.metadata.provider=TVMAZE \
  :watch-tracker-cli:run --args="history.csv shows.json --metadata"
```

Matches are cached under `.watch-tracker/metadata-cache` (or `--cache`).

Enriched `shows.json` entries look like: `show` + optional `metadata` + optional `progress`.

### Match confidence and review queue

Each metadata hit carries a `matchConfidence` (`EXACT`, `MANUAL_OVERRIDE`,
`CLOSEST_RELATED`, `FIRST_RESULT_FALLBACK`). After enrichment the CLI writes
`review-queue.json` next to the output for shows that need a human look:

| Reason | Meaning |
|--------|---------|
| `NO_METADATA_MATCH` | No provider hit |
| `LOW_CONFIDENCE_MATCH` | Weak match, or a movie with more than one raw alias |
| `NO_PROGRESS_RESOLVED` | Metadata present but episode progress could not be resolved |

## Manual overrides

Humans correct bad matches without fighting the searcher on every run.

### `overrides.json` (you edit)

Default path: next to the output file, or `--overrides <file>`.

```json
[
  {
    "title": "The Lady's Companion",
    "provider": "TMDB",
    "mediaType": "tv",
    "providerId": 280890
  }
]
```

Matching is exact on show title or alias, or a colon-prefix on the canonical title
(so an override for `"The Lady's Companion"` covers `"The Lady's Companion: Lesson 1"`).

Overrides also drive **grouping**: episode-level Netflix titles can consolidate under the
override title.

### Sticky `MANUAL_OVERRIDE` (from previous `shows.json`)

On the next `--metadata` run, rows already confirmed as `MANUAL_OVERRIDE` are merged back
into the live override store (file `overrides.json` wins on the same title). That keeps
human confirms stable even if you temporarily remove a line from `overrides.json`.

### `override_data.json` (machine-written)

Rewritten after every successful metadata run. Contains every current `MANUAL_OVERRIDE`
as override rows. This is the **generated backup** of human confirmations — not a file you
hand-edit day to day.

Recover after deleting `shows.json` or the cache:

```bash
cp override_data.json overrides.json
./gradlew :watch-tracker-cli:run --args="history.csv shows.json --metadata"
```

### `revoked-overrides.json` (drop sticky)

When a sticky confirm should no longer win:

```json
[
  "The Lady's Companion",
  { "title": "Kingsman" }
]
```

Default path next to output, or `--revoked <file>`. Matching is case-insensitive exact or
`title: …` prefix.

Effect:

- Sticky merge from previous `shows.json` skips these titles
- Remaining `MANUAL_OVERRIDE` metadata for them is demoted so they reappear on the review
  queue as low confidence

Remove the title from `overrides.json` as well if it is still listed there.

## Optional Trakt history

Trakt is **not** used to resolve Netflix metadata. It is an optional **history source**:
connect other apps/OTTs to Trakt, then pull those plays into the same pipeline.

| Source | Role |
|--------|------|
| Netflix CSV | Complete past history for Netflix |
| Trakt (optional) | Additional / future plays from connected services |
| TMDb / TVMaze | Catalogue metadata for both |

Offline dump (array shaped like Trakt’s `/users/me/history` response):

```bash
./gradlew :watch-tracker-cli:run --args="history.csv shows.json --metadata --trakt-history trakt-history.json"
```

Live API (OAuth user token + app client id):

```bash
export TRAKT_CLIENT_ID='...'
export TRAKT_ACCESS_TOKEN='...'
./gradlew :watch-tracker-cli:run --args="history.csv shows.json --metadata --trakt"
```

Create a Trakt API app at https://trakt.tv/oauth/applications. A user access token is
required for private history.

## Diagnostics

```bash
./gradlew :watch-tracker-cli:run --args="history.csv shows.json --diagnostics"
./gradlew :watch-tracker-cli:run --args="history.csv shows.json --diagnostics /path/to/diags"
```

Default output directory: `build/diagnostics/` (one JSON file per show). Stages include
CSV parse, title normalization, show building, metadata, episode resolution, and progress.

Can be combined with metadata and cache flags:

```bash
./gradlew :watch-tracker-cli:run --args="history.csv shows.json --metadata --cache .cache --diagnostics"
```

Example stage payload:

```json
{
  "show": "Money Heist",
  "stages": [
    {
      "stage": "CSV_PARSER",
      "output": { "title": "Money Heist: Part 3: Episode 5", "date": "2024-02-02T00:00:00" }
    },
    {
      "stage": "TITLE_NORMALIZER",
      "input": "Money Heist: Part 3: Episode 5",
      "output": { "season": 3, "episode": 5 }
    },
    {
      "stage": "METADATA_PROVIDER",
      "message": "Matched metadata",
      "output": { "provider": "TMDB", "id": 71446 }
    }
  ]
}
```

## Output files (typical metadata run)

| File | Written by | Purpose |
|------|------------|---------|
| `shows.json` | always (JSON output) | Enriched library |
| `review-queue.json` | `--metadata` | Shows needing human attention |
| `override_data.json` | `--metadata` | Backup of all `MANUAL_OVERRIDE` rows |
| `overrides.json` | you | Human-edited corrections |
| `revoked-overrides.json` | you (optional) | Titles to stop treating as sticky confirms |

## Extension points

New history sources implement an importer that produces `WatchEvent` values, then reuse
the normalizer, show builder, and enrichment pipeline (same pattern as
`TraktHistoryImporter`).

## Roadmap (not required for daily Netflix use)

- Deeper Trakt sync (dedupe by id, OAuth UX when a web module exists)
- Persistence beyond JSON files
- Thin web UI for review / overrides
- Additional importers if useful platforms appear
