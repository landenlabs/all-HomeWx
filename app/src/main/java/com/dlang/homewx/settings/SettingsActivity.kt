package com.dlang.homewx.settings

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.dlang.homewx.R
import com.dlang.homewx.databinding.ActivitySettingsBinding
import com.dlang.homewx.model.SensorReading
import com.dlang.homewx.news.WebRequestLogger
import com.dlang.homewx.state.AppState
import com.dlang.homewx.weather.WeatherSourceConfig
import com.dlang.homewx.weather.WeatherSourceId

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private var sensorIds: List<String> = emptyList()
    private var sensorVisibilityCheckBoxes: List<CheckBox> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        applySystemBarInsetPadding(binding.root)

        binding.backButton.setOnClickListener { finish() }

        setUpWeatherSourceSpinner()
        setUpSensorSpinner()
        setUpSensorVisibilityCheckBoxes()
        setUpBackgroundDarkenSlider()
        setUpLightThresholdSlider()
        bindCurrentValues()

        binding.tempOverrideSwitch.setOnCheckedChangeListener { _, checked ->
            binding.tempOverrideSensorSpinner.isEnabled = checked && sensorIds.isNotEmpty()
        }

        binding.saveButton.setOnClickListener { saveSettings() }
    }

    /** Pads [view] by the system bars on all four sides, added on top of its existing padding. */
    private fun applySystemBarInsetPadding(view: View) {
        val baseLeft = view.paddingLeft
        val baseTop = view.paddingTop
        val baseRight = view.paddingRight
        val baseBottom = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(baseLeft + bars.left, baseTop + bars.top, baseRight + bars.right, baseBottom + bars.bottom)
            insets
        }
    }

    private fun setUpWeatherSourceSpinner() {
        val sources = WeatherSourceId.values().toList()
        binding.weatherSourceSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            sources.map { it.name }
        )
        val activeIndex = sources.indexOf(WeatherSourceConfig.getActiveSource(this)).coerceAtLeast(0)
        binding.weatherSourceSpinner.setSelection(activeIndex)
        // Only one source exists today - nothing to switch between yet, but the
        // spinner already works once WeatherProviderFactory grows a second branch.
        binding.weatherSourceSpinner.isEnabled = sources.size > 1
    }

    private fun setUpSensorSpinner() {
        val sensors = AppState.uiState.value.sensors
        sensorIds = sensors.map { it.id }
        val labels = if (sensors.isEmpty()) listOf("No indoor sensors yet") else sensors.map { it.roomName }
        binding.tempOverrideSensorSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels
        )
        binding.tempOverrideSensorSpinner.isEnabled = sensors.isNotEmpty()
    }

    private fun setUpSensorVisibilityCheckBoxes() {
        val sensors: List<SensorReading> = AppState.uiState.value.sensors
        val container = binding.sensorVisibilityContainer
        container.removeAllViews()

        if (sensors.isEmpty()) {
            container.addView(
                TextView(this).apply {
                    text = getString(R.string.settings_no_sensors)
                    setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_secondary))
                }
            )
            sensorVisibilityCheckBoxes = emptyList()
            return
        }

        val hiddenIds = AppSettings.getHiddenSensorIds(this)
        sensorVisibilityCheckBoxes = sensors.map { sensor ->
            CheckBox(this).apply {
                text = sensor.roomName
                tag = sensor.id
                isChecked = sensor.id !in hiddenIds
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_primary))
            }
        }
        sensorVisibilityCheckBoxes.forEach { container.addView(it) }
    }

    private fun setUpBackgroundDarkenSlider() {
        binding.backgroundDarkenSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                binding.backgroundDarkenValueText.text = "$progress%"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
    }

    private fun setUpLightThresholdSlider() {
        binding.lightThresholdSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                binding.lightThresholdValueText.text = "${progressToLux(progress)} lux"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
    }

    /** Slider progress (0-95) maps to a lux threshold (5-100); the floor keeps the derived dark threshold at or above 0. */
    private fun progressToLux(progress: Int): Int = progress + AppSettings.MIN_LIGHT_THRESHOLD_LUX.toInt()

    private fun bindCurrentValues() {
        binding.weatherIntervalEditText.setText(
            AppSettings.getWeatherSampleIntervalMinutes(this).toString()
        )

        val darkenPercent = AppSettings.getBackgroundDarkenPercent(this)
        binding.backgroundDarkenSlider.progress = darkenPercent
        binding.backgroundDarkenValueText.text = "$darkenPercent%"

        val lightThresholdLux = AppSettings.getLightThresholdLux(this).toInt()
        binding.lightThresholdSlider.progress = lightThresholdLux - AppSettings.MIN_LIGHT_THRESHOLD_LUX.toInt()
        binding.lightThresholdValueText.text = "$lightThresholdLux lux"

        val overrideEnabled = AppSettings.isTempSensorOverrideEnabled(this)
        binding.tempOverrideSwitch.isChecked = overrideEnabled
        binding.tempOverrideSensorSpinner.isEnabled = overrideEnabled && sensorIds.isNotEmpty()

        val storedSensorId = AppSettings.getTempSensorOverrideSensorId(this)
        val sensorIndex = sensorIds.indexOf(storedSensorId).coerceAtLeast(0)
        binding.tempOverrideSensorSpinner.setSelection(sensorIndex)

        binding.webviewLoggingSwitch.isChecked = AppSettings.isWebViewRequestLoggingEnabled(this)
        binding.webviewLogPathText.text = getString(
            R.string.settings_webview_logging_path,
            WebRequestLogger.logFile(this).absolutePath
        )
    }

    private fun saveSettings() {
        val minutes = binding.weatherIntervalEditText.text.toString().toIntOrNull()
            ?: AppSettings.DEFAULT_WEATHER_SAMPLE_INTERVAL_MINUTES
        AppSettings.setWeatherSampleIntervalMinutes(this, minutes)
        AppSettings.setBackgroundDarkenPercent(this, binding.backgroundDarkenSlider.progress)
        AppSettings.setLightThresholdLux(this, progressToLux(binding.lightThresholdSlider.progress).toFloat())

        val selectedSource = WeatherSourceId.values()[binding.weatherSourceSpinner.selectedItemPosition]
        WeatherSourceConfig.setActiveSource(this, selectedSource)

        val overrideEnabled = binding.tempOverrideSwitch.isChecked
        val selectedSensorId = sensorIds.getOrNull(binding.tempOverrideSensorSpinner.selectedItemPosition)
        AppSettings.setTempSensorOverride(this, overrideEnabled, selectedSensorId)

        val hiddenIds = sensorVisibilityCheckBoxes
            .filter { !it.isChecked }
            .map { it.tag as String }
            .toSet()
        AppSettings.setHiddenSensorIds(this, hiddenIds)

        AppSettings.setWebViewRequestLoggingEnabled(this, binding.webviewLoggingSwitch.isChecked)

        Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
        finish()
    }
}
