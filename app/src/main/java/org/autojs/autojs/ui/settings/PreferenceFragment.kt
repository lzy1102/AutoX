package org.autojs.autojs.ui.settings

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import com.afollestad.materialdialogs.MaterialDialog
import com.stardust.pio.PFiles
import com.stardust.util.ClipboardUtil
import de.psdev.licensesdialog.LicensesDialog
import org.autojs.autojs.Pref
import org.autojs.autojs.external.open.RunIntentActivity
import org.autojs.autojs.mcp.McpConfig
import org.autojs.autojs.ui.widget.CommonMarkdownView
import org.autojs.autoxjs.R

class PreferenceFragment : PreferenceFragmentCompat() {
    private val ACTION_MAP = mutableMapOf<String, (activity: Activity) -> Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ACTION_MAP.apply {
            //.put(getString(R.string.text_theme_color), () -> selectThemeColor(getActivity()))
            // .put(getString(R.string.text_check_for_updates), () -> new UpdateCheckDialog(getActivity()).show())
            // .put(getString(R.string.text_issue_report), () -> startActivity(new Intent(getActivity(), IssueReporterActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)))
            put(getString(R.string.text_about_me_and_repo)) {
                it.startActivity(
                    Intent(it, AboutActivity_::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            put(getString(R.string.text_licenses)) { showLicenseDialog(it) }
            put(getString(R.string.text_licenses_other)) { showLicenseDialog2(it) }
        }

    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preferences)
        refreshMcpTokenSummary()
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        if (preference is ScriptDirPathPreference) {
            ScriptDirPathPreferenceFragmentCompat.newInstance(preference.getKey())?.let {
                it.setTargetFragment(this, 1234)
                it.show(
                    this.parentFragmentManager,
                    "androidx.preference.PreferenceFragment.DIALOG1"
                )
                return
            }
        }
        super.onDisplayPreferenceDialog(preference)
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (preference.key) {
            getString(R.string.key_mcp_token_copy) -> {
                copyMcpToken()
                return true
            }
            getString(R.string.key_mcp_token_regenerate) -> {
                confirmRegenerateMcpToken()
                return true
            }
        }
        val action = ACTION_MAP[preference.title.toString()]
        val activity = requireActivity()
        if (preference.title == getString(R.string.text_intent_run_script)) {
            val state = if ((preference as SwitchPreference).isChecked) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            activity.packageManager.setComponentEnabledSetting(
                ComponentName(activity, RunIntentActivity::class.java),
                state,
                PackageManager.DONT_KILL_APP
            );
            return true
        }
        return if (action != null) {
            action(activity)
            true
        } else {
            super.onPreferenceTreeClick(preference)
        }
    }

    // ------------------------------------------------------------ MCP Token

    /** 摘要只显示脱敏 Token，完整值通过点击复制，避免设置页被旁人一眼看全 */
    private fun refreshMcpTokenSummary() {
        val preference = findPreference<Preference>(getString(R.string.key_mcp_token_copy)) ?: return
        val token = Pref.getMcpToken()
        // 注意：Preference 同时有 setSummary(CharSequence) 与 setSummary(int)，
        // Kotlin 合成属性不可用，须显式调用 setter
        preference.setSummary(
            if (token.isNullOrEmpty()) {
                getString(R.string.summary_mcp_token_copy)
            } else {
                getString(R.string.text_mcp_token_current, maskToken(token))
            }
        )
    }

    private fun maskToken(token: String): String =
        if (token.length <= 12) token else token.substring(0, 6) + "…" + token.takeLast(4)

    private fun copyMcpToken() {
        val token = McpConfig.token()
        ClipboardUtil.setClip(requireContext(), token)
        Toast.makeText(requireContext(), R.string.text_mcp_token_copied, Toast.LENGTH_SHORT).show()
        refreshMcpTokenSummary()
    }

    private fun confirmRegenerateMcpToken() {
        MaterialDialog.Builder(requireActivity())
            .title(R.string.text_mcp_token_regenerate)
            .content(R.string.summary_mcp_token_regenerate)
            .positiveText(R.string.ok)
            .negativeText(R.string.cancel)
            .onPositive { _, _ ->
                val token = McpConfig.regenerateToken()
                ClipboardUtil.setClip(requireContext(), token)
                Toast.makeText(
                    requireContext(),
                    R.string.text_mcp_token_regenerated,
                    Toast.LENGTH_SHORT
                ).show()
                refreshMcpTokenSummary()
            }
            .show()
    }

    companion object {
        private fun showLicenseDialog(context: Context) {
            LicensesDialog.Builder(context)
                .setNotices(R.raw.licenses)
                .setIncludeOwnLicense(true)
                .build()
                .show()
        }

        private fun showLicenseDialog2(context: Context) {
            CommonMarkdownView.DialogBuilder(context)
                .padding(36, 0, 36, 0)
                .markdown(PFiles.read(context.resources.openRawResource(R.raw.licenses_other)))
                .title(R.string.text_licenses_other)
                .positiveText(R.string.ok)
                .canceledOnTouchOutside(false)
                .show()
        }
    }
}