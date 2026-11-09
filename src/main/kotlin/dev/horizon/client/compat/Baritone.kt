package dev.horizon.client.compat

import dev.horizon.client.HorizonClient
import net.fabricmc.loader.api.FabricLoader
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Baritone, when it's installed next to the client: its commands, its elytra (nether flight planned by
 * nether-pathfinder) and its settings. Reached by reflection through its public API (`baritone.api`), so the client
 * neither needs nor bundles it. Without it, [present] is false and the rest does nothing.
 */
object Baritone {
    private val installed = FabricLoader.getInstance().isModLoaded("baritone")
    private var ready = false
    private var broken = false
    private var provider: Any? = null
    private var settings: Any? = null
    private lateinit var primary: Method
    private lateinit var commandManager: Method
    private lateinit var execute: Method
    private lateinit var elytraProcess: Method
    private lateinit var elytraLoaded: Method
    private lateinit var elytraPathTo: Method
    private lateinit var active: Method
    private lateinit var pathing: Method
    private lateinit var cancelEverything: Method
    private lateinit var goalXZ: Constructor<*>
    private lateinit var goalBlock: Constructor<*>

    /** Baritone is installed and its API answered. */
    val present: Boolean get() = installed && load()

    @Synchronized
    private fun load(): Boolean {
        if (ready || broken) return ready
        try {
            val api = Class.forName("baritone.api.BaritoneAPI")
            provider = api.getMethod("getProvider").invoke(null)
            settings = api.getMethod("getSettings").invoke(null)
            primary = Class.forName("baritone.api.IBaritoneProvider").getMethod("getPrimaryBaritone")
            val ib = Class.forName("baritone.api.IBaritone")
            commandManager = ib.getMethod("getCommandManager")
            execute = Class.forName("baritone.api.command.manager.ICommandManager").getMethod("execute", String::class.java)
            elytraProcess = ib.getMethod("getElytraProcess")
            val ep = Class.forName("baritone.api.process.IElytraProcess")
            elytraLoaded = ep.getMethod("isLoaded")
            elytraPathTo = ep.getMethod("pathTo", Class.forName("baritone.api.pathing.goals.Goal"))
            active = Class.forName("baritone.api.process.IBaritoneProcess").getMethod("isActive")
            pathing = ib.getMethod("getPathingBehavior")
            cancelEverything = Class.forName("baritone.api.behavior.IPathingBehavior").getMethod("cancelEverything")
            val int = Int::class.javaPrimitiveType
            goalXZ = Class.forName("baritone.api.pathing.goals.GoalXZ").getConstructor(int, int)
            goalBlock = Class.forName("baritone.api.pathing.goals.GoalBlock").getConstructor(int, int, int)
            ready = provider != null && settings != null
        } catch (_: ReflectiveOperationException) {
            broken = true
        } catch (_: RuntimeException) {
            broken = true
        } catch (_: LinkageError) {
            broken = true
        }
        return ready
    }

    /** Runs [f] when Baritone is there; null when it isn't, or when Baritone threw (logged as [what]). */
    private fun <T> call(what: String, f: () -> T): T? {
        if (!present) return null
        return try {
            f()
        } catch (e: InvocationTargetException) {
            HorizonClient.LOG.warn("Baritone: {} failed", what, e.targetException)
            null
        } catch (e: ReflectiveOperationException) {
            HorizonClient.LOG.warn("Baritone: {} failed", what, e)
            null
        } catch (e: RuntimeException) {
            HorizonClient.LOG.warn("Baritone: {} failed", what, e)
            null
        }
    }

    private fun baritone(): Any = primary.invoke(provider)

    private fun elytra(): Any = elytraProcess.invoke(baritone())

    /** Runs a Baritone command (no chat message), as "#[text]" would. False without Baritone, or when it failed. */
    fun command(text: String): Boolean = call("command $text") {
        execute.invoke(commandManager.invoke(baritone()), text) as? Boolean ?: true
    } ?: false

    /** Baritone's elytra can fly on this computer (nether-pathfinder's native library loaded). */
    val elytraSupported: Boolean get() = call("elytra check") { elytraLoaded.invoke(elytra()) as Boolean } ?: false

    /** Baritone's elytra is flying somewhere, or taking off or landing; false once it has landed or was stopped. */
    val elytraActive: Boolean get() = call("elytra state") { active.invoke(elytra()) as Boolean } ?: false

    /**
     * Has Baritone's elytra fly towards [x] [z], as "#goal x z" then "#elytra" would; it lands when it gets there. Starts
     * a new flight each time (planned from where you are). Only in the nether.
     */
    fun elytraTo(x: Int, z: Int): Boolean = call("elytra to $x $z") { elytraPathTo.invoke(elytra(), goalXZ.newInstance(x, z)); true } ?: false

    /** The same, to land near the open spot [x] [y] [z]. */
    fun elytraTo(x: Int, y: Int, z: Int): Boolean =
        call("elytra to $x $y $z") { elytraPathTo.invoke(elytra(), goalBlock.newInstance(x, y, z)); true } ?: false

    /** Stops everything Baritone is doing, as "#stop" would. */
    fun stop() {
        call("stop") { cancelEverything.invoke(pathing.invoke(baritone())) }
    }

    /** A Baritone setting's current value ([name] as in "#set"), or null. */
    fun setting(name: String): Any? = call("setting $name") {
        val s = settings!!.javaClass.getField(name).get(settings)
        s.javaClass.getField("value").get(s)
    }

    /** Sets a Baritone setting for this session (not saved to Baritone's settings.txt). */
    fun set(name: String, value: Any) {
        call("setting $name") {
            val s = settings!!.javaClass.getField(name).get(settings)
            s.javaClass.getField("value").set(s, value)
        }
    }
}
