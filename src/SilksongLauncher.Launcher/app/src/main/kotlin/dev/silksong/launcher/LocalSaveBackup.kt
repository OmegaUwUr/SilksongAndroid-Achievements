package dev.silksong.launcher

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Runs on the Cloud IO dispatcher, before any save is overwritten. */
internal object LocalSaveBackup {
    fun latest(context: Context): File? = context.getExternalFilesDir(null)?.let { base ->
        File(base, "cloud-download-backups").listFiles()
            ?.filter { it.isFile && it.name.startsWith("before-cloud-") && it.extension == "zip" }
            ?.maxByOrNull { it.name }
    }

    @Synchronized fun beforeDownload(context: Context) {
        val saves = SaveDir.of(context)
        val files = saves.listFiles()?.filter { it.isFile }?.sortedBy { it.name }
            ?: if (!saves.exists()) emptyList() else error("Cannot read local saves; Cloud download stopped")
        if (files.isEmpty()) return
        val root = File(context.getExternalFilesDir(null) ?: error("Local backup storage unavailable"), "cloud-download-backups")
        check(root.isDirectory || root.mkdirs()) { "Cannot create local backup folder" }
        val name = "before-cloud-${System.currentTimeMillis()}-${UUID.randomUUID()}.zip"
        val temporary = File(root, "$name.part")
        val destination = File(root, name)
        try {
            FileOutputStream(temporary).use { output ->
                ZipOutputStream(output).use { zip ->
                files.forEach { file ->
                    check(file.canonicalFile.parentFile == saves.canonicalFile) { "Unexpected save path" }
                    zip.putNextEntry(ZipEntry(file.name).apply { time = file.lastModified() })
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                zip.finish()
                zip.flush()
                output.fd.sync()
                }
            }
            ZipFile(temporary).use { zip ->
                check(zip.size() == files.size) { "Incomplete backup" }
                files.forEach { file ->
                    val entry = zip.getEntry(file.name) ?: error("Missing backup entry")
                    check(entry.size == file.length()) { "Save changed while backing up; download stopped" }
                    val crc = java.util.zip.CRC32()
                    zip.getInputStream(entry).use { input ->
                        val buffer = ByteArray(8192)
                        while (true) { val n = input.read(buffer); if (n < 0) break; crc.update(buffer, 0, n) }
                    }
                    check(crc.value == entry.crc) { "Backup verification failed" }
                }
            }
            check(temporary.renameTo(destination)) { "Could not finalize local backup" }
            LauncherLog.log("Local saves backed up before Cloud download: ${destination.absolutePath}")
            // Keep five completed snapshots. Never prune before the new backup is verified.
            root.listFiles()?.filter { it.isFile && it.name.startsWith("before-cloud-") && it.extension == "zip" }
                ?.sortedByDescending { it.name }?.drop(5)?.forEach { it.delete() }
        } catch (error: Exception) {
            temporary.delete()
            throw java.io.IOException("Local save backup failed; Cloud download stopped", error)
        }
    }
}
