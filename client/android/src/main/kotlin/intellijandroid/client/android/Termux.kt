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

    /**
     * Runs `intellij-backend <arguments>` in the background; [onResult] gets its exit code and output on the main
     * thread, or a failure when Termux couldn't run it (e.g. `allow-external-apps` is off).
     */
    fun run(arguments: List<String>, onResult: (kotlin.Result<Result>) -> Unit) {
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
            .putExtra("com.termux.RUN_COMMAND_PATH", COMMAND)
            .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arguments.toTypedArray())
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", resultIntent)
        runCatching { context.startService(command) }.onFailure { failure ->
            context.unregisterReceiver(receiver)
            onResult(kotlin.Result.failure(failure))
        }
    }

    companion object {
        const val PACKAGE = "com.termux"
        const val PERMISSION = "com.termux.permission.RUN_COMMAND"
        const val COMMAND = "/data/data/com.termux/files/usr/bin/intellij-backend"

        /** `intellij-backend link` / `status`: exit code when no backend is running. */
        const val EXIT_NOT_RUNNING = 3

        /** `intellij-backend start`: another IntelliJ is using the same settings. */
        const val EXIT_SETTINGS_IN_USE = 4

        private val requestCodes = AtomicInteger()
    }
}
