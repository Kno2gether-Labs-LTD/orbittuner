package dev.avijit.orbittuner.library

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** `sunday-set.zip` from "Sunday set" (letters and digits kept, anything else becomes a hyphen). */
fun exportZipName(playlistName: String): String {
    val slug = playlistName.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-').take(60)
    return slug.ifEmpty { "playlist" } + ".zip"
}

/** Deletes exports older than a day (their shares are long finished). */
internal fun pruneExports(root: File, now: Long = System.currentTimeMillis()) {
    root.listFiles()?.forEach { f -> if (now - f.lastModified() > 86_400_000L) f.deleteRecursively() }
}

/**
 * Writes [export] to `cache/exports/zip-<time>-<n>/<slug>.zip` and offers it through the system share sheet (FileProvider,
 * `${applicationId}.exports`). Returns false when the file couldn't be written.
 */
suspend fun shareExportZip(context: Context, export: ExportReady): Boolean {
    val name = exportZipName(export.name)
    val file = withContext(Dispatchers.IO) {
        try {
            val root = File(context.cacheDir, "exports").apply { mkdirs() }
            pruneExports(root)
            // Own folder per export: a share still reading an earlier ZIP of the same name is never overwritten.
            val dir = File(root, "zip-" + System.currentTimeMillis() + "-" + (1000..9999).random()).apply { mkdirs() }
            File(dir, name).apply { writeBytes(export.bytes) }
        } catch (_: Exception) {
            null
        }
    } ?: return false
    val uri = FileProvider.getUriForFile(context, context.packageName + ".exports", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, name)
        putExtra(Intent.EXTRA_SUBJECT, export.name)
        clipData = ClipData.newRawUri(name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(send, "Share ${export.name}")
    if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
    return true
}
