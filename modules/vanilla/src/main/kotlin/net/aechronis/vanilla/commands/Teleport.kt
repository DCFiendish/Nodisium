package net.aechronis.vanilla.commands

import net.aechronis.utils.Command
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player

class Teleport : Command("teleport", "vanilla.teleport", "tp") {
    init {
        setDefaultExecutor { player: Player, _ ->
            player.sendMessage(Component.text("Usage:", NamedTextColor.LIGHT_PURPLE))
            player.sendMessage(Component.text("/teleport <playerA-name>", NamedTextColor.LIGHT_PURPLE))
            player.sendMessage(Component.text("/teleport <position>", NamedTextColor.LIGHT_PURPLE))
            player.sendMessage(Component.text("/teleport <playerA-name> <playerB-name>", NamedTextColor.LIGHT_PURPLE))
            player.sendMessage(Component.text("/teleport <playerA-name> <position>", NamedTextColor.LIGHT_PURPLE))
        }

        val playerAArg = ArgumentType.Entity("playerA-name").singleEntity(true).onlyPlayers(true)
        val playerBArg = ArgumentType.Entity("playerB-name").singleEntity(true).onlyPlayers(true)
        val posArg = ArgumentType.RelativeVec3("position")

        // teleport self to other player
        addSyntax({ sender: Player, context ->
            val target =
                context[playerAArg].findFirstPlayer(sender) ?: run {
                    sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED))
                    return@addSyntax
                }
            sender.teleport(target.position)
        }, playerAArg)

        // teleport self to coords
        addSyntax({ sender: Player, context ->
            val pos = context[posArg].from(sender.position).asPos()
            sender.teleport(pos)
        }, posArg)

        // teleport player to other player
        addSyntax({ sender: Player, context ->
            val player =
                context[playerAArg].findFirstPlayer(sender) ?: run {
                    sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED))
                    return@addSyntax
                }
            val target =
                context[playerBArg].findFirstPlayer(sender) ?: run {
                    sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED))
                    return@addSyntax
                }
            player.teleport(target.position)
        }, playerAArg, playerBArg)

        // teleport player to coords
        addSyntax({ sender: Player, context ->
            val player =
                context[playerAArg].findFirstPlayer(sender) ?: run {
                    sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED))
                    return@addSyntax
                }
            val pos = context[posArg].from(player.position).asPos()
            player.teleport(pos)
        }, playerAArg, posArg)
    }
}
