// Show Rust-provided translations and version after initializing app-private directories.
package io.github.techuouo520.folia

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.techuouo520.folia.bridge.FoliaBridge
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class BridgeResult(val title: String, val language: String, val version: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val result by produceState<Result<BridgeResult>?>(initialValue = null) {
                    value = withContext(Dispatchers.IO) {
                        try {
                            FoliaBridge.initialize(filesDir.absolutePath, cacheDir.absolutePath).use { bridge ->
                                val locale = Locale.getDefault().toLanguageTag()
                                Result.success(BridgeResult(bridge.text(locale, "app.name"), bridge.text(locale, "options.appLanguage"), bridge.version()))
                            }
                        } catch (error: Exception) {
                            Log.e("FoliaBridge", "Rust bridge initialization failed", error)
                            Result.failure(error)
                        } catch (error: LinkageError) {
                            Log.e("FoliaBridge", "Rust bridge loading failed", error)
                            Result.failure(error)
                        }
                    }
                }
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.safeDrawingPadding().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val state = result
                        if (state == null) {
                            CircularProgressIndicator()
                        } else {
                            state.fold(
                                onSuccess = { bridge ->
                                    Text(bridge.title, style = MaterialTheme.typography.headlineLarge)
                                    Text(bridge.language, style = MaterialTheme.typography.titleMedium)
                                    Text("Rust ${bridge.version}", style = MaterialTheme.typography.bodyLarge)
                                },
                                onFailure = { error -> Text(error.javaClass.simpleName, color = MaterialTheme.colorScheme.error) }
                            )
                        }
                    }
                }
            }
        }
    }
}
