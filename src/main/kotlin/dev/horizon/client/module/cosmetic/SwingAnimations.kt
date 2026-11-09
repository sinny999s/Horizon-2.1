package dev.horizon.client.module.cosmetic

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import dev.horizon.client.event.EquipArmEvent
import dev.horizon.client.event.SwingArmEvent
import dev.horizon.client.event.SwingDurationEvent
import dev.horizon.client.module.Categories
import dev.horizon.client.module.Module
import dev.horizon.client.module.Modules
import dev.horizon.client.render.Easing
import net.minecraft.util.Mth
import net.minecraft.world.entity.HumanoidArm
import kotlin.math.sin

/**
 * Every swing lasts [speed] ticks (for every entity), and the right hand's
 * first-person swing is replaced by the [mode]'s own transform (View Model's offset applied around it).
 */
class SwingAnimations : Module("swing-animations", Categories.COSMETIC) {
    @Suppress("EnumEntryName")
    enum class Mode { Default, Bonk, Rubber, Akrien, Flip, Snap, Hook, Swipe, Tap, Inject, Slap }

    val mode = enum("mode", Mode.Default)
    val speed = int("speed", 12, 0..64)

    /** The right hand's last inverse arm height. */
    private var equip = 0f

    init {
        on<SwingDurationEvent> { e ->
            e.value = speed.value
            e.cancel()
        }
        on<EquipArmEvent> { e -> if (e.arm == HumanoidArm.RIGHT) equip = e.progress }
        on<SwingArmEvent> { e -> swing(e) }
    }

    private fun swing(e: SwingArmEvent) {
        if (e.arm != HumanoidArm.RIGHT) return
        val m = e.matrices
        val arm = e.arm
        val sp = e.swingProgress
        val vm = Modules.get<ViewModel>()
        // Undo vanilla's arm transform; each mode puts the arm back itself.
        m.translate(-0.56f, 0.52f - equip * -0.6f, 0.72f)
        e.cancel()
        val s = sin(Mth.sqrt(sp) * Math.PI.toFloat())
        when (mode.value) {
            Mode.Default -> {
                armPos(m, arm, 0f)
                vm.translate(m)
                attackRotation(m, arm, sp)
                vm.untranslate(m)
            }
            Mode.Bonk -> eased(m, arm, vm, Easing.OUT_ELASTIC, sp, -90f)
            Mode.Rubber -> eased(m, arm, vm, Easing.OUT_ELASTIC, sp, -360f)
            Mode.Flip -> eased(m, arm, vm, Easing.OUT_SINE, sp, -360f)
            Mode.Snap -> eased(m, arm, vm, Easing.OUT_BACK, sp, -360f)
            Mode.Hook -> {
                armPos(m, arm, 0f)
                vm.translate(m)
                m.rotate(Axis.XP.rotationDegrees(50f))
                m.rotate(Axis.YP.rotationDegrees(-30f * (1f - s) - 30f))
                m.rotate(Axis.ZP.rotationDegrees(110f))
                vm.untranslate(m)
            }
            Mode.Swipe -> {
                armPos(m, arm, 0f)
                vm.translate(m)
                m.rotate(Axis.XP.rotationDegrees(50f))
                m.rotate(Axis.YP.rotationDegrees(-60f * s - 50))
                m.rotate(Axis.ZP.rotationDegrees(110f))
                vm.untranslate(m)
            }
            Mode.Tap -> {
                armPos(m, arm, 0f)
                vm.translate(m)
                m.rotate(Axis.XP.rotationDegrees(50f))
                m.rotate(Axis.YP.rotationDegrees(-60f))
                m.rotate(Axis.ZP.rotationDegrees(110f + 20f * s))
                vm.untranslate(m)
            }
            Mode.Inject -> {
                armPos(m, arm, 0f)
                m.translate(0.0, 0.0, -s / 4.0)
                vm.translate(m)
                m.rotate(Axis.XP.rotationDegrees(-120f))
                vm.untranslate(m)
            }
            Mode.Slap -> {
                armPos(m, arm, 0f)
                vm.translate(m)
                m.rotate(Axis.XP.rotationDegrees(-sin(sp * 3f) * 60f))
                m.rotate(Axis.ZP.rotationDegrees(-60f * s))
                vm.untranslate(m)
            }
            Mode.Akrien -> akrien(m, arm, vm, sp, s)
        }
    }

    /** Bonk, Rubber, Flip and Snap: the arm turned about X by [easing] of the swing times [degrees]. */
    private fun eased(m: PoseStack, arm: HumanoidArm, vm: ViewModel, easing: Easing, sp: Float, degrees: Float) {
        armPos(m, arm, 0f)
        vm.translate(m)
        m.rotate(Axis.XP.rotationDegrees(easing.apply(sp.toDouble()).toFloat() * degrees))
        vm.untranslate(m)
    }

    private fun akrien(m: PoseStack, arm: HumanoidArm, vm: ViewModel, sp: Float, s: Float) {
        if (sp > 0f) {
            m.translate(0.56f, equip * -0.2f - 0.5f, -0.7f)
            vm.translate(m)
            m.rotate(Axis.YP.rotationDegrees(45f))
            m.rotate(Axis.XP.rotationDegrees(s * -85f))
            if (vm.enabled) {
                val k = vm.scale.value
                m.translate(-0.1f * k, 0.28f * k, 0.2f * k)
            } else {
                m.translate(-0.1f, 0.28f, 0.2f)
            }
            m.rotate(Axis.XP.rotationDegrees(-85f))
            vm.untranslate(m)
            return
        }
        // Not swinging: vanilla's swing translation and arm transform (with the equip height).
        val y = 0.2f * sin(Mth.sqrt(sp) * (Math.PI * 2).toFloat())
        val z = -0.2f * sin(sp * Math.PI.toFloat())
        m.translate(-0.4f * s, y, z)
        armPos(m, arm, equip)
        attackRotation(m, arm, sp)
    }

    /** Vanilla's `applyItemArmTransform`. */
    private fun armPos(m: PoseStack, arm: HumanoidArm, height: Float) {
        m.translate((if (arm == HumanoidArm.RIGHT) 1 else -1) * 0.56f, -0.52f + height * -0.6f, -0.72f)
    }

    /** Vanilla's `applyItemArmAttackTransform`. */
    private fun attackRotation(m: PoseStack, arm: HumanoidArm, sp: Float) {
        val i = if (arm == HumanoidArm.RIGHT) 1 else -1
        val ry = sin(sp * sp * Math.PI.toFloat())
        m.rotate(Axis.YP.rotationDegrees(i * (45f + ry * -20f)))
        val rxz = sin(Mth.sqrt(sp) * Math.PI.toFloat())
        m.rotate(Axis.ZP.rotationDegrees(i * rxz * -20f))
        m.rotate(Axis.XP.rotationDegrees(rxz * -80f))
        m.rotate(Axis.YP.rotationDegrees(i * -45f))
    }
}
