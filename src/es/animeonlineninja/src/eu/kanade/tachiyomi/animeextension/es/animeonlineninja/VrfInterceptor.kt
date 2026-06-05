package eu.kanade.tachiyomi.animeextension.es.animeonlineninja

import app.cash.quickjs.QuickJs
import eu.kanade.tachiyomi.network.GET
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import okhttp3.Headers

class VrfInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        
        // Headers mejorados con User-Agent móvil realista
        val newRequest = request.newBuilder()
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Mobile Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8")
            .header("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
            .header("Accept-Encoding", "gzip, deflate, br")
            .header("DNT", "1")
            .header("Connection", "keep-alive")
            .header("Upgrade-Insecure-Requests", "1")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "none")
            .header("Sec-Fetch-User", "?1")
            .header("Cache-Control", "max-age=0")
            .build()
        
        val response = chain.proceed(newRequest)
        val respBody = response.body.string()
        
        // Saltar imágenes
        if (response.headers["Content-Type"]?.contains("image") == true) {
            return response
        }
        
        val body = if (respBody.contains("One moment, please") || respBody.contains("Checking your browser")) {
            println("VRF: Detectada protección Cloudflare, resolviendo...")
            val parsed = Jsoup.parse(respBody)
            
            // Buscar el script de verificación
            val scriptElement = parsed.selectFirst("script:containsData(west=)")
            if (scriptElement == null) {
                println("VRF: No se encontró script de verificación")
                return response
            }
            
            val js = scriptElement.data()
            val west = js.substringAfter("west=").substringBefore(",").trim()
            val east = js.substringAfter("east=").substringBefore(",").trim()
            val form = parsed.selectFirst("form#wsidchk-form")?.attr("action") ?: "/"
            
            val eval = evalJs(west, east)
            val getLink = "https://" + request.url.host + form + "?wsidchk=$eval"
            
            println("VRF: Resuelto, obteniendo $getLink")
            val newResponse = chain.proceed(GET(getLink, newRequest.headers))
            newResponse.body.string().toResponseBody(newResponse.body.contentType())
        } else {
            respBody.toResponseBody(response.body.contentType())
        }
        
        return response.newBuilder().body(body).build()
    }

    private fun evalJs(west: String, east: String): String = QuickJs.create().use { qjs ->
        val jscript = """$west + $east;"""
        qjs.evaluate(jscript).toString()
    }
}
