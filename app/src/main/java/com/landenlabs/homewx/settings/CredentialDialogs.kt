package com.landenlabs.homewx.settings

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.landenlabs.homewx.R
import com.landenlabs.homewx.databinding.DialogDroughtBinding
import com.landenlabs.homewx.databinding.DialogFeedsBinding
import com.landenlabs.homewx.databinding.DialogGoveeBinding
import com.landenlabs.homewx.databinding.DialogWxdataBinding
import com.landenlabs.homewx.databinding.DialogWyzeBinding
import com.landenlabs.homewx.news.UsStates
import com.landenlabs.homewx.state.AppState
import com.landenlabs.homewx.weather.wxdata.WxDataWeatherProvider

/**
 * The Govee / Wyze / RSS-feed / drought-state dialogs opened from Settings > Accounts & Feeds. Unlike the rest of
 * the Settings screen (auto-saved on close, undoable), each dialog saves immediately on Save and
 * tells the pollers and the News tabs via [AppState.notifyConfigChanged]. [onChanged] lets the
 * Settings screen refresh its status lines.
 */
class CredentialDialogs(private val activity: AppCompatActivity, private val onChanged: () -> Unit) {

    fun showGovee() {
        val binding = DialogGoveeBinding.inflate(LayoutInflater.from(activity))
        binding.goveeApiKeyEditText.setText(CredentialStore.getGoveeApiKey(activity))
        binding.goveeDefaultNoteText.visibility = if (CredentialStore.isGoveeDefault(activity)) View.VISIBLE else View.GONE

        dialogBuilder(R.string.cred_govee_title, binding.root)
            .setNeutralButton(R.string.cred_clear) { _, _ ->
                CredentialStore.setGoveeApiKey(activity, "")
                changed()
            }
            .show()
            .onSave {
                CredentialStore.setGoveeApiKey(activity, binding.goveeApiKeyEditText.text.toString())
                changed()
                true
            }
    }

    fun showWxData() {
        val binding = DialogWxdataBinding.inflate(LayoutInflater.from(activity))
        binding.wxdataApiKeyEditText.setText(CredentialStore.getWxDataApiKey(activity))
        binding.wxdataDefaultNoteText.visibility = if (CredentialStore.isWxDataDefault(activity)) View.VISIBLE else View.GONE

        dialogBuilder(R.string.cred_wxdata_title, binding.root)
            .setNeutralButton(R.string.cred_clear) { _, _ ->
                CredentialStore.setWxDataApiKey(activity, "")
                wxDataKeyChanged()
            }
            .show()
            .onSave {
                CredentialStore.setWxDataApiKey(activity, binding.wxdataApiKeyEditText.text.toString())
                wxDataKeyChanged()
                true
            }
    }

    /** The WxData SDK starts once per process. A first key starts it now; replacing a key it
     *  already started with can't take effect until the app restarts, so say so. */
    private fun wxDataKeyChanged() {
        WxDataWeatherProvider.initialize(activity)
        if (WxDataWeatherProvider.needsRestartForKey(activity)) {
            Toast.makeText(activity, R.string.cred_wxdata_restart, Toast.LENGTH_LONG).show()
        }
        changed()
    }

    fun showWyze() {
        val binding = DialogWyzeBinding.inflate(LayoutInflater.from(activity))
        binding.wyzeUserEditText.setText(CredentialStore.getWyzeUser(activity))
        binding.wyzePasswordEditText.setText(CredentialStore.getWyzePassword(activity))
        binding.wyzeDefaultNoteText.visibility = if (CredentialStore.isWyzeDefault(activity)) View.VISIBLE else View.GONE
        binding.wyzeUserEditText.doAfterTextChanged { binding.wyzeUserEditText.error = null }
        binding.wyzePasswordEditText.doAfterTextChanged { binding.wyzePasswordEditText.error = null }

        dialogBuilder(R.string.cred_wyze_title, binding.root)
            .setNeutralButton(R.string.cred_clear) { _, _ ->
                CredentialStore.setWyzeLogin(activity, "", "")
                changed()
            }
            .show()
            .onSave {
                val user = binding.wyzeUserEditText.text.toString().trim()
                val password = binding.wyzePasswordEditText.text.toString()
                // Both or neither: half a login can't sign in and would just trip the retry breaker.
                binding.wyzeUserEditText.error =
                    activity.getString(R.string.cred_wyze_both_required).takeIf { user.isBlank() && password.isNotBlank() }
                binding.wyzePasswordEditText.error =
                    activity.getString(R.string.cred_wyze_both_required).takeIf { password.isBlank() && user.isNotBlank() }
                if (binding.wyzeUserEditText.error != null || binding.wyzePasswordEditText.error != null) return@onSave false
                CredentialStore.setWyzeLogin(activity, user, password)
                changed()
                true
            }
    }

    fun showFeeds() {
        val binding = DialogFeedsBinding.inflate(LayoutInflater.from(activity))
        val feed1 = AppSettings.getNewsFeed(activity, 1)
        val feed2 = AppSettings.getNewsFeed(activity, 2)
        binding.feed1NameEditText.setText(feed1?.label)
        binding.feed1UrlEditText.setText(feed1?.feedUrl)
        binding.feed2NameEditText.setText(feed2?.label)
        binding.feed2UrlEditText.setText(feed2?.feedUrl)
        binding.feed1UrlEditText.doAfterTextChanged { binding.feed1UrlLayout.error = null }
        binding.feed2UrlEditText.doAfterTextChanged { binding.feed2UrlLayout.error = null }

        dialogBuilder(R.string.feeds_title, binding.root)
            .setNeutralButton(R.string.feeds_defaults) { _, _ ->
                AppSettings.resetNewsFeeds(activity)
                changed()
            }
            .show()
            .onSave {
                val url1 = binding.feed1UrlEditText.text.toString().trim()
                val url2 = binding.feed2UrlEditText.text.toString().trim()
                binding.feed1UrlLayout.error = activity.getString(R.string.feeds_url_invalid).takeIf { !isFeedUrl(url1) }
                binding.feed2UrlLayout.error =
                    activity.getString(R.string.feeds_url_invalid).takeIf { url2.isNotEmpty() && !isFeedUrl(url2) }
                if (binding.feed1UrlLayout.error != null || binding.feed2UrlLayout.error != null) return@onSave false
                AppSettings.setNewsFeed(activity, 1, binding.feed1NameEditText.text.toString(), url1)
                AppSettings.setNewsFeed(activity, 2, binding.feed2NameEditText.text.toString(), url2)
                changed()
                true
            }
    }

    fun showDrought() {
        val binding = DialogDroughtBinding.inflate(LayoutInflater.from(activity))
        binding.droughtStateSpinner.adapter = ArrayAdapter(
            activity,
            android.R.layout.simple_spinner_dropdown_item,
            UsStates.all.map { "${it.second} (${it.first})" }
        )
        fun select(code: String) =
            binding.droughtStateSpinner.setSelection(UsStates.all.indexOfFirst { it.first == code }.coerceAtLeast(0))
        select(AppSettings.getDroughtState(activity))

        dialogBuilder(R.string.drought_title, binding.root)
            .setNeutralButton(R.string.drought_default) { _, _ ->
                AppSettings.setDroughtState(activity, UsStates.DEFAULT_CODE)
                changed()
            }
            .show()
            .onSave {
                AppSettings.setDroughtState(activity, UsStates.all[binding.droughtStateSpinner.selectedItemPosition].first)
                changed()
                true
            }
    }

    private fun dialogBuilder(titleRes: Int, view: View) = MaterialAlertDialogBuilder(activity)
        .setTitle(titleRes)
        .setView(view)
        .setNegativeButton(android.R.string.cancel, null)
        // Real handler is attached by onSave after show(), so a failed validation can keep the dialog open.
        .setPositiveButton(R.string.cred_save, null)

    /** Replaces the positive button's default dismiss-on-click with [save], closing only when it returns true. */
    private fun AlertDialog.onSave(save: () -> Boolean) {
        getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { if (save()) dismiss() }
    }

    private fun changed() {
        AppState.notifyConfigChanged()
        onChanged()
    }

    /** RSS feeds must be https - cleartext http is blocked by default on this target SDK. */
    private fun isFeedUrl(url: String): Boolean {
        val uri = Uri.parse(url)
        return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
    }
}
