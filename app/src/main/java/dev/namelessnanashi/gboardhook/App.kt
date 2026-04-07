package dev.namelessnanashi.gboardhook

import android.app.Application
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlin.concurrent.Volatile

class App : Application(), XposedServiceHelper.OnServiceListener {
    companion object {
        @Volatile
        var service: XposedService? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        Companion.service = service
    }

    override fun onServiceDied(service: XposedService) {
        Companion.service = null
    }
}
