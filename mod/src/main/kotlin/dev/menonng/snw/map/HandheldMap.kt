package dev.menonng.snw.map

import dev.menonng.snw.api.ChunkKey
import dev.menonng.snw.logic.map.HandheldMapRules as R
import dev.menonng.snw.nation.Nations
import dev.menonng.snw.ui.Gui
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.level.saveddata.maps.MapDecoration
import net.minecraft.world.level.saveddata.maps.MapDecorationType
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes
import net.minecraft.world.level.saveddata.maps.MapId
import net.minecraft.world.level.saveddata.maps.MapItemSavedData
import java.util.Optional
import java.util.UUID

/**
 * 휴대 지도(docs/design/handheld-map.md). 바닐라 빈 지도를 쓰면 그 사람 전용 지도가 되고, 들고 있는 동안 서버가 그 사람 주변을 패킷으로 그린다.
 * - 지도 번호는 서버의 빈 번호를 하나 받아 쓰지만 저장 데이터는 만들지 않는다(바닐라는 이 지도를 건드리지 않는다).
 * - 내용은 들고 있는 사람 기준으로 그 사람에게만 보낸다(남의 지도를 주워도 내 기준).
 * - 지형은 지표면만, 불러온 청크만(청크 단위 캐시 30초). 네더는 지형을 그리지 않는다.
 * - 아이콘: 나, 수도(국가 깃발), 근처 자국민(파란 표지), 사망 지점(본인만). 적국 플레이어는 그리지 않는다.
 * - 웅크리고 쓰면 감염 레벨 무늬 켜기/끄기(사람마다 기억).
 */
object HandheldMap {
    private const val KEY = "snw"
    private const val ID = "handheld_map"

    private class View(var mapId: Int = -1, var dim: String = "", var cx: Int = 0, var cz: Int = 0, var image: ByteArray? = null, var lastTerrain: Long = 0L)
    private val views = HashMap<UUID, View>()
    private val pattern = HashSet<UUID>()

    private class Col(val color: ByteArray, val height: IntArray, val water: IntArray, val at: Long)
    private val cache = HashMap<ChunkKey, Col>()

    private val palette: List<Int> by lazy { (0 until 64).map { i -> runCatching { MapColor.byId(i).col }.getOrDefault(0) } }

    fun isHandheld(stack: ItemStack): Boolean =
        stack.`is`(Items.FILLED_MAP) && stack.get(DataComponents.CUSTOM_DATA)?.copyTag()?.getStringOr(KEY, "") == ID

    fun create(level: ServerLevel): ItemStack = ItemStack(Items.FILLED_MAP).apply {
        set(DataComponents.MAP_ID, level.server.overworld().freeMapId)
        set(DataComponents.ITEM_NAME, Component.literal("휴대 지도"))
        set(DataComponents.CUSTOM_DATA, CustomData.of(CompoundTag().apply { putString(KEY, ID) }))
    }

    // ------------------------------------------------------------------ 지형

    private fun column(level: ServerLevel, cx: Int, cz: Int, now: Long): Col? {
        val key = ChunkKey(level.dimension().identifier().toString(), cx, cz)
        cache[key]?.let { if (now - it.at < R.CHUNK_CACHE_TICKS) return it }
        val chunk = level.chunkSource.getChunkNow(cx, cz) ?: return null
        val color = ByteArray(256); val height = IntArray(256); val water = IntArray(256)
        val pos = BlockPos.MutableBlockPos()
        for (lx in 0 until 16) for (lz in 0 until 16) {
            val i = lz * 16 + lx
            var y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz)
            val min = level.minY
            var st = chunk.getBlockState(pos.set(cx * 16 + lx, y, cz * 16 + lz))
            while (y > min && st.getMapColor(level, pos) == MapColor.NONE) { y--; st = chunk.getBlockState(pos.set(cx * 16 + lx, y, cz * 16 + lz)) }
            var depth = 0
            if (!st.fluidState.isEmpty) {
                var yy = y - 1
                while (yy > min && depth < 16 && !chunk.getBlockState(pos.set(cx * 16 + lx, yy, cz * 16 + lz)).fluidState.isEmpty) { depth++; yy-- }
            }
            pos.set(cx * 16 + lx, y, cz * 16 + lz)
            color[i] = st.getMapColor(level, pos).id.toByte()
            height[i] = y; water[i] = if (!st.fluidState.isEmpty && st.`is`(Blocks.WATER)) depth + 1 else 0
        }
        return Col(color, height, water, now).also { cache[key] = it }
    }

    /** 지도 그림 한 장(128 × 128). 중심 [cx],[cz] 블록, 보는 사람 [viewer] 기준(국경 실선·점선, 무늬). */
    internal fun render(level: ServerLevel, cx: Int, cz: Int, viewer: UUID, withPattern: Boolean): ByteArray {
        val img = ByteArray(R.SIZE * R.SIZE)
        val now = level.gameTime
        val dim = level.dimension().identifier().toString()
        val drawTerrain = level.dimension() != net.minecraft.world.level.Level.NETHER
        val x0 = cx - R.HALF; val z0 = cz - R.HALF
        val mine = Nations.registry.nationOf(viewer)?.id
        val owners = HashMap<Long, String?>()
        fun owner(chx: Int, chz: Int): String? = owners.getOrPut((chx.toLong() shl 32) or (chz.toLong() and 0xffffffffL)) { Nations.registry.ownerOf(ChunkKey(dim, chx, chz))?.id }
        val tints = HashMap<Long, R.Tint>()
        val src = Nations.infectionLevel
        fun tint(chx: Int, chz: Int): R.Tint {
            if (src == null) return R.Tint.NONE
            val gx = R.patternCell(chx); val gz = R.patternCell(chz)
            return tints.getOrPut((gx.toLong() shl 32) or (gz.toLong() and 0xffffffffL)) {
                R.patternTint(runCatching { src(dim, gx * R.PATTERN_CELL_CHUNKS + 2, gz * R.PATTERN_CELL_CHUNKS + 2) }.getOrDefault(0))
            }
        }
        val tintColor = mapOf(R.Tint.GREEN to R.nearestColor(0x55BB44, palette), R.Tint.PURPLE to R.nearestColor(0x660099, palette), R.Tint.RED to R.nearestColor(0xFF0045, palette))
        val nationColor = HashMap<String, Int>()
        for (pz in 0 until R.SIZE) for (px in 0 until R.SIZE) {
            val wx = x0 + px; val wz = z0 + pz
            val chx = wx shr 4; val chz = wz shr 4; val lx = wx and 15; val lz = wz and 15
            var v: Byte = 0
            if (drawTerrain) {
                val col = column(level, chx, chz, now)
                if (col != null) {
                    val i = lz * 16 + lx
                    val c = col.color[i].toInt() and 255
                    val shade = if (col.water[i] > 0) R.waterShade(col.water[i], px, pz) else {
                        val nz = wz - 1
                        val north = if (lz > 0) col.height[i - 16] else column(level, chx, nz shr 4, now)?.height?.get((nz and 15) * 16 + lx) ?: col.height[i]
                        R.landShade(col.height[i], north, px, pz)
                    }
                    v = R.packed(c, shade)
                }
            }
            val o = owner(chx, chz)
            if (o != null && R.borderAt(lx, lz, o, owner(chx - 1, chz), owner(chx + 1, chz), owner(chx, chz - 1), owner(chx, chz + 1), px, pz, o == mine)) {
                val nc = nationColor.getOrPut(o) { R.nearestColor(Nations.registry.nation(o)?.colorRgb ?: 0xFFFFFF, palette) }
                v = R.packed(nc, R.HIGH)
            } else if (withPattern && R.patternDot(px, pz)) {
                val t = tint(chx, chz)
                if (t != R.Tint.NONE) v = R.packed(tintColor[t]!!, R.HIGH)
            }
            img[pz * R.SIZE + px] = v
        }
        return img
    }

    // ------------------------------------------------------------------ 아이콘

    private fun banner(rgb: Int): Holder<MapDecorationType> = when (rgb) {
        0xFF0045 -> MapDecorationTypes.RED_BANNER
        0x3355BB -> MapDecorationTypes.BLUE_BANNER
        0xFFCC11 -> MapDecorationTypes.YELLOW_BANNER
        0x55BB44 -> MapDecorationTypes.LIME_BANNER
        0x660099 -> MapDecorationTypes.PURPLE_BANNER
        0x39C5BB -> MapDecorationTypes.CYAN_BANNER
        0xFF7E00 -> MapDecorationTypes.ORANGE_BANNER
        0xFFB4CC -> MapDecorationTypes.PINK_BANNER
        else -> MapDecorationTypes.WHITE_BANNER
    }

    internal fun icons(p: ServerPlayer, cx: Int, cz: Int): List<MapDecoration> {
        val out = ArrayList<MapDecoration>()
        val dim = p.level().dimension().identifier().toString()
        fun add(type: Holder<MapDecorationType>, x: Double, z: Double, rot: Byte = 8, name: Component? = null) {
            val dx = x - cx; val dz = z - cz
            if (!R.onMap(dx, dz)) return
            out += MapDecoration(type, R.iconCoord(dx), R.iconCoord(dz), rot, Optional.ofNullable(name))
        }
        // 수도(모든 나라의 깃발)
        for (n in Nations.registry.alive()) {
            val c = n.core ?: continue
            if (c.dimension == dim) add(banner(n.colorRgb), c.x + 0.5, c.z + 0.5, 8, Component.literal(n.name))
        }
        // 사망 지점(본인만)
        p.lastDeathLocation.ifPresent { gp ->
            if (gp.dimension().identifier().toString() == dim) add(MapDecorationTypes.RED_X, gp.pos().x + 0.5, gp.pos().z + 0.5)
        }
        // 근처 자국민(파란 표지). 다른 나라 사람은 그리지 않는다
        val mine = Nations.registry.nationOf(p.uuid)
        if (mine != null) for (o in p.level().players()) {
            if (o === p || o.isSpectator || o.uuid !in mine.members) continue
            add(MapDecorationTypes.BLUE_MARKER, o.x, o.z, R.iconRotation(o.yRot))
        }
        add(MapDecorationTypes.PLAYER, p.x, p.z, R.iconRotation(p.yRot))
        return out
    }

    // ------------------------------------------------------------------ 보내기

    private fun held(p: ServerPlayer): ItemStack? = listOf(p.offhandItem, p.mainHandItem).firstOrNull { isHandheld(it) }

    private fun tick(s: MinecraftServer) {
        val now = s.overworld().gameTime
        for (p in s.playerList.players) {
            val stack = held(p) ?: continue
            val id = stack.get(DataComponents.MAP_ID)?.id() ?: continue
            val level = p.level()
            val dim = level.dimension().identifier().toString()
            val v = views.getOrPut(p.uuid) { View() }
            var full = v.mapId != id || v.dim != dim || v.image == null || R.needsRecenter(v.cx, v.cz, p.x, p.z)
            if (full) { v.cx = p.blockX; v.cz = p.blockZ; v.dim = dim; v.mapId = id }
            var patch: MapItemSavedData.MapPatch? = null
            if (full || now - v.lastTerrain >= R.TERRAIN_REFRESH_TICKS) {
                val img = render(level, v.cx, v.cz, p.uuid, p.uuid in pattern)
                if (full || !img.contentEquals(v.image)) patch = MapItemSavedData.MapPatch(0, 0, R.SIZE, R.SIZE, img)
                v.image = img; v.lastTerrain = now
            }
            p.connection.send(ClientboundMapItemDataPacket(MapId(id), 0, false, Optional.of(icons(p, v.cx, v.cz)), Optional.ofNullable(patch)))
        }
    }

    /** 무늬를 켜고 끈 뒤 바로 다시 그리게. */
    private fun invalidate(p: ServerPlayer) { views[p.uuid]?.image = null }

    fun register() {
        UseItemCallback.EVENT.register { player, level, hand ->
            val p = player as? ServerPlayer ?: return@register InteractionResult.PASS
            val stack = p.getItemInHand(hand)
            when {
                stack.`is`(Items.MAP) -> {
                    val map = create(level as ServerLevel)
                    stack.consume(1, p)
                    if (stack.isEmpty) p.setItemInHand(hand, map) else p.inventory.placeItemBackInInventory(map, net.minecraft.util.Prediction.SERVER_ONLY)
                    p.sendSystemMessage(Gui.text("휴대 지도가 되었습니다. 보조손에 들면 주변이 그려집니다. 웅크리고 쓰면 감염 레벨 무늬를 켜고 끕니다.", Gui.rgb(0x39C5BB)), true)
                    InteractionResult.SUCCESS
                }
                isHandheld(stack) && p.isShiftKeyDown -> {
                    val on = if (pattern.remove(p.uuid)) false else { pattern += p.uuid; true }
                    invalidate(p)
                    p.sendSystemMessage(Gui.text(if (on) "감염 레벨 무늬를 켰습니다." else "감염 레벨 무늬를 껐습니다."), true)
                    InteractionResult.SUCCESS
                }
                else -> InteractionResult.PASS
            }
        }
        ServerTickEvents.END_SERVER_TICK.register { s ->
            if (s.tickCount % R.ICON_TICKS == 0) tick(s)
            if (s.tickCount % 6000 == 0) { val now = s.overworld().gameTime; cache.entries.removeIf { now - it.value.at > R.CHUNK_CACHE_TICKS * 4 } }
        }
        ServerPlayConnectionEvents.DISCONNECT.register { h, _ -> views.remove(h.player.uuid) }
    }
}
