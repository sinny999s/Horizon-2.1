package dev.horizon.client.hud

import dev.horizon.client.config.HolderEntry
import dev.horizon.client.config.NamedConfig
import dev.horizon.client.config.SettingsConfigs
import dev.horizon.client.config.SettingsHolder
import dev.horizon.client.config.Storage
import dev.horizon.client.config.WidgetsFile

/** HUD widget configs (`horizon/widgets/<name>.hzn`); the current one is in shared.hzn. */
object WidgetConfig : SettingsConfigs<WidgetsFile>("widgets", WidgetsFile.serializer(), "Widgets are not loaded from file for some reasons.") {
    override fun holders(): List<SettingsHolder> = Hud.widgets
    override fun wrap(id: String, entries: List<HolderEntry>) = WidgetsFile(id, entries)
    override fun unwrap(file: WidgetsFile) = NamedConfig(file.id, file.widgets)
    override var currentName: String
        get() = Storage.shared.widgetsConfig
        set(v) {
            Storage.shared.widgetsConfig = v
        }
}
