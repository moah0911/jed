package dev.jed.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.jed.app.notes.NoteRepository
import dev.jed.app.ui.JedApp
import dev.jed.app.ui.JedTheme
import dev.jed.app.ui.JedViewModel
import dev.jed.app.ui.JedViewModelFactory

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val repo = NoteRepository(this)
        setContent {
            JedTheme {
                val vm: JedViewModel = viewModel(factory = JedViewModelFactory(repo, this))
                // Files are shared ground (file managers, git, attached
                // folders): re-read on return, the focus-refresh belt.
                val owner = LocalLifecycleOwner.current
                DisposableEffect(owner) {
                    val obs = LifecycleEventObserver { _, e ->
                        if (e == Lifecycle.Event.ON_RESUME) vm.refresh()
                    }
                    owner.lifecycle.addObserver(obs)
                    onDispose { owner.lifecycle.removeObserver(obs) }
                }
                JedApp(vm)
            }
        }
    }
}
