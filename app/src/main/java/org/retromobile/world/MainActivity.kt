package org.retromobile.world

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager

class MainActivity : Activity() {
    private lateinit var phoneView: RetroPhoneView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        val profiles = runCatching {
            assets.open("device-profiles.json").bufferedReader().use { reader ->
                ProfileJsonLoader.parse(reader.readText())
            }
        }.getOrElse { ProfileCatalog.builtIn }
        phoneView = RetroPhoneView(this, profiles)
        setContentView(phoneView)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (::phoneView.isInitialized && phoneView.handleBack()) return
        super.onBackPressed()
    }
}
