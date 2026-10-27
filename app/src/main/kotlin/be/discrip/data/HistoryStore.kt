package be.discrip.data

import android.util.AtomicFile
import android.util.Log
import be.discrip.core.history.RipHistoryJson
import be.discrip.core.history.RipRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import java.io.File
import java.io.IOException

/**
 * The rip history ("Geschiedenis"), newest first, as JSON in the app's own storage. A file that
 * cannot be read is kept aside as `<name>.broken` and the history starts again; a failed write
 * (a full disk) keeps the change in memory for this run.
 */
class HistoryStore(private val file: File) {

    private val atomic = AtomicFile(file)
    private val mutex = Mutex()
    private val state = MutableStateFlow<List<RipRecord>?>(null)

    /** Null until [load] or the first change. */
    val records: StateFlow<List<RipRecord>?> = state.asStateFlow()

    suspend fun load(): List<RipRecord> = withContext(Dispatchers.IO) {
        mutex.withLock { state.value ?: read().also { state.value = it } }
    }

    suspend fun add(record: RipRecord) = change { records -> listOf(record) + records.filter { it.id != record.id } }

    suspend fun update(id: String, transform: (RipRecord) -> RipRecord) =
        change { records -> records.map { if (it.id == id) transform(it) else it } }

    suspend fun remove(id: String) = change { records -> records.filter { it.id != id } }

    private suspend fun change(transform: (List<RipRecord>) -> List<RipRecord>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val changed = transform(state.value ?: read()).take(MAX_RECORDS)
            state.value = changed
            write(changed)
        }
    }

    private fun read(): List<RipRecord> {
        if (!file.exists()) return emptyList()
        return try {
            RipHistoryJson.decode(atomic.readFully().decodeToString())
        } catch (e: IOException) {
            Log.w(TAG, "Cannot read the history", e)
            emptyList()
        } catch (e: SerializationException) {
            keepAside(e)
        } catch (e: IllegalArgumentException) {
            keepAside(e)
        }
    }

    private fun keepAside(e: Exception): List<RipRecord> {
        Log.w(TAG, "The history file is damaged; starting a new one", e)
        file.copyTo(File(file.path + ".broken"), overwrite = true)
        atomic.delete()
        return emptyList()
    }

    private fun write(records: List<RipRecord>) {
        val stream = try {
            atomic.startWrite()
        } catch (e: IOException) {
            Log.w(TAG, "Cannot save the history", e)
            return
        }
        try {
            stream.write(RipHistoryJson.encode(records).encodeToByteArray())
            atomic.finishWrite(stream)
        } catch (e: IOException) {
            atomic.failWrite(stream)
            Log.w(TAG, "Cannot save the history", e)
        }
    }

    private companion object {
        const val TAG = "DiscRip"
        const val MAX_RECORDS = 500
    }
}
