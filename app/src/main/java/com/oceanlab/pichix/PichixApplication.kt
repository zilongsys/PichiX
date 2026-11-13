package com.oceanlab.pichix

import android.app.Application
import com.oceanlab.pichix.data.BotEventLog
import com.oceanlab.pichix.data.PichiFileLog

class PichixApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PichiFileLog.init(this)
        BotEventLog.init(this)
        com.oceanlab.pichix.dashboardcontrol.PichixDashboard.init(this)
        // Lecturas del CSV fuera del hilo principal; de paso se precalienta la caché de parseo.
        Thread {
            try {
                com.oceanlab.pichix.data.OfferLogCsvStore(this).readAllEntries()
            } catch (_: Exception) {
            }
            try {
                com.oceanlab.pichix.util.PeakHoursScheduler.reschedule(this)
            } catch (_: Exception) {
            }
            try {
                com.oceanlab.pichix.util.ClickHoursGate.recompute(this)
            } catch (_: Exception) {
            }
        }.start()
    }
}
