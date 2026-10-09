package keiyoushi.templating

import eu.kanade.tachiyomi.animesource.model.SAnime

object StatusUtils {
    private val COMPLETED_STATUSES = setOf(
        "completed", "completo", "finished airing", "ended",
        "released", "concluído", "tamamlandı", "завершено",
        "completado", "terminé", "finito", "beendet",
        "完了", "completed airing",
    )

    private val ONGOING_STATUSES = setOf(
        "ongoing", "lançamento", "releasing", "currently airing",
        "emission", "em andamento", "devam ediyor", "в эфире",
        "emisión", "emissão", "in corso", "w toku",
        "放送中", "airing",
    )

    private val UPCOMING_STATUSES = setOf(
        "upcoming", "próximo", "not yet aired", "not aired",
        "pending", "premiere", "em breve",
        "prochainement", "prossimo", "wkrótce",
        "未放送", "not yet released", "tba",
    )

    private val ON_HIATUS_STATUSES = setOf(
        "on hiatus",
        "hiatus",
        "em hiato",
        "en pausa",
        "auf pause",
        "på vent",
        "en attente",
        "休止中",
    )

    fun parse(statusString: String?): Int {
        val status = statusString?.trim()?.lowercase() ?: return SAnime.UNKNOWN
        return when {
            COMPLETED_STATUSES.any { status.contains(it) } -> SAnime.COMPLETED
            ONGOING_STATUSES.any { status.contains(it) } -> SAnime.ONGOING
            ON_HIATUS_STATUSES.any { status.contains(it) } -> SAnime.ON_HIATUS
            UPCOMING_STATUSES.any { status.contains(it) } -> SAnime.LICENSED
            else -> SAnime.UNKNOWN
        }
    }

    fun toLocalizedString(status: Int): String = when (status) {
        SAnime.ONGOING -> "Ongoing"
        SAnime.COMPLETED -> "Completed"
        SAnime.LICENSED -> "Upcoming"
        SAnime.ON_HIATUS -> "On Hiatus"
        else -> "Unknown"
    }

    fun isOngoing(status: Int): Boolean = status == SAnime.ONGOING
    fun isCompleted(status: Int): Boolean = status == SAnime.COMPLETED
    fun isUpcoming(status: Int): Boolean = status == SAnime.LICENSED
    fun isOnHiatus(status: Int): Boolean = status == SAnime.ON_HIATUS
    fun isAired(status: Int): Boolean = status == SAnime.COMPLETED || status == SAnime.ONGOING
    fun isActive(status: Int): Boolean = status == SAnime.ONGOING || status == SAnime.ON_HIATUS
}

fun String?.parseAnimeStatus(): Int = StatusUtils.parse(this)
fun Int.toStatusString(): String = StatusUtils.toLocalizedString(this)
fun Int.isOngoing(): Boolean = StatusUtils.isOngoing(this)
fun Int.isCompleted(): Boolean = StatusUtils.isCompleted(this)
fun Int.isUpcoming(): Boolean = StatusUtils.isUpcoming(this)
fun Int.isOnHiatus(): Boolean = StatusUtils.isOnHiatus(this)
fun Int.isAired(): Boolean = StatusUtils.isAired(this)
fun Int.isActive(): Boolean = StatusUtils.isActive(this)
