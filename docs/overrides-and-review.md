# Overrides and review

Human judgment sits above automated search. Several files cooperate; each has a job.

## File roles

| File | Who writes it | Role |
|------|----------------|------|
| `overrides.json` | **You** | Explicit corrections (title → provider id) |
| `override_data.json` | **Pipeline** each `--metadata` run | Backup of every current `MANUAL_OVERRIDE` |
| `revoked-overrides.json` | **You** (optional) | Titles that must stop being sticky confirms |
| `shows.json` | Pipeline | Full enriched library (includes confidence) |
| `review-queue.json` | Pipeline | Subset of shows that need attention |

Default locations are next to the JSON output path. Override with `--overrides` /
`--revoked` when needed.

## `overrides.json` shape

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

`mediaType` is `movie` or `tv` (or `series` as a synonym for `tv`). Matching:

1. Exact canonical title  
2. Exact alias  
3. Canonical title starts with `overrideTitle + ": "`  

Longest matching key wins. Overrides also affect **grouping** so split episode titles can
merge under one show name.

## Sticky `MANUAL_OVERRIDE`

After a successful override (or a prior export that already stored `MANUAL_OVERRIDE`),
the next run merges those rows back into the live override store from `shows.json`.

- File `overrides.json` **wins** on the same title key.
- Sticky keeps confirms alive if you remove a line from the file but leave history in
  `shows.json`.

## `override_data.json`

Generated list of all `MANUAL_OVERRIDE` rows after each metadata run. Prefer editing
`overrides.json` for day-to-day work. Use `override_data.json` to **recover**:

```bash
cp override_data.json overrides.json
```

## Revoking a sticky confirm

`revoked-overrides.json`:

```json
[
  "The Lady's Companion",
  { "title": "Kingsman" }
]
```

Effects:

1. Sticky merge skips those titles.  
2. Enrichment demotes remaining `MANUAL_OVERRIDE` metadata so the show can return to the
   review queue as low confidence.

Also delete the title from `overrides.json` if it is still listed there.

## Review queue reasons

| Reason | When |
|--------|------|
| `NO_METADATA_MATCH` | No provider metadata |
| `LOW_CONFIDENCE_MATCH` | Non-exact / non-override confidence, or movie with more than one alias |
| `NO_PROGRESS_RESOLVED` | Metadata present, progress empty |

Workflow: read `review-queue.json` → add or fix rows in `overrides.json` → re-run with
`--metadata` → confirm `shows.json` and a quieter review queue.
