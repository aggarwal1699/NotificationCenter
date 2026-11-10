package com.waterwatch

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

class WaterWatchMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        getSharedPreferences("waterwatch", Context.MODE_PRIVATE).edit().putString("fcm_token", token).apply()
    }
    override fun onMessageReceived(message: RemoteMessage) {
        val channelId = "water_overflow_alerts"
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(channelId, "Water overflow alerts", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).putExtra("alert_id", message.data["alert_id"]), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val body = message.notification?.body ?: "Please check the motor and switch it OFF if running."
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(message.notification?.title ?: "WATER OVERFLOW ALERT")
            .setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).setContentIntent(open).build()
        manager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
    }
}
