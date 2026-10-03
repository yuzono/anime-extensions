@file:Suppress("RegExpRedundantEscape")

package eu.kanade.tachiyomi.animeextension.all.torbox

private val GROUP_REGEX = Regex("""^\[\w+\] ?""")
private val TAG_REGEX = Regex("""( ?\[[\s\w-]+\]| ?\([\s\w-]+\))(\.mkv|\.mp4|\.avi|\.flv|\.mov|\.webm|\.wmv)?$""")

fun String.trimInfo(): String {
    var newString = this.replaceFirst(GROUP_REGEX, "")

    while (TAG_REGEX.containsMatchIn(newString)) {
        newString = TAG_REGEX.replace(newString) { matchResult ->
            matchResult.groups[2]?.value ?: ""
        }
    }

    return newString.trim()
}
