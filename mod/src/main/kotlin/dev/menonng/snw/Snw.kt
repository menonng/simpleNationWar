package dev.menonng.snw

import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import org.slf4j.LoggerFactory

object Snw : ModInitializer {
    const val MOD_ID = "snw"
    val log = LoggerFactory.getLogger("simpleNationWar")

    override fun onInitialize() {
        log.info("simpleNationWar 초기화")
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            if (System.getProperty("snw.smoke") == "true") {
                log.info("[simpleNationWar] smoke test passed")
                server.halt(false)
            }
        }
    }
}
