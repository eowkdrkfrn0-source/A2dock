package com.nakyung.a2dock.tally

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class Release(val build: Int, val url: String, val apiUrl: String, val name: String)

/** GitHub Releases 에서 최신 APK 확인·다운로드 */
object Updater {

    private fun open(url: String, token: String, accept: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.instanceFollowRedirects = true
        c.setRequestProperty("Accept", accept)
        c.setRequestProperty("User-Agent", "Tally-App")
        if (token.isNotBlank()) c.setRequestProperty("Authorization", "Bearer ${token.trim()}")
        return c
    }

    fun latest(repo: String, token: String): Release? {
        val c = open("https://api.github.com/repos/${repo.trim()}/releases/latest", token, "application/vnd.github+json")
        val code = c.responseCode
        if (code == 404) throw Exception("업데이트 서버를 찾을 수 없음")
        if (code != 200) throw Exception("HTTP $code")
        val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        val n = j.optString("tag_name").substringAfterLast('-').filter { it.isDigit() }.toIntOrNull() ?: return null
        val assets = j.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk"))
                return Release(n, a.optString("browser_download_url"), a.optString("url"), a.optString("name"))
        }
        return null
    }

    fun download(r: Release, token: String, dest: File, progress: (Int) -> Unit) {
        val c = if (token.isNotBlank()) open(r.apiUrl, token, "application/octet-stream")
        else open(r.url, "", "application/octet-stream")
        val code = c.responseCode
        if (code != 200) throw Exception("다운로드 실패 HTTP $code")
        val total = c.contentLength
        dest.parentFile?.mkdirs()
        c.inputStream.use { ins ->
            dest.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var last = -1
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val p = (done * 100 / total).toInt()
                        if (p != last) { last = p; progress(p) }
                    }
                }
            }
        }
    }
}
