package io.embodit.airsketch

import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import io.embodit.airsketch.ui.SketchScreen
import io.embodit.airsketch.ui.theme.SketchTheme

fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer {
            SketchTheme {
                SketchScreen()
            }
        }
    }
