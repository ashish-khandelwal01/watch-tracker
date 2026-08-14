# Architecture

## Goals

- Import watch history without guessing completion or media type from platform strings alone.
- Keep a **reusable core** independent of how the process is started (CLI today, web later).
- Allow **human correction** of catalogue matches without redoing work every run.
- Support **additional history sources** without rewriting enrichment.

## Gradle modules

```text
watch-tracker/
├── watch-tracker-core      # library: domain + pipeline
├── watch-tracker-cli       # application: main + arg parsing
├── watch-tracker-testdata  # golden fixtures on the test classpath
└── watch-tracker-web       # reserved; depends on core when implemented
```

Dependency rule: **cli → core**, never core → cli. Testdata is consumed by tests only.
Web, when added, should also depend only on core (same rule as an automation runner
depending on a framework library).

## Core packages

| Package | Responsibility |
|---------|----------------|
| `model` | Immutable domain: `WatchEvent`, `Show`, `ShowType`, user-intent enums |
| `parser` | Netflix CSV → raw `WatchEvent` |
| `importer` | Other history sources (e.g. Trakt) → raw `WatchEvent` |
| `normalizer` | Generic title patterns → season / episode / canonical show |
| `service` | Group events into `Show` aggregates (`ShowBuilderService`) |
| `metadata` | Providers, enrichment, progress, overrides, review queue |
| `exporter` | JSON / CSV / review-queue writers |
| `diagnostics` | Optional per-show stage traces |
| `config` | Provider selection and env-based secrets |

## Pipeline

```text
History sources
  NetflixCsvParser
  TraktHistoryImporter          (optional)
        │
        ▼
  TitleNormalizer               (platform-agnostic)
        │
        ▼
  ShowBuilderService            (+ MetadataOverrideStore for consolidation)
        │
        ▼
  MetadataEnrichmentService     (TMDb / TVMaze; overrides; prior snapshot)
        │
        ├── ProgressResolver
        │
        ▼
  outputs: shows.json, review-queue.json, override_data.json
```

Enrichment consults, in order of authority:

1. Explicit **overrides.json** (and sticky `MANUAL_OVERRIDE` from a previous export)
2. **Previous** `shows.json` when the show is unchanged (automated matches)
3. **Provider search** (cache → TMDb / TVMaze)
4. **Progress** from events + provider episode guides

## Design principles

1. **Raw import is lossless.** Normalization and enrichment must not destroy the original title/date.
2. **Do not infer user intent from watch counts.** `WatchStatus` (user) stays separate from
   mechanical `CompletionStatus` on progress.
3. **Confidence is explicit.** Weak matches go to the review queue instead of being hidden.
4. **Human confirms are durable.** `MANUAL_OVERRIDE` is sticky across runs until revoked.
5. **History sources ≠ catalogue sources.** Netflix/Trakt feed plays; TMDb/TVMaze feed identity.

## Where complexity lives

| Component | Approx. role |
|-----------|----------------|
| `MetadataEnrichmentService` | Orchestrates override / prior / search / type fixups |
| `ProgressResolver` | Highest episode position from events + named-episode lookup |
| `TitleNormalizer` | Pattern-only; no show-name catalogue |
| `TmdbMetadataProvider` / `TvMazeMetadataProvider` | HTTP + match ranking |

Further splits of enrichment are optional; prefer extracting a policy only when a second
reason appears (e.g. a Trakt-backed metadata path).
