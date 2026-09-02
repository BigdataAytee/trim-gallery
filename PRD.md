# PRD.md — Product Requirements: Trim

## 1. Problem
Phones fill up with video and photos that are 2–3× larger than they need to be. Existing fixes are bad: cloud backup (privacy, cost, upload), "cleaner" apps (fake, spammy), manual compressors (one file at a time, visible quality loss). Nobody offers "keep everything, use half the space, lose nothing you can see, nothing leaves the phone."

## 2. Product
A background utility that shrinks wasteful videos and photos using the phone's hardware encoder to a verified visually-lossless level, keeps originals recoverable, and runs while the phone charges overnight — entirely on-device, with no network permission.

**Not a gallery.** The user keeps whatever gallery they already have; this app has five screens and a share-sheet entry, and gets out of the way. Sharing a video to it from any gallery trims that video and hands back a new file, which makes the user's existing gallery the front end.

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
| The scan is worth running | % of first scans that end in a trim, or in the user reading "can't be trimmed" | ≥ 60% |
| Live in the background | % of installs with the nightly run still enabled at D30 | ≥ 50% |
| Retention | D30 retention | ≥ 35% |
| Trust | Play rating | ≥ 4.5 |

The gallery-adoption metric is gone with the gallery. Its replacement is deliberately not "opens per week": an app the user has to open often has failed at being a background utility.

## 5. Non-goals (v1)
Cloud backup or sync · shared albums · cache/Downloads cleaning · generative editing · HDR video re-encoding · Motion/Live photo re-encoding · any network feature · desktop/web · **browsing, viewing, editing, searching or organising media**.

## 6. Core requirements
R1 Background optimisation runs only while charging, idle and cool; stops on unplug, pickup, heat, storage-low, cap or alarm.
R2 Hardware encoders only; no software video encoding.
R3 Visually-lossless gate (VMAF ≥ 95 video, SSIMULACRA2 ≥ 85 photo); files failing the gate are left untouched.
R4 Originals recoverable: undo bin, offload to SD/USB, or delete-after-window, per folder.
R5 Name, date, location, rotation and metadata preserved; files stay in place.
R6 Five screens: Home, Big files, Folders, History, Settings. Each with an emulator UI test before any build ships.
R7 The scan reports big files that *cannot* be trimmed, with the reason, so it is useful even when nothing is compressible.
R8 No INTERNET permission, ever. Displayed as a feature.
R9 The UI stays at display refresh rate during background work.
R10 Freemium: free tier with monthly optimisation cap; Pro removes cap and unlocks advanced features (see MONETIZATION.md).
R11 Works completely on SAF-granted folders alone. Whole-phone scan is optional and may never ship (BUILD.md § 4).
R12 Share-sheet entry accepts a video and returns a new trimmed file, never replacing the sender's copy.

## 7. Competitive positioning
| | Google Photos | Samsung Gallery | Cleaner apps | Trim |
|---|---|---|---|---|
| Frees local space | deletes backed-up copies | no | deletes files | shrinks files, keeps them |
| Quality control | fixed downscale, cloud | — | — | per-file verified |
| Privacy | cloud | local | ad-heavy | no network |
| Undo | limited | trash | no | bin/offload/window |
| Replaces your gallery | yes | yes | no | **no, works with it** |

Not competing with the gallery is now the positioning, not a gap in it. The comparison that matters is against cleaner apps, and it is won on the same ground as before: this one shrinks files instead of deleting them, and proves the result before touching an original.

## 8. Risks
| Risk | Mitigation |
|---|---|
| Hardware encoders too inefficient on low-end chips | triage skips; field test 3+ device classes before launch |
| Play rejects the file-management declaration for All files access | (a) SAF-only is the whole product; (b) is additive and can be dropped without a redesign |
| A utility with no daily surface is forgettable | the share-sheet entry puts it inside the gallery the user already opens; the nightly result is a notification, not a screen to visit |
| Users fear "compression" | never use the word in UI; say "trim", show before/after, one-tap restore |
| Thermal/battery complaints | headroom-based pause, charging-only, energy shown |
| Slow first scan on huge libraries | largest-saving-first order; progress state; expectation copy |

## 9. Release scope
v1 Android: all of §6 on SAF grants. v1.1: whole-phone scan, if Play accepts the declaration. v1.5: iOS. v2: subscription tier features.
