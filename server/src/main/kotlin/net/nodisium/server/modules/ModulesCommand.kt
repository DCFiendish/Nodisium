package net.nodisium.server.modules

import net.aechronis.utils.hasPermission
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.command.ConsoleSender
import net.minestom.server.command.builder.Command
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player

private const val PERMISSION = "nodisium.modules"

/**
 * `/modules list|reload <id>` -- lives on the core classloader (registered once at boot in
 * Main.kt), not inside any of the four modules it manages, so it keeps working even if the module
 * currently being reloaded fails to come back up.
 */
class ModulesCommand : Command("modules") {
    init {
        setCondition { sender, _ -> sender is ConsoleSender || (sender is Player && sender.hasPermission(PERMISSION)) }
        setDefaultExecutor { sender, _ -> sender.sendMessage(usage()) }

        addSyntax({ sender, _ ->
            val ids = ModuleManager.ids().joinToString(", ")
            sender.sendMessage(Component.text("Modules: $ids", NamedTextColor.YELLOW))
        }, ArgumentType.Literal("list"))

        val moduleId = ArgumentType.Word("module").from(*ModuleManager.ids().toTypedArray())
        addSyntax({ sender, context ->
            val id = context[moduleId]
            sender.sendMessage(Component.text("Reloading '$id'...", NamedTextColor.YELLOW))
            ModuleManager.reload(id)
                .onSuccess {
                    sender.sendMessage(Component.text("Reloaded '$id'", NamedTextColor.GREEN))
                }
                .onFailure { error ->
                    // error.message already names the module that actually failed -- which can
                    // differ from $id once a dependency cascade is involved (see reload's kdoc).
                    sender.sendMessage(Component.text(error.message ?: "Failed to reload '$id'", NamedTextColor.RED))
                    error.printStackTrace()
                }
        }, ArgumentType.Literal("reload"), moduleId)
    }

    private fun usage() = Component.text("Usage: /modules <list|reload> [module]", NamedTextColor.YELLOW)
}
