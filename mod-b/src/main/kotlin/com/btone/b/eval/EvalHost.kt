package com.btone.b.eval

import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.implicitReceivers
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate

object EvalHost {
    // Kotlin scripting is not thread-safe across a shared compilation cache; a single host is
    // fine here because MCP calls arrive serially through the HTTP handler.
    private val host = BasicJvmScriptingHost()
    private val compilationCfg = createJvmCompilationConfigurationFromTemplate<BtoneScript>()

    data class Result(
        val ok: Boolean,
        val value: Any?,
        val stdout: String,
        val stderr: String,
        val errorMsg: String?,
    )

    fun eval(source: String, ctx: EvalContext): Result {
        val outBuf = StringBuilder()
        val errBuf = StringBuilder()
        val savedOut = System.out
        val savedErr = System.err
        val tee = { target: StringBuilder ->
            java.io.PrintStream(
                object : java.io.OutputStream() {
                    override fun write(b: Int) {
                        target.append(b.toChar())
                    }
                },
                true,
            )
        }
        System.setOut(tee(outBuf))
        System.setErr(tee(errBuf))
        try {
            val evalCfg = ScriptEvaluationConfiguration {
                implicitReceivers(ctx)
            }
            val res = host.eval(source.toScriptSource(), compilationCfg, evalCfg)
            return when (res) {
                is ResultWithDiagnostics.Success -> {
                    val returnValue = when (val rv = res.value.returnValue) {
                        is ResultValue.Value -> rv.value
                        is ResultValue.Unit -> null
                        is ResultValue.NotEvaluated -> null
                        is ResultValue.Error -> null
                    }
                    val errorMsg = (res.value.returnValue as? ResultValue.Error)
                        ?.error?.message
                    val ok = res.value.returnValue !is ResultValue.Error
                    Result(ok, returnValue, outBuf.toString(), errBuf.toString(), errorMsg)
                }
                is ResultWithDiagnostics.Failure -> Result(
                    ok = false,
                    value = null,
                    stdout = outBuf.toString(),
                    stderr = errBuf.toString(),
                    errorMsg = res.reports.joinToString("\n") { it.message },
                )
            }
        } finally {
            System.setOut(savedOut)
            System.setErr(savedErr)
        }
    }
}
