package com.btone.b

import net.minecraft.client.MinecraftClient
import java.util.concurrent.TimeUnit

object ClientThread {
    inline fun <T> call(timeoutMs: Long = 2_000, crossinline block: () -> T): T {
        val mc = MinecraftClient.getInstance()
        // Re-entry guard: when the caller is already on the client thread, run inline.
        // Without this, mc.submit().get() from inside the client thread deadlocks on itself.
        return if (mc.isOnThread) block()
        else mc.submit<T> { block() }.get(timeoutMs, TimeUnit.MILLISECONDS)
    }

    inline fun run(crossinline block: () -> Unit) {
        val mc = MinecraftClient.getInstance()
        if (mc.isOnThread) block() else mc.execute { block() }
    }
}
