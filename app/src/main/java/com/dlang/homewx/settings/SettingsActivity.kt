package com.dlang.homewx.settings

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.dlang.homewx.BuildConfig
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
    private var sensorLabelEditTexts: List<EditText> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        applySystemBarInsetPadding(binding.root)

        binding.backButton.setOnClickListener { finish() }

        binding.versionInfoText.text = getString(
            R.string.settings_version_info,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
            BuildConfig.BUILD_TIME
        )

        setUpWeatherSourceSpinner()
        setUpSensorSpinner()
        setUpSensorVisibilityCheckBoxes()
        setUpScreenBrightnessSlider()
        setUpBackgroundDarkenSlider()
        setUpLightThresholdSlider()
        bindCurrentValues()

        binding.tempOverrideSwitch.setOnCheckedChangeListener { _, checked ->
            binding.tempOverrideSensorSpinner.isEnabled = checked && sensorIds.isNotEmpty()
        }

        binding.undoButton.setOnClickListener { bindCurrentValues() }
    }

    /** Settings auto-save on close instead of requiring an explicit Save tap; onPause covers
     *  the back button, home button, and app-switch-away alike. */
    override fun onPause() {
        super.onPause()
        saveSettings()
    }

    /** Pads [view] by the system bars on all four sides, added on top of its existing padding.
     *  Also pads the bottom by the IME inset when the keyboard is showing - with
     *  decorFitsSystemWindows(false) the window never resizes on its own, so without this the
     *  keyboard simply overlaps whatever EditText is focused instead of the ScrollView shrinking
     *  to scroll it into view. */
    private fun applySystemBarInsetPadding(view: View) {
        val baseLeft = view.paddingLeft
        val baseTop = view.paddingTop
        val baseRight = view.paddingRight
        val baseBottom = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(baseLeft + bars.left, baseTop + bars.top, baseRight + bars.right, baseBottom + maxOf(bars.bottom, ime.bottom))
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
        // Only one source exists today - nothing to switch between yet, but the
        // spinner already works once WeatherProviderFactory grows a second branch.
        binding.weatherSourceSpinner.isEnabled = sources.size > 1
    }

    private fun setUpSensorSpinner() {
        val sensors = AppState.uiState.value.sensors
        sensorIds = sensors.map { it.id }
        val labels = if (sensors.isEmpty()) {
            listOf("No indoor sensors yet")
        } else {
            sensors.map { AppSettings.getSensorLabel(this, it.id) ?: it.roomName }
        }
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
            sensorLabelEditTexts = emptyList()
            return
        }

        val hiddenIds = AppSettings.getHiddenSensorIds(this)
        val marginPx = (8 * resources.displayMetrics.density).toInt()
        sensorVisibilityCheckBoxes = sensors.map { sensor ->
            CheckBox(this).apply {
                text = sensor.roomName
                tag = sensor.id
                isChecked = sensor.id !in hiddenIds
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_primary))
            }
        }
        sensorLabelEditTexts = sensors.map { sensor ->
            EditText(this).apply {
                tag = sensor.id
                hint = getString(R.string.settings_sensor_label_hint)
                setText(AppSettings.getSensorLabel(this@SettingsActivity, sensor.id))
                inputType = android.text.InputType.TYPE_CLASS_TEXT
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_primary))
                setHintTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = marginPx
                }
            }
        }
        sensorVisibilityCheckBoxes.zip(sensorLabelEditTexts).forEach { (checkBox, labelEditText) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            row.addView(checkBox)
            row.addView(labelEditText)
            container.addView(row)
        }
    }

    private fun setUpScreenBrightnessSlider() {
        binding.screenBrightnessSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val percent = progressToBrightnessPercent(progress)
                binding.screenBrightnessValueText.text = "$percent%"
                // Live-preview on this screen's own window - the actual MainActivity window
                // only picks up the saved value once you leave (ScreenPowerController.refresh()
                // in MainActivity.onResume()), so without this the slider looks like it does
                // nothing until you back out.
                previewBrightness(percent)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
    }

    private fun previewBrightness(percent: Int) {
        val params = window.attributes
        params.screenBrightness = percent / 100f
        window.attributes = params
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

    /** Slider progress (0-90) maps to a brightness percent (10-100); the floor keeps the screen from going unreadably dim. */
    private fun progressToBrightnessPercent(progress: Int): Int = progress + AppSettings.MIN_SCREEN_BRIGHTNESS_PERCENT

    /** Reloads every control from the last-saved [AppSettings] values, discarding any unsaved
     *  edits. Used both to populate the screen on open and to implement the Undo button. */
    private fun bindCurrentValues() {
        val sources = WeatherSourceId.values().toList()
        val activeIndex = sources.indexOf(WeatherSourceConfig.getActiveSource(this)).coerceAtLeast(0)
        binding.weatherSourceSpinner.setSelection(activeIndex)

        val hiddenIds = AppSettings.getHiddenSensorIds(this)
        sensorVisibilityCheckBoxes.forEach { checkBox ->
            checkBox.isChecked = (checkBox.tag as String) !in hiddenIds
        }
        sensorLabelEditTexts.forEach { labelEditText ->
            labelEditText.setText(AppSettings.getSensorLabel(this, labelEditText.tag as String))
        }

        binding.weatherIntervalEditText.setText(
            AppSettings.getWeatherSampleIntervalMinutes(this).toString()
        )
        binding.forecastDaysEditText.setText(
            AppSettings.getForecastDays(this).toString()
        )

        val brightnessPercent = AppSettings.getScreenBrightnessPercent(this)
        binding.screenBrightnessSlider.progress = brightnessPercent - AppSettings.MIN_SCREEN_BRIGHTNESS_PERCENT
        binding.screenBrightnessValueText.text = "$brightnessPercent%"
        previewBrightness(brightnessPercent)

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
        val forecastDays = binding.forecastDaysEditText.text.toString().toIntOrNull()
            ?: AppSettings.DEFAULT_FORECAST_DAYS
        AppSettings.setForecastDays(this, forecastDays)
        AppSettings.setScreenBrightnessPercent(this, progressToBrightnessPercent(binding.screenBrightnessSlider.progress))
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

        sensorLabelEditTexts.forEach { labelEditText ->
            AppSettings.setSensorLabel(this, labelEditText.tag as String, labelEditText.text.toString())
        }

        AppSettings.setWebViewRequestLoggingEnabled(this, binding.webviewLoggingSwitch.isChecked)
    }
}
