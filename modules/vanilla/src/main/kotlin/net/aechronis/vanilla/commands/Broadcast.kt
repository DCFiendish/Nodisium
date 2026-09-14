package net.aechronis.vanilla.commands

import net.aechronis.utils.Command
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player

private const val MAX_BROADCAST_LENGTH = 512

class Broadcast : Command("broadcast", "vanilla.broadcast") {
    val messageArg = ArgumentType.StringArray("message")

    init {
        setDefaultExecutor { player, _ ->
            player.sendMessage(Component.text("Usage:").color(NamedTextColor.LIGHT_PURPLE))
            player.sendMessage(Component.text("/broadcast <message>").color(NamedTextColor.LIGHT_PURPLE))
        }

        addSyntax({ sender: Player, context ->
            val text = context.get(messageArg).joinToString(" ")
            if (text.length > MAX_BROADCAST_LENGTH) {
                sender.sendMessage(Component.text("Message too long (max $MAX_BROADCAST_LENGTH characters).", NamedTextColor.RED))
                return@addSyntax
            }
            val component =
                Component
                    .text("[brodcast] ", NamedTextColor.GOLD)
                    .append(Component.text(text, NamedTextColor.GREEN))

            for (player in MinecraftServer.getConnectionManager().onlinePlayers) {
                player.sendMessage(component)
            }
        }, messageArg)
    }
}
