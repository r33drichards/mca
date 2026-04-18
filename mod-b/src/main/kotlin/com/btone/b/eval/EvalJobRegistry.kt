package com.btone.b.eval

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object EvalJobRegistry {
    data class Snapshot(
        val state: String,
        val value: Any?,
        val stdout: String,
        val stderr: String,
        val error: String?,
    )

    private val jobs = ConcurrentHashMap<String, AtomicReference<Snapshot>>()
    private val executor = Executors.newCachedThreadPool(object : ThreadFactory {
        private var n = 0
        @Synchronized
        override fun newThread(r: Runnable): Thread {
            val t = Thread(r, "btone-eval-${n++}")
            t.isDaemon = true
            return t
        }
    })

    fun start(source: String, timeoutMs: Long, ctxFactory: () -> LiveEvalContext): String {
        val id = UUID.randomUUID().toString()
        val ref = AtomicReference(Snapshot(state = "running", value = null, stdout = "", stderr = "", error = null))
        jobs[id] = ref

        val future = executor.submit {
            val ctx = try {
                ctxFactory()
            } catch (t: Throwable) {
                ref.set(Snapshot("error", null, "", "", "ctx init failed: ${t.message ?: t.javaClass.simpleName}"))
                return@submit
            }
            val res = try {
                EvalHost.eval(source, ctx)
            } catch (t: Throwable) {
                ref.set(Snapshot("error", null, "", "", t.message ?: t.javaClass.simpleName))
                return@submit
            }
            val state = if (res.ok) "done" else "error"
            ref.set(Snapshot(state, res.value, res.stdout, res.stderr, res.errorMsg))
        }

        // Timeout watchdog: if the job is still running past timeoutMs, cancel and mark timeout.
        executor.submit {
            try {
                future.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (t: Throwable) {
                val current = ref.get()
                if (current.state == "running") {
                    future.cancel(true)
                    ref.set(Snapshot("timeout", null, current.stdout, current.stderr, "timeout after ${timeoutMs}ms"))
                }
            }
        }

        return id
    }

    fun get(id: String): Snapshot? = jobs[id]?.get()
}
