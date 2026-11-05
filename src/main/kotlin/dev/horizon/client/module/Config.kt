package dev.horizon.client.module

import dev.horizon.client.config.HolderEntry
import dev.horizon.client.config.ModulesFile
import dev.horizon.client.config.NamedConfig
import dev.horizon.client.config.SettingsConfigs
import dev.horizon.client.config.SettingsHolder
import dev.horizon.client.config.Storage

/** Module configs (`horizon/modules/<name>.hzn`, the original's format); the current one is in shared.hzn. */
object Config : SettingsConfigs<ModulesFile>("modules", ModulesFile.serializer(), "Modules are not loaded from file for some reasons.") {
    override fun holders(): List<SettingsHolder> = Modules.all
    override fun wrap(id: String, entries: List<HolderEntry>) = ModulesFile(id, entries)
    override fun unwrap(file: ModulesFile) = NamedConfig(file.id, file.modules)
    override var currentName: String
        get() = Storage.shared.modulesConfig
        set(v) {
            Storage.shared.modulesConfig = v
        }

    /**
     * The original's `cb.n(previous)`, after [apply]: a module that is on now and wasn't on in [previous] runs its enable
     * hook; one that is off now and was on (or missing) in [previous] runs its disable hook. No sounds.
     */
    fun reconcile(previous: NamedConfig?) {
        dev.horizon.client.module.legit.GhostMode.configApplied()
        for (m in Modules.all) {
            val was = previous?.holders?.firstOrNull { it.id == m.id }?.settings?.firstOrNull { it.id == "enabled" }?.value?.toBoolean()
            m.unplug()
            if (m.enabled) {
                m.plug()
                if (m.enabled != was) m.onEnable()
            } else if (previous != null && m.enabled != was) {
                m.onDisable()
            }
        }
        dev.horizon.client.module.legit.GhostMode.enforce()
    }
}
