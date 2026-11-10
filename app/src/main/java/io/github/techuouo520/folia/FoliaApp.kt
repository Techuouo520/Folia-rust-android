// Process entry: Android context first, then the Rust bridge kept for the process lifetime.
package io.github.techuouo520.folia

import android.app.Application
import android.content.Context
import android.util.Log
import io.github.techuouo520.folia.bridge.FoliaBridge
import java.util.Locale

class FoliaApp : Application() {
    lateinit var bridge: FoliaBridge
        private set

    override fun onCreate() {
        super.onCreate()
        AndroidContext.nativeInit(this)
        bridge = FoliaBridge.initialize(filesDir.absolutePath, cacheDir.absolutePath)
        bridge.setSystemLocale(Locale.getDefault().toLanguageTag())
    }

    companion object {
        fun from(context: Context): FoliaApp = context.applicationContext as FoliaApp
    }
}

fun FoliaBridge.t(key: String): String = label(key)
