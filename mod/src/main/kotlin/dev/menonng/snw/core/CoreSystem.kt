package dev.menonng.snw.core

import dev.menonng.snw.api.Relation
import dev.menonng.snw.logic.core.NationCore
import dev.menonng.snw.logic.core.NationCore.Attacker
import dev.menonng.snw.logic.core.NationCore.Weapon
import dev.menonng.snw.logic.join.LateJoin
import dev.menonng.snw.logic.join.NationLoad
import dev.menonng.snw.logic.state.NationRegistry
import dev.menonng.snw.mixin.InteractionAccessor
import dev.menonng.snw.nation.NationCommands
import dev.menonng.snw.nation.Nations
import dev.menonng.snw.protect.Protection
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.player.AttackEntityCallback
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.fabricmc.fabric.api.event.player.UseEntityCallback
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.core.registries.Registries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.ItemTags
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.Interaction
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.monster.Enemy
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.arrow.AbstractArrow
import net.minecraft.world.entity.projectile.arrow.ThrownTrident
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.random.Random

/**
 * 국가 코어 런타임(nation.md 5-1·5-1-1·5-1-2).
 * 신호기(아래 철 블록 3×3, 위 국가 색 유리) + 그 자리에 겹친 상호작용 엔티티로 좌클릭을 받는다.
 */
object CoreSystem {
    const val CORE_TAG = "snw_core"
    const val MOB_AGGRO_RADIUS = 16.0
    const val MOB_REACH = 2.2
    private val lastCounted = HashMap<UUID, Long>()          // 플레이어별 직전 '센 공격' 시각(0.5초에 한 번)
    private val mobCooldown = HashMap<UUID, Long>()
    private var tick = 0

    private fun glassFor(rgb: Int): BlockState = when (rgb) {
        0xFF0045 -> Blocks.STAINED_GLASS.red()
        0x3355BB -> Blocks.STAINED_GLASS.blue()
        0xFFCC11 -> Blocks.STAINED_GLASS.yellow()
        else -> Blocks.STAINED_GLASS.white()
    }.defaultBlockState()

    fun levelOf(s: MinecraftServer, dim: String): ServerLevel? =
        s.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(dim)))

    fun beaconPos(c: NationRegistry.CorePos) = BlockPos(c.x, c.y, c.z)

    /** 코어 구조물 칸(신호기·아래 3×3 철 블록·위 유리). */
    fun structure(c: NationRegistry.CorePos): List<BlockPos> {
        val b = beaconPos(c)
        return listOf(b, b.above()) + (-1..1).flatMap { dx -> (-1..1).map { dz -> b.offset(dx, -1, dz) } }
    }

    fun coreAt(level: ServerLevel, pos: BlockPos): NationRegistry.Nation? {
        val d = Protection.dim(level)
        return Nations.registry.alive().firstOrNull { n -> n.core?.let { it.dimension == d && pos in structure(it) } == true }
    }

    private fun place(level: ServerLevel, n: NationRegistry.Nation) {
        val c = n.core ?: return
        val b = beaconPos(c)
        for (dx in -1..1) for (dz in -1..1) level.setBlockAndUpdate(b.offset(dx, -1, dz), Blocks.IRON_BLOCK.defaultBlockState())
        level.setBlockAndUpdate(b, Blocks.BEACON.defaultBlockState())
        level.setBlockAndUpdate(b.above(), glassFor(n.colorRgb))
        ensureHitbox(level, n)
    }

    private fun hitboxOf(level: ServerLevel, n: NationRegistry.Nation): Interaction? {
        val c = n.core ?: return null
        val b = beaconPos(c)
        return level.getEntitiesOfClass(Interaction::class.java, net.minecraft.world.phys.AABB(b).inflate(1.0)) { it.entityTags().contains("$CORE_TAG:${n.id}") }.firstOrNull()
    }

    private fun ensureHitbox(level: ServerLevel, n: NationRegistry.Nation) {
        if (hitboxOf(level, n) != null) return
        val c = n.core ?: return
        val e = EntityTypes.INTERACTION.create(level, EntitySpawnReason.COMMAND) ?: return
        (e as InteractionAccessor).`snw$setWidth`(1.1f)
        (e as InteractionAccessor).`snw$setHeight`(1.1f)
        (e as InteractionAccessor).`snw$setResponse`(true)
        e.snapTo(c.x + 0.5, c.y - 0.05, c.z + 0.5, 0f, 0f)
        e.addTag(CORE_TAG); e.addTag("$CORE_TAG:${n.id}")
        level.addFreshEntity(e)
    }

    /** 운영자: [n] 의 코어를 [pos] 에 둔다(옛 자리는 지운다). */
    fun setCore(level: ServerLevel, n: NationRegistry.Nation, pos: BlockPos) {
        n.core?.let { old -> levelOf(level.server, old.dimension)?.let { removeStructure(it, n) } }
        n.core = NationRegistry.CorePos(Protection.dim(level), pos.x, pos.y, pos.z)
        place(level, n)
        Nations.claimAndNotify(n.id, listOf(n.core!!.chunk))
        Nations.markDirty()
    }

    private fun removeStructure(level: ServerLevel, n: NationRegistry.Nation) {
        hitboxOf(level, n)?.discard()
        n.core?.let { c -> for (p in structure(c)) level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState()) }
    }

    private fun weaponOf(stack: ItemStack): Weapon = when {
        stack.isEmpty -> Weapon.HAND
        stack.`is`(ItemTags.SWORDS) -> Weapon.SWORD
        stack.`is`(ItemTags.AXES) -> Weapon.AXE
        stack.`is`(ItemTags.SPEARS) -> Weapon.SPEAR
        stack.`is`(Items.MACE) -> Weapon.MACE
        stack.`is`(Items.TRIDENT) -> Weapon.TRIDENT
        else -> Weapon.OTHER
    }

    private fun attackerKind(n: NationRegistry.Nation, attackerNation: String?, now: Long): Attacker = when {
        attackerNation == null -> Attacker.NEUTRAL
        attackerNation == n.id -> Attacker.OWN
        Nations.registry.relation(n.id, attackerNation, now) == Relation.ALLIED -> Attacker.ALLY
        Nations.registry.hostile(n.id, attackerNation, now) -> Attacker.HOSTILE
        else -> Attacker.NEUTRAL
    }

    private fun ownersOnline(s: MinecraftServer, n: NationRegistry.Nation) = n.members.count { s.playerList.getPlayer(it) != null }

    /** 한 번의 공격(플레이어). 피해량을 돌려준다. */
    internal fun playerHit(p: ServerPlayer, n: NationRegistry.Nation, weapon: Weapon, raw: Double, charge: Float, melee: ItemStack?): Double {
        val now = System.currentTimeMillis()
        val ticksSince = ((now - (lastCounted[p.uuid] ?: 0L)) / 50L).toInt()
        val hit = NationCore.playerHit(weapon, raw, charge, ticksSince)
        // 내구도: 칠 때마다 반드시(내구성 무시)
        if (melee != null && hit.durabilityCost > 0 && melee.isDamageableItem) {
            val dmg = melee.damageValue + hit.durabilityCost
            if (dmg >= melee.maxDamage) {
                melee.shrink(1)
                p.level().playSound(null, p.x, p.y, p.z, SoundEvents.ITEM_BREAK.value(), SoundSource.PLAYERS, 0.8f, 1f)
            } else melee.damageValue = dmg
        }
        val attackerNation = Nations.registry.nationOf(p.uuid)?.id
        val kind = attackerKind(n, attackerNation, now)
        val dealt = NationCore.damage(hit.raw, kind, ownersOnline(p.level().server, n), truce = Nations.truceActive,
            siegeOpen = SiegeSchedule.isOpen(now), attackerProtected = Protection.isProtected(p.uuid, now))
        if (hit.raw > 0) lastCounted[p.uuid] = now
        when {
            kind == Attacker.OWN || kind == Attacker.ALLY -> tell(p, "자국·동맹 국가의 코어는 공격할 수 없습니다.")
            kind == Attacker.NEUTRAL -> tell(p, "적대 관계인 국가의 코어만 공격할 수 있습니다.")
            Nations.truceActive -> tell(p, "블러드문 휴전 중에는 코어를 공격할 수 없습니다.")
            !SiegeSchedule.isOpen(now) -> tell(p, "공성 시간(${SiegeSchedule.todayLabel(now)})에만 코어를 공격할 수 있습니다.")
            Protection.isProtected(p.uuid, now) -> tell(p, "접속·부활 직후 보호 중에는 공격할 수 없습니다.")
            weapon == Weapon.HAND || weapon == Weapon.OTHER -> tell(p, "무기로만 코어를 공격할 수 있습니다.")
            hit.raw <= 0 -> tell(p, "공격 쿨다운이 다 찬 공격만 코어에 들어갑니다.")
        }
        if (dealt > 0) applyDamage(p.level(), n, dealt, attackerNation, p)
        return dealt
    }

    private fun tell(p: ServerPlayer, msg: String) = p.sendSystemMessage(Component.literal(msg).withStyle(ChatFormatting.GRAY), true)

    fun applyDamage(level: ServerLevel, n: NationRegistry.Nation, amount: Double, attackerNation: String?, attacker: Entity?) {
        val now = System.currentTimeMillis()
        val fell = Nations.registry.hitCore(n, amount, attackerNation, now)
        Nations.markDirty()
        val c = n.core ?: return
        level.playSound(null, c.x + 0.5, c.y + 0.5, c.z + 0.5, SoundEvents.ANVIL_PLACE, SoundSource.BLOCKS, 0.35f, 1.6f)
        val hp = Nations.registry.coreHp(n, now)
        (attacker as? ServerPlayer)?.sendSystemMessage(Component.literal("${n.name} 코어 ${"%,d".format(hp.toInt())} / 100,000")
            .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(n.colorRgb))), true)
        if (fell) fall(level, n, attackerNation)
    }

    /** 국가 멸망(5-1-1). */
    private fun fall(level: ServerLevel, n: NationRegistry.Nation, lastHitNation: String?) {
        val s = level.server
        val now = System.currentTimeMillis()
        val successor = Nations.registry.successorOf(n, lastHitNation, now)
        n.core?.let { c -> dev.menonng.snw.nation.Treasury.mergeOnFall(s, n.id, successor, level to Vec3(c.x + 0.5, c.y + 1.0, c.z + 0.5)) }
        removeStructure(level, n)
        val people = Nations.registry.fall(n)
        val alive = Nations.registry.alive()
        for (p in people) {
            val to = successor ?: LateJoin.assign(alive.map { NationLoad(it.id, it.members.count { m -> !Nations.afkCheck(m) && s.playerList.getPlayer(m) != null }, it.members.size) }, Random.Default)
            Nations.assignAndSync(p, to)
        }
        Nations.syncTeams(s)
        val where = successor?.let { Nations.registry.nation(it)?.name } ?: "남은 국가들"
        NationCommands.broadcast(s, "${n.name}이(가) 무너졌습니다. 국민은 ${where}(으)로 옮겨집니다.", 0xFF0045)
        Nations.publish("nation_fallen", mapOf("nation" to n.name, "successor" to (successor?.let { Nations.registry.nation(it)?.name })))
        Nations.markDirty()
    }

    // ---- 믹스인이 부르는 곳 ----
    @JvmStatic
    fun onProjectileHit(arrow: AbstractArrow, pos: BlockPos) {
        val level = arrow.level() as? ServerLevel ?: return
        val n = coreAt(level, pos) ?: return
        val shooter = arrow.owner as? ServerPlayer
        if (shooter == null) { if (arrow.owner is Enemy) applyDamage(level, n, NationCore.damage(2.0, Attacker.MOB, ownersOnline(level.server, n)), null, arrow.owner); return }
        val weapon = if (arrow is ThrownTrident) Weapon.THROWN_TRIDENT else Weapon.ARROW
        val charge = if (arrow is ThrownTrident || arrow.isCritArrow) 1f else 0f
        playerHit(shooter, n, weapon, 2.0, charge, null)
    }

    @JvmStatic
    fun onExplosion(level: ServerLevel, center: Vec3, radius: Float, source: LivingEntity?) {
        val d = Protection.dim(level)
        for (n in Nations.registry.alive()) {
            val c = n.core ?: continue
            if (c.dimension != d || center.distanceTo(Vec3(c.x + 0.5, c.y + 0.5, c.z + 0.5)) > radius + 1.5) continue
            val now = System.currentTimeMillis()
            val p = source as? ServerPlayer
            val kind = if (p != null) attackerKind(n, Nations.registry.nationOf(p.uuid)?.id, now) else if (source is Enemy || source == null) Attacker.MOB else Attacker.NEUTRAL
            val dealt = NationCore.damage(2.0, kind, ownersOnline(level.server, n), truce = Nations.truceActive, siegeOpen = SiegeSchedule.isOpen(now),
                attackerProtected = p?.let { Protection.isProtected(it.uuid, now) } ?: false)
            if (dealt > 0) applyDamage(level, n, dealt, p?.let { Nations.registry.nationOf(it.uuid)?.id }, source)
        }
    }

    fun register() {
        ServerLifecycleEvents.SERVER_STARTED.register { s -> SiegeSchedule.load(s) }
        ServerLifecycleEvents.SERVER_STOPPING.register { s -> SiegeSchedule.save(s) }

        AttackEntityCallback.EVENT.register { player, level, _, entity, _ ->
            if (entity !is Interaction || !entity.entityTags().contains(CORE_TAG) || level !is ServerLevel) return@register InteractionResult.PASS
            val p = player as? ServerPlayer ?: return@register InteractionResult.FAIL
            val id = entity.entityTags().firstOrNull { it.startsWith("$CORE_TAG:") }?.removePrefix("$CORE_TAG:")
            val n = id?.let { Nations.registry.nation(it) }?.takeIf { !it.fallen } ?: return@register InteractionResult.FAIL
            val stack = p.mainHandItem
            val weapon = weaponOf(stack)
            val raw = p.getAttributeValue(Attributes.ATTACK_DAMAGE)
            val charge = p.getAttackStrengthScale(0.5f)
            playerHit(p, n, weapon, raw, charge, if (weapon.counts) stack else null)
            p.resetAttackStrengthTicker()
            InteractionResult.SUCCESS
        }
        UseEntityCallback.EVENT.register { player, level, hand, entity, _ ->
            if (hand != InteractionHand.MAIN_HAND || entity !is Interaction || !entity.entityTags().contains(CORE_TAG) || level !is ServerLevel) return@register InteractionResult.PASS
            val p = player as? ServerPlayer ?: return@register InteractionResult.PASS
            val id = entity.entityTags().firstOrNull { it.startsWith("$CORE_TAG:") }?.removePrefix("$CORE_TAG:")
            val n = id?.let { Nations.registry.nation(it) } ?: return@register InteractionResult.PASS
            showInfo(p, n); InteractionResult.SUCCESS
        }
        PlayerBlockBreakEvents.BEFORE.register { level, player, pos, _, _ ->
            if (level !is ServerLevel) return@register true
            val n = coreAt(level, pos) ?: return@register true
            if (player.isCreative && net.minecraft.commands.Commands.LEVEL_GAMEMASTERS.check((player as ServerPlayer).permissions())) return@register true
            (player as? ServerPlayer)?.sendSystemMessage(Component.literal("${n.name}의 국가 코어는 블록으로 부서지지 않습니다. 무기로 쳐서 체력을 깎아야 합니다.").withStyle(ChatFormatting.GRAY), true)
            false
        }
        ServerTickEvents.END_SERVER_TICK.register { s ->
            tick++
            if (tick % 20 == 0) secondTick(s)
            if (tick % 1200 == 0) { SiegeSchedule.sample(s); SiegeSchedule.save(s) }
        }
    }

    private fun showInfo(p: ServerPlayer, n: NationRegistry.Nation) {
        val mine = Nations.registry.nationOf(p.uuid)?.id == n.id
        if (!mine) { p.sendSystemMessage(Component.literal("${n.name}의 국가 코어입니다.").withStyle(Style.EMPTY.withColor(TextColor.fromRgb(n.colorRgb)))); return }
        val now = System.currentTimeMillis()
        val hp = Nations.registry.coreHp(n, now)
        val s = p.level().server
        val leader = n.leader?.let { Nations.nameOf(s, it) } ?: "없음"
        val sinceHit = if (n.coreLastHit == 0L) "맞은 적 없음" else "${(now - n.coreLastHit) / 60_000}분 전"
        val regen = if (n.coreLastHit == 0L || hp >= NationCore.MAX_HP) "가득 참" else {
            val wait = NationCore.REGEN_DELAY_MILLIS - (now - n.coreLastHit)
            if (wait > 0) "회복까지 ${wait / 60_000}분" else "회복 중(시간당 10)"
        }
        p.sendSystemMessage(Component.literal(
            "[${n.name}] 원수 $leader · 국민 ${n.members.size}명 · 영토 ${Nations.registry.territory(n.id).size}/${NationCommands.cap(n)}청크\n" +
            "코어 ${"%,d".format(hp.toInt())} / 100,000 · 최근에 맞은 시각: $sinceHit · $regen\n" +
            "오늘 공성 시간 ${SiegeSchedule.todayLabel(now)}${if (SiegeSchedule.isOpen(now)) " (지금 열림)" else ""}"
        ).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(n.colorRgb))))
    }

    /** 1초마다: 구조물 복구, 판정 엔티티 유지, 몬스터가 코어를 노린다. */
    private fun secondTick(s: MinecraftServer) {
        val now = System.currentTimeMillis()
        for (n in Nations.registry.alive()) {
            val c = n.core ?: continue
            val level = levelOf(s, c.dimension) ?: continue
            val b = beaconPos(c)
            if (!level.isLoaded(b)) continue
            if (!level.getBlockState(b).`is`(Blocks.BEACON) || structure(c).drop(2).any { !level.getBlockState(it).`is`(Blocks.IRON_BLOCK) }) place(level, n)
            ensureHitbox(level, n)
            val center = Vec3(c.x + 0.5, c.y + 0.5, c.z + 0.5)
            val mobs = level.getEntitiesOfClass(Mob::class.java, net.minecraft.world.phys.AABB(b).inflate(MOB_AGGRO_RADIUS)) { it is Enemy && it.isAlive && it.getAttributes().hasAttribute(Attributes.ATTACK_DAMAGE) }
            for (m in mobs) {
                val target = m.target
                val dCore = m.position().distanceTo(center)
                if (target is Player && m.distanceTo(target) < dCore) continue        // 플레이어가 더 가까우면 플레이어 우선
                if (dCore > MOB_REACH) { if (m.navigation.isDone || tick % 60 == 0) m.navigation.moveTo(center.x, c.y.toDouble(), center.z, 1.0); continue }
                m.navigation.stop(); m.lookControl.setLookAt(center)
                if ((mobCooldown[m.uuid] ?: 0L) > now) continue
                mobCooldown[m.uuid] = now + 1000
                m.swingForAttack(InteractionHand.MAIN_HAND)
                val raw = m.getAttributeValue(Attributes.ATTACK_DAMAGE)
                val dealt = NationCore.damage(raw, Attacker.MOB, ownersOnline(s, n))
                if (dealt > 0) applyDamage(level, n, dealt, null, m)
            }
        }
        if (tick % 200 == 0) mobCooldown.entries.removeIf { it.value < now - 10_000 }
    }
}
