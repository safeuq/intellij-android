package intellijandroid.client.android

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs `intellij-backend` in Termux through Termux's RUN_COMMAND service (docs/connections.md, "Termux setup"). Needs
 * `allow-external-apps = true` in `~/.termux/termux.properties` and the runtime permission [PERMISSION].
 */
class Termux(private val context: Context) {
    data class Result(val exitCode: Int, val stdout: String, val stderr: String)

    val isInstalled: Boolean
        get() = runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

    val hasPermission: Boolean
        get() = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** A folder in Termux's file system; [isProject] when it holds an `.idea` folder. */
    data class Folder(val name: String, val isProject: Boolean)

    /**
     * Runs `<executable> <arguments>` (by default `intellij-backend`) in the background; [onResult] gets its exit code
     * and output on the main thread, or a failure when Termux couldn't run it (e.g. `allow-external-apps` is off).
     */
    fun run(arguments: List<String>, executable: String = COMMAND, onResult: (kotlin.Result<Result>) -> Unit) {
        check(hasPermission) { "The app doesn't have Termux's RUN_COMMAND permission yet" }
        val action = "${context.packageName}.TERMUX_RESULT.${requestCodes.incrementAndGet()}"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                context.unregisterReceiver(this)
                val bundle = intent.getBundleExtra("result")
                onResult(
                    when {
                        bundle == null -> kotlin.Result.failure(IllegalStateException("Termux answered without a result"))
                        bundle.getInt("err", Activity.RESULT_OK) != Activity.RESULT_OK ->
                            kotlin.Result.failure(IllegalStateException("Termux couldn't run intellij-backend: ${bundle.getString("errmsg")}"))
                        else -> kotlin.Result.success(Result(bundle.getInt("exitCode"), bundle.getString("stdout").orEmpty(), bundle.getString("stderr").orEmpty()))
                    },
                )
            }
        }
        // Termux sends the PendingIntent as this app, so a receiver that isn't exported still gets it.
        context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        val resultIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(action).setPackage(context.packageName),
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE,
        )
        val command = Intent("com.termux.RUN_COMMAND")
            .setClassName(PACKAGE, "com.termux.app.RunCommandService")
            .putExtra("com.termux.RUN_COMMAND_PATH", executable)
            .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arguments.toTypedArray())
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", resultIntent)
        runCatching { context.startService(command) }.onFailure { failure ->
            context.unregisterReceiver(receiver)
            onResult(kotlin.Result.failure(failure))
        }
    }

    /**
     * The folders directly inside [path], hidden ones included, sorted like IntelliJ's file chooser (ignoring case).
     * This app can't read Termux's private files itself, so Termux's shell lists them.
     */
    fun listFolders(path: String, onResult: (kotlin.Result<List<Folder>>) -> Unit) =
        run(listOf("-c", LIST_FOLDERS, "sh", path), executable = SHELL) { result ->
            onResult(
                result.mapCatching { output ->
                    check(output.exitCode == 0) { "Can't list $path: ${output.stderr.ifBlank { "exit ${output.exitCode}" }}" }
                    output.stdout.lines().filter { it.isNotEmpty() }
                        .map { line -> Folder(line.substringAfter('/'), isProject = line.startsWith("project/")) }
                        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                },
            )
        }

    companion object {
        const val PACKAGE = "com.termux"
        const val PERMISSION = "com.termux.permission.RUN_COMMAND"
        const val COMMAND = "/data/data/com.termux/files/usr/bin/intellij-backend"

        private const val SHELL = "/data/data/com.termux/files/usr/bin/sh"

        /** Prints `folder/<name>` or `project/<name>` (has `.idea`) per sub-folder of `$1`. Names can't hold `/`. */
        private const val LIST_FOLDERS = """cd "${'$'}1" || exit 1
for entry in * .[!.]* ..?*; do
  [ -d "${'$'}entry" ] || continue
  if [ -d "${'$'}entry/.idea" ]; then echo "project/${'$'}entry"; else echo "folder/${'$'}entry"; fi
done"""

        /** Termux's home folder; RUN_COMMAND passes arguments without a shell, so `~` isn't expanded. */
        const val HOME = "/data/data/com.termux/files/home"

        /** `intellij-backend link` / `status`: exit code when no backend is running. */
        const val EXIT_NOT_RUNNING = 3

        /** `intellij-backend start`: another IntelliJ is using the same settings. */
        const val EXIT_SETTINGS_IN_USE = 4

        private val requestCodes = AtomicInteger()
    }
}
