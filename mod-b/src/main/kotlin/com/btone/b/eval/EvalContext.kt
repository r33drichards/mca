package com.btone.b.eval

// The receiver available inside every eval. All public members appear as unqualified identifiers
// thanks to ScriptCompilationConfiguration.implicitReceivers(EvalContext::class) +
// ScriptEvaluationConfiguration.implicitReceivers(ctx) at eval time.
abstract class EvalContext {
    abstract val mc: net.minecraft.client.MinecraftClient
    abstract val baritone: baritone.api.IBaritone?
    abstract val meteor: com.btone.b.meteor.MeteorFacade?
    abstract val events: com.btone.b.events.EventBus
    abstract fun registerCleanup(fn: () -> Unit)
}
