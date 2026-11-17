package xyz.mikaelavpn.app

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Api.init(this)
    }
}
