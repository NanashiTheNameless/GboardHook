package dev.namelessnanashi.gboardhook

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import com.google.android.material.switchmaterial.SwitchMaterial
import android.widget.TextView
import android.widget.Toast
import androidx.core.net.toUri

/**
 * Created by cy on 2022/1/14.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Ensure app theme matches system light/dark mode
        try {
            val delegateClass = Class.forName("androidx.appcompat.app.AppCompatDelegate")
            val modeField = delegateClass.getField("MODE_NIGHT_FOLLOW_SYSTEM")
            val modeValue = modeField.getInt(null)
            val setDefaultNightMode = delegateClass.getMethod("setDefaultNightMode", Int::class.java)
            setDefaultNightMode.invoke(null, modeValue)
        } catch (_: Exception) {}
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val sw0 = findViewById<SwitchMaterial>(R.id.sw0)
        val et0 = findViewById<EditText>(R.id.et0)
        val et1 = findViewById<EditText>(R.id.et1)
        val bt0 = findViewById<Button>(R.id.bt0)
        val swLog = findViewById<SwitchMaterial>(R.id.swLog)
        val tvHint = findViewById<TextView>(R.id.tvHint)

        tvHint.text = listOf(
            getString(R.string.restart_requirement),
            getString(R.string.first_setup),
            getString(R.string.repo_link),
            getString(R.string.fork_credit),
            getString(R.string.original_credit)
        ).joinToString("\n")

        val pref: SharedPreferences? = try {
            getSharedPreferences(PluginEntry.SP_FILE_NAME, MODE_PRIVATE)
        } catch (e: Exception) {
            Log.d("MainActivity", "getSharedPreferences failed---$e")
            Toast.makeText(this, "Failed to read configuration", Toast.LENGTH_SHORT).show()
            null
        }

        pref?.getString(PluginEntry.SP_KEY, null)?.split(",")?.let { list ->
            et0.text.append(list[0])
            et1.text.append(list[1])
            val switchOn = list.getOrNull(2)?.equals("true", true) ?: false
            sw0.isChecked = switchOn
        }
        swLog.isChecked = pref?.getBoolean(PluginEntry.SP_KEY_LOG, false) ?: false

        bt0.setOnClickListener {
            val num = et0.text.toString().toIntOrNull() ?: PluginEntry.DEFAULT_NUM
            val time = et1.text.toString().toLongOrNull() ?: PluginEntry.DEFAULT_TIME
            val switchOn = sw0.isChecked.toString()
            pref?.edit()?.apply {
                putString(PluginEntry.SP_KEY, "$num,$time,$switchOn")
                putBoolean(PluginEntry.SP_KEY_LOG, swLog.isChecked)
                apply()
            }

            startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .apply {
                        addCategory(Intent.CATEGORY_DEFAULT)
                        data = "package:${PluginEntry.PACKAGE_NAME}".toUri()
                    })
        }
        tvHint.setOnClickListener {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    "https://github.com/NanashiTheNameless/GboardHook".toUri()
                )
            )
        }
    }
}
