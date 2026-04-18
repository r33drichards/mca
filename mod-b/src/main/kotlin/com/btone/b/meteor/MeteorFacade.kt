package com.btone.b.meteor

class MeteorFacade private constructor(
    private val modulesClass: Class<*>,
    private val modulesInstance: Any,
) {
    fun list(): List<String> {
        val getAll = modulesClass.getMethod("getAll")
        @Suppress("UNCHECKED_CAST")
        val modules = getAll.invoke(modulesInstance) as Collection<Any>
        return modules.map { m -> m.javaClass.getMethod("getName").invoke(m) as String }
    }

    fun toggle(name: String, enable: Boolean?) {
        val get = modulesClass.getMethod("get", String::class.java)
        val m = get.invoke(modulesInstance, name) ?: throw IllegalArgumentException("no module $name")
        if (enable == null) {
            m.javaClass.getMethod("toggle").invoke(m)
        } else {
            val isActive = m.javaClass.getMethod("isActive").invoke(m) as Boolean
            if (isActive != enable) m.javaClass.getMethod("toggle").invoke(m)
        }
    }

    companion object {
        fun tryGet(): MeteorFacade? = try {
            val cls = Class.forName("meteordevelopment.meteorclient.systems.modules.Modules")
            val inst = cls.getMethod("get").invoke(null)
            MeteorFacade(cls, inst)
        } catch (_: Throwable) { null }
    }
}
