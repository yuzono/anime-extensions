package eu.kanade.tachiyomi.animeextension.all.supjav

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlin.system.exitProcess

/**
 * Springboard that accepts https://supjav.com/<language>/<item> intents
 * and redirects them to the main Aniyomi process.
 */
class SupJavUrlActivity : Activity() {

    private val tag = javaClass.simpleName

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mainIntent = Intent().apply {
            action = "eu.kanade.tachiyomi.ANIMESEARCH"
            putExtra("query", intent.data.toString())
            putExtra("filter", packageName)
        }

        try {
            startActivity(mainIntent)
        } catch (e: ActivityNotFoundException) {
            Log.e(tag, "Unable to launch activity", e)
        }

        finish()
        exitProcess(0)
    }
}
