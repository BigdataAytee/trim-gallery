# SCHEMA.md — Database schema (SQLDelight, shared across platforms)

All timestamps are epoch milliseconds UTC (INTEGER). Sizes in bytes. Booleans as INTEGER 0/1. IDs are TEXT UUIDv7 unless noted.

## media_item
| Column | Type | Notes |
|---|---|---|
| id | TEXT PK | |
| platform_ref | TEXT NOT NULL UNIQUE | SAF document URI (Android) / PHAsset localIdentifier (iOS) |
| folder_grant_id | TEXT FK → folder_grant | |
| name | TEXT NOT NULL | |
| kind | TEXT NOT NULL | VIDEO / PHOTO / PNG / OTHER |
| mime | TEXT | |
| codec | TEXT | h264 / hevc / av1 / jpeg / heic / png … |
| width, height | INTEGER | |
| fps | REAL | |
| bitrate | INTEGER | bps |
| duration_ms | INTEGER | video only |
| size | INTEGER NOT NULL | |
| mtime | INTEGER NOT NULL | for change detection |
| taken_at | INTEGER | |
| lat, lon | REAL | |
| camera_model | TEXT | |
| flags | INTEGER NOT NULL DEFAULT 0 | bitmask: hdr=1, motion_photo=2, ultra_hdr=4, live_photo=8, raw=16, in_cloud_only=32, favourite=64, hidden=128 |
| phash | INTEGER | 64-bit perceptual hash |
| sha256 | BLOB | 32 bytes |
| status | TEXT NOT NULL DEFAULT 'NEW' | NEW / INDEXED / CANDIDATE / PROCESSING / DONE / SKIPPED / FAILED |
| skip_reason | TEXT | |
| est_saving | INTEGER | bytes, from triage; ordering key |
| created_at, updated_at | INTEGER NOT NULL | |
Indexes: (status, est_saving DESC), (taken_at DESC), (folder_grant_id), (phash), (sha256), (flags).

## job
| Column | Type | Notes |
|---|---|---|
| id | TEXT PK | |
| media_id | TEXT FK → media_item ON DELETE CASCADE | |
| run_session_id | TEXT FK → run_session | null for Compress now |
| state | TEXT NOT NULL | QUEUED / PROBING / ENCODING / VERIFYING / REPLACING / SUCCEEDED / PAUSED / CANCELLED / FAILED |
| stage_before_pause | TEXT | |
| engine | TEXT | hevc / av1 / jpegli / heic / jxl / png |
| setting | TEXT | JSON: bitrate or quality value, mode, profile |
| probes | INTEGER | |
| xpsnr | REAL | |
| vmaf | REAL | |
| ssim2 | REAL | photos |
| original_size, new_size | INTEGER | |
| encode_ms, verify_ms | INTEGER | |
| realtime_multiple | REAL | |
| thermal_start, thermal_end | REAL | headroom 0–1 |
| energy_wh | REAL | estimate |
| attempts | INTEGER NOT NULL DEFAULT 0 | |
| error | TEXT | |
| user_initiated | INTEGER NOT NULL DEFAULT 0 | Compress now |
| started_at, finished_at | INTEGER | |
Indexes: (media_id), (run_session_id), (state), (finished_at DESC).

## undo_entry
| Column | Type | Notes |
|---|---|---|
| id | TEXT PK | |
| media_id | TEXT FK → media_item | |
| job_id | TEXT FK → job | |
| location | TEXT NOT NULL | BIN / OFFLOAD / SYSTEM_TRASH |
| ref | TEXT NOT NULL | path or URI of the parked original |
| original_size | INTEGER | |
| expires_at | INTEGER | null = never (KEEP / OFFLOAD) |
| state | TEXT NOT NULL | ACTIVE / RESTORED / EXPIRED / OFFLOADED |
| created_at | INTEGER NOT NULL | |
Indexes: (state, expires_at), (media_id).

## folder_grant
| Column | Type | Notes |
|---|---|---|
| id | TEXT PK | |
| platform_ref | TEXT NOT NULL UNIQUE | tree URI / album id |
| display_name | TEXT | |
| mode | TEXT NOT NULL | KEEP / OFFLOAD / FREE |
| offload_ref | TEXT | tree URI of SD/USB target when mode = OFFLOAD |
| enabled | INTEGER NOT NULL DEFAULT 1 | |
| last_scanned_at | INTEGER | |

## run_session
| Column | Type | Notes |
|---|---|---|
| id | TEXT PK | |
| started_at, finished_at | INTEGER | |
| stop_reason | TEXT | UNPLUGGED / FOREGROUND / THERMAL / CAP / STORAGE / STOP_BY / COMPLETE / CAP_FREE_TIER |
| files_done, files_skipped, files_failed | INTEGER | |
| bytes_freed | INTEGER | |
| minutes_worked | REAL | |
| energy_wh | REAL | |
| thermal_pauses | INTEGER | |
| seen | INTEGER NOT NULL DEFAULT 0 | morning card dismissed |

## predictor
| Column | Type | Notes |
|---|---|---|
| platform, device, camera_model, codec, width, height, fps_bucket, bitrate_bucket | TEXT/INTEGER | composite PK |
| setting_mean, setting_var | REAL | |
| samples | INTEGER | |
| updated_at | INTEGER | |

## label
| media_id FK, label TEXT, confidence REAL, source TEXT (mlkit / vision) | PK (media_id, label) | index (label) |

## face
| id PK, media_id FK, bbox (l,t,r,b REAL), embedding BLOB (512 float16), person_id FK nullable, quality REAL | indexes (media_id), (person_id) |

## person
| id PK, name TEXT, cover_face_id FK, hidden INTEGER, is_pet INTEGER, created_at | |

## text_block
| media_id FK, text TEXT, bbox, confidence | FTS5 virtual table `text_fts(text, media_id UNINDEXED)` |

## duplicate_group
| id PK, kind TEXT (EXACT / NEAR), created_at, resolved INTEGER |
## duplicate_member
| group_id FK, media_id FK, is_best INTEGER, distance INTEGER | PK (group_id, media_id) |

## settings (DataStore, not SQL)
See ARCHITECTURE.md §12.

## Search
FTS5 over labels (`label_fts`), text (`text_fts`), person names, and camera_model; combined with date/place filters in a query builder.

## Migrations
Versioned SQLDelight migrations; never drop `media_item`, `job`, `undo_entry` columns (restore depends on them). Migration tests in shared tests.

## Size estimates
100k media items ≈ 60 MB (with embeddings ≈ +100 MB for 100k faces). Acceptable; embeddings can be float16 and pruned for hidden people.
