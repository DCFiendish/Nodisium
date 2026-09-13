# Discord Bot — Planned Features

Status: v1 built and live (console bridge + war start/end webhook, see "Built so far" below). This
doc exists to stop these ideas from living only in chat history / memory files, scattered across
`LAUNCH_CHECKLIST.md`, `RESEARCH.md`, and `research-todo/07-community-and-onboarding.md`. Add to
this list as new ideas come up instead of letting them scatter again.

## Built so far

- **Console bridge bot** — separate repo, `DCFiendish/nodisium-discord-bot` (private), deployed as
  a systemd service (`nodisium-discord-bot.service`) on the same VM as the game server
  (150.136.235.233), not a Pterodactyl-managed server — creating one needs panel admin API access
  this deploy doesn't have. Runs as an unprivileged `discordbot` system user, capped at 256MB
  RAM / 25% CPU, `Restart=always`. A message typed by an Admin/Owner-role member in the `#console`
  channel gets sent as a console command via the Pterodactyl Client API, reacts ✅/❌, and echoes
  back whatever the server printed to console in the following ~1.5s (via Pterodactyl's websocket
  console stream). `TickMonitor` lines get pulled out and batch-posted to `#tickmonitor` instead of
  cluttering command replies. See that repo's README for setup/deploy details.
- **War start/end webhook** — `/nodesadmin war enable`/`disable` post to the `#events` channel via
  a plain Discord webhook (not the bot), added directly in
  [DiscordWebhook.kt](../modules/nodes/src/main/kotlin/net/aechronis/nodes/DiscordWebhook.kt) and
  wired into [NodesAdminCommand.kt](../modules/nodes/src/main/kotlin/net/aechronis/nodes/commands/NodesAdminCommand.kt).
  The webhook URL lives in `nodisium-data/discord_war_webhook.txt` on the VM (`chmod 600`), not in
  source or an env var — this server's Pterodactyl egg has no such startup variable and adding one
  needs panel admin access. `/nodesadmin war skirmish` does not post (not asked for yet).
- Found and fixed along the way: a "testing-only" `Nodes.enableWar()` call in
  `NodesLiveModule.initialize()` was re-enabling war on every `/modules reload nodes`, not just
  first boot — removed. Also found (and spun off, now fixed) a separate pre-existing bug where
  zero-argument `/nodesadmin` console commands (war enable/disable, save, load, etc.) silently
  failed to dispatch via Pterodactyl/console while working fine in-game.

## Features

1. **Staff-command automation** — bot runs `/nodesadmin town leader` when a staff member opens a
   Ticket Tool v2 support ticket for a leader-transfer request. Leader transfer stays admin-only by
   design (not player-facing); this just removes the manual console step. Floated, not committed.
   The console bridge (above) already lets staff run this by hand from Discord today; the
   ticket-triggered automation part is still undone.

2. **In-game ↔ Discord identity linking** — link a player's Minecraft account to their Discord
   account. Aspirational, not scoped yet (no verification flow, no data model decided).

3. **Discord ↔ in-game chat bridge** — mirror chat between a Discord channel and in-game chat.
   Undecided: self-hosted bot vs. Discord webhooks.

4. **Town-application notification hook** — when a player submits a `/town apply`, ping the town's
   officers in Discord (webhook or bot message) if none are online in-game. Applications currently
   auto-expire after 60 seconds with no notification, which is a bad first impression for new
   players.

5. **Nation pre-approval workflow** — nations are sometimes approved via a Discord conversation
   before the team has picked territory or founded a town (`Nation.create` already supports a
   null-town nation for this case). Whether the bot should have a role in this approval step
   (application form, staff-review command, auto-creating the nation record) is undecided.

6. **`/stats <playername>` command** — show a player's stats in Discord. Same stats need to be
   shown on the website too, so the data source/API should be shared between the bot and the
   website rather than built twice. Stat fields to show: not decided yet.

7. **Auto-upload `.litematica` files to the VM** — for the paid "paste" feature (player buys a
   build, admin pastes it in via WorldEdit/Litematica): bot takes the uploaded `.litematica` file
   from Discord and pushes it straight to the server's `schematics` folder on the VM
   (`WorldEditConfig.saveDir`, see
   [modules/worldedit/.../WorldEditConfig.kt:37](../modules/worldedit/src/main/kotlin/io/github/openminigameserver/worldedit/platform/config/WorldEditConfig.kt)),
   replacing the manual SFTP/console step. Needs: upload size/type validation, destination path
   (probably per-order subfolder so pastes don't collide/overwrite), and who's allowed to trigger
   it (paying customer only, or staff-confirmed after payment).

## Explicitly out of scope

- Nation/town membership vetting — stays entirely in-game via `/town apply`/`/town invite`. No
  Discord gate, no review-ticket step. (Resolved 2026-07-30.)

## Open questions across all features above

- ~~Bot hosting: self-hosted process vs. a serverless/webhook-only approach.~~ Resolved: self-hosted
  systemd service on the same VM (see "Built so far"). Confirmed the VM has plenty of headroom
  (23GB RAM / 4 cores, game server capped at its own fixed 18.9GB regardless of what else runs) so
  this doesn't compete with scaling the game server up.
- What backs `/stats` and the website stats page — read directly from the server's data store, or
  through a small API the server exposes? Still open — leaning toward a small read-only HTTP API
  added to the Minestom server itself, shared by both the bot and the static website, so the data
  source isn't built twice. Not started.
- Auth/permissions model for any bot command that touches server state (leader-transfer, nation
  approval). Still open for bot-triggered actions specifically. The console bridge itself is
  gated to the Admin/Owner Discord roles only (not the general Staff role) since it can run
  arbitrary console commands.
