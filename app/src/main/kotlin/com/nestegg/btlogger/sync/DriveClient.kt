package com.nestegg.btlogger.sync

import android.content.Context
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import java.io.ByteArrayOutputStream
import com.google.api.services.drive.model.File as DriveFile

// drive.file scope sees only files we created; Drive has no append, so we re-upload whole.
class DriveClient(private val drive: Drive) {

    fun appendCsvRows(yearMonth: String, deviceTag: String, header: String, rows: List<String>): Int {
        if (rows.isEmpty()) return 0
        val folderId = ensureFolder(FOLDER_NAME)
        val fileName = "bluetooth-log-$deviceTag-$yearMonth.csv"
        val existing = findFile(fileName, folderId)

        val newContent = buildString {
            if (existing == null) {
                appendLine(header)
            } else {
                val current = downloadText(existing.id).trimEnd('\n')
                if (current.isNotEmpty()) {
                    append(current)
                    append('\n')
                }
            }
            appendRows(rows)
        }

        putContent(fileName, folderId, existing, newContent)
        return rows.size
    }

    /** Replaces [fileName] wholesale with [header] plus [rows], rather than appending. */
    fun overwriteCsv(fileName: String, header: String, rows: List<String>): Int {
        val folderId = ensureFolder(FOLDER_NAME)
        val existing = findFile(fileName, folderId)
        val content = buildString {
            appendLine(header)
            appendRows(rows)
        }
        putContent(fileName, folderId, existing, content)
        return rows.size
    }

    private fun StringBuilder.appendRows(rows: List<String>) {
        for (row in rows) {
            append(row)
            append('\n')
        }
    }

    private fun putContent(fileName: String, folderId: String, existing: DriveFile?, content: String) {
        val media = ByteArrayContent("text/csv", content.toByteArray(Charsets.UTF_8))
        if (existing == null) {
            val metadata = DriveFile()
                .setName(fileName)
                .setParents(listOf(folderId))
                .setMimeType("text/csv")
            drive.files().create(metadata, media).setFields("id").execute()
        } else {
            drive.files().update(existing.id, DriveFile(), media).execute()
        }
    }

    private fun ensureFolder(name: String): String {
        val q = "mimeType='application/vnd.google-apps.folder' " +
            "and name='${escapeQ(name)}' and trashed=false"
        drive.files().list()
            .setQ(q)
            .setSpaces("drive")
            .setFields("files(id)")
            .execute()
            .files
            .firstOrNull()
            ?.let { return it.id }

        val folder = DriveFile()
            .setName(name)
            .setMimeType("application/vnd.google-apps.folder")
        return drive.files().create(folder).setFields("id").execute().id
    }

    private fun findFile(name: String, folderId: String): DriveFile? {
        val q = "name='${escapeQ(name)}' and '$folderId' in parents and trashed=false"
        return drive.files().list()
            .setQ(q)
            .setSpaces("drive")
            .setFields("files(id,name)")
            .execute()
            .files
            .firstOrNull()
    }

    private fun downloadText(fileId: String): String {
        val out = ByteArrayOutputStream()
        drive.files().get(fileId).executeMediaAndDownloadTo(out)
        return out.toString(Charsets.UTF_8.name())
    }

    private fun escapeQ(value: String): String =
        value.replace("\\", "\\\\").replace("'", "\\'")

    companion object {
        const val FOLDER_NAME = "Bluetooth Logger"
        private const val APP_NAME = "BluetoothLogger"

        fun forAccountName(context: Context, accountName: String): DriveClient {
            val credential = GoogleAccountCredential.usingOAuth2(
                context, listOf(DriveScopes.DRIVE_FILE)
            ).apply { selectedAccountName = accountName }

            val drive = Drive.Builder(
                NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                credential,
            ).setApplicationName(APP_NAME).build()

            return DriveClient(drive)
        }
    }
}
