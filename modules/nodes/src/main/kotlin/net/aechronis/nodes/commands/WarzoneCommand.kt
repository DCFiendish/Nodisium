package net.aechronis.nodes.commands

import net.aechronis.nodes.Message
import net.aechronis.nodes.commands.arguments.ArgumentTerritory
import net.aechronis.nodes.commands.arguments.ArgumentTerritoryArray
import net.aechronis.nodes.objects.NodesCommand
import net.aechronis.nodes.objects.Town
import net.aechronis.nodes.utils.ChatColor
import net.aechronis.nodes.war.Warzone

class WarzoneCommand : NodesCommand("warzone") {
    init {
        setDefaultExecutor { player, _, _ ->
            Message.print(player, "Usage: /warzone <territory-id>")
        }

        val territoryArg = ArgumentTerritory.create("territory-id")
        addSyntax({ player, _, context ->
            val territory = context[territoryArg]
            val ranking = Warzone.ranking(territory)
            Message.print(player, "${ChatColor.BOLD}Warzone rankings for territory ${territory.id}:")
            if (ranking.isEmpty()) {
                Message.print(player, "- No nation occupies this territory yet")
            } else {
                ranking.forEachIndexed { index, score ->
                    Message.print(player, "${index + 1}. ${score.nation.name}${ChatColor.WHITE}: ${formatTime(score.millis)}")
                }
            }
        }, territoryArg)
    }

    private fun formatTime(millis: Long): String {
        val seconds = millis / 1000L
        return "%02d:%02d:%02d".format(seconds / 3600L, (seconds % 3600L) / 60L, seconds % 60L)
    }
}

class NodesAdminWarzoneCommand : NodesCommand("warzone", "nodes.admin") {
    init {
        setDefaultExecutor { player, _, _ ->
            Message.print(player, "Usage: /nda warzone <territory-ids>")
            Message.print(player, "/nda warzone stop <territory-id>")
        }

        val territoriesArg = ArgumentTerritoryArray.create("territory-ids")
        addConsoleSyntax({ sender, context ->
            val territories = context[territoriesArg]
            val claimed = territories.filter { it.town != null }
            val unclaimed = territories - claimed.toSet()
            Warzone.register(claimed)
            if (claimed.isNotEmpty()) {
                Message.print(sender, "Enabled warzones for territories: ${claimed.joinToString(", ") { it.id.toString() }}")
            }
            if (unclaimed.isNotEmpty()) {
                Message.error(sender, "Warzone territories must belong to a town: ${unclaimed.joinToString(", ") { it.id.toString() }}")
            }
        }, territoriesArg)

        addSubcommand(NodesAdminWarzoneStopCommand())
    }
}

private class NodesAdminWarzoneStopCommand : NodesCommand("stop", "nodes.admin") {
    init {
        setDefaultExecutor { player, _, _ ->
            Message.print(player, "Usage: /nda warzone stop <territory-id>")
        }

        val territoryArg = ArgumentTerritory.create("territory-id")
        addConsoleSyntax({ sender, context ->
            val territory = context[territoryArg]
            Warzone.stop(territory)
                .onSuccess { winner ->
                    // A warzone winner always already owns contested territory via a town (see
                    // NodesAdminWarzoneCommand's "must belong to a town" check), so capital is
                    // never actually null here -- still guarded since capital is nullable now.
                    val capital = winner.capital
                    if (capital == null) {
                        Message.error(sender, "Warzone winner \"${winner.name}\" has no capital to award territory ${territory.id} to")
                        return@onSuccess
                    }
                    Town.capture(capital, territory)
                    Message.broadcast(
                        "${ChatColor.DARK_RED}[Warzone] ${winner.name} won territory ${territory.id}; " +
                            "it has been awarded to ${capital.name}",
                    )
                }
                .onFailure { error -> Message.error(sender, error.message ?: "Failed to stop warzone") }
        }, territoryArg)
    }
}
