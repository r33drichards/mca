package com.btone.b

import net.minecraft.client.MinecraftClient
import java.util.concurrent.TimeUnit

object ClientThread {
    inline fun <T> call(timeoutMs: Long = 2_000, crossinline block: () -> T): T {
        val mc = MinecraftClient.getInstance()
        return mc.submit<T> { block() }.get(timeoutMs, TimeUnit.MILLISECONDS)
    }

    inline fun run(crossinline block: () -> Unit) {
        MinecraftClient.getInstance().execute { block() }
    }
}
