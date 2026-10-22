package me.hadoku.app

/**
 * A test that talks to production with a real session and real bandwidth.
 * Excluded from `pnpm test:device` (Gradle passes notAnnotation); each has its
 * own script that runs it by class, on purpose.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class LiveProduction
