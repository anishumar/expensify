package com.anishumar.kharcha

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.anishumar.kharcha.report.Period
import com.anishumar.kharcha.report.PeriodType
import com.anishumar.kharcha.report.ReportWorker
import java.util.concurrent.TimeUnit

class KharchaApp : Application() {

    companion object {
        const val CH_CAPTURE = "captures"
        const val CH_REPORT = "reports"
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_CAPTURE, "Logged payments", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shown when a payment is auto-logged from SMS or a notification" }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_REPORT, "Weekly & monthly reports", NotificationManager.IMPORTANCE_DEFAULT)
        )

        // First run: don't send reports for periods before the app was installed
        val prefs = getSharedPreferences("kharcha", MODE_PRIVATE)
        if (!prefs.contains("reported_week")) {
            prefs.edit()
                .putLong("reported_week", Period.of(PeriodType.WEEK).shift(-1).start)
                .putLong("reported_month", Period.of(PeriodType.MONTH).shift(-1).start)
                .apply()
        }

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "reports",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ReportWorker>(6, TimeUnit.HOURS).build()
        )
    }
}
