# Logger Staff Guide

The logger module records block, container, and feature (custom mechanic) changes so staff can
look up who did what, and reverse it. Base command: `/logger` (alias `/lo`).

## Permissions

Each subcommand has its own node — grant what a rank actually needs, not blanket admin:

| Node | Grants |
|---|---|
| `logger.inspect` | `/logger inspect` |
| `logger.lookup` | `/logger lookup` |
| `logger.page` | `/logger page` |
| `logger.snapshot` | `/logger snapshot` |
| `logger.rollback` / `logger.rollback.force` / `logger.rollback.confirm` | `/logger rollback`, `#force` flag, confirming without safety mode |
| `logger.restore` / `logger.restore.force` / `logger.restore.confirm` | `/logger restore`, same as above |
| `logger.restorechunk` | `/logger restorechunk` |
| `logger.undo` | `/logger undo` |
| `logger.redo` | `/logger redo` |
| `logger.safety` | `/logger safety` |
| `logger.recover` | `/logger recover` |

## Everyday commands

**`/logger inspect`** (`/logger i`) — toggle inspect mode. While on, punching/using a block tells
you its change history instead of breaking/using it normally.

**`/logger lookup ...`** (`/logger l`) — search history without inspect mode. Parameters are
space-separated `key:value` tokens, any order, all optional:

| Key | Aliases | Meaning |
|---|---|---|
| `u:` | `user:` | Player name(s), comma-separated |
| `t:` | `time:` | Time window, e.g. `t:1d`, `t:2h30m`, or a range `t:2d-1d` |
| `r:` | `radius:` | Block radius from you, or `r:#global` for the whole world |
| `cr:` | `chunkradius:` | Chunk radius instead of block radius |
| `a:` | `actions:` | `break`, `place`, `block` (both), `interact`/`use`, or container: `container`/`storage`/`item`, `+container`/`deposit`, `-container`/`withdraw` |
| `i:` | `include:` | Only these block types |
| `e:` | `exclude:` | Skip these block types |
| `c:` | `context:` | Filter by block source/context |
| `o:` | `origin:` | Filter by recording origin |
| `s:` | `source:` | Feature source — routes to the feature log instead of block log (can't combine with `i:`/`e:`/`c:`) |

Examples:
```
/logger lookup u:Steve t:1d r:50 a:break
/logger lookup s:mining_boost u:Steve t:6h
```
Results page — use `/logger page <number>` to flip through.

**`/logger snapshot <player>`** — view a player's saved history snapshots list.

## Reversing changes

**`/logger rollback ...`** (`/logger rb`) and **`/logger restore ...`** (`/logger rs`) take the
same `key:value` params as lookup, plus flags:
- `#preview` — show what would change, don't apply it
- `#force` — skip the safety confirmation (needs `.force` permission)
- `#verbose` / `#silent` — output detail

Only block history can currently be mutated this way (not storage/container, except see below).
Global operations (`r:#global`) require `u:<user>` unless you're targeting an entity domain, or
doing a source-scoped storage op. Storage/container mutations require `r:#global` **and** either
`u:<user>` or `c:<source>`.

By default (safety mode on) the command shows a preview and gives you a confirm token:
```
/logger rollback confirm:<token>
/logger rollback cancel:<token>
```
Toggle safety mode with **`/logger safety [on|off|toggle]`** — with it off, rollback/restore
apply immediately without a confirm step (until you log out). Turn it off only if you're doing a
batch of ops you've already sanity-checked.

**`/logger restorechunk`** (`/logger rc`) — restores a chunk (or chunk radius, max 8) to its
original generated state, undoing all player edits in it. Same confirm/cancel token flow as
rollback. Usage:
```
/logger restorechunk                              (chunk you're standing in)
/logger restorechunk <chunk-x> <chunk-z>
/logger restorechunk radius <radius>
/logger restorechunk radius <chunk-x> <chunk-z> <radius>
```
This is destructive to player builds in the area — confirm the chunk coordinates before hitting
confirm.

**`/logger undo`** / **`/logger redo`** — undo/redo your own last rollback/restore operation.

**`/logger recover acknowledge`** — only after the server crashed or restarted mid-rollback. Check
manually that the interrupted operation actually landed in a consistent state before acknowledging.

## Quick reference

```
/logger inspect
/logger lookup u:<user> t:<time> r:<radius> a:<action> i:<include> e:<exclude> c:<source> o:<origin>
/logger lookup s:<feature-source> u:<user> t:<time> r:<radius> a:<action> o:<origin>
/logger page <number>
/logger snapshot <player>
/logger rollback ...  [#preview] [#force]
/logger restore ...   [#preview] [#force]
/logger restorechunk [<x> <z> | radius <r> | radius <x> <z> <r>]
/logger undo
/logger redo
/logger safety [on|off|toggle]
/logger recover acknowledge
```
