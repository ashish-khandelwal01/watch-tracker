# Metadata enrichment and progress

Enrichment is optional (`--metadata`). Without it, the CLI only exports grouped shows
from imported history.

## Providers

`MetadataProvider` is implemented by:

| Provider | Notes |
|----------|--------|
| `TmdbMetadataProvider` | Needs `TMDB_API_READ_ACCESS_TOKEN` |
| `TvMazeMetadataProvider` | No API key |
| `FallbackMetadataProvider` | Primary then secondary on miss |
| `CachingMetadataProvider` | File cache in front of any of the above |

Selection: JVM property `watchtracker.metadata.provider`  
(`TMDB`, `TVMAZE`, `TMDB_WITH_TVMAZE_FALLBACK`).

Important API surface:

- `findByTitle` — search by string (ambiguous; ranked with `MatchConfidence`)
- `findById` — used for **manual overrides** (authoritative)
- `resolveEpisode` / `resolveEpisodeAnySeason` — named-episode progress

## Orchestration (`MetadataEnrichmentService`)

Per show, roughly:

1. **Override** → `findById` → confidence `MANUAL_OVERRIDE`
2. Else **previous snapshot** (`PreviousEnrichmentSnapshot` / `JsonEnrichedShowSnapshot`)
   - Unchanged automated match → reuse
   - `MANUAL_OVERRIDE` → sticky reuse (even if id/data shifted), unless revoked
3. Else **title search** (with Limited Series strip and some full-title retries)
4. **Type fixups** (e.g. limited series, documentary signals)
5. **Progress** via `ProgressResolver`

Provider failures are treated as misses so one bad HTTP call does not abort the run.

## Match confidence

| Value | Typical origin |
|-------|----------------|
| `MANUAL_OVERRIDE` | Human override or sticky confirm |
| `EXACT` | Strong title match |
| `CLOSEST_RELATED` | Related / partial match |
| `FIRST_RESULT_FALLBACK` | Weak accept of first hit (also used when demoting revoked overrides) |

## Progress (`ProgressResolver`)

- **Movies:** any belonging watch event → completed progress (no season/episode).
- **Series:** Netflix-numbered positions from events, plus named-episode resolution against
  the provider guide when titles are present without numbers.
- Completion vs in-progress uses distinct episode counts and optional `totalEpisodes` /
  `productionEnded` from metadata — mechanical only, not user “I’ve finished this” intent.

Some titles (specials, lesson-style labels, crossovers) may keep metadata but leave
progress empty; those surface as `NO_PROGRESS_RESOLVED` on the review queue.
