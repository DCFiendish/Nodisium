# Nodisium — Public Launch Checklist

Master consolidated list of what stands between current state and a public launch. Compiled
2026-09-07 from `HANDOFF.md`, `COMBAT_DEEP_DIVE.md`/`NODES_DEEP_DIVE.md`/`VANILLA_DEEP_DIVE.md`,
and `research-todo/*.md`, then corrected same-day against the user's actual decisions (several items
below were stale/wrong on the deep-dive docs' word alone — see inline notes). This is an index into
those docs, not a replacement for them — check the source doc before acting on anything here, since
several of these move fast.

**Non-negotiable, above everything else on this list: keep 20 TPS.** Any feature/fix here that risks
tick time (unbatched broadcasts, per-tick allocations, synchronous I/O on an event thread, O(n)
scans) gets fixed or reverted before it ships, full stop — this is the single thing the playerbase
cares about most.

`FlagWar.kt`'s H8 blocking-save fix (both `WarSerializer.save()` calls in
`resolveTownDefeat()` made async) landed and is committed (2026-09-12), alongside a new
`Nation.autoReserveUnclaimedTerritory()` map-setup tool (`/nodesadmin nation autoreserveterritory`).
Current branch has uncommitted work only in `modules/nodes/.../TestWeapons.kt` (mid-edit weapon
roster changes, unrelated to this list).

---

## 1. Features not started at all

**World/theme — WorldPainter dropped, real downloaded map confirmed (2026-09-07, resolved 2026-09-12)**
- The public map is a real Underilla-downloaded Europe world, confirmed in place at
  `server/nodisium-data/world` and wired up in `AgadirWorld.kt`/`Main.kt`. WorldPainter
  (`tools/agadir-mapgen/`) is fully deleted — not used anywhere in this project anymore. See
  `docs/HANDOFF.md`'s 2026-09-12 entry for the full trace.
- **The "several chunks corrupted" bug from 2026-09-07 was never diagnosed and is still open** —
  not investigated in the 2026-09-12 pass either. Worth a real look before trusting the border data
  below as final (`tools/nodes-real-borders/paint_borders.py` reads this same map's real biome data
  to decide land vs. ocean per chunk, so a corrupted chunk could misclassify silently).
- **Node/territory painting: done for a first pass, script-built, not hand-painted.**
  `tools/nodes-real-borders/` classifies real country border polygons against the real map's real
  biome data (not hand-tuned boxes) and subdivides into ~4-chunk territories. Deployed to
  `server/nodisium-data/nodes/`, boot-verified. Nations pre-reserve their territories
  (`Nation.reservedTerritories`) — no towns created yet. Real open gaps: land outside the 10
  nations' real border polygons had no territory at all (not Wilderness, just unclaimed) is now
  **mostly closed (2026-09-12)**: `Nation.autoReserveUnclaimedTerritory()` flood-fills remaining free
  territory to the nearest nation, run via `/nodesadmin nation autoreserveterritory`. Territory
  equidistant between two nations is deliberately left contested for hand-assignment — not a bug,
  by design. Trieste/Gibraltar/river-mouth-scale hand precision still hasn't been touched —
  `nodes.soy`'s editor is still the reference for that kind of touch-up once someone's looking at
  this map in-game.
- Alliances and towns get set up **after** node painting is done — sequencing, not parallel work.
  Node painting has had its first pass now; towns/alliances still not started.
- KOTH map needs to be pasted into the new world, then warps + KOTH arena setup done on top of it —
  currently just a floating schematic/build, not placed.
- Rivers/hydrology — zero data sourced yet, and the real downloaded map's own hydrology hasn't been
  checked either way — confirm whether it already has rivers before treating this as still open.
- Custom terrain brushes per biome zone — moot now that WorldPainter (the thing that needed
  brushes) is gone; the real downloaded map's own terrain/biomes are whatever the download shipped
  with, not something this project paints.

**Combat/assets**
- Vehicles (plan steps 5-8 of `.claude/plans/lets-plan-out-guns-merry-twilight.md`) — guns/melee
  only so far; vehicle code, drones, resource-pack pipeline for vehicles all unstarted.
- Weapon sounds — no `.ogg` files exist anywhere in the project.
- More rifle/weapon variety beyond the current roster, plus uniforms/knives/bayonets — open-ended,
  user adding more ideas as they come.
- 7 of 8 obj³ rifles (Lebel M1886, Fedorov Avtomat, Mossberg Patriot, SKS, Springfield 1873,
  VPO-102, Beretta 57) have no real `Gun` stats/balance — placeholder `/testgun` items only.
- Uniforms, buildings, and any non-weapon resource-pack assets for the 1911 theme — nothing started
  beyond rifles/knives (`research-todo/10-asset-sourcing-and-licensing.md`).
- Recipes/obtain-methods for every custom item need to be defined — none of this is speced yet
  (crafting recipes, drop tables, shop purchase, quest reward, whatever mix is decided).
- Thematic renames for existing vanilla resources (e.g. iron → something period-appropriate like
  aluminum) — small, but a real list of what-renames-to-what needs writing before it's a config change.

**Economy/progression**
- **Currency named "Coins"** (decided) — needs to integrate with both the in-game economy and a
  planned website shop. Currency layer itself (`nodes` income → Coins conversion, wallet storage,
  spend points) not built yet.
- Website shop + payment method — not started (see Community/ops below, this is the same website).
- Voucher/redeemable-buff system — confirmed absent, no design started; now more concretely a
  "spend Coins on X" question once Coins exist.
- Territory Tier system (1-10) — **decided: will be implemented**, tiers/cap-time bonus/ore-rate
  bonus curve. **Numbers not decided yet** (don't copy Aechronis's table directly — it was flagged
  reference-only for a reason, but reusing its shape as a starting point is fine).

**Community/ops**
- Discord bot to run staff commands (e.g. automate leader-transfer via a Ticket Tool v2 support
  ticket) — floated, not committed or built.
- In-game↔Discord identity linking — explicitly aspirational ("phenomenal achievement... will
  definitely try"), not scoped, no plan yet.
- Chat/command-usage logging + a bad-word filter — confirmed still absent (`/logger lookup` covers
  block changes/kills/loot/containers only, `NodesChatListener.kt` has zero logging or filtering
  hooked in — checked directly against source 2026-09-07). Two separate small builds: a searchable
  chat-log listener following `logger`'s existing writer pattern, and a filter listener gating
  `PlayerChatEvent` before it broadcasts.
- Network/DDoS protection — **nothing built at any layer** (confirmed still needed):
  - Layer 1 (volumetric): no proxy chosen (TCPShield free tier is the recommended default,
    Cloudflare Spectrum the paid fallback — `research-todo/09-network-and-ddos-security.md`).
  - Layer 2 (firewall backend to proxy-only traffic): not written into any deployment doc yet.
  - Layer 3 (application-layer bot/join-flood mitigation): doesn't exist anywhere in the stack —
    genuine from-scratch build, same category as the voucher system.
- Legal/EULA compliance pass (only matters once/if monetization ships — and it now will, via the
  Coins/website shop) — not done.
- Basic data-handling/GDPR-adjacent pass (what's stored, for how long, deletion-request feasibility)
  — not done.
- Domain — server name is confirmed **Nodisium**; domain will be bought later, off that name.
- **Website** — not started: rules page, a better territory-viewer map (the current `nodes-map` fork
  of Aechronis's is "not satisfactory" per the user), payment method integration, and presumably
  wherever the Coins shop lives.
- Real load test at target scale — the `rust-mc-bot` 50→100→150 bot ladder has never been run
  against the current (right-sized) VM; `RESEARCH.md` §7's ~16GB/3-OCPU figure is still an unverified
  estimate. **Directly gates the 20 TPS commitment above** — this is the actual evidence for whether
  the current VM sizing holds at real player counts, not just idle/bot-load.
- CI/testing policy — no minimum-bar decision made (what needs unit/integration tests vs. what
  doesn't); `TestServer.kt` (in `utils`) exists as a usable harness but nothing decided on top of it.
- `modules/utils` audit — user isn't sure what's in it beyond the LuckPerms bridge/base `Command`
  class; probably has more worth using. Worth a real read-through before assuming it's just glue.
- nodes-map custom Nodisium branding (replace the loading-screen logo) — blocked on the user
  producing artwork. Folds into the "better nodes map" website item above anyway.

---

## 2. Started but incomplete

- ~~**Real terrain (WorldPainter/SRTM15+ pipeline)**: elevation, sea-level, biome zoning...~~
  **Superseded 2026-09-12** — WorldPainter is fully dropped in favor of the real downloaded
  Underilla map (see item 1's "World/theme" entry above and `docs/HANDOFF.md`'s 2026-09-12 entry).
  Everything this bullet used to track (custom-terrain export bug, Mediterranean sclerophyll
  species, Atlantic moorland/heath groundcover, `tools/agadir-mapgen/README.md` itself) is gone
  along with the pipeline — the real map's terrain/biomes are whatever the download shipped with,
  not something generated by this project.
- **Kar98k (first obj³ weapon)**: modeled, textured, hip-fire + third/first-person ADS all confirmed
  in-game. Left-hand poses (third + first person) never visually checked. `resourcepack/CREDITS.md`
  entry still missing. `DevLoadout.kt`'s `TEMP` slot-22 test item not removed.
- **Springfield/Karabiner ADS centering**: Springfield hand-edited in Blockbench to `x=-8.09` but
  never checked in-game after that edit. Karabiner still has its original disproved ratio-estimate
  (`-7.56`), never looked at in-game at all.
- ~~**`modules/worldedit`**: ported, compiles, boots clean, unit tests pass. Nobody has run
  `//wand`/`//set`/`//undo` through a real client yet.~~ **Confirmed working in-game 2026-09-07.**
- **`/warp` destinations and pvp-prep safe-zone boxes**: both `warpsConfig.warps` and
  `pvpPrepConfig.zones` are empty in `Main.kt` — the damage-side bug is fixed, but no actual
  coordinates are picked yet.
- **IntelliJ local run**: CLI (`./gradlew :server:run`) works clean; the IDE's own gutter-arrow run
  hits a `LinkageError` from IntelliJ's JPS builder. Untried fix: run the Gradle `application` task
  directly from the Gradle tool window instead of the gutter arrow.
- **`modules/utils` (external dependency)**: post-2026-08-02 upstream history never audited for
  fixes worth porting, unlike `nodes`/`vanilla`/the from-scratch `combat`.

---

## 3. Known bugs — current status by module

### `modules/combat` (from-scratch rewrite, replaced `Aechronis/combat` entirely)
Guns/melee bug classes from the old `COMBAT_DEEP_DIVE.md` audit (C2 ammo-theft reload dupe, C3
raycast wall-passthrough, C4 no melee reach check, H1 thread-safety, H5 cooldown-swap bypass) were
**designed out from the start** in the rewrite, and a further TOCTOU cooldown race (found via a
`/code-review` pass, 2026-09-02) is fixed. **Vehicle/explosion-specific findings (C1 async
unsynchronized world mutation, C5 explosion no line-of-sight, H3/H6/H7/H8) are moot for now** since
vehicles aren't built yet (§1) — re-open this check once vehicle work (plan steps 5-8) starts.

### `modules/nodes` (confirmed re-checked against current code, 2026-09-02)
All CRITICAL fixed. Still open:
- ~~**H4** — no player-facing leader transfer.~~ **Not a bug — intended design, confirmed by the
  user 2026-09-07**: only admin (`/nodesadmin town leader`, "NDA" = the admin role) should be able
  to change town leader. A Discord-ticket-triggered admin command (§1, Ticket Tool v2 idea) is the
  one floated way to make this less manual, not a player-facing command.
- ~~**H8** — `Resident.renderMinimaps()` render-storm risk during large sieges.~~ **Fixed and
  committed 2026-09-12**: 2026-09-03 same-tick CAS debounce plus `FlagWar.resolveTownDefeat()`'s two
  `WarSerializer.save()` calls switched to async (was blocking the main thread on disk I/O during
  every town defeat/annex).
- **M10** — wilderness permission checks skipped entirely for block-interact events (doors/levers).
- **M12** — home-teleport warmup only cancels on full-block movement, not strafing/teleport-events.
- **M13** — friendly-fire listener has no FlagWar/siege awareness.
- M5/M6/M11/M14 and the full LOW list (~20 items) — not re-checked this pass, verify before assuming
  open or closed.
- **Peace treaties** — NODES_DEEP_DIVE/RESEARCH flagged these as fully absent from the codebase.
  **Resolved as a design decision 2026-09-07: not a gap to fill** — peace/truce is handled through
  the existing `/ally` command instead of a separate treaty mechanic. No new code needed here.

### `modules/vanilla`
**Re-verified against current code 2026-09-12 (see `VANILLA_DEEP_DIVE.md`'s 2026-09-12 banner for
full detail) — all CRITICAL and HIGH items are now fixed, plus all actionable MEDIUMs, plus a new
tool-durability/Unbreaking feature that never existed at all.** Cross-checked against
`Aechronis/aechronis` upstream along the way; ported nothing wholesale since upstream hadn't fixed
the barrel-migration/Saplings/Elevator items either, but confirmed their unrelated
scheduler-thread-mutates-state pattern (`Crops`/`Combat`/`Food`/`EnvironmentalDamage`) is left
exactly as-is even 40+ commits later — that MEDIUM item is treated as a non-issue, not re-litigated.
- CRITICAL: **all fixed or moot.** KillShop/Shop was removed entirely (not patched). Barrel-break
  dupe, PlayerData shutdown/disconnect-save race, and `Commands.kt`'s hot-path `HashMap`s are all
  fixed.
- HIGH: **all fixed.** Ore→resource drop table filled in, fall-damage teleport-reset rewritten
  (also closes the old Slow Falling/Feather Falling/elytra gaps), whitelist writes synchronized,
  `Recipes`/`Mannequin` converted to `ConcurrentHashMap`, autosave added for both `PlayerData` and
  `Storage`, and legacy-barrel-migration's blocking I/O made async.
- MEDIUM: Saplings' `getOrPut` race and Elevator's explosion-staleness gap both fixed this pass;
  `VanillaConfig` validation and the remaining thread-safety conversions were already fixed as of
  the same pass. Tool durability — previously entirely unimplemented — now exists, with a real
  Unbreaking enchantment skip-chance and Unbreakable-flag check neither this fork nor upstream had.

---

## 4. Decisions needed (not bugs, not code — pick an answer)

- Territory Tier numbers — cap-time reduction and ore-rate bonus per tier 1-10 (implementation is
  decided, the actual curve isn't).
- DDoS Layer 1 vendor (TCPShield free tier recommended default) and Layer 3 build-vs-adapt
  (port EpicGuard's design vs. build Minestom-native).
- CI/testing minimum bar.
- Discord↔game chat bridge mechanism — self-hosted bot vs. webhooks, undecided.
- Website/shop payment processor — not picked yet, gates the Coins shop going live.
- Public-map corruption diagnosis (§1) — need source/tool/symptom before this is even a scoped
  problem, not a preference decision, but blocking either way.

**Resolved since the first pass of this doc** (kept here so nobody re-litigates them): project name
(Nodisium), currency name (Coins), peace mechanic (`/ally`, no separate treaty system), leader
transfer (admin-only is intended, not a bug), Territory Tiers adopted in principle, Discord server
itself (exists, staff structure done), WorldEdit (confirmed working in-game).

---

## Suggested order

1. **Fix the new map's corrupted chunks**, then run node/territory painting via the `nodes.soy`
   editor, set alliances/towns, paste the KOTH map + wire warps — this is the current single biggest
   "world doesn't really exist yet" blocker and everything else in §1 (tiers, alliances, KOTH) is
   sequenced behind it.
2. ~~Re-verify `VANILLA_DEEP_DIVE.md` against current code~~ **Done 2026-09-12** — all CRITICAL/HIGH
   fixed, all actionable MEDIUMs fixed, tool durability/Unbreaking added. See that doc's banner.
3. Run the real load-test ladder against the current VM once the new map is live — this is the
   actual evidence for the 20 TPS commitment, not the current unverified estimate.
4. Stand up DDoS Layer 1+2 before the server is ever publicly reachable — this is explicitly called
   out in `research-todo/00-index.md` as the one item that must land before public exposure,
   independent of everything else's readiness.
5. Everything else in §1/§2 (vehicles, more weapons, Coins economy, website/shop, recipes) can ship
   incrementally post-soft-launch.
