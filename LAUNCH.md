# LAUNCH.md — Launch plan (Android v1)

## Timeline
| Phase | When | Gate to next phase |
|---|---|---|
| Private alpha | after milestone 7 | field test on 3+ devices: ≥ 30% median video saving, restore rate < 2%, zero thermal complaints |
| Closed beta (Play Internal → Closed track) | after milestone 11 | 100–300 testers, 2 weeks, crash-free ≥ 99.5%, gallery jank < 1% |
| Open beta | after milestone 13 | 2–4 weeks, 1,000+ users, rating ≥ 4.3 |
| Launch | open beta gate passed | — |
| v1.1 | +6 weeks | Memories, Map |
| iOS | +4–6 months | shared core reused |

## Store listing
- Name: Trim Gallery. Tagline: "All your photos and videos, half the space, nothing lost."
- First screenshot: "Freed 8.2 GB overnight" card over the grid. Second: before/after slider on a video. Third: "No internet permission" with the permissions screen. Fourth: people/search. Fifth: undo.
- Description leads with benefit, then privacy, then gallery features. Copy from BUILD.md §8 for cleanup features.
- Data safety form: no data collected, no data shared, no network.
- Category: Photography. Content rating: Everyone.

## Pre-launch checklist
- Play policy review of any file-access wording; SAF only, no All files access.
- Accessibility pass (TalkBack labels, contrast, dynamic type).
- Localisation: EN first; then ES, PT-BR, HI, ID, FR, DE, AR (largest target markets).
- Support: in-app "Export diagnostics" (opt-in, user-shared file) + FAQ page in-app; email support alias.
- Crash/ANR via Play Console vitals only.

## Marketing
- Privacy angle: outreach to privacy-focused Android communities and press (no network permission is the hook).
- Storage angle: creators and parents; short before/after videos showing space freed.
- Regional angle: mid-range device markets where storage upgrades are expensive; regional pricing.
- Launch week: Product Hunt, r/Android, XDA, YouTube reviewers who cover galleries and storage.
- Reference write-up: publish the field-test numbers (GB/hour, Wh/GB, VMAF distributions) as a blog post — credibility with technical users.

## Review handling
- Reply to every 1–3 star review within 48 h in the first month.
- Triage: quality complaints → check restore path and VMAF logs; battery → check thermal logs; permission confusion → improve onboarding copy.

## Rollback plan
Staged rollout 5% → 20% → 50% → 100% over 10 days; halt on crash rate > 0.5% or rating drop > 0.3.
