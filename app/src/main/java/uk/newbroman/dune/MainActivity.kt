package uk.newbroman.dune

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {
    private val vm: LauncherVM by viewModels()

    private var permCallback: ((Boolean) -> Unit)? = null
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        permCallback?.invoke(it); permCallback = null
    }

    private val shortcutLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK && !vm.acceptPin(it.data)) toast("That shortcut couldn't be added")
    }

    /** Open an app's "create shortcut" screen (Termux lists your ~/.shortcuts scripts there). */
    fun addShortcutFrom(vararg pkgs: String) {
        for (pkg in pkgs) {
            val list = packageManager.queryIntentActivities(Intent(Intent.ACTION_CREATE_SHORTCUT).setPackage(pkg), 0)
            val ai = list.firstOrNull()?.activityInfo ?: continue
            try {
                shortcutLauncher.launch(Intent(Intent.ACTION_CREATE_SHORTCUT).setClassName(ai.packageName, ai.name))
                return
            } catch (e: Exception) { }
        }
        toast("Termux has no shortcut screen – add the widget from Termux instead")
    }

    fun requestPerm(permission: String, cb: (Boolean) -> Unit) {
        permCallback = cb
        permLauncher.launch(permission)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this) { vm.back() }
        setContent { DuneApp(vm) }
    }

    /** Pressing Home while already home returns to the top of the home screen. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        vm.goHome()
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
    }
}
