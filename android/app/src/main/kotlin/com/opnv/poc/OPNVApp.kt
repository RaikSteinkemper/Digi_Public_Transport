package com.opnv.poc

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

class OPNVApp : Application() {
    companion object {
        lateinit var prefs: SharedPreferences
    }

    override fun onCreate() {
        super.onCreate()
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
    }
}
