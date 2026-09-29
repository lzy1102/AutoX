package org.autojs.autojs;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Environment;
import android.widget.Toast;
import androidx.preference.PreferenceManager;
import com.stardust.app.GlobalAppContext;
import com.stardust.autojs.runtime.accessibility.AccessibilityConfig;

import org.autojs.autojs.autojs.key.GlobalKeyObserver;
import org.autojs.autojs.mcp.McpConfig;
import org.autojs.autojs.mcp.McpServer;
import org.autojs.autoxjs.R;

import java.io.File;
import java.util.concurrent.TimeUnit;

/**
 * Created by Stardust on 2017/1/31.
 */
public class Pref {

    private static final SharedPreferences DISPOSABLE_BOOLEAN = GlobalAppContext.get().getSharedPreferences("DISPOSABLE_BOOLEAN", Context.MODE_PRIVATE);
    private static final String KEY_SERVER_ADDRESS = "KEY_SERVER_ADDRESS";
    private static final String KEY_SHOULD_SHOW_ANNUNCIATION = "KEY_SHOULD_SHOW_ANNUNCIATION";
    private static final String KEY_FLOATING_MENU_SHOWN = "KEY_FLOATING_MENU_SHOWN";
    private static final String KEY_EDITOR_THEME = "editor.theme";
    private static final String KEY_EDITOR_TEXT_SIZE = "editor.textSize";

    private static SharedPreferences.OnSharedPreferenceChangeListener onSharedPreferenceChangeListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
        @Override
        public void onSharedPreferenceChanged(SharedPreferences p, String key) {
            if (key.equals(getString(R.string.key_guard_mode))) {
                AccessibilityConfig.setIsUnintendedGuardEnabled(p.getBoolean(getString(R.string.key_guard_mode), false));
            } else if ((key.equals(getString(R.string.key_use_volume_control_record)) || key.equals(getString(R.string.key_use_volume_control_running)))
                    && p.getBoolean(key, false)) {
                GlobalKeyObserver.init();
            } else if (key.equals(getString(R.string.key_mcp_enabled))) {
                syncMcpService(p.getBoolean(key, false));
            } else if (key.equals(getString(R.string.key_mcp_port)) || key.equals(getString(R.string.key_mcp_allow_lan))) {
                restartMcpIfRunning();
            }
        }
    };

    /**
     * 设置页的主开关。与抽屉开关走同一套启停逻辑，避免出现「开关有值但服务没动」的死开关。
     */
    private static void syncMcpService(boolean enabled) {
        try {
            if (enabled) {
                if (!McpServer.INSTANCE.isRunning()) {
                    McpServer.INSTANCE.start();
                }
            } else if (McpServer.INSTANCE.isRunning()) {
                McpServer.INSTANCE.stop();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 端口或绑定范围变化后必须重建监听引擎，否则用户改了端口却连不上（旧引擎仍占着原端口）。
     */
    private static void restartMcpIfRunning() {
        try {
            if (!McpServer.INSTANCE.isRunning()) {
                return;
            }
            McpServer.INSTANCE.restart();
            String address = McpConfig.INSTANCE.getHost() + ":" + McpConfig.INSTANCE.getPort();
            Toast.makeText(
                    GlobalAppContext.get(),
                    GlobalAppContext.getString(R.string.text_mcp_service_restarted, address),
                    Toast.LENGTH_SHORT
            ).show();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    static {
        AccessibilityConfig.setIsUnintendedGuardEnabled(def().getBoolean(getString(R.string.key_guard_mode), false));
    }

    private static SharedPreferences def() {
        return PreferenceManager.getDefaultSharedPreferences(GlobalAppContext.get());
    }

    private static boolean getDisposableBoolean(String key, boolean defaultValue) {
        boolean b = DISPOSABLE_BOOLEAN.getBoolean(key, defaultValue);
        if (b == defaultValue) {
            DISPOSABLE_BOOLEAN.edit().putBoolean(key, !defaultValue).apply();
        }
        return b;
    }

    public static boolean isNightModeEnabled() {
        return def().getBoolean(getString(R.string.key_night_mode), false);
    }

    public static boolean isFirstGoToAccessibilitySetting() {
        return getDisposableBoolean("isFirstGoToAccessibilitySetting", true);
    }

    public static int oldVersion() {
        return 0;
    }

    public static boolean isRunningVolumeControlEnabled() {
        return def().getBoolean(getString(R.string.key_use_volume_control_running), false);
    }

    public static boolean shouldEnableAccessibilityServiceByRoot() {
        return def().getBoolean(getString(R.string.key_enable_accessibility_service_by_root), false);
    }

    private static String getString(int id) {
        return GlobalAppContext.getString(id);
    }

    public static boolean isFirstUsing() {
        return getDisposableBoolean("isFirstUsing", true);
    }

    static {
        def().registerOnSharedPreferenceChangeListener(onSharedPreferenceChangeListener);
    }

    public static boolean isEditActivityFirstUsing() {
        return getDisposableBoolean("Love Honmua 18.7.9", true);
    }

    public static String getServerAddressOrDefault(String defaultAddress) {
        return def().getString(KEY_SERVER_ADDRESS, defaultAddress);
    }

    public static void saveServerAddress(String address) {
        def().edit().putString(KEY_SERVER_ADDRESS, address).apply();
    }

    public static boolean shouldShowAnnunciation() {
        return getDisposableBoolean(KEY_SHOULD_SHOW_ANNUNCIATION, true);
    }

    private static boolean isFirstDay() {
        long firstUsingMillis = def().getLong("firstUsingMillis", -1);
        if (firstUsingMillis == -1) {
            def().edit().putLong("firstUsingMillis", System.currentTimeMillis()).apply();
            return true;
        }
        return System.currentTimeMillis() - firstUsingMillis <= TimeUnit.DAYS.toMillis(1);
    }

    public static boolean isRecordToastEnabled() {
        return def().getBoolean(getString(R.string.key_record_toast), true);
    }

    public static boolean rootRecordGeneratesBinary() {
        return def().getString(getString(R.string.key_root_record_out_file_type), "binary")
                .equals("binary");
    }

    public static boolean isObservingKeyEnabled() {
        return def().getBoolean(getString(R.string.key_enable_observe_key), false);
    }

    public static boolean isStableModeEnabled() {
        return def().getBoolean(getString(R.string.key_stable_mode), false);
    }

    public static boolean isSingleBuildCleanModeEnabled() {
        return def().getBoolean(getString(R.string.key_single_build_clean_mode), true);
    }

    public static String getDocumentationUrl() {
//        String docSource = def().getString(getString(R.string.key_documentation_source), null);
        return "http://doc.autoxjs.com/";
    }

    public static boolean isFloatingMenuShown() {
        return def().getBoolean(KEY_FLOATING_MENU_SHOWN, false);
    }

    public static boolean isAutoBack() {
        return def().getBoolean(getString(R.string.key_auto_backup), true);
    }

    public static void setFloatingMenuShown(boolean checked) {
        def().edit().putBoolean(KEY_FLOATING_MENU_SHOWN, checked).apply();
    }

    public static String getCurrentTheme() {
        return def().getString(KEY_EDITOR_THEME, null);
    }

    public static void setCurrentTheme(String theme) {
        def().edit().putString(KEY_EDITOR_THEME, theme).apply();
    }

    public static void setEditorTextSize(int value) {
        def().edit().putInt(KEY_EDITOR_TEXT_SIZE, value).apply();
    }

    public static int getEditorTextSize(int defValue) {
        return def().getInt(KEY_EDITOR_TEXT_SIZE, defValue);
    }

    public static String getScriptDirPath() {
        String dir = def().getString(getString(R.string.key_script_dir_path),
                getString(R.string.default_value_script_dir_path));
        return new File(Environment.getExternalStorageDirectory(), dir).getPath();
    }

    public static String getKeyStorePath() {
        return getScriptDirPath().concat("/.keyStore/");
    }

    public static String getKeyStorePassWord(String keyName) {
        return def().getString(keyName, "");
    }

    public static void setKeyStorePassWord(String keyName, String passWord) {
        def().edit().putString(keyName, passWord).apply();
    }

    public static boolean isForegroundServiceEnabled() {
        return def().getBoolean(getString(R.string.key_foreground_servie), false);
    }

    public static void setCode(String value) {
        def().edit().putString("user_code", value).apply();
    }

    public static String getCode(String defValue) {
        return def().getString("user_code", defValue);
    }

    public static void setWebData(String webDataJson) {
        def().edit().putString("WebData", webDataJson).apply();
    }

    public static String getWebData() {
        return def().getString("WebData", "");
    }

    public static void setPermissionCheck(Boolean flag) {
        def().edit().putBoolean("permissionCheck", flag).apply();
    }

    public static Boolean getPermissionCheck() {
        return def().getBoolean("permissionCheck", true);
    }

    public static void setLineWrap(Boolean flag) {
        def().edit().putBoolean("LineWrap", flag).apply();
    }

    public static Boolean getLineWrap() {
        return def().getBoolean("LineWrap", false);
    }

    public static void setTaskManager(int number) {
        def().edit().putInt("TaskManager", number).apply();
    }

    public static int getTaskManager() {
        return def().getInt("TaskManager", 0);
    }

    // ------------------------------ MCP ------------------------------

    public static boolean isMcpEnabled() {
        return def().getBoolean(getString(R.string.key_mcp_enabled), false);
    }

    public static void setMcpEnabled(boolean enabled) {
        def().edit().putBoolean(getString(R.string.key_mcp_enabled), enabled).apply();
    }

    public static int getMcpPort() {
        return def().getInt(getString(R.string.key_mcp_port), McpConfig.DEFAULT_PORT);
    }

    public static void setMcpPort(int port) {
        def().edit().putInt(getString(R.string.key_mcp_port), port).apply();
    }

    public static boolean isMcpAllowLan() {
        return def().getBoolean(getString(R.string.key_mcp_allow_lan), false);
    }

    public static void setMcpAllowLan(boolean allow) {
        def().edit().putBoolean(getString(R.string.key_mcp_allow_lan), allow).apply();
    }

    public static boolean isMcpTokenRequired() {
        return def().getBoolean(getString(R.string.key_mcp_token_required), true);
    }

    public static void setMcpTokenRequired(boolean required) {
        def().edit().putBoolean(getString(R.string.key_mcp_token_required), required).apply();
    }

    public static String getMcpToken() {
        return def().getString(getString(R.string.key_mcp_token), "");
    }

    public static void setMcpToken(String token) {
        def().edit().putString(getString(R.string.key_mcp_token), token).apply();
    }

    public static boolean isMcpAuditLogEnabled() {
        return def().getBoolean(getString(R.string.key_mcp_audit_log), true);
    }

    public static void setMcpAuditLogEnabled(boolean enabled) {
        def().edit().putBoolean(getString(R.string.key_mcp_audit_log), enabled).apply();
    }

}
