package app.runout.library

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * Keeps the covers Sonic writes out of the phone's photo gallery. Sonic keeps one cover per download
 * in thumbnails/ at the top of the download folder; gallery apps listed them as photos. A .nomedia
 * file there hides them (the folder holds only those covers, so nothing else is hidden), and a scan
 * of the folder drops the ones already listed. Albums' own folders are never touched: a .nomedia
 * there would hide the music from other players too.
 */
object GalleryPrivacy {
    private const val THUMBNAILS = "thumbnails"
    private const val NO_MEDIA = ".nomedia"
    private val SONIC_COVER = Regex("""^(soulseek|drop)-[^/]+\.jpg$""")

    /** Hides [dir] (a thumbnails/ folder Sonic made) from the gallery, once. */
    fun hide(context: Context, dir: DocumentFile) {
        if (dir.findFile(NO_MEDIA) != null) return
        if (dir.createFile("application/octet-stream", NO_MEDIA) != null) rescan(context, dir)
    }

    /**
     * A thumbnails/ folder an older Sonic left at the top of [root] (a music or download folder):
     * hidden only when everything in it is a Sonic cover, so a folder of the user's own isn't.
     */
    fun hideLeftovers(context: Context, root: DocumentFile?) {
        val dir = root?.findFile(THUMBNAILS)?.takeIf { it.isDirectory } ?: return
        if (dir.findFile(NO_MEDIA) != null) return
        val names = dir.listFiles().mapNotNull { it.name }
        if (names.isNotEmpty() && names.all { SONIC_COVER.matches(it) }) hide(context, dir)
    }

    /**
     * Asks Android's media scanner to look at [dir] again, so covers it had already listed drop out
     * now that it has a .nomedia. Only on the phone's own storage, where the document id maps to a
     * path; elsewhere they go at the next full media scan.
     */
    private fun rescan(context: Context, dir: DocumentFile) {
        runCatching {
            if (dir.uri.authority != "com.android.externalstorage.documents") return
            val id = DocumentsContract.getDocumentId(dir.uri)
            if (!id.startsWith("primary:")) return
            val path = File(Environment.getExternalStorageDirectory(), id.removePrefix("primary:"))
            val files = (path.listFiles()?.map { it.absolutePath } ?: emptyList()) + path.absolutePath
            MediaScannerConnection.scanFile(context, files.toTypedArray(), null, null)
        }
    }
}
