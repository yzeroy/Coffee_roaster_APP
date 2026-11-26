package cz.prazicka.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import cz.prazicka.protocol.arr
import cz.prazicka.protocol.bool
import cz.prazicka.protocol.lng
import cz.prazicka.protocol.ProtoJson
import cz.prazicka.protocol.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseInfo(val tag: String, val name: String, val notes: String, val apkUrl: String, val apkSize: Long, val prerelease: Boolean)

/**
 * Kontrola aktualizací přes GitHub Releases (veřejný repozitář, bez přihlášení).
 * Provozní varianta bere jen stabilní vydání, testovací jen pre-release.
 */
object Updater {
    suspend fun latest(repo: String, test: Boolean): ReleaseInfo? = withContext(Dispatchers.IO) {
        val conn = URL("https://api.github.com/repos/$repo/releases?per_page=30").openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        try {
            if (conn.responseCode != 200) throw IllegalStateException("GitHub odpověděl ${conn.responseCode}")
            val text = conn.inputStream.bufferedReader().readText()
            val arr = ProtoJson.parseToJsonElement(text) as? JsonArray ?: return@withContext null
            arr.mapNotNull { it as? JsonObject }
                .filter { it.bool("draft") != true && (it.bool("prerelease") == true) == test }
                .mapNotNull { r ->
                    val asset = r.arr("assets")?.mapNotNull { it as? JsonObject }?.firstOrNull { (it.str("name") ?: "").endsWith(".apk") }
                        ?: return@mapNotNull null
                    ReleaseInfo(
                        tag = r.str("tag_name") ?: return@mapNotNull null,
                        name = r.str("name") ?: (r.str("tag_name") ?: ""),
                        notes = r.str("body") ?: "",
                        apkUrl = asset.str("browser_download_url") ?: return@mapNotNull null,
                        apkSize = asset.lng("size") ?: 0L,
                        prerelease = r.bool("prerelease") == true,
                    )
                }
                .maxWithOrNull { a, b -> compareVersions(a.tag, b.tag) }
        } finally {
            conn.disconnect()
        }
    }

    /** Porovnání „v1.2.3“ / „1.2.3-test.4“. */
    fun compareVersions(a: String, b: String): Int {
        fun parts(v: String): List<Int> {
            val clean = v.trim().removePrefix("v")
            val main = clean.substringBefore("-")
            val nums = main.split(".").map { it.toIntOrNull() ?: 0 }.toMutableList()
            while (nums.size < 3) nums.add(0)
            val test = Regex("test\\.(\\d+)").find(clean)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE
            return nums.take(3) + test
        }
        val pa = parts(a)
        val pb = parts(b)
        for (i in pa.indices) if (pa[i] != pb[i]) return pa[i].compareTo(pb[i])
        return 0
    }

    fun isNewer(tag: String, current: String) = compareVersions(tag, current) > 0

    suspend fun download(context: Context, info: ReleaseInfo, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "prazicka-${info.tag}.apk")
        var url = URL(info.apkUrl)
        var conn = url.openConnection() as HttpURLConnection
        // GitHub přesměrovává na úložiště souborů
        var redirects = 0
        while (conn.responseCode in 300..399 && redirects < 5) {
            val loc = conn.getHeaderField("Location") ?: break
            conn.disconnect()
            url = URL(url, loc)
            conn = url.openConnection() as HttpURLConnection
            redirects++
        }
        try {
            if (conn.responseCode != 200) throw IllegalStateException("Stažení selhalo (${conn.responseCode})")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.apkSize
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) progress(done.toFloat() / total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        file
    }

    /** Spustí systémovou instalaci. Android ji vždy nechá potvrdit uživatelem. */
    fun install(context: Context, file: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }
}
