package com.drliuhuan.sayboardpro

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.drliuhuan.sayboardpro.ui.SettingsScreen

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
            SettingsScreen(
                initialSection = section,
                micGranted = micGranted.value,
                imeEnabled = imeEnabled.value,
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

    override fun onResume() {
        super.onResume()
        // 权限/输入法状态可能变化，更新状态触发重组
        micGranted.value = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        imeEnabled.value = isImeEnabled()
    }

    private fun isImeEnabled(): Boolean {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any { it.packageName == packageName }
    }
}
