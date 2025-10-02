package eu.kanade.tachiyomi.animeextension.all.jellyfin

import eu.kanade.tachiyomi.animeextension.all.jellyfin.dto.DeviceProfileDto
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.JsonNamingStrategy
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private val NEWLINE_REGEX = Regex("""\n""")

// From https://github.com/jellyfin/jellyfin-sdk-kotlin
fun getAuthHeader(deviceInfo: Jellyfin.DeviceInfo, token: String? = null): String {
    val params = arrayOf(
        "Client" to deviceInfo.clientName,
        "Version" to deviceInfo.version,
        "DeviceId" to deviceInfo.id,
        "Device" to deviceInfo.name,
        "Token" to token,
    )

    return params
        .filterNot { (_, value) -> value == null }
        .joinToString(
            separator = ", ",
            prefix = "MediaBrowser ",
            transform = { (key, value) ->
                val value = value!!
                    .trim()
                    .replace(NEWLINE_REGEX, " ")
                    .let { URLEncoder.encode(it, StandardCharsets.UTF_8.name()) }

                """$key="$value""""
            },
        )
}

fun String.getImageUrl(baseUrl: String, id: String, name: String = "Primary", index: Int? = null): String = baseUrl.toHttpUrl().newBuilder().apply {
    addPathSegment("Items")
    addPathSegment(id)
    addPathSegment("Images")
    addPathSegment(name)
    index?.let { addPathSegment(it.toString()) }
    addQueryParameter("tag", this@getImageUrl)
}.build().toString()

object PascalCaseToCamelCase : JsonNamingStrategy {
    override fun serialNameForJson(
        descriptor: SerialDescriptor,
        elementIndex: Int,
        serialName: String,
    ): String = serialName.replaceFirstChar { it.uppercase() }
}

object Constants {
    val QUALITIES_LIST = listOf(
        Quality(420_000, 128_000, "420 kbps"),
        Quality(720_000, 192_000, "720 kbps"),
        Quality(1_500_000, 192_000, "1.5 Mbps"),
        Quality(3_000_000, 192_000, "3 Mbps"),
        Quality(4_000_000, 192_000, "4 Mbps"),
        Quality(6_000_000, 192_000, "6 Mbps"),
        Quality(8_000_000, 192_000, "8 Mbps"),
        Quality(10_000_000, 192_000, "10 Mbps"),
        Quality(15_000_000, 192_000, "15 Mbps"),
        Quality(20_000_000, 192_000, "20 Mbps"),
        Quality(40_000_000, 192_000, "40 Mbps"),
        Quality(60_000_000, 192_000, "60 Mbps"),
        Quality(80_000_000, 192_000, "80 Mbps"),
        Quality(120_000_000, 192_000, "120 Mbps"),
    )

    data class Quality(
        val videoBitrate: Int,
        val audioBitrate: Int,
        val description: String,
    )

    val LANG_CODES = setOf(
        "aar", "abk", "ace", "ach", "ada", "ady", "afh", "afr", "ain", "aka", "akk", "ale", "alt", "amh", "ang", "anp",
        "apa", "ara", "arc", "arg", "arn", "arp", "arw", "asm", "ast", "ath", "ava", "ave", "awa", "aym", "aze", "bai",
        "bak", "bal", "bam", "ban", "bas", "bej", "bel", "bem", "ben", "ber", "bho", "bik", "bin", "bis", "bla", "bod",
        "bos", "bra", "bre", "bua", "bug", "bul", "byn", "cad", "car", "cat", "ceb", "ces", "cha", "chb", "che", "chg",
        "chk", "chm", "chn", "cho", "chp", "chr", "chu", "chv", "chy", "cnr", "cop", "cor", "cos", "cre", "crh", "csb",
        "cym", "dak", "dan", "dar", "del", "den", "deu", "dgr", "din", "div", "doi", "dsb", "dua", "dum", "dyu", "dzo",
        "efi", "egy", "eka", "ell", "elx", "eng", "enm", "epo", "est", "eus", "ewe", "ewo", "fan", "fao", "fas", "fat",
        "fij", "fil", "fin", "fiu", "fon", "fra", "frm", "fro", "frr", "frs", "fry", "ful", "fur", "gaa", "gay", "gba",
        "gez", "gil", "gla", "gle", "glg", "glv", "gmh", "goh", "gon", "gor", "got", "grb", "grc", "grn", "gsw", "guj",
        "gwi", "hai", "hat", "hau", "haw", "heb", "her", "hil", "hin", "hit", "hmn", "hmo", "hrv", "hsb", "hun", "hup",
        "hye", "iba", "ibo", "ido", "iii", "ijo", "iku", "ile", "ilo", "ina", "inc", "ind", "inh", "ipk", "isl", "ita",
        "jav", "jbo", "jpn", "jpr", "jrb", "kaa", "kab", "kac", "kal", "kam", "kan", "kar", "kas", "kat", "kau", "kaw",
        "kaz", "kbd", "kha", "khm", "kho", "kik", "kin", "kir", "kmb", "kok", "kom", "kon", "kor", "kos", "kpe", "krc",
        "krl", "kru", "kua", "kum", "kur", "kut", "lad", "lah", "lam", "lao", "lat", "lav", "lez", "lim", "lin", "lit",
        "lol", "loz", "ltz", "lua", "lub", "lug", "lui", "lun", "luo", "lus", "mad", "mag", "mah", "mai", "mak", "mal",
        "man", "mar", "mas", "mdf", "mdr", "men", "mga", "mic", "min", "mkd", "mkh", "mlg", "mlt", "mnc", "mni", "moh",
        "mon", "mos", "mri", "msa", "mus", "mwl", "mwr", "mya", "myv", "nah", "nap", "nau", "nav", "nbl", "nde", "ndo",
        "nds", "nep", "new", "nia", "nic", "niu", "nld", "nno", "nob", "nog", "non", "nor", "nqo", "nso", "nub", "nwc",
        "nya", "nym", "nyn", "nyo", "nzi", "oci", "oji", "ori", "orm", "osa", "oss", "ota", "oto", "pag", "pal", "pam",
        "pan", "pap", "pau", "peo", "phn", "pli", "pol", "pon", "por", "pro", "pus", "que", "raj", "rap", "rar", "roh",
        "rom", "ron", "run", "rup", "rus", "sad", "sag", "sah", "sam", "san", "sas", "sat", "scn", "sco", "sel", "sga",
        "shn", "sid", "sin", "slk", "slv", "sma", "sme", "smj", "smn", "smo", "sms", "sna", "snd", "snk", "sog", "som",
        "son", "sot", "spa", "sqi", "srd", "srn", "srp", "srr", "ssw", "suk", "sun", "sus", "sux", "swa", "swe", "syc",
        "syr", "tah", "tai", "tam", "tat", "tel", "tem", "ter", "tet", "tgk", "tgl", "tha", "tig", "tir", "tiv", "tkl",
        "tlh", "tli", "tmh", "tog", "ton", "tpi", "tsi", "tsn", "tso", "tuk", "tum", "tup", "tur", "tvl", "twi", "tyv",
        "udm", "uga", "uig", "ukr", "umb", "urd", "uzb", "vai", "ven", "vie", "vol", "vot", "wal", "war", "was", "wen",
        "wln", "wol", "xal", "xho", "yao", "yap", "yid", "yor", "zap", "zbl", "zen", "zgh", "zha", "zho", "zul", "zun",
        "zza",
    )
}

// From https://github.com/jellyfin/jellyfin-mpv-shim/blob/6cc27da739180a65baadfd981e42662bdf248221/jellyfin_mpv_shim/utils.py#L100
fun getDeviceProfile(
    name: String,
    videoCodec: String,
    videoBitrate: Int,
    audioBitrate: Int,
): DeviceProfileDto {
    val subtitleProfilesList = buildList {
        listOf("srt", "ass", "sub", "ssa", "smi").forEach {
            add(
                DeviceProfileDto.SubtitleProfileDto(
                    format = it,
                    method = "External",
                ),
            )

            add(
                DeviceProfileDto.SubtitleProfileDto(
                    format = it,
                    method = "Embed",
                ),
            )
        }

        @Suppress("SpellCheckingInspection")
        listOf("pgssub", "dvdsub", "dvbsub", "pgs").forEach {
            add(
                DeviceProfileDto.SubtitleProfileDto(
                    format = it,
                    method = "Embed",
                ),
            )
        }
    }

    return DeviceProfileDto(
        name = name,
        maxStreamingBitrate = videoBitrate,
        maxStaticBitrate = videoBitrate,
        musicStreamingTranscodingBitrate = audioBitrate,
        transcodingProfiles = listOf(
            DeviceProfileDto.ProfileDto(
                type = "Audio",
            ),
            DeviceProfileDto.ProfileDto(
                type = "Photo",
                container = "jpeg",
            ),
            DeviceProfileDto.ProfileDto(
                type = "Video",
                container = "mp4",
                protocol = "hls",
                audioCodec = "aac,mp3,ac3,opus,flac,vorbis",
                videoCodec = videoCodec,
                maxAudioChannels = "6",
            ),
        ),
        directPlayProfiles = listOf(
            DeviceProfileDto.ProfileDto(
                type = "Audio",
            ),
            DeviceProfileDto.ProfileDto(
                type = "Photo",
            ),
            DeviceProfileDto.ProfileDto(
                type = "Video",
            ),
        ),
        responseProfiles = emptyList(),
        containerProfiles = emptyList(),
        codecProfiles = listOf(
            DeviceProfileDto.ProfileDto(
                type = "Video",
                codec = videoCodec,
                conditions = listOf(
                    DeviceProfileDto.ProfileDto.ProfileConditionDto(
                        condition = "Equals",
                        property = "Width",
                        value = "0",
                    ),
                ),
            ),
        ),
        subtitleProfiles = subtitleProfilesList,
    )
}
