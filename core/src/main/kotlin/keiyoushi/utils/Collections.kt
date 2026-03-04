package keiyoushi.utils

import android.util.Log
import kotlinx.coroutines.runBlocking

// From https://github.com/keiyoushi/extensions-source/blob/main/core/src/main/kotlin/keiyoushi/utils/Collections.kt

/**
 * Returns the first element that is an instances of specified type parameter T.
 *
 * @throws [NoSuchElementException] if no such element is found.
 */
inline fun <reified T> Iterable<*>.firstInstance(): T = first { it is T } as T

/**
 * Returns the first element that is an instances of specified type parameter T, or `null` if element was not found.
 */
inline fun <reified T> Iterable<*>.firstInstanceOrNull(): T? = firstOrNull { it is T } as? T

/**
 * Thread-blocking implementation of [Iterable.mapNotNull].
 *
 * @since extensions-lib 14
 */
inline fun <A, B> Iterable<A>.mapNotNullBlocking(crossinline f: suspend (A) -> B?): List<B> = runBlocking { mapNotNull { f(it) } }

/**
 * Thread-blocking implementation of [Iterable.flatMap].
 *
 * @since extensions-lib 14
 */
inline fun <A, B> Iterable<A>.flatMapBlocking(crossinline f: suspend (A) -> Iterable<B>): List<B> = runBlocking { flatMap { f(it) } }

/**
 * Implementation of [Iterable.flatMap], but running
 * the transformation function inside a try-catch block.
 *
 * @since extensions-lib 14
 */
suspend inline fun <A, B> Iterable<A>.catchingFlatMap(crossinline f: suspend (A) -> Iterable<B>): List<B> = flatMap {
    try {
        f(it)
    } catch (e: Throwable) {
        Log.e("Collections", "An error occurred in catchingFlatMap", e)
        emptyList()
    }
}

/**
 * Thread-blocking parallel implementation of [Iterable.flatMap], but running
 * the transformation function inside a try-catch block.
 *
 * @since extensions-lib 14
 */
inline fun <A, B> Iterable<A>.catchingFlatMapBlocking(crossinline f: suspend (A) -> Iterable<B>): List<B> = runBlocking { catchingFlatMap(f) }
