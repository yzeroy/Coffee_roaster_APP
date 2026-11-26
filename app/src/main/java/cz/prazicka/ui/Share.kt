package cz.prazicka.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Sdílení souboru přes systémový dialog (e-mail, Disk, …). */
fun shareFile(context: Context, fileName: String, mime: String, content: String) {
    val dir = File(context.cacheDir, "export").apply { mkdirs() }
    val f = File(dir, fileName.replace(Regex("[^A-Za-z0-9._-]"), "_"))
    f.writeText(content)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
    val send = Intent(Intent.ACTION_SEND)
        .setType(mime)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Sdílet").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun readText(context: Context, uri: Uri): String? = try {
    context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
} catch (e: Exception) {
    null
}
