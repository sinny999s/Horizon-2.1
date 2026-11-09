package dev.horizon.client.module.protect

import dev.horizon.client.combat.Explosions
import dev.horizon.client.combat.Inventory
import dev.horizon.client.combat.MsTimer
import dev.horizon.client.combat.Packets
import dev.horizon.client.combat.PlayerState
import dev.horizon.client.combat.moduleMessage
import dev.horizon.client.config.Friends
import dev.horizon.client.event.ContainerClickEvent
import dev.horizon.client.event.HotbarRenderEvent
import dev.horizon.client.event.PostSyncEvent
import dev.horizon.client.event.PostTasksEvent
import dev.horizon.client.event.ReceivePacketEvent
import dev.horizon.client.event.Render2DEvent
import dev.horizon.client.event.SelectSlotEvent
import dev.horizon.client.gui.Keys
import dev.horizon.client.hud.ServerInfo
import dev.horizon.client.mixin.MinecraftAccessor
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import dev.horizon.client.module.equip.ReFiller
import dev.horizon.client.module.misc.Notifier
import dev.horizon.client.module.player.NoInteract
import dev.horizon.client.module.rage.ElytraTarget
import dev.horizon.client.module.world.FreeCam
import dev.horizon.client.setting.Bind
import dev.horizon.client.setting.GroupMode
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.resources.Identifier
import net.minecraft.tags.ItemTags
import net.minecraft.world.InteractionHand
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.boss.enderdragon.EndCrystal
import net.minecraft.world.entity.item.PrimedTnt
import net.minecraft.world.entity.monster.Creeper
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball
import net.minecraft.world.entity.vehicle.minecart.MinecartTNT
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.ShieldItem
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.BlockHitResult
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Once a frame picks what the off-hand should hold (a forced item, a totem when health is low or something dangerous is
 * near, a gapple for right-click eating, or the bind-swap item) and swaps it in.
 */
class Offhand : Module("offhand", Categories.PROTECT, listOf("auto-totem", "autototem")) {
    enum class ForceItem { OFF, CRYSTAL, GAPPLE }
    enum class Swap { GAPPLE_SHIELD, BALL_SHIELD, GAPPLE_BALL, BALL_TOTEM, TOTEM_TOTEM, BALL_BALL, CUSTOM }
    enum class RightClickGapple { OFF, ALWAYS, ONLY_SAFE }
    enum class GapplePriority { NORMAL, ENCHANTED }

    val delayBetweenSwaps = int("delay-between-swaps", 0, 0..10)
    val totemHp = float("totem-hp", 16.0f, 0.0f..36.0f)
    val totemElytraHp = float("totem-elytra-hp", 16.0f, 0.0f..36.0f)
    val pre1165Swap = bool("pre-1.16.5-swap", false)
    val forceItem = enum("force-item", ForceItem.OFF)
    val bindSwap = bool("bind-swap", false) { forceItem.value == ForceItem.OFF }
    val swapButton = bind("swap-button", Bind()) { forceItem.value == ForceItem.OFF && bindSwap.value }
    val swap = enum("swap", Swap.GAPPLE_SHIELD) { forceItem.value == ForceItem.OFF && bindSwap.value }
    val swapItems = group("swap-items") { bindSwap.value && swap.value == Swap.CUSTOM && forceItem.value == ForceItem.OFF }
    val notationSetting = notation("notation", "Hold the bind and click items in inv to add them. Hold the bind and use the hotbar to swap items") { forceItem.value == ForceItem.OFF && bindSwap.value && swap.value == Swap.CUSTOM && swapItems.value.opened }
    val swap1 = string("swap1", "") { forceItem.value == ForceItem.OFF && bindSwap.value && swap.value == Swap.CUSTOM && swapItems.value.opened }
    val swap2 = string("swap2", "") { forceItem.value == ForceItem.OFF && bindSwap.value && swap.value == Swap.CUSTOM && swapItems.value.opened }
    val swap3 = string("swap3", "") { forceItem.value == ForceItem.OFF && bindSwap.value && swap.value == Swap.CUSTOM && swapItems.value.opened }
    val swap4 = string("swap4", "") { forceItem.value == ForceItem.OFF && bindSwap.value && swap.value == Swap.CUSTOM && swapItems.value.opened }
    val swap5 = string("swap5", "") { forceItem.value == ForceItem.OFF && bindSwap.value && swap.value == Swap.CUSTOM && swapItems.value.opened }
    val swap6 = string("swap6", "") { forceItem.value == ForceItem.OFF && bindSwap.value && swap.value == Swap.CUSTOM && swapItems.value.opened }
    val swap7 = string("swap7", "") { forceItem.value == ForceItem.OFF && bindSwap.value && swap.value == Swap.CUSTOM && swapItems.value.opened }
    val swap8 = string("swap8", "") { forceItem.value == ForceItem.OFF && bindSwap.value && swap.value == Swap.CUSTOM && swapItems.value.opened }
    val swap9 = string("swap9", "") { forceItem.value == ForceItem.OFF && bindSwap.value && swap.value == Swap.CUSTOM && swapItems.value.opened }
    val hotBarSwap = bool("hot-bar-swap", false)
    val resetAttackCooldown = bool("reset-attack-cooldown", false) { hotBarSwap.value }
    val saveEnchanted = bool("save-enchanted", false)
    val rightClickGapple = enum("right-click-gapple", RightClickGapple.OFF)
    val gapplePriority = enum("gapple-priority", GapplePriority.ENCHANTED) { rightClickGapple.value != RightClickGapple.OFF }
    val crappleSpoof = bool("crapple-spoof", true) { forceItem.value == ForceItem.GAPPLE }
    val deathVerbose = bool("death-verbose", true)
    val containers = bool("containers", true)
    val safetyFactors = group("safety-factors", GroupMode.BOOLS)
    val onCrystalInHand = bool("on-crystal-in-hand", false, parent = safetyFactors.value)
    val onFalling = bool("on-falling", true, parent = safetyFactors.value)
    val onMace = bool("on-mace", true, parent = safetyFactors.value)
    val onExplosiveEntities = bool("on-explosive-entities", true, parent = safetyFactors.value)
    val onAnchors = bool("on-anchors", true, parent = safetyFactors.value)

    /** Ticks until the next swap may happen: set to `delay-between-swaps` on a swap, minus one each PostSync (no floor). */
    @Volatile
    var swapCooldown = 0

    /** The stack a totem replaced, put back once nothing is wanted. */
    private var saved: ItemStack? = null
    private var lastHp = 0f
    private var damage = 0f
    private var prevDamage = 0f

    /** Meant as the last pop's time; never updated (on purpose), so the death message always says OK. */
    private val lastPop = 1_000_000L

    /** The custom row's selection, counted from the left, and the next swapN to write. */
    private var customIndex = 0
    private var customWritePtr = 0

    /** True only while a hot-bar swap switches slots itself. */
    private val busy = AtomicBoolean(false)
    private val bindRepeat = MsTimer()
    private var hiddenOffhand: ItemStack = ItemStack.EMPTY

    /** What the off-hand should hold. */
    private data class Wish(val item: Item, val another: Boolean = false, val enchanted: Boolean = false, val name: String = "")

    private val swapNames get() = listOf(swap1, swap2, swap3, swap4, swap5, swap6, swap7, swap8, swap9)

    init {
        on<PostTasksEvent> {
            val p = mc.player ?: return@on
            if (mc.level == null) return@on
            val hp = p.health + p.absorptionAmount
            if (hp < lastHp) {
                prevDamage = damage
                damage = lastHp - hp
            }
            lastHp = hp
            swap(decide())
        }
        on<PostSyncEvent> {
            val p = mc.player
            if (p != null && rcGapActive() && !p.isUsingItem) (mc as MinecraftAccessor).horizon_startUseItem()
            swapCooldown--
        }
        on<ReceivePacketEvent> { e ->
            val pk = e.packet as? ClientboundEntityEventPacket ?: return@on
            val p = mc.player ?: return@on
            val level = mc.level ?: return@on
            if (!deathVerbose.value) return@on
            if (pk.eventId.toInt() != 3 && pk.eventId.toInt() != 60) return@on
            if (pk.getEntity(level) === p) deathAnalysis()
        }
        on<SelectSlotEvent> { e -> selectCustom(e) }
        on<ContainerClickEvent> { e -> editCustom(e) }
        on<HotbarRenderEvent> { e ->
            val p = mc.player ?: return@on
            if (mc.cameraEntity == null || swap.value != Swap.CUSTOM || !bindSwap.value) return@on
            if (e.pre) {
                hiddenOffhand = p.offhandItem
                p.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY)
            } else {
                p.setItemInHand(InteractionHand.OFF_HAND, hiddenOffhand)
            }
        }
        on<Render2DEvent> { e -> drawCustomRow(e) }
    }

    private fun hp(): Float = mc.player?.let { it.health + it.absorptionAmount } ?: 0f

    /** `totem-elytra-hp` after more than 15 ticks gliding, else `totem-hp`. */
    private fun threshold(): Float {
        val p = mc.player ?: return totemHp.value
        return if (p.isFallFlying && PlayerState.elytraTicks > 15) totemElytraHp.value else totemHp.value
    }

    private fun held(b: Bind) = if (b.mouse) b.key in Keys.heldMouse else Keys.down(b.key)

    private fun menuToIndex(slot: Int) = if (slot >= 36) slot - 36 else slot

    /** The inventory index whose stack goes into the off-hand now, or −1. */
    private fun decide(): Int {
        val p = mc.player ?: return -1
        if (mc.level == null) return -1
        val egap = Inventory.find(Items.ENCHANTED_GOLDEN_APPLE)
        val gap = Inventory.find(Items.GOLDEN_APPLE)
        val totem = Inventory.find(Items.TOTEM_OF_UNDYING)
        val plainTotem = Inventory.find { it.`is`(Items.TOTEM_OF_UNDYING) && !it.isEnchanted }
        val off = p.offhandItem
        val offItem = off.item
        val offGap = offItem == Items.GOLDEN_APPLE
        val offEgap = offItem == Items.ENCHANTED_GOLDEN_APPLE
        var want: Wish? = when (forceItem.value) {
            ForceItem.OFF -> bindSwapWish(offItem, offGap)
            ForceItem.CRYSTAL -> Wish(Items.END_CRYSTAL)
            ForceItem.GAPPLE -> {
                val absorption = p.getEffect(MobEffects.ABSORPTION)
                val preferPlain = !crappleSpoof.value || (absorption != null && absorption.amplifier > 2)
                if (preferPlain) {
                    if (gap != null || offGap) Wish(Items.GOLDEN_APPLE)
                    else if (egap != null || offEgap) Wish(Items.ENCHANTED_GOLDEN_APPLE) else null
                } else {
                    if (egap != null || offEgap) Wish(Items.ENCHANTED_GOLDEN_APPLE)
                    else if (gap != null || offGap) Wish(Items.GOLDEN_APPLE) else null
                }
            }
        }
        if (hp() <= threshold() && (totem != null || offItem == Items.TOTEM_OF_UNDYING)) want = Wish(Items.TOTEM_OF_UNDYING)
        want = safety(want)
        if (rcGapActive()) {
            val mode = rightClickGapple.value
            if (mode == RightClickGapple.ALWAYS || (mode == RightClickGapple.ONLY_SAFE && hp() > threshold())) {
                if (gapplePriority.value == GapplePriority.NORMAL) {
                    if (gap != null || offGap) want = Wish(Items.GOLDEN_APPLE)
                    else if (egap != null || offEgap) want = Wish(Items.ENCHANTED_GOLDEN_APPLE)
                } else {
                    if (egap != null || offEgap) want = Wish(Items.ENCHANTED_GOLDEN_APPLE)
                    else if (gap != null || offGap) want = Wish(Items.GOLDEN_APPLE)
                }
            }
        }
        if (want != null && want.item == Items.TOTEM_OF_UNDYING && offItem != Items.AIR) {
            val keep = offItem != Items.TOTEM_OF_UNDYING || (off.isEnchanted && saveEnchanted.value)
            if (keep && !(offItem == Items.FIREWORK_ROCKET && Modules.get<ElytraTarget>().enabled)) saved = off
        }
        if (forceItem.value == ForceItem.OFF && bindSwap.value) {
            val b = swapButton.value
            if (b.key != -1 && held(b)) saved = null
        }
        if (want == null || want.item == Items.AIR) {
            val s = saved ?: return -1
            saved = null
            val a = Inventory.find { it === s }?.slot ?: -1
            val b = Inventory.find { it.item == s.item }?.slot ?: -1
            return menuToIndex(if (a == -1) b else a)
        }
        if (want.name == "" && want.item == Items.TOTEM_OF_UNDYING && saveEnchanted.value && offItem == Items.TOTEM_OF_UNDYING &&
            off.isEnchanted && plainTotem != null) return menuToIndex(plainTotem.slot)
        for (j in 44 downTo 9) {
            if (want.name == "") {
                if (offItem == want.item && !want.another) return -1
            } else if (off.hoverName.string == want.name) {
                return -1
            }
            val idx = menuToIndex(j)
            val st = p.inventory.getItem(idx)
            if (st.item == want.item && (want.name == "" || want.name == st.hoverName.string) && (!want.enchanted || st.isEnchanted)) return idx
        }
        return -1
    }

    /** The bind-swap wish (force-item OFF). */
    private fun bindSwapWish(offItem: Item, offGap: Boolean): Wish? {
        val p = mc.player ?: return null
        if (!bindSwap.value) return null
        val key = swapButton.value
        if (key.key == -1) return null
        if (swap.value == Swap.CUSTOM) {
            val list = customList()
            if (list.isEmpty()) return null
            val k = (list.size - customIndex - 1) % list.size
            if (k !in list.indices) return null
            val s = list[k]
            return Wish(s.item, another = true, enchanted = false, name = s.hoverName.string)
        }
        if (!held(key)) return null
        if (!bindRepeat.passed(250)) return null
        bindRepeat.reset()
        val empty = p.offhandItem.isEmpty
        return when (swap.value) {
            Swap.BALL_SHIELD -> if (!empty && offItem != Items.SHIELD) Wish(Items.SHIELD) else Wish(Items.PLAYER_HEAD)
            Swap.GAPPLE_BALL -> if (!empty && !offGap) Wish(Items.GOLDEN_APPLE) else Wish(Items.PLAYER_HEAD)
            Swap.GAPPLE_SHIELD -> if (!empty && offItem != Items.SHIELD) Wish(Items.SHIELD) else Wish(Items.GOLDEN_APPLE)
            Swap.BALL_TOTEM -> if (!empty && offItem != Items.TOTEM_OF_UNDYING) Wish(Items.TOTEM_OF_UNDYING) else Wish(Items.PLAYER_HEAD)
            Swap.TOTEM_TOTEM -> Wish(Items.TOTEM_OF_UNDYING, another = true, enchanted = true)
            else -> Wish(Items.PLAYER_HEAD, another = true)
        }
    }

    /** The safety factors, each of which can only ask for a totem. */
    private fun safety(want: Wish?): Wish? {
        val p = mc.player ?: return want
        val level = mc.level ?: return want
        val totem = Wish(Items.TOTEM_OF_UNDYING)
        var r = want
        if (onFalling.value && hp() - ((PlayerState.fallDistance - 3.0) / 2.0 + 3.5) < 0.5 && !p.isFallFlying) {
            if (!Modules.get<FreeCam>().enabled) r = totem
        }
        if (onCrystalInHand.value) {
            for (o in PlayerState.players) {
                if (Friends.isFriend(o.gameProfile.name) || o === p) continue
                if (PlayerState.serverPos().distanceToSqr(o.position()) < 36.0 && (dangerous(o.mainHandItem) || dangerous(o.offhandItem))) r = totem
            }
        }
        if (onExplosiveEntities.value) {
            for (e in ArrayList(level.entitiesForRendering().toList())) {
                if (e is LargeFireball && e.position().distanceTo(p.position()) <= 12.0) {
                    r = totem
                    break
                }
                if (e.isAlive && PlayerState.serverPos().distanceToSqr(e.position()) <= 144.0 &&
                    (e is EndCrystal || e is PrimedTnt || e is MinecartTNT || e is Creeper)) {
                    if (hp() - Explosions.damage(e.position(), p, 0, false, emptyList(), emptyList()) < threshold()) {
                        r = totem
                        break
                    }
                }
            }
        }
        if (onAnchors.value) {
            // Around the world origin, not the player (on purpose); the break only leaves the z loop.
            for (x in -6..6) for (y in -6..6) for (z in -6..6) {
                if (level.getBlockState(BlockPos(x, y, z)).block == Blocks.RESPAWN_ANCHOR) {
                    r = totem
                    break
                }
            }
        }
        if (onMace.value) {
            val notifier = Modules.get<Notifier>()
            val m = level.players().firstOrNull { o ->
                p.position().distanceTo(o.position()) < 12.0 && (o.deltaMovement.y < -0.1f || o.yo - o.y > 0.5) &&
                    (o.mainHandItem.`is`(Items.MACE) || o.offhandItem.`is`(Items.MACE)) && o !== p && !Friends.isFriend(o.gameProfile.name)
            }
            if (m != null) {
                notifier.airAlarm = true
                notifier.airAlarmTimer.reset()
                r = totem
            } else {
                notifier.airAlarm = false
            }
        }
        return r
    }

    private fun dangerous(s: ItemStack) = s.`is`(Items.OBSIDIAN) || s.`is`(Items.END_CRYSTAL)

    private fun swap(idx: Int) {
        val p = mc.player ?: return
        if (mc.level == null) return
        val screen = mc.gui.screen()
        val inContainer = screen is ShulkerBoxScreen || screen is ContainerScreen
        if (!containers.value && inContainer) return
        if (idx == -1 || swapCooldown > 0) return
        swapCooldown = delayBetweenSwaps.value
        if (idx < 9 && hotBarSwap.value) {
            busy.set(true)
            Inventory.saveAndSelect(idx)
            Packets.send(ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN))
            Inventory.restore()
            busy.set(false)
            val refiller = Modules.get<ReFiller>()
            refiller.window = 3
            refiller.slot = idx
            if (resetAttackCooldown.value) p.resetOnlyAttackStrengthTicker()
            return
        }
        val m = if (idx < 9) idx + 36 else idx
        if (!p.containerMenu.carried.isEmpty) mc.gameMode?.handleContainerInput(p.containerMenu.containerId, -999, 0, ContainerInput.PICKUP, p)
        if (pre1165Swap.value) {
            // Through a spare hotbar slot, never button 40.
            val tmp = p.inventory.selectedSlot % 8 + 1
            Inventory.swap(m, tmp, false)
            Inventory.swap(45, tmp, false)
            Inventory.swap(m, tmp, !inContainer)
        } else {
            Inventory.swap(m, 40, !inContainer)
        }
    }

    /** Holding use with a sword (not at a container) eats an off-hand gapple. */
    private fun rcGapActive(): Boolean {
        val p = mc.player ?: return false
        val level = mc.level ?: return false
        val hit = mc.hitResult
        val target = if (hit is BlockHitResult) level.getBlockState(hit.blockPos).block else Blocks.DIRT
        val ok = rightClickGapple.value != RightClickGapple.OFF && p.mainHandItem.`is`(ItemTags.SWORDS) && mc.options.keyUse.isDown &&
            p.offhandItem.item !is ShieldItem
        return ok && ((target !is BaseEntityBlock && target != Blocks.CRAFTING_TABLE) || Modules.get<NoInteract>().enabled)
    }

    /** The stacks swap1..9 name (off-hand first, else the first inventory match). */
    private fun customList(): List<ItemStack> {
        val p = mc.player ?: return emptyList()
        val out = ArrayList<ItemStack>()
        for (s in swapNames) {
            val name = s.value
            if (name.isEmpty() || name == "Air") continue
            if (p.offhandItem.hoverName.string == name) {
                out += p.offhandItem
                continue
            }
            for (i in 0 until 36) {
                val st = p.inventory.getItem(i)
                if (st.hoverName.string == name) {
                    out += st
                    break
                }
            }
        }
        return out
    }

    /** The scroll wheel and number keys pick from the custom row while the bind is held. */
    private fun selectCustom(e: SelectSlotEvent) {
        val p = mc.player ?: return
        if (mc.level == null || busy.get()) return
        val sel = p.inventory.selectedSlot
        if (swap.value != Swap.CUSTOM || !held(swapButton.value)) return
        val list = customList()
        if (list.isEmpty()) return
        val hotbarKey = (0..8).any { mc.options.keyHotbarSlots[it].consumeClick() || mc.options.keyHotbarSlots[it].isDown }
        if (hotbarKey) {
            customIndex = e.slot % list.size
        } else {
            val d = if (e.slot == (sel + 1) % 9) 1 else if (e.slot == (sel + 8) % 9) -1 else 0
            customIndex = if (d != 0) Math.floorMod(customIndex + d, list.size) else Math.floorMod(e.slot, list.size)
        }
        e.cancel()
    }

    /** Clicking items with the bind held adds them to (or removes them from) the row. */
    private fun editCustom(e: ContainerClickEvent) {
        val p = mc.player ?: return
        if (swap.value != Swap.CUSTOM) return
        if (!held(swapButton.value) || mc.gui.screen() == null) return
        if (e.input != ContainerInput.PICKUP) return
        val id = e.slotId
        if (id !in 0 until 45) return
        val name = p.inventory.getItem(if (id > 35) id - 36 else id).hoverName.string
        if (name == "Air") return
        val names = swapNames
        val exists = names.any { it.value == name }
        // swap9 is never cleared and swap8 is checked twice (on purpose).
        for (i in 0..7) if (names[i].value == name) names[i].value = ""
        if (names[7].value == name) names[7].value = ""
        if (exists) {
            e.cancel()
            return
        }
        names[customWritePtr].value = name
        customWritePtr = (customWritePtr + 1) % 9
        e.cancel()
    }

    /** The custom row left of the hotbar, element 0 where vanilla's off-hand slot goes. */
    private fun drawCustomRow(e: Render2DEvent) {
        if (!bindSwap.value || swap.value != Swap.CUSTOM) return
        val g = e.graphics
        val w = mc.window.guiScaledWidth
        val h = mc.window.guiScaledHeight
        val y = h - 23
        val itemY = h - 16 - 3
        val base = w / 2 - 91 - 29
        val list = customList()
        if (list.isEmpty()) return
        for ((k, s) in list.withIndex()) {
            val x = base - k * 21
            g.blitSprite(RenderPipelines.GUI_TEXTURED, OFFHAND_LEFT, x, y, 29, 24)
            if (!s.isEmpty) g.item(s, x + 3, itemY)
        }
        if (customIndex == list.size) customIndex -= 1
        val sx = base - (list.size - customIndex - 1) * 21
        g.blitSprite(RenderPipelines.GUI_TEXTURED, SELECTION, sx - 1, y, 24, 23)
    }

    /** The "[Death Analysis]" chat block. */
    private fun deathAnalysis() {
        val p = mc.player ?: return
        val ping = PlayerState.ping
        val tps = ServerInfo.tps()
        val avgTps = ServerInfo.averageTps()
        val sincePop = System.currentTimeMillis() - lastPop
        var count = 0
        for (k in 0 until minOf(45, p.inventory.containerSize)) {
            val st = p.inventory.getItem(k)
            if (st.`is`(Items.TOTEM_OF_UNDYING)) count += st.count
        }
        val mainT = p.mainHandItem.`is`(Items.TOTEM_OF_UNDYING)
        val offT = p.offhandItem.`is`(Items.TOTEM_OF_UNDYING)
        var locs = ""
        if (mainT) locs += "mainhand "
        if (offT) locs += "offhand "
        if (mainT) locs += "cursor "
        val spike = abs(avgTps - tps)
        val lag = ping > 400 || tps < 5.0f || spike > 5.0f || sincePop < 450
        val failed = count > 0 || mainT || offT
        val double = failed && damage - prevDamage > 0 && sincePop < 500
        val g = "§a"
        val r = "§c"
        val y = "§7"
        val conclusion = when {
            !failed -> g + "No totems + skill issue"
            lag || threshold() >= 36.0f -> g + "Unstable internet connection / Server lag"
            else -> r + "OffHand does not have time to take the totem increase minimum health"
        }
        val text = "[" + r + "Death Analysis" + y + "]\n failed =" + (if (failed) r else g) + " " + failed +
            (if (failed) " ($count totems left)" else "") +
            y + (if (failed) "\n totem locations = (" + (if (locs.isEmpty()) "inventory" else "") + locs.trim() + ")" else "") +
            y + "\n damage = " + damage +
            y + "\n prevDamage = " + prevDamage +
            y + "\n double pop = " + double + " " +
            y + "\n ping = " + ping + " | " + (if (ping > 400) r + "BAD" else g + "OK") +
            y + "\n tps = " + tps + " | " + (if (tps < 5.0f) r + "BAD" else g + "OK") +
            y + "\n lastPop = " + sincePop + " | " + (if (sincePop < 450) r + "BAD" else g + "OK") +
            y + "\n tpsSpike = " + spike + " | " + (if (spike > 5.0f) r + "BAD" else g + "OK") +
            y + "\n\n Conclusion: " + conclusion
        moduleMessage("Offhand", text)
    }

    private companion object {
        val SELECTION: Identifier = Identifier.withDefaultNamespace("hud/hotbar_selection")
        val OFFHAND_LEFT: Identifier = Identifier.withDefaultNamespace("hud/hotbar_offhand_left")
    }
}
