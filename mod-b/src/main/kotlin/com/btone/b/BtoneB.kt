package com.btone.b

import net.fabricmc.api.ClientModInitializer
import org.slf4j.LoggerFactory

class BtoneB : ClientModInitializer {
    companion object { val log = LoggerFactory.getLogger("btone-b") }
    override fun onInitializeClient() { log.info("btone-mod-b initialized") }
}
