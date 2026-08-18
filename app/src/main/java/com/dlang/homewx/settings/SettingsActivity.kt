package com.dlang.homewx.settings

import android.os.Bundle
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
import androidx.core.view.updatePadding
import com.dlang.homewx.R
import com.dlang.homewx.databinding.ActivitySettingsBinding
import com.dlang.homewx.model.SensorReading
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
        applyStatusBarInsetPadding()

        binding.backButton.setOnClickListener { finish() }

        setUpWeatherSourceSpinner()
        setUpSensorSpinner()
        setUpSensorVisibilityCheckBoxes()
        setUpBackgroundDarkenSlider()
        bindCurrentValues()

        binding.tempOverrideSwitch.setOnCheckedChangeListener { _, checked ->
            binding.tempOverrideSensorSpinner.isEnabled = checked && sensorIds.isNotEmpty()
        }

        binding.saveButton.setOnClickListener { saveSettings() }
    }

    private fun applyStatusBarInsetPadding() {
        val basePaddingTop = binding.settingsHeaderRow.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(binding.settingsHeaderRow) { view, insets ->
            val statusBarTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.updatePadding(top = basePaddingTop + statusBarTop)
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

    private fun bindCurrentValues() {
        binding.weatherIntervalEditText.setText(
            AppSettings.getWeatherSampleIntervalMinutes(this).toString()
        )

        val darkenPercent = AppSettings.getBackgroundDarkenPercent(this)
        binding.backgroundDarkenSlider.progress = darkenPercent
        binding.backgroundDarkenValueText.text = "$darkenPercent%"

        val overrideEnabled = AppSettings.isTempSensorOverrideEnabled(this)
        binding.tempOverrideSwitch.isChecked = overrideEnabled
        binding.tempOverrideSensorSpinner.isEnabled = overrideEnabled && sensorIds.isNotEmpty()

        val storedSensorId = AppSettings.getTempSensorOverrideSensorId(this)
        val sensorIndex = sensorIds.indexOf(storedSensorId).coerceAtLeast(0)
        binding.tempOverrideSensorSpinner.setSelection(sensorIndex)
    }

    private fun saveSettings() {
        val minutes = binding.weatherIntervalEditText.text.toString().toIntOrNull()
            ?: AppSettings.DEFAULT_WEATHER_SAMPLE_INTERVAL_MINUTES
        AppSettings.setWeatherSampleIntervalMinutes(this, minutes)
        AppSettings.setBackgroundDarkenPercent(this, binding.backgroundDarkenSlider.progress)

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

        Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
        finish()
    }
}
