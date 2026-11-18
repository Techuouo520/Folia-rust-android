package io.github.techuouo520.folia

import android.content.ComponentCallbacks2
import android.os.Build

internal fun shouldTrimSceneMemory(level: Int): Boolean =
    if (Build.VERSION.SDK_INT < 34) legacyMemoryPressure(level)
    else level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN

// Android 14 stopped delivering running-memory levels, but older supported devices still do.
@Suppress("DEPRECATION")
private fun legacyMemoryPressure(level: Int): Boolean = level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
