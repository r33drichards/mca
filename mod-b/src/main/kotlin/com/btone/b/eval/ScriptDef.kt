package com.btone.b.eval

import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.implicitReceivers
import kotlin.script.experimental.jvm.dependenciesFromClassloader
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
        // Use the mod's own classloader (Fabric's Knot). dependenciesFromCurrentContext
        // resolves to the thread's context loader, which under Fabric is the system loader
        // and doesn't see Minecraft or Baritone classes.
        dependenciesFromClassloader(
            classLoader = BtoneScript::class.java.classLoader!!,
            wholeClasspath = true,
        )
    }
    defaultImports(
        "com.btone.b.api.BtoneApi",
        "com.btone.b.events.EventBus",
    )
})
