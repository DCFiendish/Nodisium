/**
 * Utility class to reduce boilerplate in command handlers
 */

package net.aechronis.nodes.objects

import net.aechronis.nodes.Message
import net.aechronis.utils.Command
import net.aechronis.utils.hasPermission
import net.minestom.server.command.CommandSender
import net.minestom.server.command.ConsoleSender
import net.minestom.server.command.builder.CommandContext
import net.minestom.server.command.builder.CommandExecutor
import net.minestom.server.command.builder.arguments.Argument
import net.minestom.server.entity.Player

open class NodesCommand(
    name: String,
    permission: String? = null,
    vararg aliases: String,
) : Command(name, permission, *aliases) {

    /**
     * Add a default executor that requires the sender to be a resident.
     */
    fun setDefaultExecutor(
        executor: (player: Player, resident: Resident, context: CommandContext) -> Unit,
    ) {
        super.setDefaultExecutor { player: Player, context ->
            val resident = Resident.fromPlayer(player)
            executor(player, resident!!, context)
        }
    }

    /**
     * Add a syntax that requires the sender to be a resident.
     */
    fun addSyntax(
        executor: (player: Player, resident: Resident, context: CommandContext) -> Unit,
        vararg args: Argument<*>,
    ) {
        super.addSyntax({ player: Player, context ->
            val resident = Resident.fromPlayer(player)
            executor(player, resident!!, context)
        }, *args)
    }

    /**
     * Add a syntax that requires the sender to be a resident that is in a town
     */
    fun addSyntax(
        executor: (player: Player, resident: Resident, town: Town, context: CommandContext) -> Unit,
        vararg args: Argument<*>,
    ) {
        super.addSyntax({ player: Player, context ->
            val resident = Resident.fromPlayer(player)
            if (resident == null) {
                Message.error(player, "This command can only be used by players")
                return@addSyntax
            }

            val town = Town.fromPlayer(player)
            if (town == null) {
                Message.error(player, "You must be in a town to use this command")
                return@addSyntax
            }

            executor(player, resident, town, context)
        }, *args)
    }

    /**
     * Add a syntax that requires the sender to be a resident that is in a nation
     */
    fun addSyntax(
        executor: (player: Player, resident: Resident, town: Town, nation: Nation, context: CommandContext) -> Unit,
        vararg args: Argument<*>,
    ) {
        super.addSyntax({ player: Player, context ->
            val resident = Resident.fromPlayer(player)
            if (resident == null) {
                Message.error(player, "This command can only be used by players")
                return@addSyntax
            }

            val town = resident.town
            if (town == null) {
                Message.error(player, "You must be in a town to use this command")
                return@addSyntax
            }

            val nation = resident.nation
            if (nation == null) {
                Message.error(player, "You must be in a nation to use this command")
                return@addSyntax
            }

            executor(player, resident, town, nation, context)
        }, *args)
    }

    /**
     * Add a syntax callable from console (RCON/Pterodactyl) as well as an op player, for
     * nodes.admin commands that take fully explicit arguments and don't need a Resident.
     *
     * The inherited Function2<Player, ...> addSyntax overloads above always bounce non-Player
     * senders before the executor runs (see net.aechronis.utils.Command's validatedPlayer), so
     * console could never reach nodes.admin commands even though its permission was already
     * being treated as always-allowed elsewhere (e.g. Main.kt's Spark permission handler). This
     * calls the raw Minestom Command.addSyntax directly to bypass that Player requirement.
     *
     * Console is always allowed, matching every other CommandSender that isn't a Player
     * elsewhere in this codebase. A Player sender still needs this command's permission.
     */
    fun addConsoleSyntax(
        executor: (sender: CommandSender, context: CommandContext) -> Unit,
        vararg args: Argument<*>,
    ) {
        val requiredPermission = permission
        super.addSyntax(CommandExecutor { sender, context ->
            val allowed = sender is ConsoleSender ||
                (sender is Player && (requiredPermission == null || hasPermission(sender, requiredPermission)))
            if (!allowed) {
                Message.error(sender, "You don't have permission to use this command")
                return@CommandExecutor
            }
            executor(sender, context)
        }, *args)
    }
}
