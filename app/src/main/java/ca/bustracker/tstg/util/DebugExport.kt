package ca.bustracker.tstg.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import ca.bustracker.tstg.data.PortalPage
import java.io.File

object DebugExport {
    fun writePages(context: Context, pages: List<PortalPage>): List<File> {
        val directory = File(context.filesDir, "debug").apply { mkdirs() }
        return pages.mapIndexed { index, page ->
            val name = when {
                page.url.contains("WheresMyBus", ignoreCase = true) -> "wheres-my-bus.html"
                page.url.contains("ChildTransportInfo", ignoreCase = true) ->
                    "child-transport-info.html"

                else -> "portal-page-${index + 1}.html"
            }
            File(directory, name).apply {
                writeText(page.html, Charsets.UTF_8)
            }
        }
    }

    fun share(context: Context, files: List<File>) {
        if (files.isEmpty()) return
        val uris = files.map { file ->
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
        }
        val intent = (if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/html"
                putExtra(Intent.EXTRA_STREAM, uris.first())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "text/html"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        }).apply {
            putExtra(Intent.EXTRA_SUBJECT, "Bus Tracker debug pages")
            putExtra(
                Intent.EXTRA_TEXT,
                "Logged-in BusPlanner pages. They contain personal information; " +
                    "share only with someone you trust.",
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share debug pages"))
    }
}
