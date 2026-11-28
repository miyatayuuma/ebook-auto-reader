package io.github.ebookautoreader.reader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

enum class ControlCommand { PRIMARY, END }

internal fun commandAllowed(mode: String, command: ControlCommand): Boolean = when (command) {
    ControlCommand.PRIMARY -> mode == "IDLE" || mode == "READING" || mode == "PAUSED"
    ControlCommand.END -> mode == "READING" || mode == "PAUSED" || mode == "PROBE" || mode == "BUSY"
}

internal object ReaderControls {
    private const val CHANNEL = "reader_controls"
    private const val NOTIFICATION_ID = 2501
    private var service: ReaderAccessibility? = null
    private var mode = "IDLE"
    private var status = "読み上げ停止中"

    fun attach(reader: ReaderAccessibility) { service = reader }
    fun detach(reader: ReaderAccessibility) {
        if (service === reader) {
            service = null
            reader.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            TileService.requestListeningState(reader, ComponentName(reader, ReaderTileService::class.java))
        }
    }
    fun send(command: ControlCommand) { service?.controlCommand(command) }
    fun tileLabel(): String = when (mode) {
        "READING" -> "一時停止"
        "PAUSED" -> "再開"
        "IDLE" -> "読み上げ開始"
        else -> "処理中"
    }
    fun tileState(): Int = if (service == null || !commandAllowed(mode, ControlCommand.PRIMARY))
        Tile.STATE_UNAVAILABLE else if (mode == "READING") Tile.STATE_ACTIVE else Tile.STATE_INACTIVE

    fun update(context: Context, next: String, message: String) {
        mode = next
        status = message
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "読み上げ操作", NotificationManager.IMPORTANCE_LOW))
        fun action(command: ControlCommand): PendingIntent {
            val intent = Intent(context, ReaderControlReceiver::class.java).setAction(command.name)
            return PendingIntent.getBroadcast(context, command.ordinal, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Ebook Auto Reader · ${tileLabel()}")
            .setContentText(status.replace('\n', ' '))
            .setStyle(Notification.BigTextStyle().bigText(status))
            .setOnlyAlertOnce(true)
            .setOngoing(next != "IDLE")
        if (commandAllowed(next, ControlCommand.PRIMARY)) {
            builder.addAction(Notification.Action.Builder(null, tileLabel(), action(ControlCommand.PRIMARY)).build())
        }
        if (commandAllowed(next, ControlCommand.END)) {
            builder.addAction(Notification.Action.Builder(null, "終了", action(ControlCommand.END)).build())
        }
        try { manager.notify(NOTIFICATION_ID, builder.build()) } catch (_: SecurityException) { }
        TileService.requestListeningState(context, ComponentName(context, ReaderTileService::class.java))
    }
}

class ReaderControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ControlCommand.entries.firstOrNull { it.name == intent.action }?.let(ReaderControls::send)
    }
}

class ReaderTileService : TileService() {
    override fun onStartListening() { super.onStartListening(); refresh() }
    override fun onClick() { super.onClick(); ReaderControls.send(ControlCommand.PRIMARY); refresh() }
    private fun refresh() {
        qsTile?.apply {
            label = ReaderControls.tileLabel()
            state = ReaderControls.tileState()
            updateTile()
        }
    }
}
