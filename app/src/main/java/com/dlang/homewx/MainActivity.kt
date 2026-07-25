package com.dlang.homewx

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.dlang.homewx.databinding.ActivityMainBinding
import com.dlang.homewx.model.LightMode
import com.dlang.homewx.power.ScreenPowerController
import com.dlang.homewx.service.HomeWxMonitorService
import com.dlang.homewx.state.AppState
import com.dlang.homewx.ui.SensorAdapter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var screenPowerController: ScreenPowerController
    private val sensorAdapter = SensorAdapter()

    private val clockFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        screenPowerController = ScreenPowerController(this)
        binding.sensorRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.sensorRecyclerView.adapter = sensorAdapter

        HomeWxMonitorService.start(this)

        observeState()
        startClock()
    }

    // Not gated to STARTED: the light-triggered "wake the screen" action must
    // apply its window flags even while the activity is merely STOPPED
    // (screen off but not destroyed), otherwise the tablet never wakes back up.
    private fun observeState() {
        lifecycleScope.launch {
            AppState.uiState.collect { state ->
                screenPowerController.apply(state.lightMode)
                sensorAdapter.submit(state.sensors)
                binding.modeChip.text = if (state.lightMode == LightMode.ACTIVE) "☀ Active" else "🌙 Quiet"
                binding.weatherText.text = state.weatherSummary.ifBlank { getString(R.string.weather_placeholder) }

                val error = state.lastError
                binding.sensorErrorText.text = error?.let { "Sensor error: $it" }
                binding.sensorErrorText.visibility = if (error != null) android.view.View.VISIBLE else android.view.View.GONE
            }
        }
    }

    private fun startClock() {
        lifecycleScope.launch {
            while (true) {
                val now = Date()
                binding.clockText.text = clockFormat.format(now)
                binding.dateText.text = dateFormat.format(now)
                delay(30_000L)
            }
        }
    }
}
