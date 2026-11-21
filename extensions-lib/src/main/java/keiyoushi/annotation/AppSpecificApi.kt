package keiyoushi.annotation

/** Only Animiru implements this. Use keiyoushi.source.AniSource. */
@RequiresOptIn(level = RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
annotation class AnimiruOnly

/** Only Anikku implements this. Use keiyoushi.source.AniSource. */
@RequiresOptIn(level = RequiresOptIn.Level.WARNING)
@Retention(AnnotationRetention.BINARY)
annotation class AnikkuOnly
