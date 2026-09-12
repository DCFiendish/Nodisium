# Discord Bot — Planned Features

Status: planning only, nothing built yet. This doc exists to stop these ideas from living only in
chat history / memory files, scattered across `LAUNCH_CHECKLIST.md`, `RESEARCH.md`, and
`research-todo/07-community-and-onboarding.md`. Add to this list as new ideas come up instead of
letting them scatter again.

## Features

1. **Staff-command automation** — bot runs `/nodesadmin town leader` when a staff member opens a
   Ticket Tool v2 support ticket for a leader-transfer request. Leader transfer stays admin-only by
   design (not player-facing); this just removes the manual console step. Floated, not committed.

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

- Bot hosting: self-hosted process vs. a serverless/webhook-only approach.
- What backs `/stats` and the website stats page — read directly from the server's data store, or
  through a small API the server exposes?
- Auth/permissions model for any bot command that touches server state (leader-transfer, nation
  approval).
