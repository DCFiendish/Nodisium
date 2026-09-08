package net.nodisium.server.modules

import java.net.URL
import java.net.URLClassLoader
import java.util.Collections
import java.util.Enumeration

/**
 * Parent-first: a class already visible to the core classloader (Minestom, gson, the shared
 * `net.aechronis:utils` jar, and any module this one depends on, via [dependencies]) always
 * resolves there first. Only the module's *own* package, absent from every one of those, is
 * loaded fresh from [jar] -- which is exactly what makes a rebuilt module jar actually take
 * effect on reload: if the module's classes were also reachable from the parent (e.g. because
 * they were compiled into the core shadowJar), the parent copy would always win and a freshly
 * built module jar would be silently ignored. Adapted from `aechronis/aechronis`'s
 * `ModuleClassLoader` (read in full; same parent-first shape), trimmed to this project's actual
 * dependency graph (a static list per module, not a general resolver).
 */
internal class ModuleClassLoader(
    jar: URL,
    private val dependencies: List<ModuleClassLoader>,
) : URLClassLoader(arrayOf(jar), HotSwappableModule::class.java.classLoader) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> =
        synchronized(getClassLoadingLock(name)) {
            val type = findLoadedClass(name) ?: run {
                try {
                    parent.loadClass(name)
                } catch (_: ClassNotFoundException) {
                    dependencies.firstNotNullOfOrNull { dependency ->
                        try {
                            dependency.loadClass(name)
                        } catch (_: ClassNotFoundException) {
                            null
                        }
                    } ?: findClass(name)
                }
            }
            if (resolve && type.classLoader === this) resolveClass(type)
            type
        }

    override fun getResource(name: String): URL? =
        parent.getResource(name) ?: findResource(name) ?: dependencies.firstNotNullOfOrNull { it.getResource(name) }

    override fun getResources(name: String): Enumeration<URL> {
        val resources = parent.getResources(name).toList() + findResources(name).toList() +
            dependencies.flatMap { it.getResources(name).toList() }
        return Collections.enumeration(resources.distinct())
    }
}
