package com.dlang.homewx.settings

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.dlang.homewx.R
import com.dlang.homewx.databinding.ActivityErrorLogBinding
import com.dlang.homewx.model.AppErrorEntry
import com.dlang.homewx.state.AppState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Scrollable view of the last few background-poller failures (see [AppState.recordError]),
 *  with Clear and Share so a problem can be wiped once understood, or forwarded as-is. */
class ErrorLogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityErrorLogBinding
    private val timeFormat = SimpleDateFormat("MM/dd HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityErrorLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        applySystemBarInsetPadding(binding.root)

        binding.errorLogBackButton.setOnClickListener { finish() }
        binding.errorLogClearButton.setOnClickListener {
            AppState.clearErrors()
            renderErrors(emptyList())
        }
        binding.errorLogShareButton.setOnClickListener { shareErrors() }
    }

    override fun onResume() {
        super.onResume()
        // Re-render rather than just binding once on create - the background pollers keep
        // running while this screen is open and can add a new entry at any point.
        renderErrors(AppState.uiState.value.errorLog)
    }

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

    private fun renderErrors(errors: List<AppErrorEntry>) {
        binding.errorLogListContainer.removeAllViews()
        binding.errorLogEmptyText.visibility = if (errors.isEmpty()) View.VISIBLE else View.GONE
        binding.errorLogClearButton.isEnabled = errors.isNotEmpty()
        binding.errorLogShareButton.isEnabled = errors.isNotEmpty()

        val marginPx = (16 * resources.displayMetrics.density).toInt()
        errors.forEach { entry ->
            binding.errorLogListContainer.addView(
                TextView(this).apply {
                    text = formatEntry(entry)
                    setTextColor(ContextCompat.getColor(this@ErrorLogActivity, R.color.text_primary))
                    textSize = 14f
                    setPadding(0, marginPx, 0, marginPx)
                }
            )
            binding.errorLogListContainer.addView(
                View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                    setBackgroundColor(ContextCompat.getColor(this@ErrorLogActivity, R.color.divider))
                }
            )
        }
    }

    private fun formatEntry(entry: AppErrorEntry): String =
        "${timeFormat.format(Date(entry.timestampMillis))}  [${entry.source}]\n${entry.message}"

    private fun shareErrors() {
        val errors = AppState.uiState.value.errorLog
        if (errors.isEmpty()) return
        val body = errors.joinToString("\n\n") { formatEntry(it) }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.error_log_share_subject))
            putExtra(Intent.EXTRA_TEXT, body)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.error_log_share)))
    }
}
