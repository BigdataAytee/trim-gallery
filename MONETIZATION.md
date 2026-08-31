# MONETIZATION.md — Freemium first, subscription later

## Phase 1 (launch): Freemium with a one-time Pro purchase
No ads, no accounts, no network. Billing via Play Billing (App Store later), which works without an INTERNET permission in the app.

| | Free | Pro (one-time) |
|---|---|---|
| Full gallery, viewer, albums, favourites, trash, locked folder, editor | ✓ | ✓ |
| Background optimisation | up to **3 GB freed per month** | unlimited |
| Compress now | 5 per day | unlimited |
| Search, text in photos | ✓ | ✓ |
| People & pets | ✓ | ✓ |
| Duplicates | find and review | ✓ + bulk actions |
| Chat media review | ✓ | ✓ |
| AV1 encode | – | ✓ |
| Offload originals to SD/USB | – | ✓ |
| Careful verify, Compact mode | – | ✓ |
| Reversible JPEG XL mode | – | ✓ |
| Undo retention | 30 days | up to 90 days |

**Retention is not a paywall.** The free tier keeps originals for the 30 days BUILD.md § 6
promises; Pro extends the ceiling to 90. An earlier draft gave free users 7, which put this
document against § 6 and made the app's most dangerous promise the one it broke — "Free
space" mode tells a user their originals are recoverable, and a paywall that quietly
shortens that window deletes photos three weeks before they expect it. Deleting someone's
only copy is not a conversion moment.

Price: regional, roughly USD 9.99–14.99 one-time (lower in price-sensitive regions via Play's regional pricing). Launch discount 40% for the first 30 days.

**Why the cap is GB freed, not files:** it's the number the user already cares about and it scales with how much the app is worth to them. 3 GB/month is enough to feel the benefit and hit the wall within weeks for a heavy shooter.

**Conversion moments (no dark patterns):**
- Cap reached: card in the grid "You've freed 3 GB this month. Pro removes the limit." with the running total shown.
- Space screen: projected saving "About 19 GB more possible" with a Pro button.
- Offload/AV1/Compact toggle in Settings: locked with a one-line explanation.
- Never interrupt the viewer, never nag more than once per week, never block restore.

## Phase 2 (after ~6 months / iOS launch): Subscription tier
Add **Pro+** monthly/yearly on top of the one-time Pro, for features with ongoing cost or value:
- Per-scene optimisation (more savings)
- On-device object removal and enhance models (updated with releases)
- Memories with music, map view themes
- Higher undo retention, family plan (multiple devices via Play family library)
- Priority support
Existing one-time Pro buyers keep everything they had; Pro+ is additive. Yearly price ≈ 2× the one-time Pro; monthly ≈ 1/6 of yearly.

## Refunds and trust
- 7-day no-questions refund message in-app pointing to Play's refund flow.
- No feature is removed from Free after launch.
- Restore original is never gated.

## Metrics
Free→Pro conversion (target 4–6%), ARPU, cap-hit rate, refund rate (< 3%), rating impact of paywall cards.
