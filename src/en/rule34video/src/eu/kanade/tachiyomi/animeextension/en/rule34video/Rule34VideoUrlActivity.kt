package eu.kanade.tachiyomi.animeextension.en.rule34video

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlin.system.exitProcess

class Rule34VideoUrlActivity : Activity() {

    private val tag = "Rule34VideoUrlActivity"

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
