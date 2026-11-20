package dev.menonng.snw.logic.join

import kotlin.random.Random

/**
 * 늦게 합류하는 사람. docs/design/nation.md '국가' 3-2.
 *
 * - 국가 추첨 뒤에 새로 들어온 사람: **최근 72시간 활동 국민 수가 가장 적은 국가**로 자동 배정
 *   (같으면 전체 국민 수가 적은 쪽, 그래도 같으면 무작위). 운영자가 바꿀 수 있다.
 * - 시즌 시작 3일째 이후 **처음** 접속하는 사람에게 한 번 '늦깎이 보급 상자'. 날짜가 늦을수록 조금 좋다.
 *   마법부여 없음. 그 시점 기존 국민의 평균 장비보다 낮게 잡는다. 이미 접속한 적 있는 사람(복귀자)은 받지 않는다.
 * - 처음 접속한 뒤 플레이 30분 동안은 좀비 떼 대상에서 빠진다.
 */
data class NationLoad(val id: String, val activeMembers: Int, val totalMembers: Int)

enum class KitTier { NONE, STONE, IRON, IRON_ARMOR }

object LateJoin {
    const val HORDE_GRACE_MILLIS = 30 * 60_000L

    fun assign(nations: List<NationLoad>, random: Random): String {
        require(nations.isNotEmpty())
        val minActive = nations.minOf { it.activeMembers }
        val a = nations.filter { it.activeMembers == minActive }
        val minTotal = a.minOf { it.totalMembers }
        val b = a.filter { it.totalMembers == minTotal }
        return b[random.nextInt(b.size)].id
    }

    /** [seasonDay] = 시즌 시작일을 0일째로 센 날짜. */
    fun tier(seasonDay: Int, firstJoinEver: Boolean): KitTier = when {
        !firstJoinEver -> KitTier.NONE
        seasonDay < 3 -> KitTier.NONE
        seasonDay < 8 -> KitTier.STONE
        seasonDay < 15 -> KitTier.IRON
        else -> KitTier.IRON_ARMOR
    }

    /** 보급 상자 내용(바닐라 아이템 id → 개수). 붕대·해독제는 zomApo 아이템 이름. */
    fun kit(tier: KitTier): List<Pair<String, Int>> {
        val stone = listOf(
            "minecraft:stone_pickaxe" to 1, "minecraft:stone_axe" to 1, "minecraft:stone_shovel" to 1, "minecraft:stone_sword" to 1,
            "minecraft:bread" to 16, "minecraft:torch" to 16, "minecraft:white_bed" to 1, "zomapo:bandage" to 4,
        )
        val iron = listOf(
            "minecraft:iron_pickaxe" to 1, "minecraft:iron_axe" to 1, "minecraft:iron_sword" to 1, "minecraft:stone_shovel" to 1,
            "minecraft:shield" to 1, "minecraft:cooked_beef" to 24, "minecraft:torch" to 32, "minecraft:white_bed" to 1,
            "zomapo:bandage" to 6, "zomapo:antidote" to 1,
        )
        val armor = listOf(
            "minecraft:iron_helmet" to 1, "minecraft:iron_chestplate" to 1, "minecraft:iron_leggings" to 1, "minecraft:iron_boots" to 1,
            "minecraft:bow" to 1, "minecraft:arrow" to 32,
        )
        return when (tier) {
            KitTier.NONE -> emptyList()
            KitTier.STONE -> stone
            KitTier.IRON -> iron
            KitTier.IRON_ARMOR -> iron + armor
        }
    }

    /** 첫 접속 뒤 플레이 시간이 이보다 짧으면 좀비 떼 대상에서 뺀다. */
    fun inHordeGrace(playMillisSinceFirstJoin: Long): Boolean = playMillisSinceFirstJoin < HORDE_GRACE_MILLIS
}
