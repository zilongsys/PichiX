package com.oceanlab.pichix

import android.app.Application
import com.oceanlab.pichix.data.BotEventLog
import com.oceanlab.pichix.data.PichiFileLog

class PichixApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PichiFileLog.init(this)
        BotEventLog.init(this)
    }
}
