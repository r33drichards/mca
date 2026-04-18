package com.btone.b.meteor

class MeteorFacade private constructor(
    private val modulesClass: Class<*>,
    private val modulesInstance: Any,
) {
    fun list(): List<String> {
        val getAll = modulesClass.getMethod("getAll")
        @Suppress("UNCHECKED_CAST")
        val modules = getAll.invoke(modulesInstance) as Collection<Any>
        return modules.mapNotNull { m -> nameOf(m) }
    }

    private fun nameOf(m: Any): String? {
        // Meteor modules expose `name` as a public field, not a getter.
        return runCatching { m.javaClass.getField("name").get(m) as? String }.getOrNull()
            ?: runCatching { m.javaClass.getMethod("getName").invoke(m) as? String }.getOrNull()
    }

    fun toggle(name: String, enable: Boolean?) {
        val get = modulesClass.getMethod("get", String::class.java)
        val m = get.invoke(modulesInstance, name) ?: throw IllegalArgumentException("no module $name")
        val cls = m.javaClass
        val toggleMethod = findMethod(cls, "toggle")
            ?: throw IllegalStateException("no toggle() on $name")
        if (enable == null) {
            toggleMethod.invoke(m)
        } else {
            val isActive = (findMethod(cls, "isActive")?.invoke(m) as? Boolean) ?: return
            if (isActive != enable) toggleMethod.invoke(m)
        }
    }

    private fun findMethod(cls: Class<*>, name: String): java.lang.reflect.Method? {
        var c: Class<*>? = cls
        while (c != null) {
            try { return c.getDeclaredMethod(name).also { it.isAccessible = true } } catch (_: NoSuchMethodException) {}
            c = c.superclass
        }
        return null
    }

    companion object {
        fun tryGet(): MeteorFacade? = try {
            val cls = Class.forName("meteordevelopment.meteorclient.systems.modules.Modules")
            val inst = cls.getMethod("get").invoke(null)
            MeteorFacade(cls, inst)
        } catch (_: Throwable) { null }
    }
}
