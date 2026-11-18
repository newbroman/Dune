package uk.newbroman.dune

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Geocoder
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.CalendarContract
import android.provider.Settings
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Things you might ask of the phone itself: torch, volume, battery, weather, calendar, camera, sums… */
sealed class PhoneCmd : Cmd() {
    abstract val label: String
    data class Torch(val on: Boolean?) : PhoneCmd() { override val label get() = "Torch " + when (on) { true -> "on"; false -> "off"; null -> "on/off" } }
    /** [to] a level 0–100, or null with [step] +1 up / -1 down; [mute] true/false. */
    data class Volume(val step: Int = 0, val to: Int? = null, val mute: Boolean? = null) : PhoneCmd() {
        override val label get() = when { mute == true -> "Mute"; mute == false -> "Unmute"; to != null -> "Volume to $to%"; step > 0 -> "Volume up"; else -> "Volume down" }
    }
    data class Ringer(val mode: String) : PhoneCmd() { override val label get() = mode.replaceFirstChar { it.uppercase() } + " mode" }
    data class Dnd(val on: Boolean) : PhoneCmd() { override val label get() = "Do not disturb " + if (on) "on" else "off" }
    data class Brightness(val step: Int = 0, val to: Int? = null) : PhoneCmd() {
        override val label get() = if (to != null) "Brightness to $to%" else if (step > 0) "Brighter" else "Dimmer"
    }
    data class Radio(val what: String, val on: Boolean?) : PhoneCmd() { override val label get() = "$what " + when (on) { true -> "on"; false -> "off"; null -> "settings" } }
    object Battery : PhoneCmd() { override val label = "Battery level" }
    data class DateTime(val what: String) : PhoneCmd() { override val label get() = if (what == "time") "The time" else "Today's date" }
    data class Weather(val day: Int, val place: String?, val rain: Boolean) : PhoneCmd() {
        override val label get() = (if (rain) "Will it rain" else "Weather") + (if (day == 1) " tomorrow" else "") + (place?.let { " in $it" } ?: "")
    }
    data class Agenda(val day: Int?, val next: Boolean = false) : PhoneCmd() {
        override val label get() = if (next) "Next appointment" else "What's on " + if (day == 1) "tomorrow" else "today"
    }
    data class Camera(val video: Boolean, val selfie: Boolean) : PhoneCmd() {
        override val label get() = if (video) (if (selfie) "Record a selfie video" else "Take a video") else if (selfie) "Take a selfie" else "Take a photo"
    }
    data class OpenKind(val kind: String) : PhoneCmd() { override val label get() = "Open " + kind }
    object WhereAmI : PhoneCmd() { override val label = "Where am I" }
    data class ScreenAct(val action: String) : PhoneCmd() { override val label get() = if (action == "lock") "Lock the phone" else "Take a screenshot" }
    data class Sum(val expr: String, val value: Double) : PhoneCmd() { override val label get() = "$expr = ${Phone.num(value)}" }
    data class Chance(val what: String) : PhoneCmd() { override val label get() = if (what == "coin") "Flip a coin" else "Roll a dice" }
}

object Phone {
    // ---------------------------------------------------------------- understanding

    private const val ON = "(?:on|up)"; private const val OFF = "(?:off|down)"

    fun parse(s: String): Cmd? {
        // torch
        Regex("^(?:turn |switch |put )?(on|off)? ?(?:the |my )?(torch|flashlight|flash ?light)(?: (on|off))?$").find(s)?.let { m ->
            val w = m.groupValues[1].ifEmpty { m.groupValues[3] }
            return PhoneCmd.Torch(when (w) { "on" -> true; "off" -> false; else -> null })
        }
        Regex("^(?:turn|switch) (?:the |my )?(torch|flashlight|flash ?light) (on|off)$").find(s)?.let { return PhoneCmd.Torch(it.groupValues[2] == "on") }

        // volume
        if (Regex("^(?:volume up|louder|(?:turn|put) (?:it|the volume|the music|the sound) up|(?:turn )?up the (?:volume|music|sound)|increase (?:the )?volume|raise (?:the )?volume)$").matches(s)) return PhoneCmd.Volume(step = 1)
        if (Regex("^(?:volume down|quieter|softer|(?:turn|put) (?:it|the volume|the music|the sound) down|(?:turn )?down the (?:volume|music|sound)|lower (?:the )?volume|decrease (?:the )?volume|reduce (?:the )?volume)$").matches(s)) return PhoneCmd.Volume(step = -1)
        if (Regex("^(?:mute|silence)(?: the)?(?: phone| sound| volume| music| it)?$").matches(s)) return PhoneCmd.Volume(mute = true)
        if (Regex("^unmute(?: the)?(?: phone| sound| volume| music| it)?$|^(?:turn |put )?(?:the )?sound (?:back )?on$").matches(s)) return PhoneCmd.Volume(mute = false)
        if (Regex("^(?:max(?:imum)? volume|volume (?:max|maximum|full)|full volume|turn it all the way up)$").matches(s)) return PhoneCmd.Volume(to = 100)
        Regex("^(?:set |put |turn )?(?:the )?volume (?:to |at )?([a-z]+|\\d+)(?: ?%| percent)?$").find(s)?.let { m ->
            val n = Assistant.num(m.groupValues[1]) ?: return@let
            return PhoneCmd.Volume(to = if (n <= 10 && !s.contains("%") && !s.contains("percent")) n * 10 else n.coerceIn(0, 100))
        }

        // ringer and do not disturb
        Regex("^(?:turn on |switch on |go (?:to|into|on) |put (?:the |my )?phone (?:on|in|into|to) |set (?:the |my )?phone to )?(silent|vibrate|vibration)(?: mode)?(?: on)?$").find(s)?.let {
            return PhoneCmd.Ringer(if (it.groupValues[1] == "silent") "silent" else "vibrate")
        }
        if (Regex("^(?:turn |switch )?off (?:silent|vibrate)(?: mode)?$|^(?:silent|vibrate) (?:mode )?off$|^(?:ring|normal|sound) mode$|^turn (?:the )?ringer on$|^ringer on$").matches(s)) return PhoneCmd.Ringer("normal")
        Regex("^(?:turn |switch )?(on|off) do not disturb$|^do not disturb ?(on|off)?$|^(?:turn |switch )?do not disturb (on|off)$|^dnd (on|off)$").find(s)?.let { m ->
            val w = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: "on"
            return PhoneCmd.Dnd(w == "on")
        }

        // brightness
        if (Regex("^(?:brightness up|brighter|make (?:the )?screen brighter|(?:turn |put )?(?:the )?brightness up|increase (?:the )?brightness|(?:turn )?up the brightness)$").matches(s)) return PhoneCmd.Brightness(step = 1)
        if (Regex("^(?:brightness down|dimmer|darker|dim (?:the )?screen|make (?:the )?screen (?:dimmer|darker)|(?:turn |put )?(?:the )?brightness down|decrease (?:the )?brightness|lower (?:the )?brightness|(?:turn )?down the brightness)$").matches(s)) return PhoneCmd.Brightness(step = -1)
        if (Regex("^(?:max(?:imum)? brightness|brightness (?:max|maximum|full)|full brightness)$").matches(s)) return PhoneCmd.Brightness(to = 100)
        if (Regex("^(?:min(?:imum)? brightness|brightness (?:min|minimum|lowest))$").matches(s)) return PhoneCmd.Brightness(to = 0)
        Regex("^(?:set |put |turn )?(?:the )?brightness (?:to |at )?([a-z]+|\\d+)(?: ?%| percent)?$").find(s)?.let { m ->
            val n = Assistant.num(m.groupValues[1]) ?: return@let
            return PhoneCmd.Brightness(to = if (n <= 10 && !s.contains("%") && !s.contains("percent")) n * 10 else n.coerceIn(0, 100))
        }

        // wi-fi, bluetooth, data, flight mode, location, hotspot
        val radio = "(wi-?fi|wi fi|bluetooth|mobile data|data|airplane mode|aeroplane mode|flight mode|location|gps|hotspot|nfc)"
        Regex("^(?:turn |switch )?(on|off) (?:the |my )?$radio$|^(?:turn |switch )(?:the |my )?$radio (on|off)$|^$radio (on|off)$|^open (?:the )?$radio settings$").find(s)?.let { m ->
            val g = m.groupValues
            val what = listOf(g[2], g[3], g[5], g[7]).first { it.isNotEmpty() }
            val on = listOf(g[1], g[4], g[6]).firstOrNull { it.isNotEmpty() }?.let { it == "on" }
            return PhoneCmd.Radio(when {
                what.startsWith("wi") -> "Wi-Fi"; what == "bluetooth" -> "Bluetooth"; what.contains("data") -> "Mobile data"
                what.contains("plane") || what.startsWith("flight") -> "Flight mode"; what == "gps" || what == "location" -> "Location"
                what == "hotspot" -> "Hotspot"; else -> "NFC"
            }, on)
        }

        // battery, date, time
        if (Regex("^(?:battery|battery level|battery life|how much battery.*|how'?s (?:my|the) battery|what'?s (?:my |the )?battery(?: level| at)?|is (?:it|the phone|my phone) charging|am i charging|how much charge.*)$").matches(s)) return PhoneCmd.Battery
        if (Regex("^(?:what'?s|what is) (?:the |today'?s )?date(?: today)?$|^what date is it(?: today)?$|^what day is it(?: today)?$|^what'?s today$|^today'?s date$|^the date$").matches(s)) return PhoneCmd.DateTime("date")
        if (Regex("^what time is it(?: now)?$|^(?:what'?s|what is) the time in english$|^the time in english$").matches(s)) return PhoneCmd.DateTime("time")

        // weather
        Regex("^(?:what'?s|what is|how'?s|how is) the (?:weather|forecast)(?: like)?(?: going to be(?: like)?)?(?: (today|tomorrow|now))?(?: (?:in|for|at) (.+?))?(?: (today|tomorrow))?$|^(?:weather|forecast)(?: (today|tomorrow|now))?(?: (?:in|for|at) (.+?))?(?: (today|tomorrow))?$").find(s)?.let { m ->
            val g = m.groupValues
            val day = if (listOf(g[1], g[3], g[4], g[6]).any { it == "tomorrow" }) 1 else 0
            return PhoneCmd.Weather(day, g[2].ifEmpty { g[5] }.ifEmpty { null }, rain = false)
        }
        Regex("^(?:will it|is it going to|is it gonna|does it look like it'?ll) (?:rain|snow)(?: (today|tomorrow|later))?(?: (?:in|at) (.+?))?(?: (today|tomorrow))?$|^do i need (?:an umbrella|a coat|a jacket)(?: (today|tomorrow))?$").find(s)?.let { m ->
            val g = m.groupValues
            return PhoneCmd.Weather(if (listOf(g[1], g[3], g[4]).contains("tomorrow")) 1 else 0, g[2].ifEmpty { null }, rain = true)
        }
        if (Regex("^(?:how (?:cold|hot|warm) is it|what'?s the temperature|what is the temperature|how many degrees is it)(?: outside| now| today)?$").matches(s)) return PhoneCmd.Weather(0, null, false)

        // calendar
        Regex("^(?:what'?s|what is) on(?: my| the)?(?: calendar| diary| agenda| schedule)?(?: for)? ?(today|tomorrow)?$|^what (?:have i got|do i have|am i doing)(?: on)? ?(today|tomorrow)?$|^(?:any|have i got any|do i have any) (?:appointments|meetings|events|plans)(?: for)? ?(today|tomorrow)?$|^(?:my )?(?:calendar|diary|agenda|schedule)(?: for)? ?(today|tomorrow)?$").find(s)?.let { m ->
            return PhoneCmd.Agenda(if (m.groupValues.drop(1).contains("tomorrow")) 1 else 0)
        }
        if (Regex("^(?:what'?s|what is|when'?s|when is) my next (?:appointment|meeting|event)$|^next (?:appointment|meeting|event)$").matches(s)) return PhoneCmd.Agenda(null, next = true)

        // camera
        Regex("^(?:take|snap|grab) (?:a |me a )?(photo|picture|pic|snap|snapshot|selfie)(?: of me)?$|^(?:take|record|shoot|make|film|start recording) (?:a |me a )?(selfie )?(video|movie|film|clip)$|^(?:record|film) (?:a )?(selfie)$|^(?:start )?(?:video )?recording(?: a)? video$").find(s)?.let { m ->
            val g = m.groupValues
            val video = g[3].isNotEmpty() || s.contains("record") && !s.contains("selfie") || s.contains("video")
            val selfie = g[1] == "selfie" || g[2].isNotEmpty() || g[4].isNotEmpty()
            return PhoneCmd.Camera(video, selfie)
        }

        // where am I, lock, screenshot, coin, dice
        if (Regex("^where am i(?: now| right now)?$|^what'?s my (?:location|address)$|^where are we$").matches(s)) return PhoneCmd.WhereAmI
        if (Regex("^(?:lock|lock (?:the |my )?(?:phone|screen)|screen off|turn (?:the )?screen off)$").matches(s)) return PhoneCmd.ScreenAct("lock")
        if (Regex("^(?:take a |grab a )?screen ?shot$|^screenshot (?:this|the screen)$|^capture (?:the )?screen$").matches(s)) return PhoneCmd.ScreenAct("shot")
        if (Regex("^(?:flip|toss) a coin$|^heads or tails$").matches(s)) return PhoneCmd.Chance("coin")
        if (Regex("^(?:roll|throw) (?:a |the )?(?:dice|die)$").matches(s)) return PhoneCmd.Chance("dice")

        // kinds of app, opened whichever app the phone uses for it
        Regex("^(?:open |launch |start |show |go to )?(?:the |my )?(calculator|calendar|contacts|address book|gallery|photos|pictures|messages|texts|sms|email|mail|inbox|browser|internet|web browser|files|file manager|downloads|settings|clock|alarms|stopwatch|music|camera|phone|dialler|dialer|keypad|play store|app store)(?: app)?$").find(s)?.let {
            return PhoneCmd.OpenKind(it.groupValues[1])
        }

        // sums: "what's 12 times 7", "15 percent of 80", "square root of 81"
        Regex("^(?:what'?s|what is|calculate|how much is|work out) (.+)$").find(s)?.let { m ->
            Calc.eval(m.groupValues[1])?.let { v -> return PhoneCmd.Sum(m.groupValues[1], v) }
        }
        Calc.eval(s)?.let { v -> if (Regex("\\d").containsMatchIn(s) && Regex("[+*/^x-]|plus|minus|times|divided|percent|root|squared|cubed|power").containsMatchIn(s)) return PhoneCmd.Sum(s, v) }

        // "remind me to call mum at 5" → an alarm labelled with it; without a time it stays a task
        Regex("^remind me (?:to )?(.+)$").find(s)?.let { m ->
            val alarm = Alarms.parse("set an alarm to " + m.groupValues[1])
            if (alarm is Cmd.Alarm && alarm.label != null) return alarm
        }
        return null
    }

    fun num(v: Double): String = if (v == Math.floor(v) && kotlin.math.abs(v) < 1e12) v.toLong().toString()
        else String.format(Locale.UK, "%.6f", v).trimEnd('0').trimEnd('.')

    // ---------------------------------------------------------------- doing

    /** Answer later (weather, calendar, where am I): shown, and spoken if the question was spoken. */
    private fun later(act: MainActivity, vm: LauncherVM, block: suspend () -> String) {
        act.lifecycleScope.launch {
            val r = runCatching { block() }.getOrElse { "Sorry, that didn't work (${it.javaClass.simpleName})" }
            vm.assistantReply = r
            if (act.lastAskSpoken) Speaker.speak(act, r)
        }
    }

    fun run(act: MainActivity, vm: LauncherVM, c: PhoneCmd): String? = when (c) {
        is PhoneCmd.Torch -> torch(act, c.on)
        is PhoneCmd.Volume -> volume(act, c)
        is PhoneCmd.Ringer -> ringer(act, c.mode)
        is PhoneCmd.Dnd -> dnd(act, c.on)
        is PhoneCmd.Brightness -> brightness(act, c)
        is PhoneCmd.Radio -> radio(act, c)
        PhoneCmd.Battery -> battery(act)
        is PhoneCmd.DateTime -> {
            val now = LocalDateTime.now()
            if (c.what == "time") "It's " + now.format(DateTimeFormatter.ofPattern("h:mm a", Locale.UK)).lowercase()
            else "It's " + now.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.UK))
        }
        is PhoneCmd.Weather -> { later(act, vm) { weather(act, c) }; "Checking the weather…" }
        is PhoneCmd.Agenda -> agenda(act, c)
        is PhoneCmd.Camera -> camera(act, vm, c)
        is PhoneCmd.OpenKind -> if (openKind(act, vm, c.kind)) "Opening ${c.kind}" else "I couldn't find a ${c.kind} app"
        PhoneCmd.WhereAmI -> { if (!Geo.hasPermission(act)) { act.requestPerm(Manifest.permission.ACCESS_FINE_LOCATION) { }; "Allow location, then ask again" }
            else { later(act, vm) { whereAmI(act) }; "Finding where you are…" } }
        is PhoneCmd.ScreenAct -> screen(act, c.action)
        is PhoneCmd.Sum -> "${c.expr} = ${num(c.value)}"
        is PhoneCmd.Chance -> if (c.what == "coin") (if (Math.random() < 0.5) "Heads" else "Tails") else "You rolled a ${(1..6).random()}"
    }

    // torch
    fun torch(ctx: Context, want: Boolean?): String {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull {
            val ch = cm.getCameraCharacteristics(it)
            ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true && ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: return "This phone has no torch"
        val on = want ?: !torchOn
        return try { cm.setTorchMode(id, on); torchOn = on; "Torch " + if (on) "on" else "off" }
        catch (e: Exception) { "The torch is busy – close the camera and try again" }
    }
    @Volatile private var torchOn = false

    // volume: media volume (what you hear from music, video, maps)
    private fun volume(ctx: Context, c: PhoneCmd.Volume): String {
        val am = ctx.getSystemService(AudioManager::class.java)
        val st = AudioManager.STREAM_MUSIC
        val ui = AudioManager.FLAG_SHOW_UI
        when {
            c.mute == true -> am.adjustStreamVolume(st, AudioManager.ADJUST_MUTE, ui)
            c.mute == false -> am.adjustStreamVolume(st, AudioManager.ADJUST_UNMUTE, ui)
            c.to != null -> am.setStreamVolume(st, (am.getStreamMaxVolume(st) * c.to / 100.0).roundToInt(), ui)
            else -> repeat(2) { am.adjustStreamVolume(st, if (c.step > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, if (it == 1) ui else 0) }
        }
        val pct = (100.0 * am.getStreamVolume(st) / am.getStreamMaxVolume(st)).roundToInt()
        return when (c.mute) { true -> "Muted"; false -> "Unmuted – volume $pct%"; null -> "Volume $pct%" }
    }

    private fun policyAccess(ctx: Context): Boolean {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.isNotificationPolicyAccessGranted) return true
        ctx.tryStart(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        return false
    }

    private fun ringer(ctx: Context, mode: String): String {
        val am = ctx.getSystemService(AudioManager::class.java)
        val want = when (mode) { "silent" -> AudioManager.RINGER_MODE_SILENT; "vibrate" -> AudioManager.RINGER_MODE_VIBRATE; else -> AudioManager.RINGER_MODE_NORMAL }
        return try { am.ringerMode = want; "Phone on " + if (mode == "normal") "ring" else mode }
        catch (e: SecurityException) {
            if (!policyAccess(ctx)) "Allow Dune “Do Not Disturb access” in the screen that's opening, then ask again" else "Couldn't change the ringer"
        }
    }

    private fun dnd(ctx: Context, on: Boolean): String {
        if (!policyAccess(ctx)) return "Allow Dune “Do Not Disturb access” in the screen that's opening, then ask again"
        ctx.getSystemService(NotificationManager::class.java).setInterruptionFilter(
            if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
        return "Do not disturb " + if (on) "on" else "off"
    }

    private fun brightness(act: MainActivity, c: PhoneCmd.Brightness): String {
        if (!Settings.System.canWrite(act)) {
            act.tryStart(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + act.packageName)))
            return "Allow Dune to “modify system settings” in the screen that's opening, then ask again"
        }
        val cr = act.contentResolver
        val cur = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
        // most phones use 0–255; some report more, so scale against the larger of the two
        val max = maxOf(255, cur)
        val target = c.to?.let { max * it / 100 } ?: (cur + c.step * max / 5)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, target.coerceIn(1, max))
        return "Brightness ${(100.0 * target.coerceIn(1, max) / max).roundToInt()}% (auto-brightness off)"
    }

    /** Android doesn't let apps switch Wi-Fi, data, flight mode or location: Dune opens the switch. */
    @SuppressLint("MissingPermission")
    private fun radio(act: MainActivity, c: PhoneCmd.Radio): String {
        fun open(i: Intent, alt: String = Settings.ACTION_WIRELESS_SETTINGS) = act.tryStart(i) || act.tryStart(Intent(alt))
        when (c.what) {
            "Wi-Fi" -> open(if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_WIFI) else Intent(Settings.ACTION_WIFI_SETTINGS))
            "Mobile data" -> open(if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY) else Intent(Settings.ACTION_DATA_ROAMING_SETTINGS))
            "Flight mode" -> open(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS))
            "Location" -> open(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            "NFC" -> open(Intent(Settings.ACTION_NFC_SETTINGS))
            "Hotspot" -> open(Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"))
            "Bluetooth" -> {
                if (c.on == true && Drive.hasBtPermission(act)) {
                    if (act.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.isEnabled == true) return "Bluetooth is already on"
                    if (act.tryStart(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))) return "Tap Allow to turn Bluetooth on"
                }
                open(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            }
        }
        return if (c.on == null) "Opening ${c.what} settings" else "Android only lets you switch ${c.what} yourself – here's the switch"
    }

    private fun battery(ctx: Context): String {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val st = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = (st?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val full = st?.getIntExtra(BatteryManager.EXTRA_STATUS, 0) == BatteryManager.BATTERY_STATUS_FULL
        val left = if (Build.VERSION.SDK_INT >= 28 && plugged) bm.computeChargeTimeRemaining().takeIf { it > 0 } else null
        return "Battery $pct%" + when {
            full -> ", full"
            plugged -> ", charging" + (left?.let { " – full in about ${(it / 60_000).let { m -> if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min" }}" } ?: "")
            else -> ""
        }
    }

    private suspend fun weather(ctx: Context, c: PhoneCmd.Weather): String {
        val place = if (c.place != null) Geo.lookup(c.place) ?: return "I couldn't find ${c.place}"
            else Geo.current(ctx) ?: return if (Geo.hasPermission(ctx)) "I couldn't get your location" else "Allow location for local weather (Settings › Voice commands check)"
        val url = String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
            "&current=temperature_2m,weather_code,wind_speed_10m&daily=weather_code,temperature_2m_max,temperature_2m_min," +
            "precipitation_probability_max,precipitation_sum,wind_speed_10m_max&timezone=auto&forecast_days=2&wind_speed_unit=mph", place.lat, place.lon)
        val j = withContext(Dispatchers.IO) { JSONObject(get(url)) }
        val d = j.getJSONObject("daily"); val i = c.day
        val hi = d.getJSONArray("temperature_2m_max").getDouble(i).roundToInt(); val lo = d.getJSONArray("temperature_2m_min").getDouble(i).roundToInt()
        val rain = d.getJSONArray("precipitation_probability_max").optInt(i, -1); val mm = d.getJSONArray("precipitation_sum").optDouble(i, 0.0)
        val wind = d.getJSONArray("wind_speed_10m_max").getDouble(i).roundToInt()
        val desc = Weather.describe(d.getJSONArray("weather_code").getInt(i)).lowercase()
        val where = place.name
        if (c.rain) {
            val day = if (i == 1) "tomorrow" else "today"
            return when {
                rain < 0 -> "No rain forecast figure for $where $day"
                rain >= 60 -> "Yes, probably – $rain% chance of rain in $where $day" + if (mm >= 1) ", about ${mm.roundToInt()} mm" else ""
                rain >= 30 -> "Maybe – $rain% chance of rain in $where $day"
                else -> "Unlikely – $rain% chance of rain in $where $day"
            }
        }
        val now = if (i == 0) j.getJSONObject("current").let { cur -> "Now ${cur.getDouble("temperature_2m").roundToInt()}° and ${Weather.describe(cur.getInt("weather_code")).lowercase()}. " } else ""
        return (if (i == 1) "Tomorrow in $where: " else "$where: ") + now +
            "$desc, high $hi°, low $lo°" + (if (rain >= 0) ", $rain% chance of rain" else "") + ", wind up to $wind mph"
    }

    private fun agenda(act: MainActivity, c: PhoneCmd.Agenda): String {
        if (act.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            act.requestPerm(Manifest.permission.READ_CALENDAR) { ok -> if (ok) act.runCmd(c) }
            return "Allow Dune to read your calendar and I'll carry on"
        }
        val zone = ZoneId.systemDefault()
        val day = LocalDate.now().plusDays((c.day ?: 0).toLong())
        val from = if (c.next) System.currentTimeMillis() else day.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = if (c.next) from + 30L * 86_400_000 else day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, from); android.content.ContentUris.appendId(it, to) }.build()
        val ev = ArrayList<Triple<Long, Boolean, String>>()
        act.contentResolver.query(uri, arrayOf(CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.TITLE,
            CalendarContract.Instances.EVENT_LOCATION), null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { q ->
            while (q.moveToNext()) {
                val loc = q.getString(3)?.takeIf { it.isNotBlank() }?.let { " at $it" } ?: ""
                ev += Triple(q.getLong(0), q.getInt(1) == 1, (q.getString(2) ?: "(no title)") + loc)
            }
        }
        val hm = DateTimeFormatter.ofPattern("h:mm a", Locale.UK)
        fun at(e: Triple<Long, Boolean, String>) = if (e.second) "all day" else Instant.ofEpochMilli(e.first).atZone(zone).toLocalTime().format(hm).lowercase()
        if (c.next) {
            val e = ev.firstOrNull { !it.second && it.first >= from } ?: return "Nothing in your calendar in the next 30 days"
            val d = Instant.ofEpochMilli(e.first).atZone(zone).toLocalDate()
            val dayText = when (d) { LocalDate.now() -> "today"; LocalDate.now().plusDays(1) -> "tomorrow"; else -> d.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK)) }
            return "Next: ${e.third}, $dayText at ${at(e)}"
        }
        val whenText = if (c.day == 1) "tomorrow" else "today"
        if (ev.isEmpty()) return "Nothing in your calendar $whenText"
        return "${ev.size} thing${if (ev.size == 1) "" else "s"} $whenText: " + ev.joinToString("; ") { "${at(it)}, ${it.third}" }
    }

    private fun camera(act: MainActivity, vm: LauncherVM, c: PhoneCmd.Camera): String {
        val usePhone = vm.store.phoneCamera.value && !c.selfie
        if (usePhone && PhoneCamera.open(act, c.video)) return "Opening the camera" + if (c.video) " – switch to Video if it isn't already" else ""
        vm.cameraMode = if (c.video) "video" else "photo"
        vm.cameraFront = c.selfie
        vm.screen = Screen.CAMERA
        return "Opening Dune's camera" + if (c.selfie) " on the front lens" else ""
    }

    /** "calculator", "gallery"…: whichever app the phone uses for that kind of thing. */
    fun openKind(act: MainActivity, vm: LauncherVM, kind: String): Boolean {
        fun sel(cat: String) = act.tryStart(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, cat))
        val byName = mapOf("address book" to "contacts", "pictures" to "gallery", "photos" to "gallery", "texts" to "messages", "sms" to "messages",
            "mail" to "email", "inbox" to "email", "internet" to "browser", "web browser" to "browser", "file manager" to "files",
            "alarms" to "clock", "stopwatch" to "clock", "dialler" to "phone", "dialer" to "phone", "keypad" to "phone", "app store" to "play store")
        val k = byName[kind] ?: kind
        // an app with that name first (the one the person sees in their drawer)
        Assistant.findApp(vm.apps, k)?.let { if (it.label.lowercase().contains(k.split(' ').first())) { vm.launch(it); return true } }
        return when (k) {
            "calculator" -> sel(Intent.CATEGORY_APP_CALCULATOR)
            "calendar" -> sel(Intent.CATEGORY_APP_CALENDAR) || act.tryStart(Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI.buildUpon().appendPath("time").build()))
            "contacts" -> sel(Intent.CATEGORY_APP_CONTACTS)
            "gallery" -> sel(Intent.CATEGORY_APP_GALLERY)
            "messages" -> sel(Intent.CATEGORY_APP_MESSAGING)
            "email" -> sel(Intent.CATEGORY_APP_EMAIL)
            "browser" -> sel(Intent.CATEGORY_APP_BROWSER)
            "files" -> sel(Intent.CATEGORY_APP_FILES) || act.tryStart(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS))
            "downloads" -> act.tryStart(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS))
            "music" -> sel(Intent.CATEGORY_APP_MUSIC)
            "settings" -> act.tryStart(Intent(Settings.ACTION_SETTINGS))
            "clock" -> act.tryStart(Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS))
            "camera" -> PhoneCamera.open(act, false)
            "phone" -> act.tryStart(Intent(Intent.ACTION_DIAL))
            "play store" -> act.launchPackage("com.android.vending")
            else -> false
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun whereAmI(ctx: Context): String {
        val loc = Geo.fix(ctx, maxAgeMs = 60_000L, timeoutMs = 30_000L)
            ?: return if (!(ctx.getSystemService(LocationManager::class.java)?.isLocationEnabled ?: true)) "Location is switched off on the phone"
                else "I couldn't get a fix – try again outside or near a window"
        val addr = withContext(Dispatchers.IO) {
            runCatching { @Suppress("DEPRECATION") Geocoder(ctx, Locale.UK).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull() }.getOrNull()
        }
        val ago = (System.currentTimeMillis() - loc.time) / 60_000
        val line = addr?.let { a -> listOfNotNull(a.thoroughfare?.let { t -> (a.subThoroughfare?.let { "$it " } ?: "") + t }, a.locality ?: a.subAdminArea, a.postalCode).joinToString(", ") }
        return (line?.takeIf { it.isNotBlank() } ?: String.format(Locale.UK, "%.5f, %.5f", loc.latitude, loc.longitude)) +
            (if (ago > 10) " (as of $ago min ago)" else "") + String.format(Locale.UK, " · ±%d m", loc.accuracy.roundToInt())
    }

    private fun screen(act: MainActivity, what: String): String {
        val s = SplitService.instance ?: run {
            act.tryStart(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return "That needs “Dune split screen” turned on in Accessibility – the screen is opening"
        }
        return if (what == "lock") { s.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN); "Locked" }
        else { act.window.decorView.postDelayed({ s.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT) }, 600); "Screenshot taken" }
    }
}

/** A small calculator for spoken sums: + - × ÷ ^, brackets, "percent of", "square root of", "squared". */
object Calc {
    fun eval(text: String): Double? {
        var t = " " + text.lowercase().trim().removeSuffix("?") + " "
        listOf(" plus " to " + ", " add " to " + ", " minus " to " - ", " take away " to " - ", " times " to " * ", " multiplied by " to " * ",
            " x " to " * ", " × " to " * ", " divided by " to " / ", " over " to " / ", " ÷ " to " / ", " to the power of " to " ^ ",
            " squared " to " ^ 2 ", " cubed " to " ^ 3 ", " percent of " to " % ", "% of " to " % ", " per cent of " to " % ",
            " and " to " + ").forEach { (a, b) -> t = t.replace(a, b) }
        t = t.replace(Regex("(?:the )?square root of ([\\d.]+)")) { "r ${it.groupValues[1]}" }
        // number words → digits
        t = t.split(' ').joinToString(" ") { w -> if (w.isNotEmpty() && w[0].isLetter() && w != "r") Assistant.num(w)?.toString() ?: w else w }
        val toks = Regex("\\d+(?:\\.\\d+)?|[-+*/^%()r]").findAll(t.replace(",", "")).map { it.value }.toList()
        val leftover = Regex("[a-z]").containsMatchIn(t.replace(Regex("\\br\\b"), ""))
        if (toks.size < 2 || leftover || toks.none { it.first().isDigit() } || toks.none { it in setOf("+", "-", "*", "/", "^", "%", "r") }) return null
        var i = 0
        fun peek() = toks.getOrNull(i)
        fun expr(): Double? {
            fun atom(): Double? {
                val p = peek() ?: return null
                return when {
                    p == "(" -> { i++; val v = expr(); if (peek() == ")") i++; v }
                    p == "-" -> { i++; atom()?.let { -it } }
                    p == "r" -> { i++; atom()?.let { Math.sqrt(it) } }
                    p.first().isDigit() -> { i++; p.toDouble() }
                    else -> null
                }
            }
            fun pow(): Double? { var a = atom() ?: return null; while (peek() == "^") { i++; a = Math.pow(a, atom() ?: return null) }; return a }
            fun term(): Double? {
                var a = pow() ?: return null
                while (peek() in setOf("*", "/", "%")) {
                    val op = toks[i++]; val b = pow() ?: return null
                    a = when (op) { "*" -> a * b; "/" -> a / b; else -> a * b / 100 }
                }
                return a
            }
            var a = term() ?: return null
            while (peek() in setOf("+", "-")) { val op = toks[i++]; val b = term() ?: return null; a = if (op == "+") a + b else a - b }
            return a
        }
        val v = expr() ?: return null
        return if (i == toks.size && v.isFinite()) v else null
    }
}
