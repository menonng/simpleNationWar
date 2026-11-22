package dev.menonng.snw

import dev.menonng.snw.chat.ChatRouting
import dev.menonng.snw.info.InfoMenus
import dev.menonng.snw.nation.NationCommands
import dev.menonng.snw.nation.Nations
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import org.slf4j.LoggerFactory

object Snw : ModInitializer {
    const val MOD_ID = "snw"
    val log = LoggerFactory.getLogger("simpleNationWar")

    override fun onInitialize() {
        log.info("simpleNationWar 초기화")
        SnwConfig.load()
        Nations.register()
        NationCommands.register()
        dev.menonng.snw.protect.Protection.register()
        dev.menonng.snw.core.CoreSystem.register()
        ChatRouting.register()
        InfoMenus.register()
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            if (System.getProperty("snw.smoke") == "true") {
                val failures = Smoke.run(server)
                if (failures.isEmpty()) log.info("[simpleNationWar] smoke test passed")
                else log.error("[simpleNationWar] smoke test failed: {}", failures)
                server.halt(false)
            }
        }
    }
}
