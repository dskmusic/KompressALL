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
        // Canal de progreso: silencioso, se actualiza muchas veces por segundo.
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CompressionService.CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
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
