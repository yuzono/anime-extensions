#-dontobfuscate
-dontoptimize
-dontpreverify

## Partially based on https://android.googlesource.com/platform/tools/base/+/refs/heads/mirror-goog-studio-main/build-system/gradle-core/src/main/resources/com/android/build/gradle/proguard-common.txt

# For enumeration classes, see https://www.guardsquare.com/manual/configuration/examples#enumerations
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Preserve annotated Javascript interface methods.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

## Below are some of the custom rules for this repo

# Injekt — generic type tokens are captured via subclasses of FullTypeReference and
# resolved with reflection at runtime, so the Signature attribute is needed.
# https://r8.googlesource.com/r8/+/refs/heads/master/compatibility-faq.md#troubleshooting-gson-gson
-keepattributes Signature
-keep class * extends uy.kohesive.injekt.api.FullTypeReference

# kotlinx-serialization — runtime keeps required for @Serializable types and their
# generated $serializer companions.
# https://github.com/Kotlin/kotlinx.serialization/tree/dev/rules
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

-keepclassmembers @kotlinx.serialization.Serializable class ** {
    static ** Companion;
}

-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}

-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

-keepclassmembers public class **$$serializer {
    private ** descriptor;
}

-if @kotlinx.serialization.Serializable class **
-keep,allowshrinking,allowoptimization,allowobfuscation,allowaccessmodification class <1>

# Legacy video API — the app's AnimeHttpSource still declares getVideoList(SEpisode),
# videoListRequest(SEpisode) and videoListParse(Response), but extensions-lib 16 doesn't,
# so R8 sees no library method to match and renames them. The override is then silently
# lost and the app runs its own base implementation instead.
# See keiyoushi.utils.AnimeHttpLegacySource / ParsedAnimeHttpLegacySource.
-keepclassmembers class * extends eu.kanade.tachiyomi.animesource.online.AnimeHttpSource {
    *** getVideoList(eu.kanade.tachiyomi.animesource.model.SEpisode, kotlin.coroutines.Continuation);
    *** videoListRequest(eu.kanade.tachiyomi.animesource.model.SEpisode);
    *** videoListParse(okhttp3.Response);
}
