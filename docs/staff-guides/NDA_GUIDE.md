# /nda (Nodes Admin) Staff Guide

`/nda` is the short alias for `/nodesadmin` — world/town/nation admin commands for the Nodes
module. Permission node: `nodes.admin` (single node covers everything below — this command is
admin-only by design, not split into sub-permissions).

Run `/nda` or `/nda help` any time for the in-game command list.

## War

```
/nda war                    show current war state
/nda war enable             full war: territory annexation + destruction on
/nda war disable            end war
/nda war skirmish           border-only fighting, no annexation (destruction follows config)
```
Broadcasts to the server and posts to the Discord war webhook when toggled.

## Towns

```
/nda town create <name> <territory-ids>       first territory becomes the town's home
/nda town delete <name>                       blocked if it owns a registered warzone
/nda town rename <name> <new-name>
/nda town addplayer <name> <players>
/nda town removeplayer <name> <players>
/nda town addterritory <name> <territory-ids>
/nda town removeterritory <name> <territory-ids>
/nda town captureterritory <name> <territory-ids>
/nda town releaseterritory <territory-ids>
/nda town setspawn <name>                     sets to your current location
/nda town spawn <name>                        teleport to it
/nda town sethome <name> <territory-id>
/nda town defaulttownspawns <names>
/nda town addofficer <name> <players>
/nda town removeofficer <name> <players>
/nda town leader <name> <player>              admin-only by design — no player-facing leader transfer
/nda town removeleader <name>
/nda town color <name> <r> <g> <b>            0-255 each
/nda town income <name>                       opens the town's income inventory
/nda town plot                                (see building/plot commands)
/nda town merge <townA> <townB>               townB's territories move into townA, townB deleted
/nda town move <townA> <townB>                townB's residents move into townA as regular residents
/nda town lives <name> <number>               min 1
```

## Nations

```
/nda nation create <name> [town-names]
/nda nation delete <name>
/nda nation rename <name> <new-name>
/nda nation addtown <name> <town-names>
/nda nation removetown <name> <town-names>
/nda nation addally <nationA> <nationB>
/nda nation removeally <nationA> <nationB>
/nda nation addenemy <nationA> <nationB>
/nda nation removeenemy <nationA> <nationB>
/nda nation capital <name> <town>
/nda nation color <name> <r> <g> <b>
/nda nation reserveterritory <name> <territory-ids>
/nda nation unreserveterritory <territory-ids>
/nda nation autoreserveterritory                map-setup tool only — flood-fills unclaimed
                                                 territory to nearest nation, not for live use
```

## Buildings (ports/farms)

```
/nda building create port <name> <public true|false> [tier 1-3]   at your current chunk
/nda building create farm [tier 1-3]                               at your current chunk
/nda building delete                            deletes the building in your current chunk
/nda building settier <tier 1-3>                sets tier of building in your current chunk
```

## World / economy

```
/nda save [sync]        force-save the world (async by default; sync blocks until done)
/nda load                force-load the world
/nda runincome           runs income for all towns immediately (normally on a schedule)
/nda miningboost <haste|boost> <multiplier> <time>
                          global mining buff, time in seconds by default or with ms/s/m/h/d
                          suffix, e.g. "30m", "2h"
```

## Notes for staff

- There's no player-facing way to change a town's leader — that's intentional (confirmed launch
  decision), so use `/nda town leader` when a player requests a leader change, verified through
  your normal ticket/support process.
- Color commands (`town color`, `nation color`) clamp each RGB channel to 0-255.
- `nation autoreserveterritory` is a one-shot map-generation helper — don't run it on a live map
  with claimed towns nearby.
