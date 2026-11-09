package dev.horizon.client.combat

import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.game.ServerboundAttackPacket
import net.minecraft.world.entity.Entity

/** The entity an attack packet hits. */
fun attackTarget(p: ServerboundAttackPacket): Entity? = Minecraft.getInstance().level?.getEntity(p.entityId())
