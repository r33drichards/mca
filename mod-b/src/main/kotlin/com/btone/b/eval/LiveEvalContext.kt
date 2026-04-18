package com.btone.b.eval

import com.btone.b.api.BtoneApi
import com.btone.b.events.EventBus

class LiveEvalContext(
    override val events: EventBus,
    override val api: BtoneApi,
) : EvalContext() {
    private val cleanups = java.util.concurrent.ConcurrentLinkedDeque<() -> Unit>()
    override fun registerCleanup(fn: () -> Unit) { cleanups.addLast(fn) }

    fun runCleanups() {
        while (true) {
            (cleanups.pollLast() ?: break).runCatching { invoke() }
        }
    }
}
