package eu.kanade.tachiyomi

/**
 * Info about the installed Aniyomi app (NOT THE EXTENSION!).
 * Can be useful for temporary fixes, preferences, header values, logging, etc.
 */
object AppInfo {
    /**
     * Version code of the host application. May be useful for sharing as User-Agent information.
     * Note that this value differs between forks so logic should not rely on it.
     *
     * @since extension-lib 13
     */
    fun getVersionCode(): Int = throw Exception("Stub!")

    /**
     * Version name of the host application. May be useful for sharing as User-Agent information.
     * Note that this value differs between forks so logic should not rely on it.
     *
     * @since extension-lib 13
     */
    fun getVersionName(): String = throw Exception("Stub!")
}
