package com.btone.b.api

import baritone.api.BaritoneAPI
import baritone.api.IBaritone
import baritone.api.pathing.goals.GoalBlock
import baritone.api.pathing.goals.GoalXZ
import baritone.api.pathing.goals.GoalYLevel
import baritone.api.utils.SettingsUtil
import com.btone.b.ClientThread
import com.btone.b.events.EventBus
import com.btone.b.meteor.MeteorFacade
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.block.Blocks
import net.minecraft.client.MinecraftClient
import net.minecraft.item.ItemStack
import net.minecraft.registry.Registries
import net.minecraft.util.Identifier
import net.minecraft.util.hit.BlockHitResult
import net.minecraft.util.hit.EntityHitResult
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.BlockPos

// Curated, mod-side API exposed to Kotlin scripts via the implicit receiver chain on EvalContext.
// All MC-touching code lives here (and gets remapped yarn->intermediary by Loom at build time),
// so scripts never reference MC types directly. See plan: lets-figure-out-how-optimized-tome.md.
class BtoneApi(private val events: EventBus) {

    // ----- Player state -----------------------------------------------------

    private inline fun <T> read(crossinline fn: (MinecraftClient) -> T): T =
        ClientThread.call(READ_TIMEOUT_MS) { fn(MinecraftClient.getInstance()) }

    val inWorld: Boolean get() = read { it.player != null && it.world != null }
    val playerName: String? get() = read { it.player?.gameProfile?.name }
    val playerPos: Triple<Double, Double, Double>? get() = read { it.player?.let { p -> Triple(p.x, p.y, p.z) } }
    val playerBlockPos: Triple<Int, Int, Int>? get() = read { it.player?.let { p -> Triple(p.blockX, p.blockY, p.blockZ) } }
    val playerRotation: Pair<Float, Float>? get() = read { it.player?.let { p -> Pair(p.yaw, p.pitch) } }
    val health: Float? get() = read { it.player?.health }
    val food: Int? get() = read { it.player?.hungerManager?.foodLevel }
    val dim: String? get() = read { it.world?.registryKey?.value?.toString() }

    // ----- Inventory --------------------------------------------------------

    data class SlotInfo(val slot: Int, val id: String, val count: Int)

    val hotbarSlot: Int?
        get() = ClientThread.call(READ_TIMEOUT_MS) {
            MinecraftClient.getInstance().player?.inventory?.selectedSlot
        }

    fun inventory(): List<SlotInfo> = ClientThread.call(READ_TIMEOUT_MS) {
        val inv = MinecraftClient.getInstance().player?.inventory ?: return@call emptyList()
        val out = ArrayList<SlotInfo>()
        for (i in 0 until inv.size()) {
            val s = inv.getStack(i)
            if (s.isEmpty) continue
            out.add(SlotInfo(i, stackId(s), s.count))
        }
        out
    }

    fun equipped(): Pair<SlotInfo?, SlotInfo?> = ClientThread.call(READ_TIMEOUT_MS) {
        val p = MinecraftClient.getInstance().player ?: return@call Pair(null, null)
        val main = p.mainHandStack
        val off = p.offHandStack
        val mainInfo = if (main.isEmpty) null else SlotInfo(p.inventory.selectedSlot, stackId(main), main.count)
        val offInfo = if (off.isEmpty) null else SlotInfo(-1, stackId(off), off.count)
        Pair(mainInfo, offInfo)
    }

    private fun stackId(s: ItemStack): String = Registries.ITEM.getId(s.item).toString()

    // ----- World read -------------------------------------------------------

    fun block(x: Int, y: Int, z: Int): String? = ClientThread.call(READ_TIMEOUT_MS) {
        val w = MinecraftClient.getInstance().world ?: return@call null
        val state = w.getBlockState(BlockPos(x, y, z))
        Registries.BLOCK.getId(state.block).toString()
    }

    fun blocksAround(radius: Int = 3): List<Pair<Triple<Int, Int, Int>, String>> = ClientThread.call(READ_TIMEOUT_MS) {
        val mc = MinecraftClient.getInstance()
        val p = mc.player ?: return@call emptyList()
        val w = mc.world ?: return@call emptyList()
        val r = radius.coerceIn(0, 8)
        val cx = p.blockX; val cy = p.blockY; val cz = p.blockZ
        val out = ArrayList<Pair<Triple<Int, Int, Int>, String>>()
        val air = Blocks.AIR
        for (dx in -r..r) for (dy in -r..r) for (dz in -r..r) {
            val pos = BlockPos(cx + dx, cy + dy, cz + dz)
            val st = w.getBlockState(pos)
            if (st.block === air) continue
            out.add(Triple(pos.x, pos.y, pos.z) to Registries.BLOCK.getId(st.block).toString())
        }
        out
    }

    data class RaycastHit(
        val type: String,
        val x: Int?,
        val y: Int?,
        val z: Int?,
        val side: String?,
        val id: String?,
    )

    fun raycast(maxDist: Double = 5.0): RaycastHit? = ClientThread.call(READ_TIMEOUT_MS) {
        // Use the client's already-computed crosshair target when within range; otherwise null.
        // (Building a fresh RaycastContext from scratch is doable but doubles LOC and we don't
        // need a different ray than the one MC just computed.)
        val mc = MinecraftClient.getInstance()
        val p = mc.player ?: return@call null
        val ht = mc.crosshairTarget ?: return@call null
        val pos = p.eyePos
        if (pos.squaredDistanceTo(ht.pos) > maxDist * maxDist) return@call null
        when (ht.type) {
            HitResult.Type.BLOCK -> {
                val bh = ht as BlockHitResult
                val bp = bh.blockPos
                val state = mc.world?.getBlockState(bp)
                val id = state?.let { Registries.BLOCK.getId(it.block).toString() }
                RaycastHit("block", bp.x, bp.y, bp.z, bh.side.asString(), id)
            }
            HitResult.Type.ENTITY -> {
                val eh = ht as EntityHitResult
                val e = eh.entity
                RaycastHit("entity", null, null, null, null, Registries.ENTITY_TYPE.getId(e.type).toString())
            }
            else -> RaycastHit("miss", null, null, null, null, null)
        }
    }

    // ----- Chat -------------------------------------------------------------

    fun chat(text: String) {
        ClientThread.call(WRITE_TIMEOUT_MS) {
            val nh = MinecraftClient.getInstance().player?.networkHandler ?: return@call Unit
            if (text.startsWith("/")) nh.sendChatCommand(text.substring(1)) else nh.sendChatMessage(text)
            Unit
        }
    }

    fun recentChat(n: Int = 50): List<String> {
        val k = n.coerceAtLeast(0)
        synchronized(chatBuf) {
            if (k >= chatBuf.size) return chatBuf.toList()
            val skip = chatBuf.size - k
            return chatBuf.drop(skip)
        }
    }

    // ----- Baritone ---------------------------------------------------------

    private fun primaryBaritone(): IBaritone? = try {
        BaritoneAPI.getProvider().primaryBaritone
    } catch (_: Throwable) { null }

    private inline fun bar(crossinline fn: (IBaritone) -> Unit) {
        ClientThread.call(WRITE_TIMEOUT_MS) {
            try { primaryBaritone()?.let(fn) } catch (_: Throwable) {}
            Unit
        }
    }

    fun goto(x: Int, z: Int) = bar { it.customGoalProcess.setGoalAndPath(GoalXZ(x, z)) }
    fun goto(x: Int, y: Int, z: Int) = bar { it.customGoalProcess.setGoalAndPath(GoalBlock(x, y, z)) }
    fun gotoY(y: Int) = bar { it.customGoalProcess.setGoalAndPath(GoalYLevel(y)) }
    fun stopBaritone() = bar { it.pathingBehavior.cancelEverything() }

    data class BaritoneStatus(val active: Boolean, val goal: String?, val hasBaritone: Boolean)

    fun baritoneStatus(): BaritoneStatus = ClientThread.call(READ_TIMEOUT_MS) {
        val b = primaryBaritone() ?: return@call BaritoneStatus(active = false, goal = null, hasBaritone = false)
        val active = try { b.pathingBehavior.isPathing } catch (_: Throwable) { false }
        val goal = try { b.customGoalProcess?.goal?.toString() ?: b.pathingBehavior.goal?.toString() } catch (_: Throwable) { null }
        BaritoneStatus(active = active, goal = goal, hasBaritone = true)
    }

    fun mine(blockIds: List<String>, quantity: Int = -1) {
        ClientThread.call(WRITE_TIMEOUT_MS) {
            try {
                val b = primaryBaritone() ?: return@call Unit
                val blocks = blockIds.mapNotNull { id ->
                    Identifier.tryParse(id)?.let { Registries.BLOCK.get(it) }
                }.filter { it !== Blocks.AIR }
                if (blocks.isEmpty()) return@call Unit
                @Suppress("SpreadOperator")
                b.mineProcess.mine(quantity, *blocks.toTypedArray())
            } catch (_: Throwable) {}
            Unit
        }
    }

    fun follow(entityName: String) {
        ClientThread.call(WRITE_TIMEOUT_MS) {
            try {
                primaryBaritone()?.followProcess?.follow { e -> e.name.string == entityName }
            } catch (_: Throwable) {}
            Unit
        }
    }

    fun setBaritoneSetting(key: String, value: String) {
        try {
            val settings = BaritoneAPI.getSettings() ?: return
            val setting = settings.byLowerName[key.lowercase()] ?: return
            try {
                SettingsUtil.parseAndApply(settings, setting.getName(), value)
            } catch (_: Throwable) {
                // best-effort; some setting types aren't string-parseable
            }
        } catch (_: Throwable) {}
    }

    // ----- Meteor wrapper ---------------------------------------------------

    class MeteorApi(private val facade: MeteorFacade?) {
        val present: Boolean get() = facade != null
        fun modules(): List<String> = facade?.list() ?: emptyList()
        fun enable(name: String) { facade?.let { runCatching { it.toggle(name, true) } } }
        fun disable(name: String) { facade?.let { runCatching { it.toggle(name, false) } } }
        fun toggle(name: String) { facade?.let { runCatching { it.toggle(name, null) } } }
    }

    val meteor: MeteorApi = MeteorApi(MeteorFacade.tryGet())

    // ----- Events -----------------------------------------------------------

    fun emit(type: String, payload: Map<String, Any?> = emptyMap()) {
        events.emit(type, payload)
    }

    // ----- Escape hatch -----------------------------------------------------

    val mc: Any get() = MinecraftClient.getInstance()

    val baritone: Any? get() = primaryBaritone()

    /**
     * Reflectively call a yarn-named method against [receiver] using the runtime mappings.
     * [yarnClass] is dot-form (e.g. `net.minecraft.client.MinecraftClient`).
     * [descriptor] is a JVM method descriptor in yarn class names (e.g. `()Lnet/minecraft/client/network/ClientPlayerEntity;`).
     */
    fun callYarn(receiver: Any, yarnClass: String, yarnMethod: String, descriptor: String, vararg args: Any?): Any? {
        try {
            val resolver = FabricLoader.getInstance().mappingResolver
            val mappedClass = resolver.mapClassName("named", yarnClass)
            val mappedMethod = resolver.mapMethodName("named", yarnClass.replace('.', '/'), yarnMethod, descriptor)
            val cl = receiver.javaClass.classLoader
            val paramTypes = parseParamTypes(descriptor, resolver, cl)
            val m = Class.forName(mappedClass, true, cl).getDeclaredMethod(mappedMethod, *paramTypes.toTypedArray())
            m.isAccessible = true
            return m.invoke(receiver, *args)
        } catch (e: Throwable) {
            throw RuntimeException("callYarn($yarnClass#$yarnMethod) failed: ${e.message}", e)
        }
    }

    // Parses param types out of a JVM method descriptor; the return type isn't needed for invoke().
    private fun parseParamTypes(
        desc: String,
        resolver: net.fabricmc.loader.api.MappingResolver,
        cl: ClassLoader,
    ): List<Class<*>> {
        require(desc.startsWith("(")) { "bad descriptor: $desc" }
        val close = desc.indexOf(')').also { require(it > 0) { "bad descriptor: $desc" } }
        val params = ArrayList<Class<*>>()
        var i = 1
        while (i < close) {
            var arrayDims = 0
            while (desc[i] == '[') { arrayDims++; i++ }
            val (base, next) = when (val c = desc[i]) {
                'Z' -> java.lang.Boolean.TYPE to i + 1
                'B' -> java.lang.Byte.TYPE to i + 1
                'C' -> java.lang.Character.TYPE to i + 1
                'S' -> java.lang.Short.TYPE to i + 1
                'I' -> java.lang.Integer.TYPE to i + 1
                'J' -> java.lang.Long.TYPE to i + 1
                'F' -> java.lang.Float.TYPE to i + 1
                'D' -> java.lang.Double.TYPE to i + 1
                'V' -> java.lang.Void.TYPE to i + 1
                'L' -> {
                    val end = desc.indexOf(';', i + 1).also { require(it > 0) { "unterminated L at $i in $desc" } }
                    val yarn = desc.substring(i + 1, end).replace('/', '.')
                    Class.forName(resolver.mapClassName("named", yarn), false, cl) to end + 1
                }
                else -> error("bad descriptor char '$c' at $i in $desc")
            }
            var t: Class<*> = base
            repeat(arrayDims) { t = java.lang.reflect.Array.newInstance(t, 0).javaClass }
            params.add(t)
            i = next
        }
        return params
    }

    companion object {
        private const val READ_TIMEOUT_MS = 2_000L
        private const val WRITE_TIMEOUT_MS = 1_000L

        // Reserved — container ops not yet implemented but kept here so callers know the budget.
        @Suppress("unused") private const val CONTAINER_TIMEOUT_MS = 3_000L

        private const val CHAT_BUF_MAX = 256
        private val chatBuf = ArrayDeque<String>()

        @JvmStatic fun recordChat(line: String) {
            synchronized(chatBuf) {
                chatBuf.addLast(line)
                while (chatBuf.size > CHAT_BUF_MAX) chatBuf.removeFirst()
            }
        }
    }
}
