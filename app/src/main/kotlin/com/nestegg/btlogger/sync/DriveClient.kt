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

/**
 * Thin wrapper over the Google Drive REST API for our append-CSV use case.
 * Uses the drive.file scope — we can only see/touch files this app created.
 *
 * Drive's REST API has no true append, so [appendCsvRows] does
 * download → concat → re-upload via files.update. For monthly CSVs of a few
 * thousand events the size stays small (<200 KB).
 */
class DriveClient(private val drive: Drive) {

    /**
     * Append [rows] (already-encoded CSV lines, no trailing newline) to
     * bluetooth-log-[deviceTag]-[yearMonth].csv inside the [FOLDER_NAME] folder,
     * creating the folder and/or file if missing. The first creation writes
     * [header] as the first line. Returns the number of rows appended.
     *
     * The deviceTag in the filename means two phones syncing to the same
     * Google account land in two distinct files instead of racing on one.
     */
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
            for (row in rows) {
                append(row)
                append('\n')
            }
        }

        putContent(fileName, folderId, existing, newContent)
        return rows.size
    }

    /**
     * Replace sync-diagnostics-[deviceTag].csv wholesale with [header] plus [rows]
     * (already-encoded CSV lines). Unlike [appendCsvRows] this overwrites rather than
     * appends, because the journal it mirrors is a rotated, bounded snapshot — each
     * upload is the current window, not an ever-growing tail.
     */
    fun overwriteCsv(fileName: String, header: String, rows: List<String>): Int {
        val folderId = ensureFolder(FOLDER_NAME)
        val existing = findFile(fileName, folderId)
        val content = buildString {
            appendLine(header)
            for (row in rows) {
                append(row)
                append('\n')
            }
        }
        putContent(fileName, folderId, existing, content)
        return rows.size
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
