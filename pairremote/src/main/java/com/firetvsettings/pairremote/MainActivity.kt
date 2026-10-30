package com.firetvsettings.pairremote

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.firetvsettings.pairremote.Ui.dp
import com.firetvsettings.pairremote.Ui.tab
import com.firetvsettings.pairremote.Ui.text

/** Two tabs: pairing Bluetooth remotes, and forwarding their volume buttons to the TV. */
class MainActivity : ComponentActivity() {

    private enum class Tab { PAIR, VOLUME }

    private lateinit var pairPage: PairPage
    private lateinit var volumePage: VolumePage
    private lateinit var pairTab: View
    private lateinit var volumeTab: View
    private lateinit var content: FrameLayout
    private lateinit var scroll: ScrollView
    private var current: Tab? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            pairPage.onPermissionsResult(result.filterValues { granted -> !granted }.keys)
        }

    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            pairPage.onEnableBluetoothResult()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(Ui.BACKGROUND)
        pairPage = PairPage(
            this,
            requestPermissions = { permissionLauncher.launch(it) },
            requestEnableBluetooth = { enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
        )
        volumePage = VolumePage(this)
        setContentView(buildLayout())
    }

    override fun onResume() {
        super.onResume()
        val saved = runCatching { Tab.valueOf(prefs().getString(KEY_TAB, null) ?: "") }.getOrNull()
        show(current ?: saved ?: Tab.PAIR, moveFocus = current == null)
    }

    override fun onPause() {
        current?.let { pageOf(it).onHidden() }
        super.onPause()
    }

    override fun onDestroy() {
        pairPage.onDestroy()
        super.onDestroy()
    }

    private fun show(tab: Tab, moveFocus: Boolean) {
        val previous = current
        if (previous != null && previous != tab) pageOf(previous).onHidden()
        current = tab
        prefs().edit().putString(KEY_TAB, tab.name).apply()

        pairTab.isSelected = tab == Tab.PAIR
        volumeTab.isSelected = tab == Tab.VOLUME
        val page = pageOf(tab)
        if (content.getChildAt(0) !== page.view) {
            content.removeAllViews()
            content.addView(page.view)
            // After layout: the ScrollView scrolls toward whichever child grabs focus while the page is added.
            scroll.post { scroll.scrollTo(0, 0) }
        }
        page.onShown()
        if (moveFocus) page.focusTarget.requestFocus()
    }

    private fun pageOf(tab: Tab): Page = when (tab) {
        Tab.PAIR -> pairPage
        Tab.VOLUME -> volumePage
    }

    private fun buildLayout(): View {
        val title = text(26f, bold = true).apply { setText(R.string.app_name) }
        val icon = ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) }
        pairTab = tab(R.string.tab_pair) { show(Tab.PAIR, moveFocus = false) }
        volumeTab = tab(R.string.tab_volume) { show(Tab.VOLUME, moveFocus = false) }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(icon, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(16) })
            addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(pairTab)
            addView(volumeTab, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(12) })
        }

        content = FrameLayout(this)
        scroll = ScrollView(this).apply {
            isFillViewport = true
            // Room for the focus outline of the outermost rows; clipped so content never scrolls under the header.
            setPadding(dp(4), dp(28), dp(4), dp(24))
            clipToPadding = true
            addView(content)
        }

        // TV-safe margins: 48dp on the sides, 27dp top and bottom.
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(27), dp(48), 0)
            setBackgroundColor(Ui.BACKGROUND)
            addView(header)
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    private fun prefs() = getSharedPreferences("main", Context.MODE_PRIVATE)

    private companion object {
        const val KEY_TAB = "tab"
    }
}
