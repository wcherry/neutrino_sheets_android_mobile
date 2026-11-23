package com.neutrino.sheets

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.launch

class SheetsApplication : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        // Foreground: top up retired keys, so a workbook sealed to an older key still opens.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (!graph.account.auth.isSignedIn) return
                graph.scope.launch {
                    if (graph.account.keys.hasKey) runCatching { graph.account.keys.pullKeyFile() }.onSuccess { graph.account.keyringChanged() }
                }
            }
        })
    }
}

val Context.graph: AppGraph get() = (applicationContext as SheetsApplication).graph
