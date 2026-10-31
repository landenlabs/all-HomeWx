package com.landenlabs.homewx.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.landenlabs.homewx.data.BackupManager
import android.os.Bundle
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
import androidx.lifecycle.lifecycleScope
import com.landenlabs.homewx.BuildConfig
import com.landenlabs.homewx.R
import com.landenlabs.homewx.data.DailySnapshotStore
import com.landenlabs.homewx.data.WeatherMetricsHistoryStore
import com.landenlabs.homewx.databinding.ActivitySettingsBinding
import com.landenlabs.homewx.model.SensorReading
import com.landenlabs.homewx.news.UsStates
import com.landenlabs.homewx.news.WebRequestLogger
import com.landenlabs.homewx.notification.WeatherNotification
import com.landenlabs.homewx.rivers.GaugeSite
import com.landenlabs.homewx.rivers.RiverGaugeRepository
import com.landenlabs.homewx.rivers.RiverGaugeSettings
import com.landenlabs.homewx.state.AppState
import com.landenlabs.homewx.weather.WeatherSourceConfig
import com.landenlabs.homewx.weather.WeatherSourceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private var sensorIds: List<String> = emptyList()
    private var sensorVisibilityCheckBoxes: List<CheckBox> = emptyList()
    private var sensorLabelEditTexts: List<EditText> = emptyList()
    private val riverGaugeRepository = RiverGaugeRepository()
    private val credentialDialogs = CredentialDialogs(this) { bindAccountStatus() }
    private var idleSubPanels: List<IdleSubPanel> = emptyList()
    private var weatherSources: List<WeatherSourceId> = emptyList()
    private var riverGaugeCandidates: List<GaugeSite> = emptyList()
    private var riverGaugeCheckBoxes: List<CheckBox> = emptyList()
    private var riverGaugeLabelEditTexts: List<EditText> = emptyList()

    // Both pickers open on the Downloads folder; the picker (SAF) needs no storage permission.
    private val downloadsUri: Uri =
        DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download")

    private val exportLauncher = registerForActivityResult(object : ActivityResultContracts.CreateDocument("application/zip") {
        override fun createIntent(context: android.content.Context, input: String) =
            super.createIntent(context, input).putExtra(DocumentsContract.EXTRA_INITIAL_URI, downloadsUri)
    }) { uri -> uri?.let { runBackupTask { BackupManager.export(this, contentResolver.openOutputStream(it)!!).let { rows -> getString(R.string.backup_export_done, rows) } } } }

    // Android 13+ needs a runtime grant before the weather notification can be shown; if it's
    // refused, flip the switch back off so it doesn't claim to be enabled.
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) binding.weatherNotificationSwitch.isChecked = false
    }

    private val importLauncher = registerForActivityResult(object : ActivityResultContracts.OpenDocument() {
        override fun createIntent(context: android.content.Context, input: Array<String>) =
            super.createIntent(context, input).putExtra(DocumentsContract.EXTRA_INITIAL_URI, downloadsUri)
    }) { uri ->
        uri?.let {
            runBackupTask {
                val r = BackupManager.import(this, contentResolver.openInputStream(it)!!)
                getString(R.string.backup_import_done, r.rowsAdded, r.rowsSkipped, r.settingsRestored)
            }
        }
    }

    private fun runBackupTask(task: () -> String) {
        lifecycleScope.launch {
            val message = try {
                withContext(Dispatchers.IO) { task() }
            } catch (e: Exception) {
                getString(R.string.backup_failed, e.message ?: e.javaClass.simpleName)
            }
            Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        applySystemBarInsetPadding(binding.root)

        binding.backButton.setOnClickListener { finish() }

        binding.exportButton.setOnClickListener {
            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd-HHmm", java.util.Locale.US).format(java.util.Date())
            exportLauncher.launch("HomeWx-export-$stamp.zip")
        }
        binding.importButton.setOnClickListener { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) }

        binding.goveeEditButton.setOnClickListener { credentialDialogs.showGovee() }
        binding.wyzeEditButton.setOnClickListener { credentialDialogs.showWyze() }
        binding.wxdataEditButton.setOnClickListener { credentialDialogs.showWxData() }
        binding.feedsEditButton.setOnClickListener { credentialDialogs.showFeeds() }
        binding.droughtEditButton.setOnClickListener { credentialDialogs.showDrought() }

        binding.versionInfoText.text = getString(
            R.string.settings_version_info,
            BuildConfig.VERSION_NAME,
            buildDateLabel(),
            WeatherMetricsHistoryStore.DB_VERSION,
            DailySnapshotStore.DB_VERSION
        )

        setUpIdlePanelSpinners()
        setUpSensorSpinner()
        setUpSensorVisibilityCheckBoxes()
        setUpScreenBrightnessSlider()
        setUpNightBrightnessSlider()
        setUpBackgroundDarkenSlider()
        setUpLightThresholdSlider()
        setUpRiverGaugesSection()
        setUpCollapsibleSection(binding.sensorsSectionHeader, binding.sensorsSectionContent, getString(R.string.settings_section_sensors))
        setUpCollapsibleSection(binding.riversSectionHeader, binding.riversSectionContent, getString(R.string.settings_section_rivers))
        bindCurrentValues()

        binding.tempOverrideSwitch.setOnCheckedChangeListener { _, checked ->
            binding.tempOverrideSensorSpinner.isEnabled = checked && sensorIds.isNotEmpty()
        }

        binding.undoButton.setOnClickListener { bindCurrentValues() }

        binding.errorsButton.setOnClickListener { startActivity(Intent(this, ErrorLogActivity::class.java)) }
        // Collected live (not just bound once on open) since HomeWxMonitorService keeps polling
        // in the background and can log a new error while this screen is sitting in the foreground.
        lifecycleScope.launch {
            AppState.uiState.collect { state -> updateErrorsButton(state.errorLog.size) }
        }
    }

    /** BuildConfig.BUILD_TIME ("yyyy-MM-dd HH:mm") as a date-only "31-Oct-26"; the raw string if it can't be parsed. */
    private fun buildDateLabel(): String {
        val built = try {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).parse(BuildConfig.BUILD_TIME)
        } catch (e: java.text.ParseException) {
            null
        }
        return built?.let { java.text.SimpleDateFormat("dd-MMM-yy", java.util.Locale.US).format(it) } ?: BuildConfig.BUILD_TIME
    }

    private fun updateErrorsButton(errorCount: Int) {
        binding.errorsButton.isEnabled = errorCount > 0
        binding.errorsButton.text = if (errorCount > 0) {
            getString(R.string.settings_errors_button, errorCount)
        } else {
            getString(R.string.settings_errors_button_empty)
        }
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
    /** Wires [header] as a tap target that shows/hides [content], starting collapsed - shared
     *  by the Sensors and Rivers sections, since both grow with however many devices are
     *  configured and were what pushed Errors/Undo below the fold before those moved to a fixed
     *  footer. A trailing chevron on the header reflects the current state. */
    private fun setUpCollapsibleSection(header: TextView, content: View, title: String) {
        content.visibility = View.GONE
        header.text = "$title  ▸"
        header.setOnClickListener {
            val expanding = content.visibility != View.VISIBLE
            content.visibility = if (expanding) View.VISIBLE else View.GONE
            header.text = if (expanding) "$title  ▾" else "$title  ▸"
        }
    }

    /** One item's row in both the Sensors and Rivers lists: a checkbox with the item's full
     *  (sometimes long - USGS station names especially) name, then a custom-name field on its
     *  own row underneath, indented to align under the checkbox's label. Splitting the name and
     *  the edit field across two rows (rather than side by side) keeps the long official names
     *  from squeezing the edit field, and reads better on a narrower screen. */
    private fun addLabeledCheckRow(
        container: LinearLayout,
        checkBoxText: String,
        tag: String,
        isChecked: Boolean,
        currentLabel: String?
    ): Pair<CheckBox, EditText> {
        val indentPx = (36 * resources.displayMetrics.density).toInt()
        val bottomMarginPx = (12 * resources.displayMetrics.density).toInt()

        val checkBox = CheckBox(this).apply {
            text = checkBoxText
            this.tag = tag
            this.isChecked = isChecked
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_primary))
        }
        val labelEditText = EditText(this).apply {
            this.tag = tag
            hint = getString(R.string.settings_sensor_label_hint)
            setText(currentLabel)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = indentPx
                bottomMargin = bottomMarginPx
            }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        column.addView(checkBox)
        column.addView(labelEditText)
        container.addView(column)
        return checkBox to labelEditText
    }

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

    /** Lists [WeatherSourceConfig.availableSources] - WxData only appears once it has an API key -
     *  and selects the active one. Rebuilt whenever that key is added or cleared. */
    private fun setUpWeatherSourceSpinner() {
        weatherSources = WeatherSourceConfig.availableSources(this)
        binding.weatherSourceSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            weatherSources.map { it.name }
        )
        binding.weatherSourceSpinner.setSelection(
            weatherSources.indexOf(WeatherSourceConfig.getActiveSource(this)).coerceAtLeast(0)
        )
        binding.weatherSourceSpinner.isEnabled = weatherSources.size > 1
    }

    private fun setUpIdlePanelSpinners() {
        binding.idlePanelSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            IdlePanel.values().map { getString(it.labelRes) }
        )
        binding.idlePanelSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                // Switching to a different panel starts on its default tab; coming back to the
                // saved panel (or the initial bind) restores the saved tab.
                val panel = IdlePanel.values()[position]
                val savedKey = if (panel == AppSettings.getIdlePanel(this@SettingsActivity)) {
                    AppSettings.getIdleSubPanel(this@SettingsActivity)
                } else {
                    IdleSubPanelKeys.DEFAULT
                }
                bindIdleSubPanels(panel, savedKey)
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
    }

    /** Fills the tab spinner for [panel] and selects [selectedKey]; disabled when the panel has no tabs to pick. */
    private fun bindIdleSubPanels(panel: IdlePanel, selectedKey: String) {
        idleSubPanels = panel.subPanels(this)
        binding.idleSubPanelSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            idleSubPanels.map { it.label }
        )
        binding.idleSubPanelSpinner.setSelection(idleSubPanels.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0))
        binding.idleSubPanelSpinner.isEnabled = idleSubPanels.isNotEmpty()
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
        val rows = sensors.map { sensor ->
            addLabeledCheckRow(
                container = container,
                checkBoxText = sensor.roomName,
                tag = sensor.id,
                isChecked = sensor.id !in hiddenIds,
                currentLabel = AppSettings.getSensorLabel(this, sensor.id)
            )
        }
        sensorVisibilityCheckBoxes = rows.map { it.first }
        sensorLabelEditTexts = rows.map { it.second }
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

    private fun setUpNightBrightnessSlider() {
        binding.nightBrightnessSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                binding.nightBrightnessValueText.text = "${progressToNightPercent(progress)}%"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
    }

    private fun progressToNightPercent(progress: Int): Int = progress + AppSettings.MIN_NIGHT_BRIGHTNESS_PERCENT

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

    private fun setUpRiverGaugesSection() {
        binding.riverGaugesEnabledSwitch.setOnCheckedChangeListener { _, checked ->
            binding.riverZipEditText.isEnabled = checked
            binding.findGaugesButton.isEnabled = checked
        }
        binding.findGaugesButton.setOnClickListener { onFindGaugesClicked() }
    }

    private fun onFindGaugesClicked() {
        val zip = binding.riverZipEditText.text.toString().trim()
        if (zip.length != 5) {
            binding.riverSearchStatusText.text = getString(R.string.settings_rivers_search_failed, "enter a 5-digit zip code")
            return
        }
        binding.riverSearchStatusText.text = getString(R.string.settings_rivers_searching)
        binding.riverGaugeCheckboxContainer.removeAllViews()
        lifecycleScope.launch {
            try {
                val location = withContext(Dispatchers.IO) { riverGaugeRepository.geocodeZip(zip) }
                val gauges = withContext(Dispatchers.IO) { riverGaugeRepository.findNearestGauges(location) }
                riverGaugeCandidates = gauges
                binding.riverSearchStatusText.text = if (gauges.isEmpty()) {
                    getString(R.string.settings_rivers_no_gauges_found)
                } else {
                    ""
                }
                rebuildRiverGaugeCheckBoxes(gauges, checkedSiteIds = gauges.map { it.siteId }.toSet())
            } catch (e: Exception) {
                binding.riverSearchStatusText.text = getString(R.string.settings_rivers_search_failed, e.message ?: "network error")
            }
        }
    }

    private fun rebuildRiverGaugeCheckBoxes(gauges: List<GaugeSite>, checkedSiteIds: Set<String>) {
        binding.riverGaugeCheckboxContainer.removeAllViews()
        // The official USGS station names (shown on the checkbox itself, for context while
        // picking) are long and not very readable at a glance - addLabeledCheckRow lets each
        // selected gauge get a short custom name on its own row, same as indoor sensors already can.
        val rows = gauges.map { gauge ->
            addLabeledCheckRow(
                container = binding.riverGaugeCheckboxContainer,
                checkBoxText = riverGaugeLabel(gauge),
                tag = gauge.siteId,
                isChecked = gauge.siteId in checkedSiteIds,
                currentLabel = RiverGaugeSettings.getGaugeLabel(this, gauge.siteId)
            )
        }
        riverGaugeCheckBoxes = rows.map { it.first }
        riverGaugeLabelEditTexts = rows.map { it.second }
    }

    private fun riverGaugeLabel(gauge: GaugeSite): String {
        val params = listOfNotNull(
            "level".takeIf { gauge.hasGageHeight },
            "flow".takeIf { gauge.hasDischarge }
        ).joinToString("/")
        return "%s (%.1f mi) - %s".format(gauge.name, gauge.distanceMiles, params)
    }

    /** Slider progress (0-95) maps to a lux threshold (5-100); the floor keeps the derived dark threshold at or above 0. */
    private fun progressToLux(progress: Int): Int = progress + AppSettings.MIN_LIGHT_THRESHOLD_LUX.toInt()

    /** Slider progress (0-90) maps to a brightness percent (10-100); the floor keeps the screen from going unreadably dim. */
    private fun progressToBrightnessPercent(progress: Int): Int = progress + AppSettings.MIN_SCREEN_BRIGHTNESS_PERCENT

    /** Reloads every control from the last-saved [AppSettings] values, discarding any unsaved
     *  edits. Used both to populate the screen on open and to implement the Undo button. */
    private fun bindCurrentValues() {

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
        binding.smoothingWindowEditText.setText(
            AppSettings.getSmoothingWindowMinutes(this).toString()
        )
        binding.flatThresholdEditText.setText(
            AppSettings.getFlatRangeThreshold(this).toString()
        )

        val brightnessPercent = AppSettings.getScreenBrightnessPercent(this)
        binding.screenBrightnessSlider.progress = brightnessPercent - AppSettings.MIN_SCREEN_BRIGHTNESS_PERCENT
        binding.screenBrightnessValueText.text = "$brightnessPercent%"
        previewBrightness(brightnessPercent)

        val nightPercent = AppSettings.getNightBrightnessPercent(this)
        binding.nightBrightnessSlider.progress = nightPercent - AppSettings.MIN_NIGHT_BRIGHTNESS_PERCENT
        binding.nightBrightnessValueText.text = "$nightPercent%"

        val darkenPercent = AppSettings.getBackgroundDarkenPercent(this)
        binding.backgroundDarkenSlider.progress = darkenPercent
        binding.backgroundDarkenValueText.text = "$darkenPercent%"

        val lightThresholdLux = AppSettings.getLightThresholdLux(this).toInt()
        binding.lightThresholdSlider.progress = lightThresholdLux - AppSettings.MIN_LIGHT_THRESHOLD_LUX.toInt()
        binding.lightThresholdValueText.text = "$lightThresholdLux lux"

        binding.dailySplineSwitch.isChecked = AppSettings.isDailyWeatherSplineEnabled(this)
        binding.darkMapSwitch.isChecked = AppSettings.isDarkMapTheme(this)
        bindAccountStatus()
        val idlePanel = AppSettings.getIdlePanel(this)
        binding.idlePanelSpinner.setSelection(idlePanel.ordinal)
        bindIdleSubPanels(idlePanel, AppSettings.getIdleSubPanel(this))
        binding.weatherNotificationSwitch.setOnCheckedChangeListener(null)
        binding.weatherNotificationSwitch.isChecked = AppSettings.isWeatherNotificationEnabled(this)
        binding.weatherNotificationSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

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

        val riversEnabled = RiverGaugeSettings.isEnabled(this)
        binding.riverGaugesEnabledSwitch.isChecked = riversEnabled
        binding.riverZipEditText.isEnabled = riversEnabled
        binding.findGaugesButton.isEnabled = riversEnabled
        binding.riverZipEditText.setText(RiverGaugeSettings.getZipCode(this).orEmpty())
        binding.riverSearchStatusText.text = ""
        // Reload discards any unsaved Find-gauges search - show the last-saved selection again,
        // all checked, rather than an empty list (a fresh search is one Find tap away).
        riverGaugeCandidates = RiverGaugeSettings.getSelectedGauges(this)
        rebuildRiverGaugeCheckBoxes(riverGaugeCandidates, checkedSiteIds = riverGaugeCandidates.map { it.siteId }.toSet())
    }

    /** Refreshes the one-line status under each Accounts & Feeds row. These are saved by their own
     *  dialogs, so they sit outside [bindCurrentValues]'s Undo and [saveSettings]'s auto-save. */
    private fun bindAccountStatus() {
        // Adding or clearing the WxData key changes which weather sources are offered.
        setUpWeatherSourceSpinner()
        binding.wxdataStatusText.text = getString(
            if (CredentialStore.hasWxData(this)) R.string.settings_accounts_wxdata_set else R.string.settings_accounts_wxdata_off
        )
        binding.goveeStatusText.text = if (CredentialStore.hasGovee(this)) {
            getString(R.string.settings_accounts_govee_set)
        } else {
            getString(R.string.settings_accounts_not_set)
        }
        binding.wyzeStatusText.text = if (CredentialStore.hasWyze(this)) {
            getString(R.string.settings_accounts_wyze_set, CredentialStore.getWyzeUser(this))
        } else {
            getString(R.string.settings_accounts_not_set)
        }
        binding.feedsStatusText.text = AppSettings.getNewsSources(this).joinToString(", ") { it.label }
        AppSettings.getDroughtState(this).let { binding.droughtStatusText.text = "${UsStates.nameOf(it)} ($it)" }
    }

    private fun saveSettings() {
        val minutes = binding.weatherIntervalEditText.text.toString().toIntOrNull()
            ?: AppSettings.DEFAULT_WEATHER_SAMPLE_INTERVAL_MINUTES
        AppSettings.setWeatherSampleIntervalMinutes(this, minutes)
        val forecastDays = binding.forecastDaysEditText.text.toString().toIntOrNull()
            ?: AppSettings.DEFAULT_FORECAST_DAYS
        AppSettings.setForecastDays(this, forecastDays)
        val smoothingWindowMinutes = binding.smoothingWindowEditText.text.toString().toIntOrNull()
            ?: AppSettings.DEFAULT_SMOOTHING_WINDOW_MINUTES
        AppSettings.setSmoothingWindowMinutes(this, smoothingWindowMinutes)
        val flatThreshold = binding.flatThresholdEditText.text.toString().toFloatOrNull()
            ?: AppSettings.DEFAULT_FLAT_RANGE_THRESHOLD
        AppSettings.setFlatRangeThreshold(this, flatThreshold)
        AppSettings.setScreenBrightnessPercent(this, progressToBrightnessPercent(binding.screenBrightnessSlider.progress))
        AppSettings.setNightBrightnessPercent(this, progressToNightPercent(binding.nightBrightnessSlider.progress))
        AppSettings.setBackgroundDarkenPercent(this, binding.backgroundDarkenSlider.progress)
        AppSettings.setLightThresholdLux(this, progressToLux(binding.lightThresholdSlider.progress).toFloat())
        AppSettings.setDailyWeatherSplineEnabled(this, binding.dailySplineSwitch.isChecked)
        AppSettings.setDarkMapTheme(this, binding.darkMapSwitch.isChecked)
        AppSettings.setWeatherNotificationEnabled(this, binding.weatherNotificationSwitch.isChecked)
        WeatherNotification.refresh(this)

        AppSettings.setIdlePanel(
            this,
            IdlePanel.values()[binding.idlePanelSpinner.selectedItemPosition],
            idleSubPanels.getOrNull(binding.idleSubPanelSpinner.selectedItemPosition)?.key ?: IdleSubPanelKeys.DEFAULT
        )

        weatherSources.getOrNull(binding.weatherSourceSpinner.selectedItemPosition)?.let {
            WeatherSourceConfig.setActiveSource(this, it)
        }

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

        RiverGaugeSettings.setEnabled(this, binding.riverGaugesEnabledSwitch.isChecked)
        RiverGaugeSettings.setZipCode(this, binding.riverZipEditText.text.toString().trim().takeIf { it.isNotBlank() })
        val checkedSiteIds = riverGaugeCheckBoxes.filter { it.isChecked }.map { it.tag as String }.toSet()
        RiverGaugeSettings.setSelectedGauges(this, riverGaugeCandidates.filter { it.siteId in checkedSiteIds })
        riverGaugeLabelEditTexts.forEach { labelEditText ->
            RiverGaugeSettings.setGaugeLabel(this, labelEditText.tag as String, labelEditText.text.toString())
        }
    }
}
