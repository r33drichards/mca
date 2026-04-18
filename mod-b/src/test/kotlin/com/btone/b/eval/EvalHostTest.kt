package com.btone.b.eval

import com.btone.b.events.EventBus
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EvalHostTest {
    // The fake context deliberately throws on `mc` access — tests here must not reference `mc`.
    // If a future test needs `mc`, it belongs in an integration harness with a real MinecraftClient.
    private val fakeCtx = object : EvalContext() {
        override val mc: net.minecraft.client.MinecraftClient
            get() = error("not in test")
        override val baritone: baritone.api.IBaritone? = null
        override val meteor: com.btone.b.meteor.MeteorFacade? = null
        override val events: EventBus = EventBus()
        override fun registerCleanup(fn: () -> Unit) {}
    }

    @Test fun `evaluates simple expression`() {
        val r = EvalHost.eval("1 + 2", fakeCtx)
        assertTrue(r.ok, r.errorMsg ?: "")
        assertEquals(3, r.value)
    }

    @Test fun `captures stdout`() {
        val r = EvalHost.eval("""println("hi"); 42""", fakeCtx)
        assertTrue(r.ok, r.errorMsg ?: "")
        assertEquals("hi\n", r.stdout)
        assertEquals(42, r.value)
    }

    @Test fun `reports compile errors`() {
        val r = EvalHost.eval("this is not kotlin", fakeCtx)
        assertFalse(r.ok)
        val msg = r.errorMsg
        assertNotNull(msg)
        assertTrue(msg.isNotEmpty())
    }
}
