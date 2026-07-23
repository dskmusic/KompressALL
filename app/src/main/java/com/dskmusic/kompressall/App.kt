package com.dskmusic.kompressall

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.dskmusic.kompressall.data.Settings

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
        val notificationManager = getSystemService(NotificationManager::class.java)
        // Canal de progreso: importancia normal (no LOW) para que el sistema no lo
        // trate como "silencioso" y lo oculte en la pantalla de bloqueo cuando esa
        // opción está activada; el silencio real de cada actualización lo controla
        // setSilent()/setOnlyAlertOnce() en la propia notificación, no el canal.
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CompressionService.CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        // Canal de "terminado": importancia alta para que suene y vibre con el
        // tono por defecto, y marcado para saltarse No Molestar. bypassDnd solo
        // surte efecto si el usuario lo permite en Ajustes > No molestar >
        // Excepciones de apps (Android exige ese permiso manual, no se puede
        // conceder desde la app).
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CompressionService.DONE_CHANNEL_ID,
                getString(R.string.notif_done_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(true)
                setBypassDnd(true)
            }
        )
    }
}
