# PRD.md — Product Requirements: Trim Gallery

## 1. Problem
Phones fill up with video and photos that are 2–3× larger than they need to be. Existing fixes are bad: cloud backup (privacy, cost, upload), "cleaner" apps (fake, spammy), manual compressors (one file at a time, visible quality loss). Nobody offers "keep everything, use half the space, lose nothing you can see, nothing leaves the phone."

## 2. Product
A modern gallery app that, while the phone charges overnight, shrinks wasteful videos and photos using the phone's hardware encoder to a verified visually-lossless level, keeps originals recoverable, and indexes the library for search, people, text and duplicates — entirely on-device, with no network permission.

## 3. Target users
- **Primary:** people on 64–256 GB phones who shoot lots of video (parents, travellers, creators) and hit "Storage full."
- **Secondary:** privacy-conscious users who refuse cloud photo services.
- **Tertiary:** people with long-lived phones and years of accumulated media.
Regions first: markets with mid-range Android devices and expensive storage upgrades (Africa, South and Southeast Asia, Latin America), plus privacy-motivated users in Europe.

## 4. Goals and success metrics
| Goal | Metric | Target (6 months post-launch) |
|---|---|---|
| Free real space | Median GB freed per active user in first 30 days | ≥ 8 GB |
| Invisible quality | Restore rate (users restoring originals) | < 2% of optimised files |
| Never hurt the phone | Battery complaints per 1,000 reviews; thermal pauses per night | < 5; median < 3 |
| Become the gallery | % of installs that set Trim as default gallery | ≥ 40% |
| Retention | D30 retention | ≥ 35% |
| Trust | Play rating | ≥ 4.5 |

## 5. Non-goals (v1)
Cloud backup or sync · shared albums · cache/Downloads cleaning · generative editing · HDR video re-encoding · Motion/Live photo re-encoding · any network feature · desktop/web.

## 6. Core requirements
R1 Background optimisation runs only while charging, idle and cool; stops on unplug, pickup, heat, storage-low, cap or alarm.
R2 Hardware encoders only; no software video encoding.
R3 Visually-lossless gate (VMAF ≥ 95 video, SSIMULACRA2 ≥ 85 photo); files failing the gate are left untouched.
R4 Originals recoverable: undo bin, offload to SD/USB, or delete-after-window, per folder.
R5 Name, date, location, rotation and metadata preserved; files stay in place.
R6 Full gallery: grid, viewer, albums, favourites, trash, locked folder, editor.
R7 On-device intelligence: search by content, people & pets, text in photos, duplicates, chat-media review.
R8 No INTERNET permission, ever. Displayed as a feature.
R9 Gallery stays at display refresh rate during background work.
R10 Freemium: free tier with monthly optimisation cap; Pro removes cap and unlocks advanced features (see MONETIZATION.md).

## 7. Competitive positioning
| | Google Photos | Samsung Gallery | Cleaner apps | Trim Gallery |
|---|---|---|---|---|
| Frees local space | deletes backed-up copies | no | deletes files | shrinks files, keeps them |
| Quality control | fixed downscale, cloud | — | — | per-file verified |
| Privacy | cloud | local | ad-heavy | no network |
| Search/people | yes (cloud) | partial | no | yes (on-device) |
| Undo | limited | trash | no | bin/offload/window |

## 8. Risks
| Risk | Mitigation |
|---|---|
| Hardware encoders too inefficient on low-end chips | triage skips; field test 3+ device classes before launch |
| Play rejects file-management framing | SAF grants only; gallery is the core app |
| Users fear "compression" | never use the word in UI; say "optimise", show before/after, one-tap restore |
| Thermal/battery complaints | headroom-based pause, charging-only, energy shown |
| Slow first night on huge libraries | largest-saving-first order; progress card; expectation copy |

## 9. Release scope
v1 Android: all of §6. v1.1: Memories, Map view. v1.5: iOS. v2: subscription tier features (per-scene, object removal).
