package com.btone.c.meteor;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Reflection wrapper around Meteor Client. We don't compile against Meteor,
 * because (a) it's an optional runtime dep and (b) we don't want to track
 * its API across versions.
 *
 * Lessons baked in (from mod-b production smoke):
 * - Meteor Module exposes {@code public final String name} as a field, not via
 *   {@code getName()}. We probe field-first, then fall back to method.
 * - {@code findMethod} walks the class hierarchy because {@code toggle} /
 *   {@code isActive} live on Module (the superclass) on most builds.
 * - {@link #tryGet()} should be called per-access (don't cache a null) because
 *   Meteor may not have finished initializing when our mod did.
 */
public final class MeteorFacade {
    private final Class<?> modulesClass;
    private final Object modulesInstance;

    private MeteorFacade(Class<?> modulesClass, Object modulesInstance) {
        this.modulesClass = modulesClass;
        this.modulesInstance = modulesInstance;
    }

    public List<String> list() throws Exception {
        Method getAll = modulesClass.getMethod("getAll");
        @SuppressWarnings("unchecked")
        Collection<Object> modules = (Collection<Object>) getAll.invoke(modulesInstance);
        List<String> out = new ArrayList<>(modules.size());
        for (Object m : modules) {
            String n = nameOf(m);
            if (n != null) out.add(n);
        }
        return out;
    }

    public void toggle(String name, Boolean enable) throws Exception {
        Method get = modulesClass.getMethod("get", String.class);
        Object m = get.invoke(modulesInstance, name);
        if (m == null) throw new IllegalArgumentException("no_module:" + name);
        Class<?> cls = m.getClass();
        Method toggleMethod = findMethod(cls, "toggle");
        if (toggleMethod == null) throw new IllegalStateException("no_toggle_method:" + name);
        if (enable == null) {
            toggleMethod.invoke(m);
            return;
        }
        Method isActive = findMethod(cls, "isActive");
        Boolean active = (isActive == null) ? null : (Boolean) isActive.invoke(m);
        if (active == null || active.booleanValue() != enable.booleanValue()) {
            toggleMethod.invoke(m);
        }
    }

    public boolean isActive(String name) throws Exception {
        Method get = modulesClass.getMethod("get", String.class);
        Object m = get.invoke(modulesInstance, name);
        if (m == null) throw new IllegalArgumentException("no_module:" + name);
        Method isActive = findMethod(m.getClass(), "isActive");
        if (isActive == null) return false;
        return Boolean.TRUE.equals(isActive.invoke(m));
    }

    /**
     * List every setting name on a module across all SettingGroups.
     * Settings is Iterable&lt;SettingGroup&gt;; SettingGroup is Iterable&lt;Setting&gt;.
     * Each Setting has a public field {@code name}.
     */
    public List<String> listSettings(String moduleName) throws Exception {
        Object module = moduleByName(moduleName);
        Object settings = module.getClass().getField("settings").get(module);
        List<String> out = new ArrayList<>();
        for (Object group : (Iterable<?>) settings) {
            for (Object s : (Iterable<?>) group) {
                Object n = s.getClass().getField("name").get(s);
                if (n instanceof String str) out.add(str);
            }
        }
        return out;
    }

    /** Read current value of a module setting in toString form. */
    public String getSetting(String moduleName, String settingName) throws Exception {
        Object setting = settingByName(moduleName, settingName);
        Method get = setting.getClass().getMethod("get");
        Object val = get.invoke(setting);
        return val == null ? null : String.valueOf(val);
    }

    /**
     * Set a setting via Meteor's own {@code Setting.parse(String)}. Returns
     * true if parse succeeded.
     *
     * For ItemListSetting (e.g. auto-eat blacklist), value is comma-separated
     * item ids: "minecraft:spider_eye,minecraft:pufferfish".
     * For boolean / int / double / enum settings, value is the obvious literal.
     */
    public boolean setSetting(String moduleName, String settingName, String value) throws Exception {
        Object setting = settingByName(moduleName, settingName);
        Method parse = setting.getClass().getMethod("parse", String.class);
        Object res = parse.invoke(setting, value == null ? "" : value);
        return res instanceof Boolean b && b;
    }

    private Object moduleByName(String name) throws Exception {
        Method get = modulesClass.getMethod("get", String.class);
        Object m = get.invoke(modulesInstance, name);
        if (m == null) throw new IllegalArgumentException("no_module:" + name);
        return m;
    }

    private Object settingByName(String moduleName, String settingName) throws Exception {
        Object module = moduleByName(moduleName);
        Object settings = module.getClass().getField("settings").get(module);
        Method settingsGet = settings.getClass().getMethod("get", String.class);
        Object setting = settingsGet.invoke(settings, settingName);
        if (setting == null) throw new IllegalArgumentException("no_setting:" + settingName);
        return setting;
    }

    private static String nameOf(Object m) {
        // Meteor Module: `public final String name` (field), not `getName()`.
        try {
            Object v = m.getClass().getField("name").get(m);
            if (v instanceof String s) return s;
        } catch (Throwable ignored) {}
        try {
            Object v = m.getClass().getMethod("getName").invoke(m);
            if (v instanceof String s) return s;
        } catch (Throwable ignored) {}
        return null;
    }

    private static Method findMethod(Class<?> cls, String name) {
        Class<?> c = cls;
        while (c != null) {
            try {
                Method mtd = c.getDeclaredMethod(name);
                mtd.setAccessible(true);
                return mtd;
            } catch (NoSuchMethodException ignored) {}
            c = c.getSuperclass();
        }
        return null;
    }

    /** Returns null if Meteor isn't on the classpath / not initialized yet. */
    public static MeteorFacade tryGet() {
        try {
            Class<?> cls = Class.forName("meteordevelopment.meteorclient.systems.modules.Modules");
            Object inst = cls.getMethod("get").invoke(null);
            if (inst == null) return null;
            return new MeteorFacade(cls, inst);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
