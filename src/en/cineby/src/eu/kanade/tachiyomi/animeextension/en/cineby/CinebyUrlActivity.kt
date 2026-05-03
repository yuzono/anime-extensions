package eu.kanade.tachiyomi.animeextension.en.cineby

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log

class CinebyUrlActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pathSegments = intent?.data?.pathSegments
        if (pathSegments != null && pathSegments.size >= 2) {
            val type = pathSegments[0]
            val rawId = pathSegments[1]
            val isValidType = "movie" == type || "tv" == type

            if (isValidType) {
                val mainIntent = Intent().apply {
                    action = "eu.kanade.tachiyomi.ANIMESEARCH"
                    putExtra("query", "${Cineby.PREFIX_ID}$type/$rawId")
                    putExtra("filter", packageName)
                }

                try {
                    startActivity(mainIntent)
                } catch (e: ActivityNotFoundException) {
                    Log.e("CinebyUrlActivity", e.toString())
                }
            } else {
                Log.e("CinebyUrlActivity", "unknown type segment: $type")
            }
        } else {
            Log.e("CinebyUrlActivity", "could not parse uri from intent $intent")
        }

        finish()
    }
}
