package dev.horizon.client.module.equip

import dev.horizon.client.combat.EquipUtil
import dev.horizon.client.combat.MsTimer
import dev.horizon.client.combat.PlayerState
import dev.horizon.client.combat.Rng
import dev.horizon.client.combat.Rotation
import dev.horizon.client.combat.RotationManager
import dev.horizon.client.event.PlayerUpdateEvent
import dev.horizon.client.event.PostTasksEvent
import dev.horizon.client.hud.ServerInfo
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import net.minecraft.core.Direction
import net.minecraft.world.InteractionHand
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.SwingAnimation
import net.minecraft.world.level.block.entity.EnderChestBlockEntity
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import kotlin.random.Random

/**
 * Shift-clicks the wanted stacks out of an open chest, one per [delay] (FAST: per
 * frame with a ms delay; ON_TICK: per tick, the tick setting read as ms), optionally closing it once the first 27
 * slots are empty, and optionally opens an ender chest nearby after 3 s without stealing.
 */
class ChestStealer : Module("chest-stealer", Categories.EQUIP) {
    enum class Action { ON_TICK, FAST }
    enum class Sort { NONE, WHITE_LIST, BLACK_LIST }

    val actionSetting = enum("action", Action.FAST)
    val sort = enum("sort", Sort.NONE)
    val itemsSetting = items("items") { sort.value != Sort.NONE }
    val minDelayMs = int("min-delay-ms", 80, 0..500) { actionSetting.value == Action.FAST }
    val maxDelayMs = int("max-delay-ms", 80, 0..500) { actionSetting.value == Action.FAST }
    val minDelayTicks = int("min-delay-ticks", 0, 0..30) { actionSetting.value == Action.ON_TICK }
    val maxDelayTicks = int("max-delay-ticks", 1, 0..30) { actionSetting.value == Action.ON_TICK }
    val randomizeSlots = bool("randomize-slots", false)
    val closeIfEmpty = bool("close-if-empty", false)
    val autoOpen = bool("auto-open", false)
    val rotate = bool("rotate", false) { autoOpen.value }

    /** Since the last steal click (auto-open waits 3 s on it). */
    private val sinceSteal = MsTimer()
    private val clickTimer = MsTimer()
    /** Since the chest was opened (close-if-empty waits ping + 100 ms on it). */
    private val sinceOpen = MsTimer()
    private var tickDelay = 0
    private var msDelay = 0

    init {
        on<PostTasksEvent> {
            if (actionSetting.value == Action.FAST) steal(false)
        }
        on<PlayerUpdateEvent> {
            val p = mc.player ?: return@on
            if (autoOpen.value && mc.gui.screen() == null && sinceSteal.passed(3000)) {
                for (be in ServerInfo.blockEntities) {
                    if (be !is EnderChestBlockEntity) continue
                    if (p.distanceToSqr(Vec3.atCenterOf(be.blockPos)) > 39.0) continue
                    val r = -0.4 + (0.4 - -0.4) * Random.nextFloat()
                    val hit = Vec3.atCenterOf(be.blockPos).add(r, 0.375, -r)
                    if (rotate.value) RotationManager.rotate(Rotation.lookAt(hit))
                    mc.gameMode?.useItemOn(p, InteractionHand.MAIN_HAND, BlockHitResult(hit, Direction.UP, be.blockPos, false))
                    p.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false)
                    break
                }
            }
            if (actionSetting.value == Action.ON_TICK) steal(true)
        }
    }

    private fun steal(onTick: Boolean) {
        val p = mc.player
        if (p == null || mc.level == null || p.containerMenu !is ChestMenu) {
            sinceOpen.reset()
            return
        }
        val menu = p.containerMenu as ChestMenu
        val range = 0 until menu.container.containerSize
        for (i in if (randomizeSlots.value) range.shuffled() else range) {
            val slot = menu.getSlot(i)
            // Reads the title of a screen it assumes is open.
            val title = mc.gui.screen()?.title?.string ?: return
            if (!slot.hasItem() || !wanted(slot.item)) continue
            if (!clickTimer.passedAndReset((if (onTick) tickDelay else msDelay).toLong())) continue
            if (SKIP_TITLES.any { title.contains(it) }) continue
            EquipUtil.click(i, 0, ContainerInput.QUICK_MOVE)
            sinceSteal.reset()
            msDelay = Rng.int(minDelayMs.value, maxDelayMs.value)
            tickDelay = Rng.int(minDelayTicks.value, maxDelayTicks.value)
        }
        if (empty(menu) && closeIfEmpty.value && sinceOpen.passed((PlayerState.ping + 100).toLong())) p.closeContainer()
    }

    private fun wanted(s: ItemStack) = when (sort.value) {
        Sort.NONE -> true
        Sort.WHITE_LIST -> itemsSetting.value.contains(s.item)
        Sort.BLACK_LIST -> !itemsSetting.value.contains(s.item)
    }

    /** Only the first 27 slots are looked at (it checks for a 90-slot container to look at 54). */
    private fun empty(menu: ChestMenu): Boolean {
        for (i in 0 until if (menu.container.containerSize == 90) 54 else 27) if (menu.getSlot(i).hasItem()) return false
        return true
    }

    private companion object {
        /**
         * A title filter for server auction screens: "Аукцион", "покупки" and "ВЫБОР", mis-encoded (UTF-8 read as
         * CP1251), so they never match a real title (on purpose).
         */
        val SKIP_TITLES = listOf(
            "РђСѓРєС†РёРѕРЅ",
            "РїРѕРєСѓРїРєРё",
            "Р’Р«Р‘РћР ",
        )
    }
}
