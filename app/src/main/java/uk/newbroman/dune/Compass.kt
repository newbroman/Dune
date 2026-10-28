package uk.newbroman.dune

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The true-north bearing the top of the phone points to (or, held upright, the way the back of the
 * phone faces), smoothed. Null when off or the phone has no compass. Listens only while on screen.
 */
@Composable
fun rememberHeading(enabled: Boolean, lat: Double, lon: Double): State<Float?> {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val state = remember { mutableStateOf<Float?>(null) }
    DisposableEffect(enabled, lat, lon, owner) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (!enabled || sm == null || sensor == null) { state.value = null; return@DisposableEffect onDispose { } }
        val declination = GeomagneticField(lat.toFloat(), lon.toFloat(), 0f, System.currentTimeMillis()).declination
        val r = FloatArray(9); val out = FloatArray(9); val o = FloatArray(3)
        var sx = 0.0; var sy = 0.0; var have = false
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(r, e.values)
                // Flat: where the top edge points. Upright (z axis near horizontal): where the back faces.
                val m = if (abs(r[8]) > 0.6f) r else { SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_X, SensorManager.AXIS_Z, out); out }
                SensorManager.getOrientation(m, o)
                var deg = Math.toDegrees(o[0].toDouble()) + declination + screenRotation(ctx)
                deg = ((deg % 360) + 360) % 360
                // Smooth on the circle so 359° → 1° doesn't swing the sky round.
                val rad = Math.toRadians(deg)
                if (!have) { sx = cos(rad); sy = sin(rad); have = true } else { sx += (cos(rad) - sx) * 0.12; sy += (sin(rad) - sy) * 0.12 }
                val smooth = ((Math.toDegrees(atan2(sy, sx)) + 360) % 360).toFloat()
                val prev = state.value
                if (prev == null || angleDiff(prev, smooth) > 0.4f) state.value = smooth
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        // Only while Dune is actually on screen: pause with the activity.
        val lifecycle = owner.lifecycle
        val obs = LifecycleEventObserver { _, ev ->
            when (ev) {
                Lifecycle.Event.ON_RESUME -> sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
                Lifecycle.Event.ON_PAUSE -> sm.unregisterListener(listener)
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); sm.unregisterListener(listener) }
    }
    return state
}

private fun angleDiff(a: Float, b: Float): Float { val d = abs(a - b) % 360; return if (d > 180) 360 - d else d }

private fun screenRotation(ctx: Context): Int {
    val rot = if (Build.VERSION.SDK_INT >= 30) ctx.display?.rotation ?: 0
    else @Suppress("DEPRECATION") (ctx.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.rotation
    return when (rot) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
}
