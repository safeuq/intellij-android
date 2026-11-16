package intellijandroid.client.android

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/** How a saved connection gets a fresh join link (docs/connections.md, "History entry"). SSH comes later. */
enum class ConnectionKind { LINK, TERMUX }

/**
 * A backend the app connected to. [identity] is the [intellijandroid.client.connection.ClientCertificate] the backend
 * accepted, encoded: with it the same [lastLink] works again after an app restart, until the backend restarts.
 */
data class SavedConnection(
    val kind: ConnectionKind,
    /** For example `192.168.0.105:5990`, or `This phone · smoke` for Termux. */
    val name: String,
    val host: String,
    val port: Int,
    /** The project folder on the backend; only Termux connections know it, e.g. `/data/data/com.termux/files/home/intellij/smoke`. */
    val project: String?,
    val fingerprint: String,
    val lastLink: String,
    val identity: String,
    val openFile: String?,
    /** Milliseconds since 1970. */
    val lastConnected: Long,
) {
    /** One entry per backend address, or per project for Termux (its port can change between starts). */
    val key: String get() = if (kind == ConnectionKind.TERMUX) "termux:$project" else "link:$host:$port"
}

/**
 * Saved connections, newest first, as JSON in the app's private shared preferences. They hold client private keys,
 * which is fine there: a key only works with its backend's join token, and only until that backend restarts.
 */
class ConnectionHistory(context: Context) {
    private val preferences = context.getSharedPreferences("connection-history", Context.MODE_PRIVATE)

    fun entries(): List<SavedConnection> {
        val array = JSONArray(preferences.getString(KEY, "[]"))
        return (0 until array.length()).map { index -> array.getJSONObject(index).toSavedConnection() }
    }

    fun save(entry: SavedConnection) = write(listOf(entry) + entries().filter { it.key != entry.key })

    fun remove(entry: SavedConnection) = write(entries().filter { it.key != entry.key })

    private fun write(entries: List<SavedConnection>) =
        preferences.edit { putString(KEY, JSONArray(entries.map { it.toJson() }).toString()) }

    private companion object {
        const val KEY = "entries"

        fun SavedConnection.toJson() = JSONObject()
            .put("kind", kind.name)
            .put("name", name)
            .put("host", host)
            .put("port", port)
            .put("project", project)
            .put("fingerprint", fingerprint)
            .put("lastLink", lastLink)
            .put("identity", identity)
            .put("openFile", openFile)
            .put("lastConnected", lastConnected)

        fun JSONObject.toSavedConnection() = SavedConnection(
            kind = ConnectionKind.valueOf(getString("kind")),
            name = getString("name"),
            host = getString("host"),
            port = getInt("port"),
            project = optString("project").takeIf { has("project") && !isNull("project") },
            fingerprint = getString("fingerprint"),
            lastLink = getString("lastLink"),
            identity = getString("identity"),
            openFile = optString("openFile").takeIf { has("openFile") && !isNull("openFile") },
            lastConnected = getLong("lastConnected"),
        )
    }
}
