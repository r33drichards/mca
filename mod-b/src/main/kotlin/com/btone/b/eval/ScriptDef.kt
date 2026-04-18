package com.btone.b.eval

import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.implicitReceivers
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm

// Note (deviation from plan): BtoneScript has no constructor arg. Implicit receivers are supplied
// at eval time via ScriptEvaluationConfiguration { implicitReceivers(ctx) }, not as a constructor
// parameter on the script class. Having a ctor arg here confuses the compiler host and produces
// "no such receiver" errors when the script body references `mc`, `baritone`, etc.
@KotlinScript(
    displayName = "Btone eval script",
    fileExtension = "btone.kts",
    compilationConfiguration = BtoneScriptCompilation::class,
)
abstract class BtoneScript

object BtoneScriptCompilation : ScriptCompilationConfiguration({
    implicitReceivers(EvalContext::class)
    jvm {
        dependenciesFromCurrentContext(wholeClasspath = true)
    }
    defaultImports(
        "net.minecraft.client.MinecraftClient",
        "baritone.api.BaritoneAPI",
        "baritone.api.pathing.goals.*",
        "net.minecraft.util.math.BlockPos",
    )
})
