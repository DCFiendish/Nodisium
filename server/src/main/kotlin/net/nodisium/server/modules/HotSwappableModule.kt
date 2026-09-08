package net.nodisium.server.modules

/**
 * Implemented by a small `*LiveModule` wrapper inside each of `nodes`/`vanilla`/`combat`/
 * `worldedit`, never by the module's own main object directly -- this type is loaded once on the
 * core classloader so old and new generations of a module (each loaded by its own
 * [ModuleClassLoader]) can be driven through the same contract across a hot-reload.
 */
interface HotSwappableModule {
    fun initialize(context: ModuleContext)

    /** Stop accepting new work; let work already in flight finish. Called before [shutdown]. */
    fun prepareForShutdown() = Unit

    /** Final teardown: unregister commands/listeners, stop scheduled tasks, save. */
    fun shutdown()
}
