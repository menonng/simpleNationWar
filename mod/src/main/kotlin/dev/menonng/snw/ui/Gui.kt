package dev.menonng.snw.ui

import dev.menonng.snw.info.InfoMenus
import net.minecraft.ChatFormatting
import net.minecraft.core.component.DataComponentType
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.SimpleContainer
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemLore
import net.minecraft.world.item.component.TooltipDisplay

/** 읽기 전용 상자 창(6줄) 도우미. 칸을 누르면 동작만 하고 아이템은 옮겨지지 않는다(InfoMenus.ReadOnlyMenu). */
class Gui(val title: Component) {
    val container = SimpleContainer(54)
    val actions = HashMap<Int, (ServerPlayer) -> Unit>()

    fun set(slot: Int, stack: ItemStack, action: ((ServerPlayer) -> Unit)? = null) {
        container.setItem(slot, stack)
        if (action != null) actions[slot] = action else actions.remove(slot)
    }

    fun fillBorder() {
        for (i in 0 until 54) if (container.getItem(i).isEmpty && (i < 9 || i >= 45 || i % 9 == 0 || i % 9 == 8)) container.setItem(i, filler())
    }

    fun open(p: ServerPlayer) { p.openMenu(SimpleMenuProvider({ id, inv, _ -> InfoMenus.ReadOnlyMenu(id, inv, container, actions) }, title)) }

    companion object {
        fun text(s: String, color: TextColor? = null): MutableComponent =
            Component.literal(s).withStyle(Style.EMPTY.withItalic(false).let { if (color != null) it.withColor(color) else it })
        fun rgb(v: Int) = TextColor.fromRgb(v)
        fun gray(s: String) = text(s, TextColor.fromLegacyFormat(ChatFormatting.GRAY))

        fun item(item: Item, name: Component, lore: List<Component> = emptyList(), glint: Boolean = false, hide: Set<DataComponentType<*>> = emptySet(), count: Int = 1): ItemStack =
            ItemStack(item, count.coerceIn(1, 99)).apply {
                set(DataComponents.CUSTOM_NAME, name)
                if (lore.isNotEmpty()) set(DataComponents.LORE, ItemLore(lore))
                if (glint) set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
                if (hide.isNotEmpty()) set(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay(false, LinkedHashSet(hide)))
            }

        fun filler(): ItemStack = ItemStack(Items.STAINED_GLASS_PANE.black()).apply { set(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay(true, LinkedHashSet())) }

        fun banner(rgb: Int): Item = when (rgb) {
            0xFF0045 -> Items.BANNER.red()
            0x3355BB -> Items.BANNER.blue()
            0xFFCC11 -> Items.BANNER.yellow()
            else -> Items.BANNER.white()
        }
    }
}
