package com.cfks.supersys

import android.app.Application
import com.yc.toollib.crash.CrashHandler
import com.yc.toollib.crash.CrashListener
import com.yc.toollib.crash.CrashToolUtils

class SuperSysApp : Application() {

    companion object {
        private lateinit var instance: SuperSysApp
        fun getInstance(): SuperSysApp = instance
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        CrashHandler.getInstance().init(this, object : CrashListener {
            override fun againStartApp() {
                CrashToolUtils.startCrashListActivity(this@SuperSysApp)
            }

            override fun recordException(ex: Throwable) {
                // Exception already recorded by CrashHandler
            }
        })
    }
}
