package io.embodit.airsketch.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import io.embodit.airsketch.mainApp

class SketchApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        launch(::mainApp)
    }
}
