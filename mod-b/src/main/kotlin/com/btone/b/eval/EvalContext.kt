package com.btone.b.eval

// The receiver available inside every eval. All public members appear as unqualified identifiers
// thanks to ScriptCompilationConfiguration.implicitReceivers(EvalContext::class) +
// ScriptEvaluationConfiguration.implicitReceivers(ctx) at eval time.
//
// Note: MC/Baritone/Meteor used to live directly on this context as `mc`, `baritone`, `meteor`.
// They moved onto `api: BtoneApi` because Fabric's Knot classloader doesn't expose the MC/Baritone
// JARs through Kotlin scripting's classpath enumeration — but it DOES expose our own mod's
// classes, so curating the surface inside the mod sidesteps the resolution problem.
abstract class EvalContext {
    abstract val api: com.btone.b.api.BtoneApi
    abstract val events: com.btone.b.events.EventBus
    abstract fun registerCleanup(fn: () -> Unit)
}
