package com.drliuhuan.sayboardpro

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.drliuhuan.sayboardpro.ui.SettingsScreen
import com.drliuhuan.sayboardpro.ui.SettingsTheme

/**
 * 设置页（也是启动入口）。
 * 负责：麦克风权限、输入法启用状态，以及四个设置分区。
 */
class SettingsActivity : ComponentActivity() {

    companion object {
        const val EXTRA_SECTION = "extra_section"
        const val SECTION_PROVIDER = "provider"
        const val SECTION_DICTIONARY = "dictionary"
        const val SECTION_RECORDING = "recording"
        const val SECTION_LLM = "llm"
        private const val REQUEST_MIC = 1001
    }

    private val micGranted = mutableStateOf(false)
    private val imeEnabled = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC
            )
        }

        val section = intent.getStringExtra(EXTRA_SECTION)
        setContent {
            val prefs = remember { AppPrefs(this) }
            // 主题模式用 state 持有：AppearanceSection 改主题时同步更新这里 → SettingsTheme 重组即时变色
            val appTheme = remember { mutableStateOf(prefs.appTheme) }
            SettingsTheme(appTheme.value) {
                SettingsScreen(
                    initialSection = section,
                    micGranted = micGranted.value,
                    imeEnabled = imeEnabled.value,
                    appTheme = appTheme.value,
                    onAppThemeChange = { mode ->
                        appTheme.value = mode
                        prefs.appTheme = mode
                    },
                    onRequestMic = {
                        ActivityCompat.requestPermissions(
                            this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC
                        )
                    },
                    onOpenImeSettings = {
                        startActivity(Intent("android.settings.INPUT_METHOD_SETTINGS"))
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 权限/输入法状态可能变化，更新状态触发重组
        micGranted.value = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        imeEnabled.value = isImeEnabled()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_MIC) return
        // 授权结果回调：更新 micGranted 状态（驱动设置页授权卡片消失/保留）并给出明确反馈。
        // 之前缺失此回调——用户点"允许"后 micGranted 不更新、也无任何提示，体验上"无反应"。
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        micGranted.value = granted
        if (granted) {
            Toast.makeText(this, R.string.settings_mic_granted, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.settings_mic_denied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun isImeEnabled(): Boolean {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any { it.packageName == packageName }
    }
}
