package dev.menonng.snw.ui

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
    /** 칸 동작: (플레이어, 버튼 0=좌클릭 1=우클릭). */
    val actions = HashMap<Int, (ServerPlayer, Int) -> Unit>()

    fun set(slot: Int, stack: ItemStack, action: ((ServerPlayer) -> Unit)? = null) {
        container.setItem(slot, stack)
        if (action != null) actions[slot] = { p, _ -> action(p) } else actions.remove(slot)
    }

    fun setClick(slot: Int, stack: ItemStack, action: (ServerPlayer, Int) -> Unit) { container.setItem(slot, stack); actions[slot] = action }

    fun fillBorder() {
        for (i in 0 until 54) if (container.getItem(i).isEmpty && (i < 9 || i >= 45 || i % 9 == 0 || i % 9 == 8)) container.setItem(i, filler())
    }

    fun open(p: ServerPlayer) { p.openMenu(SimpleMenuProvider({ id, inv, _ -> Menu(id, inv, container, actions) }, title)) }

    /** 읽기 전용 6줄 상자: 서버는 아무것도 옮기지 않고 칸 동작만 부른다. */
    class Menu(id: Int, inv: net.minecraft.world.entity.player.Inventory, container: SimpleContainer, private val actions: Map<Int, (ServerPlayer, Int) -> Unit>) :
        net.minecraft.world.inventory.ChestMenu(net.minecraft.world.inventory.MenuType.GENERIC_9x6, id, inv, container, 6) {
        override fun clicked(slotId: Int, button: Int, input: net.minecraft.world.inventory.ContainerInput, player: net.minecraft.world.entity.player.Player) {
            sendAllDataToRemote()
            if (player !is ServerPlayer) return
            if (input != net.minecraft.world.inventory.ContainerInput.PICKUP && input != net.minecraft.world.inventory.ContainerInput.QUICK_MOVE) return
            actions[slotId]?.invoke(player, button)
        }
        override fun quickMoveStack(player: net.minecraft.world.entity.player.Player, index: Int): ItemStack = ItemStack.EMPTY
        override fun stillValid(player: net.minecraft.world.entity.player.Player): Boolean = true
        override fun canTakeItemForPickAll(stack: ItemStack, slot: net.minecraft.world.inventory.Slot): Boolean = false
        override fun canDragTo(slot: net.minecraft.world.inventory.Slot): Boolean = false
    }

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
