package dev.horizon.client.module.esp

import dev.horizon.client.combat.Esp2
import dev.horizon.client.combat.PlaceBoxes
import dev.horizon.client.event.Render3DEvent
import dev.horizon.client.hud.ServerInfo
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.render.World3D
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.entity.vehicle.minecart.MinecartChest
import net.minecraft.world.entity.vehicle.minecart.MinecartFurnace
import net.minecraft.world.entity.vehicle.minecart.MinecartHopper
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity
import net.minecraft.world.level.block.entity.BarrelBlockEntity
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.level.block.entity.DispenserBlockEntity
import net.minecraft.world.level.block.entity.EnderChestBlockEntity
import net.minecraft.world.level.block.entity.HopperBlockEntity
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity
import net.minecraft.world.level.block.entity.TrappedChestBlockEntity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.awt.Color

/**
 * Containers as shader boxes (their shape; brewing stands and hoppers as a few boxes),
 * optional tracers. Its shader-mode drives every shader box in the client (World3D's composite reads it).
 */
class StorageEsp : Module("storage-esp", Categories.ESP) {
    enum class ShaderMode { DEFAULT, LINES, DOTS }

    val shaderMode = enum("shader-mode", ShaderMode.DEFAULT)
    val tracers = bool("tracers", false)
    val chests = bool("chests", true)
    val chest = color("chest", Color(0x64FF9B4B.toInt(), true)) { chests.value }
    val trappedChests = bool("trapped-chests", true)
    val trappedChest = color("trapped-chest", Color(0x64FFFF0C.toInt(), true)) { trappedChests.value }
    val enderChests = bool("ender-chests", true)
    val enderChest = color("ender-chest", Color(0x64FF0DFF.toInt(), true)) { enderChests.value }
    val furnaces = bool("furnaces", true)
    val furnace = color("furnace", Color(0x64808080.toInt(), true)) { furnaces.value }
    val dispencers = bool("dispencers", true)
    val dispencer = color("dispencer", Color(0x648C8CB2.toInt(), true)) { dispencers.value }
    val hoppers = bool("hoppers", true)
    val hopper = color("hopper", Color(0x6473739B.toInt(), true)) { hoppers.value }
    val shulkers = bool("shulkers", true)
    val shulker = color("shulker", Color(0x648032FF.toInt(), true)) { shulkers.value }
    val brewingStands = bool("brewing-stands", true)
    val brewingStand = color("brewing-stand", Color(0x64806432.toInt(), true)) { brewingStands.value }
    val barrels = bool("barrels", true)
    val barrel = color("barrel", Color(0x64FF9B4B.toInt(), true)) { barrels.value }
    val chestMinecarts = bool("chest-minecarts", true)
    val chestMinecart = color("chest-minecart", Color(0x64FFA54B.toInt(), true)) { chestMinecarts.value }
    val furnaceMinecarts = bool("furnace-minecarts", true)
    val furnaceMinecart = color("furnace-minecart", Color(0x64808080.toInt(), true)) { furnaceMinecarts.value }
    val hopperMinecarts = bool("hopper-minecarts", true)
    val hopperMinecart = color("hopper-minecart", Color(0x6473739B.toInt(), true)) { hopperMinecarts.value }
    val frames = bool("frames", true)
    val emptyFrame = color("empty-frame", Color(0x64731919.toInt(), true)) { frames.value }
    val itemFrame = color("item-frame", Color(0x64731919.toInt(), true)) { frames.value }

    /** Read by nothing (beds have no case in [colorOf]). */
    val beds = bool("beds", true)

    init {
        on<Render3DEvent> { render() }
    }

    /** Block entities first, then entities; anything that maps to plain white is skipped. */
    private fun render() {
        val level = mc.level ?: return
        for (be in ServerInfo.blockEntities) {
            val c = colorOf(be) ?: continue
            when (be) {
                is BrewingStandBlockEntity -> {
                    val box = AABB(be.blockPos)
                    World3D.shaderBox(box.contract(0.1, 0.9, 0.1).move(0.05, 0.0, 0.05), c)
                    World3D.shaderBox(box.contract(0.9, 0.12, 0.9).move(0.45, 0.0, 0.45), c)
                }
                is HopperBlockEntity -> {
                    val box = AABB(be.blockPos)
                    World3D.shaderBox(box.contract(0.0, 0.62, 0.0).move(0.0, 0.62, 0.0), c)
                    World3D.shaderBox(box.contract(0.5, 0.63, 0.5).move(0.25, 0.25, 0.25), c)
                    World3D.shaderBox(box.contract(0.76, 0.75, 0.75).move(0.38, 0.0, 0.38), c)
                }
                else -> World3D.shaderBox(be.blockState.getShape(level, be.blockPos).bounds().move(be.blockPos), c)
            }
            if (tracers.value) World3D.line(Esp2.tracerStart(), Vec3.atCenterOf(be.blockPos), PlaceBoxes.withAlpha(c, 1f), noCull = true)
        }
        for (e in level.entitiesForRendering()) {
            val c = colorOf(e) ?: continue
            World3D.shaderBox(e.boundingBox, c)
            if (tracers.value) World3D.line(Esp2.tracerStart(), e.boundingBox.center, PlaceBoxes.withAlpha(c, 1f), noCull = true)
        }
    }

    /** Null for "white" (not shown). A trapped chest never falls back to the chest colour. */
    private fun colorOf(be: BlockEntity): Int? {
        val c = when (be) {
            is TrappedChestBlockEntity -> if (trappedChests.value) trappedChest.value else null
            is ChestBlockEntity -> if (chests.value) chest.value else null
            is EnderChestBlockEntity -> if (enderChests.value) enderChest.value else null
            is AbstractFurnaceBlockEntity -> if (furnaces.value) furnace.value else null
            is DispenserBlockEntity -> if (dispencers.value) dispencer.value else null
            is HopperBlockEntity -> if (hoppers.value) hopper.value else null
            is ShulkerBoxBlockEntity -> if (shulkers.value) shulker.value else null
            is BrewingStandBlockEntity -> if (brewingStands.value) brewingStand.value else null
            is BarrelBlockEntity -> if (barrels.value) barrel.value else null
            else -> null
        }
        return c?.rgb?.takeIf { it != WHITE }
    }

    private fun colorOf(e: Entity): Int? {
        val c = when (e) {
            is MinecartChest -> if (chestMinecarts.value) chestMinecart.value else null
            is MinecartFurnace -> if (furnaceMinecarts.value) furnaceMinecart.value else null
            is MinecartHopper -> if (hopperMinecarts.value) hopperMinecart.value else null
            is ItemFrame -> if (frames.value) (if (e.item.isEmpty) emptyFrame.value else itemFrame.value) else null
            else -> null
        }
        return c?.rgb?.takeIf { it != WHITE }
    }

    private companion object {
        /** "Nothing" is marked with `Color.WHITE`, so a setting set to opaque white hides that kind too. */
        const val WHITE = -1
    }
}
