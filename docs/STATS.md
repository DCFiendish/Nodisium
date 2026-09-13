# Player/nation stats — tracking, daily snapshot, Discord + website

Design doc for the per-player/per-nation leaderboard stats feature. Referenced from code
comments across `modules/nodes` (`Resident.kt`, `Nation.kt`, `Town.kt`, `FlagWar.kt`,
`Attack.kt`, `NodesPlayerJoinQuitListener.kt`, `DailyStatsSnapshot.kt`) — this is where the
*why* behind those hooks lives, not repeated inline at every call site.

## What's tracked

**Per player** (`Resident`, persisted in `residents.json`):
- `kills`, `deaths` — PvP only. A death from a mob or the environment (no `Player` attacker,
  or self-damage) increments `deaths` but never anyone's `kills`.
- `capsPlaced` — number of war-flag capture attempts (`Attack`s) this player has started,
  regardless of outcome.
- `attacksDefended` — number of *enemy* capture attempts this player personally stopped by
  breaking the attacker's flag before it completed. An attacker breaking their own flag does
  not count (see `FlagWar.isDefendedBreak`).
- `totalPlaytimeMillis` — accumulated on quit from a transient join timestamp
  (`Resident.sessionStartMillis`, not persisted — always starts null on a fresh join).

**Per nation** (`Nation`, persisted in `towns.json`) — historical counters, incremented at the
same event sites as the matching player-level stat, **not** derived from current membership.
A nation's kill count doesn't drop because a top player later left it:
- `kills`, `deaths` — attributed via the killer's/victim's town's nation at the moment of death.
- `nodesCaptured`, `nodesLost` — territory-level occupation flips (`Town.capture()`/
  `Town.release()`), not per-chunk attacks. Capturing an enemy territory credits the
  capturing nation and debits the territory's actual owner (not a previous occupier, if one
  existed — see the `previousOwner` comment in `Town.capture()`). Liberating your own
  territory back from an occupier is itself a capture for your nation and a loss for the
  occupier being kicked out.
- `playtime` reported to consumers is the **live sum** of current members' `totalPlaytimeMillis`
  (`Nation.residents.sumOf { ... }` in `DailyStatsSnapshot.buildPayload()`) — not its own
  persisted counter. This one *is* membership-dependent by design (matches what was asked for:
  "the combined playtime of every player in that nation").

KD is derived at read time (`DailyStatsSnapshot.kd()`), never stored: `kills` if `deaths == 0`,
else `kills / deaths`.

## Why "caps broken" means the defender, not the attacker

In plain English, "caps broken" reads as the *attacker* breaking into a base. Here it means
the opposite: a *defender* stopping an enemy capture by destroying their flag. The user-facing
label stays **"Caps Broken"** (as decided), but the field is named `attacksDefended` internally
so the code itself isn't misleading to the next reader. See `FlagWar.isDefendedBreak()` for the
exact rule and `NodesWorldListener.onBlockBreak()` for the one real call site that can trigger it.

## Why the daily snapshot runs at midnight America/New_York

Chosen deliberately as the server's lowest-population window, to keep the (comparatively
expensive — a full walk of every resident and nation) snapshot build off the critical path
during 200-player Nodes-war peaks. `DailyStatsSnapshot` is the first wall-clock-aligned-to-a-
timezone scheduled task in this codebase (`IncomeManager` aligns to the next hour boundary
using the system default zone, which is the closest existing precedent — see its
`scheduleNext()`); using an explicit `ZoneId.of("America/New_York")` rather than a fixed UTC
offset means it tracks EST/EDT automatically and never drifts an hour on the two DST-transition
days a flat 24h repeat would.

## How the data reaches consumers

- **Discord bot**: reads the JSON file directly off local disk (`NodesConfig.dailyStatsOutputPath`,
  default `nodisium-data/stats/daily-stats.json`) — the bot already runs on the same VM as the
  game server (see `nodisium-discord-bot`'s README), so this needs no network hop and no new
  exposure.
- **Website**: has no backend of its own (a plain static site — see the `nodisium-website` repo),
  so it can't read the VM's local disk. Instead, `DailyStatsSnapshot` optionally
  (`NodesConfig.dailyStatsGitRepoPath`, null by default) also writes the same JSON into a local
  clone of a small **public** repo and pushes it, whose GitHub Pages serves it as a static file.
  This was chosen specifically because it needs **no new open port, domain, or TLS cert** on the
  VM — sidesteps the public-exposure blocker from the earlier `StatsApi` (town/nation/war
  overview) discussion entirely. The website's `js/data.js` `API.fetchPlayer`/nation-stats calls
  point at that Pages URL once it's set up.
- **One-time ops setup, not yet done**: create the public stats repo (e.g.
  `DCFiendish/nodisium-stats`), clone it somewhere on the VM, enable GitHub Pages on it, and
  point `dailyStatsGitRepoPath` at that clone (via a plain file the same way the Discord webhook
  URLs are configured in `NodesLiveModule` — not committed to source, not an env var). Until
  that's done, `dailyStatsGitRepoPath` stays null and the local file still gets written every
  night for the bot's use, just not published anywhere public.

## Relationship to the existing `StatsApi`

`StatsApi` (loopback HTTP, `/api/stats`, refreshed every few hours) is a **separate, untouched**
artifact — a live town/nation/war overview for the bot's `/nodestats` command. This daily
snapshot is deeper per-player/per-nation leaderboard data that's expensive enough to build
(a full walk of every resident) that it's deliberately not something either consumer polls live.
