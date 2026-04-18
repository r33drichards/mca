package com.btone.b.eval

import baritone.api.BaritoneAPI
import baritone.api.IBaritone
import com.btone.b.events.EventBus
import net.minecraft.client.MinecraftClient

class LiveEvalContext(override val events: EventBus) : EvalContext() {
    override val mc: MinecraftClient get() = MinecraftClient.getInstance()
    override val baritone: IBaritone?
        get() = try {
            BaritoneAPI.getProvider().primaryBaritone
        } catch (t: Throwable) {
            null
        }
    override val meteor: com.btone.b.meteor.MeteorFacade?
        get() = com.btone.b.meteor.MeteorFacade.tryGet()

    private val cleanups = java.util.concurrent.ConcurrentLinkedDeque<() -> Unit>()
    override fun registerCleanup(fn: () -> Unit) { cleanups.addLast(fn) }

    fun runCleanups() {
        while (true) {
            (cleanups.pollLast() ?: break).runCatching { invoke() }
        }
    }
}
