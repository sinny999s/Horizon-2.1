package dev.horizon.client.event

import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.world.entity.player.PlayerSkin

/**
 * The skin (body, cape, elytra) [player] is drawn with, asked by `AbstractClientPlayer.getSkin`. A
 * handler may swap [skin]; the Cape module gives you a cape. Asked for every player every frame, so it's one
 * shared instance.
 */
object SkinEvent : Event() {
    var player: AbstractClientPlayer? = null
    var skin: PlayerSkin? = null

    fun ask(p: AbstractClientPlayer, s: PlayerSkin): PlayerSkin {
        cancelled = false
        player = p
        skin = s
        EventBus.post(this)
        return skin ?: s
    }
}
