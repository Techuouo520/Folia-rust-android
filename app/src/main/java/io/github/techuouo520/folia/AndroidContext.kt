// Loads the bridge library and passes the application Context into ndk-context once.
package io.github.techuouo520.folia

import android.content.Context

class AndroidContext {
    companion object {
        init {
            System.loadLibrary("folia_bridge")
        }

        @JvmStatic
        external fun nativeInit(context: Context)
    }
}
