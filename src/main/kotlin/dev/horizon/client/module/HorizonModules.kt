package dev.horizon.client.module

import dev.horizon.client.module.attack.AntiPhase
import dev.horizon.client.module.attack.AttributeSwap
import dev.horizon.client.module.attack.Aura
import dev.horizon.client.module.attack.AutoBasePlace
import dev.horizon.client.module.attack.AutoCrystal
import dev.horizon.client.module.attack.AutoDripstope
import dev.horizon.client.module.attack.AutoMine
import dev.horizon.client.module.attack.AutoNetheriteScrap
import dev.horizon.client.module.attack.AutoSweetBerries
import dev.horizon.client.module.attack.AutoWeb
import dev.horizon.client.module.attack.Criticals
import dev.horizon.client.module.attack.HoleFiller
import dev.horizon.client.module.attack.MaceKiller
import dev.horizon.client.module.attack.MoreKnockback
import dev.horizon.client.module.attack.Trap
import dev.horizon.client.module.attack.BedAura
import dev.horizon.client.module.attack.AnchorAura
import dev.horizon.client.module.equip.AutoDrop
import dev.horizon.client.module.world.ChestAura
import dev.horizon.client.module.misc.DiscordAlerts
import dev.horizon.client.module.player.AutoMount
import dev.horizon.client.module.world.HighwayBuilder
import dev.horizon.client.module.rage.EntityControl
import dev.horizon.client.module.cosmetic.Ambience
import dev.horizon.client.module.protect.ArmorAlert
import dev.horizon.client.module.basic.AntiKnockBack
import dev.horizon.client.module.basic.Avoid
import dev.horizon.client.module.basic.ElytraRecast
import dev.horizon.client.module.basic.GuiMove
import dev.horizon.client.module.basic.HoleAnchor
import dev.horizon.client.module.basic.InfiniteElytra
import dev.horizon.client.module.basic.LegitStrafe
import dev.horizon.client.module.basic.MoveFix
import dev.horizon.client.module.basic.NoPush
import dev.horizon.client.module.basic.Sprint
import dev.horizon.client.module.basic.WindHop
import dev.horizon.client.module.cosmetic.Absorption
import dev.horizon.client.module.cosmetic.Aspect
import dev.horizon.client.module.cosmetic.BuffEffect
import dev.horizon.client.module.cosmetic.CrystalChams
import dev.horizon.client.module.cosmetic.DamageParticles
import dev.horizon.client.module.cosmetic.DamageTint
import dev.horizon.client.module.cosmetic.Dismemberment
import dev.horizon.client.module.cosmetic.FreeLook
import dev.horizon.client.module.cosmetic.FullBright
import dev.horizon.client.module.cosmetic.HandChams
import dev.horizon.client.module.cosmetic.HitFx
import dev.horizon.client.module.cosmetic.NoCameraClip
import dev.horizon.client.module.cosmetic.Particles
import dev.horizon.client.module.cosmetic.PopChams
import dev.horizon.client.module.cosmetic.SkyLanterns
import dev.horizon.client.module.cosmetic.SwingAnimations
import dev.horizon.client.module.cosmetic.ThrowPetard
import dev.horizon.client.module.cosmetic.TotemAnimation
import dev.horizon.client.module.cosmetic.Trajectories
import dev.horizon.client.module.cosmetic.ViewModel
import dev.horizon.client.module.cosmetic.WorldRenderer
import dev.horizon.client.module.equip.AutoArmor
import dev.horizon.client.module.equip.AutoTool
import dev.horizon.client.module.equip.ChestStealer
import dev.horizon.client.module.equip.EcExploit
import dev.horizon.client.module.equip.ElytraReplace
import dev.horizon.client.module.equip.ElytraSwap
import dev.horizon.client.module.equip.InventoryManager
import dev.horizon.client.module.equip.ItemScroller
import dev.horizon.client.module.equip.MiddleClick
import dev.horizon.client.module.equip.NoServerSlot
import dev.horizon.client.module.equip.ReFiller
import dev.horizon.client.module.equip.ToolSaver
import dev.horizon.client.module.equip.XCarry
import dev.horizon.client.module.esp.BedPlates
import dev.horizon.client.module.esp.BreakEsp
import dev.horizon.client.module.esp.HoleEsp
import dev.horizon.client.module.esp.ItemEsp
import dev.horizon.client.module.esp.LogoutSpots
import dev.horizon.client.module.esp.NameTags
import dev.horizon.client.module.esp.PearlPhaseEsp
import dev.horizon.client.module.esp.PlayerEsp
import dev.horizon.client.module.esp.ProjectilePredict
import dev.horizon.client.module.esp.Search
import dev.horizon.client.module.esp.StorageEsp
import dev.horizon.client.module.esp.Tracers
import dev.horizon.client.module.esp.TunnelEsp
import dev.horizon.client.module.esp.VoidEsp
import dev.horizon.client.module.esp.XRay
import dev.horizon.client.module.legit.AimAssist
import dev.horizon.client.module.legit.AnchorBind
import dev.horizon.client.module.legit.AutoBuff
import dev.horizon.client.module.legit.AutoClicker
import dev.horizon.client.module.legit.BowMinecart
import dev.horizon.client.module.legit.BowSpam
import dev.horizon.client.module.legit.CrystalBind
import dev.horizon.client.module.legit.Eagle
import dev.horizon.client.module.legit.JumpReset
import dev.horizon.client.module.legit.LegitTotem
import dev.horizon.client.module.legit.Reach
import dev.horizon.client.module.legit.Teams
import dev.horizon.client.module.legit.TriggerBot
import dev.horizon.client.module.legit.WTap
import dev.horizon.client.module.legit.BlockIn
import dev.horizon.client.module.legit.BedDefender
import dev.horizon.client.module.legit.Clutch
import dev.horizon.client.module.legit.FireballDeflect
import dev.horizon.client.module.legit.GhostMode
import dev.horizon.client.module.legit.GodBridge
import dev.horizon.client.module.legit.PearlSave
import dev.horizon.client.module.legit.ThreatAlerts
import dev.horizon.client.module.misc.AntiResourcePack
import dev.horizon.client.module.misc.Announce
import dev.horizon.client.module.misc.AutoEat
import dev.horizon.client.module.misc.AutoEz
import dev.horizon.client.module.misc.AutoLeave
import dev.horizon.client.module.misc.AutoPotionBrew
import dev.horizon.client.module.misc.AutoSoup
import dev.horizon.client.module.misc.AutoTrader
import dev.horizon.client.module.misc.ExtraTab
import dev.horizon.client.module.misc.FastLatency
import dev.horizon.client.module.misc.MessageAppend
import dev.horizon.client.module.misc.NameProtect
import dev.horizon.client.module.misc.Notifier
import dev.horizon.client.module.misc.PingSpoof
import dev.horizon.client.module.misc.RPC
import dev.horizon.client.module.misc.SourceFiller
import dev.horizon.client.module.misc.Spammer
import dev.horizon.client.module.misc.TapeMouse
import dev.horizon.client.module.misc.TeamCommunication
import dev.horizon.client.module.misc.Test
import dev.horizon.client.module.misc.Translator
import dev.horizon.client.module.player.ActionRecorder
import dev.horizon.client.module.player.AirPlace
import dev.horizon.client.module.player.AntiAim
import dev.horizon.client.module.player.AntiAttack
import dev.horizon.client.module.player.AntiHunger
import dev.horizon.client.module.player.AutoAuth
import dev.horizon.client.module.player.AutoFish
import dev.horizon.client.module.player.AutoMend
import dev.horizon.client.module.player.AutoRespawn
import dev.horizon.client.module.player.AntiAfk
import dev.horizon.client.module.player.AutoTpaAccept
import dev.horizon.client.module.player.BoxShrinker
import dev.horizon.client.module.player.ChorusDelay
import dev.horizon.client.module.player.MultiTask
import dev.horizon.client.module.player.NoDelay
import dev.horizon.client.module.player.NoInteract
import dev.horizon.client.module.misc.ShulkerView
import dev.horizon.client.module.esp.GlowEsp
import dev.horizon.client.module.esp.NewChunks
import dev.horizon.client.module.esp.BaseFinder
import dev.horizon.client.module.esp.StashFinder
import dev.horizon.client.module.esp.Breadcrumbs
import dev.horizon.client.module.esp.PortalEsp
import dev.horizon.client.module.esp.PortalPatternFinder
import dev.horizon.client.module.basic.AutoWalk
import dev.horizon.client.module.misc.AutoReconnect
import dev.horizon.client.module.world.DeathWaypoints
import dev.horizon.client.module.world.AutoCraft
import dev.horizon.client.module.world.AutoSmelt
import dev.horizon.client.module.equip.ShulkerTransport
import dev.horizon.client.module.cosmetic.Cape
import dev.horizon.client.module.cosmetic.CustomHotbar
import dev.horizon.client.module.player.NoServerRotate
import dev.horizon.client.module.player.PerfectDelay
import dev.horizon.client.module.player.PortalGodMode
import dev.horizon.client.module.player.SpeedMine
import dev.horizon.client.module.player.TraceThrough
import dev.horizon.client.module.protect.AutoGapple
import dev.horizon.client.module.protect.Blocker
import dev.horizon.client.module.protect.Burrow
import dev.horizon.client.module.protect.MainhandTotem
import dev.horizon.client.module.protect.Offhand
import dev.horizon.client.module.protect.Quiver
import dev.horizon.client.module.protect.SelfTrap
import dev.horizon.client.module.protect.Surround
import dev.horizon.client.module.rage.AntiVoid
import dev.horizon.client.module.rage.AntiWeb
import dev.horizon.client.module.rage.Blink
import dev.horizon.client.module.rage.BoatFly
import dev.horizon.client.module.rage.ElytraFreeze
import dev.horizon.client.module.rage.ElytraAutopilot
import dev.horizon.client.module.rage.ElytraPlus
import dev.horizon.client.module.rage.ElytraTarget
import dev.horizon.client.module.rage.EntitySpeed
import dev.horizon.client.module.rage.Flight
import dev.horizon.client.module.rage.HighJump
import dev.horizon.client.module.rage.LevitationControl
import dev.horizon.client.module.rage.MaceElytraTarget
import dev.horizon.client.module.rage.NoFall
import dev.horizon.client.module.rage.NoSlow
import dev.horizon.client.module.rage.Phase
import dev.horizon.client.module.rage.ReverseStep
import dev.horizon.client.module.rage.Scaffold
import dev.horizon.client.module.rage.Speed
import dev.horizon.client.module.rage.Spider
import dev.horizon.client.module.rage.Step
import dev.horizon.client.module.rage.Timer
import dev.horizon.client.module.rage.TridentBoost
import dev.horizon.client.module.rage.WaterSpeed
import dev.horizon.client.module.world.AutoBoneMeal
import dev.horizon.client.module.world.AutoLand
import dev.horizon.client.module.world.AutoShear
import dev.horizon.client.module.world.AutoSign
import dev.horizon.client.module.world.AutoWither
import dev.horizon.client.module.world.FakePlayer
import dev.horizon.client.module.world.FreeCam
import dev.horizon.client.module.world.HuntMap
import dev.horizon.client.module.world.NoRender
import dev.horizon.client.module.world.Nuker
import dev.horizon.client.module.world.PearlChaser
import dev.horizon.client.module.world.ServerHelper
import dev.horizon.client.module.world.SoilRipper
import dev.horizon.client.module.world.TpsSync
import dev.horizon.client.module.world.Zoom

/** Every the original module, in the original's registration order (the search and the Key Binds widget list them in it). */
object HorizonModules {
    fun register() {
        Modules.register(ElytraTarget())
        Modules.register(BowMinecart())
        Modules.register(ElytraFreeze())
        Modules.register(Dismemberment())
        Modules.register(ExtraTab())
        Modules.register(ThrowPetard())
        Modules.register(AntiAim())
        Modules.register(VoidEsp())
        Modules.register(Aspect())
        Modules.register(Test())
        Modules.register(NameProtect())
        Modules.register(AnchorBind())
        Modules.register(Spider())
        Modules.register(AutoBoneMeal())
        Modules.register(PearlPhaseEsp())
        Modules.register(AutoSweetBerries())
        Modules.register(MaceElytraTarget())
        Modules.register(DamageParticles())
        Modules.register(InfiniteElytra())
        Modules.register(MoreKnockback())
        Modules.register(EcExploit())
        Modules.register(AntiAttack())
        Modules.register(MainhandTotem())
        Modules.register(ServerHelper())
        Modules.register(BoatFly())
        Modules.register(Zoom())
        Modules.register(WindHop())
        Modules.register(LegitStrafe())
        Modules.register(DamageTint())
        Modules.register(FreeLook())
        Modules.register(AutoNetheriteScrap())
        Modules.register(FastLatency())
        Modules.register(PingSpoof())
        Modules.register(AutoMend())
        Modules.register(Blocker())
        Modules.register(AutoShear())
        Modules.register(SoilRipper())
        Modules.register(SwingAnimations())
        Modules.register(Particles())
        Modules.register(AutoTrader())
        Modules.register(BuffEffect())
        Modules.register(ActionRecorder())
        Modules.register(AutoPotionBrew())
        Modules.register(AutoLand())
        Modules.register(AntiKnockBack())
        Modules.register(ChestStealer())
        Modules.register(NoCameraClip())
        Modules.register(Avoid())
        Modules.register(BreakEsp())
        Modules.register(XRay())
        Modules.register(ElytraRecast())
        Modules.register(AutoBasePlace())
        Modules.register(GuiMove())
        Modules.register(MoveFix())
        Modules.register(NoPush())
        Modules.register(Sprint())
        Modules.register(AttributeSwap())
        Modules.register(AntiVoid())
        Modules.register(AntiWeb())
        Modules.register(ElytraPlus())
        Modules.register(Flight())
        Modules.register(LevitationControl())
        Modules.register(Blink())
        Modules.register(PopChams())
        Modules.register(NoFall())
        Modules.register(NoSlow())
        Modules.register(Phase())
        Modules.register(ReverseStep())
        Modules.register(Speed())
        Modules.register(Step())
        Modules.register(Timer())
        Modules.register(WaterSpeed())
        Modules.register(Aura())
        Modules.register(AutoCrystal())
        Modules.register(Absorption())
        Modules.register(SourceFiller())
        Modules.register(AutoWeb())
        Modules.register(MessageAppend())
        Modules.register(Criticals())
        Modules.register(HoleFiller())
        Modules.register(AutoBuff())
        Modules.register(BowSpam())
        Modules.register(Reach())
        Modules.register(AutoWither())
        Modules.register(HighJump())
        Modules.register(SkyLanterns())
        Modules.register(AntiPhase())
        Modules.register(AutoGapple())
        Modules.register(Burrow())
        Modules.register(LegitTotem())
        Modules.register(Offhand())
        Modules.register(Surround())
        Modules.register(AutoArmor())
        Modules.register(AutoTool())
        Modules.register(ElytraSwap())
        Modules.register(InventoryManager())
        Modules.register(ItemScroller())
        Modules.register(MiddleClick())
        Modules.register(AutoMine())
        Modules.register(NoServerSlot())
        Modules.register(ReFiller())
        Modules.register(ToolSaver())
        Modules.register(XCarry())
        Modules.register(AntiResourcePack())
        Modules.register(ClickGuiModule())
        Modules.register(Trap())
        Modules.register(SelfTrap())
        Modules.register(TeamCommunication())
        Modules.register(Notifier())
        Modules.register(RPC())
        Modules.register(Spammer())
        Modules.register(TapeMouse())
        Modules.register(Translator())
        Modules.register(AirPlace())
        Modules.register(ElytraReplace())
        Modules.register(AntiHunger())
        Modules.register(AutoAuth())
        Modules.register(AutoFish())
        Modules.register(AutoRespawn())
        Modules.register(AntiAfk())
        Modules.register(AutoTpaAccept())
        Modules.register(ChorusDelay())
        Modules.register(MultiTask())
        Modules.register(NoDelay())
        Modules.register(TraceThrough())
        Modules.register(NoServerRotate())
        Modules.register(PerfectDelay())
        Modules.register(PortalGodMode())
        Modules.register(SpeedMine())
        Modules.register(AutoSign())
        Modules.register(FakePlayer())
        Modules.register(FreeCam())
        Modules.register(NoRender())
        Modules.register(Nuker())
        Modules.register(PearlChaser())
        Modules.register(Scaffold())
        Modules.register(CrystalChams())
        Modules.register(FullBright())
        Modules.register(HandChams())
        Modules.register(HitFx())
        Modules.register(TotemAnimation())
        Modules.register(Trajectories())
        Modules.register(ViewModel())
        Modules.register(WorldRenderer())
        Modules.register(HoleAnchor())
        Modules.register(CrystalBind())
        Modules.register(Search())
        Modules.register(HoleEsp())
        Modules.register(EntitySpeed())
        Modules.register(ItemEsp())
        Modules.register(LogoutSpots())
        Modules.register(AutoDripstope())
        Modules.register(TpsSync())
        Modules.register(BoxShrinker())
        Modules.register(AutoLeave())
        Modules.register(AutoSoup())
        Modules.register(AutoEat())
        Modules.register(NameTags())
        Modules.register(ProjectilePredict())
        Modules.register(PlayerEsp())
        Modules.register(StorageEsp())
        Modules.register(Tracers())
        Modules.register(NoInteract())
        Modules.register(AutoEz())
        Modules.register(MaceKiller())
        Modules.register(TridentBoost())
        Modules.register(Quiver())
        // Not in the original.
        Modules.register(ShulkerView())
        Modules.register(GlowEsp())
        Modules.register(NewChunks())
        Modules.register(Cape())
        Modules.register(Breadcrumbs())
        Modules.register(AutoWalk())
        Modules.register(AutoReconnect())
        Modules.register(DeathWaypoints())
        Modules.register(AutoCraft())
        Modules.register(AutoSmelt())
        Modules.register(ShulkerTransport())
        Modules.register(PortalEsp())
        Modules.register(BaseFinder())
        Modules.register(StashFinder())
        Modules.register(CustomHotbar())
        Modules.register(ElytraAutopilot())
        Modules.register(HuntMap())
        Modules.register(PortalPatternFinder())
        Modules.register(TunnelEsp())
        Modules.register(Announce())
        Modules.register(BedAura())
        Modules.register(AnchorAura())
        Modules.register(AutoDrop())
        Modules.register(ChestAura())
        Modules.register(DiscordAlerts())
        Modules.register(AutoMount())
        Modules.register(HighwayBuilder())
        Modules.register(EntityControl())
        Modules.register(Ambience())
        Modules.register(ArmorAlert())
        Modules.register(Eagle())
        Modules.register(Teams())
        Modules.register(JumpReset())
        Modules.register(AutoClicker())
        Modules.register(AimAssist())
        Modules.register(TriggerBot())
        Modules.register(WTap())
        Modules.register(BlockIn())
        Modules.register(BedDefender())
        Modules.register(GodBridge())
        Modules.register(Clutch())
        Modules.register(FireballDeflect())
        Modules.register(PearlSave())
        Modules.register(ThreatAlerts())
        Modules.register(GhostMode)
        Modules.register(BedPlates())
    }
}
