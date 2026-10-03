package org.openmcx.client.app.data

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.openmcx.client.mcdata.FdPersistence
import org.openmcx.client.mcdata.FdRecord
import java.io.File

/**
 * File distribution records on the device, one JSON file per record, written atomically
 * (write, then rename). Kept apart from the history database so no schema upgrade is needed.
 */
class AttachmentStore(private val directory: File) : FdPersistence {
    constructor(context: Context) : this(File(context.filesDir, "attachments/records"))

    init { directory.mkdirs() }

    private fun file(id: String) = File(directory, "${id.filter { it.isLetterOrDigit() || it == '-' }}.json")

    @Synchronized
    override fun load(): List<FdRecord> = directory.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        .mapNotNull { runCatching { FdRecord.fromJson(Json.parseToJsonElement(it.readText()).jsonObject) }.getOrNull() }
        .sortedBy { it.at }

    @Synchronized
    override fun remove(id: String) { file(id).delete() }

    @Synchronized
    override fun put(record: FdRecord) {
        val target = file(record.id)
        val temporary = File(directory, "${target.name}.tmp")
        temporary.writeText(record.toJson().toString())
        if (!temporary.renameTo(target)) { temporary.delete(); throw IllegalStateException("cannot store attachment record") }
    }
}
