package dev.horizon.client.command

import com.mojang.blaze3d.Blaze3D
import com.mojang.blaze3d.platform.InputConstants
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import dev.horizon.client.config.ConfigType
import dev.horizon.client.config.Configs
import dev.horizon.client.config.Friends
import dev.horizon.client.gui.Keys
import dev.horizon.client.module.ClickGuiModule
import dev.horizon.client.module.Config
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import dev.horizon.client.module.equip.AutoArmor
import dev.horizon.client.setting.Bind
import net.minecraft.ChatFormatting
import net.minecraft.client.multiplayer.ClientSuggestionProvider
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.inventory.ContainerInput
import java.util.Locale
import java.util.concurrent.Executors

private typealias Builder = LiteralArgumentBuilder<ClientSuggestionProvider>

/** `bind <module> <key>` (none/null clears), `bind list`, `bind clear` / `bind reset`. */
object BindCommand : ClientCommand("bind") {
    override fun build(builder: Builder) {
        builder.then(argument("module", ModuleArgument).then(argument("key", StringArgumentType.word()).executes { c ->
            bind(c.getArgument("module", Module::class.java), c.getArgument("key", String::class.java))
            1
        }))
        builder.then(literal("list").executes { list(); 1 })
        builder.then(literal("clear").executes { clear(); 1 })
        builder.then(literal("reset").executes { clear(); 1 })
        builder.executes { message(ChatFormatting.RED.toString() + "bind <id> <key> / bind list / bind clear"); 1 }
    }

    private fun bind(module: Module, key: String?) {
        if (key == null) {
            message("There is no such button")
            return
        }
        val code = if (!key.equals("none", true) && !key.equals("null", true)) {
            try {
                InputConstants.getKey("key.keyboard." + key.lowercase(Locale.ROOT)).value
            } catch (_: NumberFormatException) {
                message("There is no such button")
                return
            }
        } else {
            -1
        }
        if (code == 0) {
            message("There is no such button")
            return
        }
        // A mouse bind when the word contains an "M" but isn't just "M" (on purpose).
        module.bind = Bind(code, false, key != "M" && key.contains("M"))
        val text = Component.empty().append(Component.literal(module.name).withStyle(ChatFormatting.GREEN))
            .append(Component.literal(" is now binded to ").withStyle(ChatFormatting.WHITE))
        text.append(Component.literal(key.uppercase(Locale.ROOT)).withStyle(ChatFormatting.GRAY))
        message(text)
    }

    private fun list() {
        val sb = StringBuilder("Binds: ")
        for (m in Modules.all) {
            if (Keys.name(m.bind) == "None") continue
            sb.append("\n" + m.name).append("-> ").append(Keys.name(m.bind)).append(if (m.bind.hold) "[hold]" else "")
        }
        message(sb.toString())
    }

    /** Every module but the UI one goes back to no bind. */
    private fun clear() {
        for (m in Modules.all.filter { it !is ClickGuiModule }) m.bind = Bind()
    }
}

/** `friend add <player>`, `friend remove <friend>`, `friend list`, `friend clear`. */
object FriendCommand : ClientCommand("friend") {
    override fun build(builder: Builder) {
        builder.then(literal("clear").executes {
            Friends.names.clear()
            message("Friends are cleared")
            1
        })
        builder.then(literal("add").then(argument("player", PlayerArgument).executes { c ->
            val name = c.getArgument("player", String::class.java)
            Friends.add(name)
            message("$name is now your friend")
            1
        }))
        builder.then(literal("remove").then(argument("player", FriendArgument).executes { c ->
            val name = c.getArgument("player", String::class.java)
            Friends.remove(name)
            message("$name is no longer a friend")
            1
        }))
        builder.then(literal("list").executes {
            if (Friends.names.isEmpty()) {
                message("Friend list is empty")
            } else {
                val sb = StringBuilder("Friends: ")
                for (n in Friends.names) sb.append(n).append(", ")
                message(sb.toString().dropLast(2))
            }
            1
        })
        builder.executes { message(ChatFormatting.RED.toString() + "friend add <name> / friend remove <name> / friend list / friend clear"); 1 }
    }
}

/** Module configs — `cfg list`, `cfg folder`, `cfg reset`, `cfg save|load|remove <name>`. */
object ConfigCommand : ClientCommand("cfg") {
    override fun build(builder: Builder) {
        builder.then(literal("list").executes {
            val sb = StringBuilder("Configs: ")
            for (c in Config.list) {
                sb.append(ChatFormatting.RESET).append("\n" + (if (c === Config.current) ChatFormatting.GREEN else ""))
                    .append(c.id).append(ChatFormatting.RESET)
            }
            message(sb.toString())
            1
        })
        builder.then(literal("folder").executes {
            try {
                val dir = Config.dir.toFile()
                if (!dir.exists()) dir.createNewFile()
                Blaze3D.openPath(dir.toPath())
            } catch (e: Exception) {
                e.printStackTrace()
            }
            1
        })
        builder.then(literal("reset").executes {
            for (m in Modules.all) {
                if (m.enabled) m.disable()
                for (s in m.settings) s.reset()
            }
            1
        })
        builder.then(literal("save").then(argument("name", StringArgumentType.word()).executes { c ->
            val name = c.getArgument("name", String::class.java)
            Configs.create(name, ConfigType.MODULES)
            message("$name saved")
            1
        }))
        builder.then(literal("load").then(argument("name", ConfigArgument).executes { c ->
            val name = c.getArgument("name", String::class.java)
            Configs.load(name, ConfigType.MODULES)
            message("Loaded $name")
            1
        }))
        builder.then(literal("remove").then(argument("name", ConfigArgument).executes { c ->
            val name = c.getArgument("name", String::class.java)
            Configs.delete(name, ConfigType.MODULES)
            message("$name removed")
            1
        }))
        builder.executes { message(ChatFormatting.RED.toString() + "cfg <load/remove/save> <id> / cfg list"); 1 }
    }
}

/** `gps <x> <z>` points the compass arrow there, `gps off` hides it. */
object GpsCommand : ClientCommand("gps") {
    override fun build(builder: Builder) {
        builder.then(literal("off").executes {
            Commands.gps = null
            1
        })
        builder.then(argument("x", IntegerArgumentType.integer()).then(argument("z", IntegerArgumentType.integer()).executes { c ->
            Commands.gps = BlockPos(c.getArgument("x", Int::class.java), 0, c.getArgument("z", Int::class.java))
            1
        }))
        builder.executes { message("gps <off / x z>"); 1 }
    }
}

/** `angle <yaw> <pitch>` sets the view; "~n" is relative, anything unreadable keeps the current value. */
object AngleCommand : ClientCommand("angle") {
    override fun build(builder: Builder) {
        builder.then(argument("yaw", StringArgumentType.string()).then(argument("pitch", StringArgumentType.string()).executes { c ->
            val p = mc.player!!
            p.yRot = value(c.getArgument("yaw", String::class.java), p.yRot)
            p.xRot = value(c.getArgument("pitch", String::class.java), p.xRot)
            1
        })).executes { message("angle <yaw> <pitch>"); 1 }
    }

    private fun value(s: String, current: Float): Float =
        if (s.startsWith("~")) current + (s.removePrefix("~").toFloatOrNull() ?: 0.0f) else s.toFloatOrNull() ?: current
}

/**
 * `fixall [delay ms]` (100 by default) runs the server's "/fix hand" on the held item, then on every
 * armour piece, hotbar item and damaged inventory item in turn, swapping each into the hand, on a background thread.
 */
object FixAllCommand : ClientCommand("fixall") {
    private val executor = Executors.newVirtualThreadPerTaskExecutor()

    override fun build(builder: Builder) {
        builder.then(argument("delay", IntegerArgumentType.integer()).executes { c ->
            fixAll(c.getArgument("delay", Int::class.java).toLong())
            1
        })
        builder.executes {
            fixAll(100)
            1
        }
    }

    private fun fixAll(delay: Long) {
        mc.connection!!.sendCommand("fix hand")
        swap(1)
        executor.execute {
            for (i in 5 until 45) {
                Modules.get<AutoArmor>().delayTimer.set(-5)
                val stack = mc.player!!.inventory.getItem(if (i >= 36) i - 36 else i)
                if (!stack.isEmpty && stack.isBarVisible || i <= 8) {
                    swap(i)
                    Thread.sleep(delay)
                    mc.connection!!.sendCommand("fix hand")
                    Thread.sleep(delay)
                    swap(i)
                    Thread.sleep(delay)
                }
            }
            swap(1)
        }
    }

    /** A number-key swap of menu slot [slot] with the selected hotbar slot (from the background thread, on purpose). */
    private fun swap(slot: Int) {
        val p = mc.player!!
        mc.gameMode!!.handleContainerInput(p.containerMenu.containerId, slot, p.inventory.selectedSlot, ContainerInput.SWAP, p)
    }
}

/** Registered, but its body is empty. */
object NeofetchCommand : ClientCommand("neofetch") {
    override fun build(builder: Builder) {
        builder.executes { 1 }
    }
}


/** Copies current position and corresponding dimension coordinates to clipboard. */
object CoordsCommand : ClientCommand("coords") {
    override fun build(builder: Builder) {
        builder.executes {
            val p = mc.player
            if (p == null) {
                message(ChatFormatting.RED.toString() + "Not in a world!")
                return@executes 1
            }
            val x = p.blockX
            val y = p.blockY
            val z = p.blockZ
            val dim = p.level().dimension()
            val isNether = dim == net.minecraft.world.level.Level.NETHER

            val (otherX, otherZ, otherDimName) = if (isNether) {
                Triple(x * 8, z * 8, "Overworld")
            } else {
                Triple(x / 8, z / 8, "Nether")
            }

            val clip = "$x $y $z"
            mc.keyboardHandler.clipboard = clip

            message(
                ChatFormatting.GREEN.toString() + "Coords: " +
                ChatFormatting.WHITE + "$x $y $z " +
                ChatFormatting.GRAY + "[$otherDimName: $otherX $y $otherZ] " +
                ChatFormatting.YELLOW + "(copied to clipboard)"
            )
            1
        }
    }
}

/** Client update status and manual check command. */
object UpdateCommand : ClientCommand("update") {
    override fun build(builder: Builder) {
        builder.then(literal("check").executes {
            message(ChatFormatting.GRAY.toString() + "Checking for Horizon updates...")
            dev.horizon.client.updater.AutoUpdater.check(manual = true)
            1
        })
        builder.then(literal("toggle").executes {
            val next = !dev.horizon.client.config.Storage.shared.autoUpdate
            dev.horizon.client.config.Storage.shared.autoUpdate = next
            dev.horizon.client.config.Storage.saveShared()
            message(ChatFormatting.GRAY.toString() + "Auto-update is now " + (if (next) ChatFormatting.GREEN.toString() + "ENABLED" else ChatFormatting.RED.toString() + "DISABLED"))
            1
        })
        builder.executes {
            message(
                ChatFormatting.GREEN.toString() + "Horizon 2.0 " +
                ChatFormatting.GRAY + "(v" + dev.horizon.client.updater.AutoUpdater.currentVersion + ")\n" +
                ChatFormatting.GRAY + "> Status: " + ChatFormatting.WHITE + (dev.horizon.client.updater.AutoUpdater.status.ifBlank { "Up to date" }) + "\n" +
                ChatFormatting.DARK_GRAY + "Use '^update check' to check now, or '^update toggle' to switch auto-update."
            )
            1
        }
    }
}
