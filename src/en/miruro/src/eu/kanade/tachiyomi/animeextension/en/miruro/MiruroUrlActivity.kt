package eu.kanade.tachiyomi.animeextension.en.miruro

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlin.system.exitProcess

/**
 * Springboard that accepts https://miruro.tv/watch/{anilistId}/{slug} intents
 * and redirects them to the main Aniyomi process.
 */
class MiruroUrlActivity : Activity() {

    private val tag = javaClass.simpleName

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pathSegments = intent?.data?.pathSegments
        if (pathSegments != null && pathSegments.size >= 2) {
            // URL format: https://miruro.tv/watch/{anilistId}/{slug}
            // pathSegments[0] = "watch", pathSegments[1] = anilistId
            val anilistId = pathSegments[1]
            val mainIntent = Intent().apply {
                action = "eu.kanade.tachiyomi.ANIMESEARCH"
                putExtra("query", "${Miruro.PREFIX_SEARCH}$anilistId")
                putExtra("filter", packageName)
            }

            try {
                startActivity(mainIntent)
            } catch (e: ActivityNotFoundException) {
                Log.e(tag, e.toString())
            }
        } else {
            Log.e(tag, "could not parse uri from intent $intent")
        }

        finish()
        exitProcess(0)
    }
}
