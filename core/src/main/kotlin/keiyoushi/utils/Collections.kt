package keiyoushi.utils

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
 * Groups consecutive elements into non-overlapping pairs: `[a, b, c, d]` -> `[(a, b), (c, d)]`.
 * A trailing element without a partner is dropped.
 */
fun <T> Iterable<T>.chunkedPairs(): List<Pair<T, T>> = windowed(2, 2) { (first, second) -> first to second }

/**
 * Groups consecutive elements into non-overlapping pairs: `[a, b, c, d]` -> `[(a, b), (c, d)]`.
 * A trailing element without a partner is dropped.
 */
fun <T> Sequence<T>.chunkedPairs(): Sequence<Pair<T, T>> = windowed(2, 2) { (first, second) -> first to second }
