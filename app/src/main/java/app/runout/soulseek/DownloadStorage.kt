package app.runout.soulseek

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.runout.library.FlacPolicy
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Shared SAF storage boundary for Soulseek and Internet Archive downloads.
 * The repository owns account checks, renaming, and the database commit.
 */
internal class DownloadStorage(
    private val context: Context,
    private val destination: () -> Uri?,
) {
    fun stagingDirectory(id: String) = File(context.noBackupFilesDir, "soulseek-staging/$id").apply { mkdirs() }

    fun requireDestination(): DocumentFile = destination()?.let { DocumentFile.fromTreeUri(context, it) }?.takeIf { it.canWrite() }
        ?: throw IllegalStateException("Choose a writable download folder first.")

    fun albumDirectory(title: String, id: String): DocumentFile {
        val root = requireDestination()
        val name = SoulseekPolicy.safeName(title) + " " + id.takeLast(10)
        return root.findFile(name)?.takeIf { it.isDirectory } ?: root.createDirectory(name) ?: throw IOException("Could not create an album folder.")
    }

    suspend fun copyValidated(stage: File, target: Uri, size: Long) {
        val source = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openOutputStream(target, "wt")?.use { output ->
            stage.inputStream().use { input ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer); if (count < 0) break
                    source.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
            }
            output.flush()
        } ?: throw IOException("Your download folder is unavailable. Choose it again and retry.")
        val valid = context.contentResolver.openInputStream(target)?.use { FlacPolicy.isValid(it) } == true
        val actualSize = context.contentResolver.openAssetFileDescriptor(target, "r")?.use { it.length } ?: -1
        val saved = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(target)?.use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                saved.update(buffer, 0, count)
            }
        } ?: throw IOException("The saved FLAC could not be read back.")
        check(source.digest().contentEquals(saved.digest())) { "The saved FLAC does not match the download byte for byte." }
        check(valid && (actualSize < 0 || actualSize == size)) { "The destination could not save the complete FLAC file. Free storage and retry." }
    }

    /**
     * The download folder's thumbnails/, kept out of the photo gallery by a .nomedia file (the folder
     * holds only covers, so nothing else is hidden). Made for older folders too, the first time it's used.
     */
    fun thumbnailsDirectory(): DocumentFile {
        val root = requireDestination()
        val dir = root.findFile("thumbnails")?.takeIf { it.isDirectory } ?: root.createDirectory("thumbnails")
            ?: throw IOException("Could not create the thumbnails folder.")
        app.runout.library.GalleryPrivacy.hide(context, dir)
        return dir
    }

    /**
     * Before 1.2.0 an album folder could hold record.vinyl.zip and copies of it, record.vinyl (1).zip
     * and on, one per finished track: the first becomes record.vinyl again and the copies, Sonic's own,
     * go.
     */
    fun tidyRecordFiles(folder: DocumentFile) {
        val files = folder.listFiles()
        if (files.none { it.name == "record.vinyl" }) files.firstOrNull { it.name == "record.vinyl.zip" }?.renameTo("record.vinyl")
        files.filter { it.name?.matches(Regex("""record\.vinyl \(\d+\)\.zip""")) == true }.forEach { runCatching { it.delete() } }
    }

    /** The thumbnails/ folder an older Sonic made in the download folder, hidden now; nothing is created otherwise. */
    fun hideExistingThumbnails() {
        app.runout.library.GalleryPrivacy.hideLeftovers(context, destination()?.let { DocumentFile.fromTreeUri(context, it) }?.takeIf { it.canWrite() })
    }

    fun writeDocument(folder: DocumentFile, name: String, bytes: ByteArray) {
        // A plain type keeps the name as given: with "application/zip" Android's storage renamed
        // record.vinyl to record.vinyl.zip, so the next write never found it and made another.
        val target = folder.findFile(name) ?: folder.createFile("application/octet-stream", name) ?: return
        context.contentResolver.openOutputStream(target.uri, "wt")?.use { it.write(bytes) }
    }
}
