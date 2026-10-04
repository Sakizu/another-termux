package com.termux.shared.termux.settings.preferences;

import android.content.Context;
import android.util.TypedValue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.android.PackageUtils;
import com.termux.shared.settings.preferences.AppSharedPreferences;
import com.termux.shared.settings.preferences.SharedPreferenceUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.logger.Logger;
import com.termux.shared.data.DataUtils;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.termux.settings.preferences.TermuxPreferenceConstants.TERMUX_APP;

public class TermuxAppSharedPreferences extends AppSharedPreferences {

    private volatile int MIN_FONTSIZE;
    private volatile int MAX_FONTSIZE;
    private volatile int DEFAULT_FONTSIZE;

    private static final String LOG_TAG = "TermuxAppSharedPreferences";

    /**
     * Single static monitor for this class. It guards lazy initialization of
     * {@link #sCachedInstance} and serializes all boot-counter increments.
     *
     * A static lock is required instead of synchronizing on the instance because
     * {@link #build(Context)} used to return a fresh instance on every call, so
     * instance-level monitors gave zero mutual exclusion between concurrent command
     * spawns and the boot counters could be double-incremented.
     */
    private static final Object sLock = new Object();

    /**
     * Per-process cached instance. Building one requires a {@code createPackageContext}
     * binder IPC, so the first successful build is cached and reused instead of paying
     * the IPC cost on every spawned command. Volatile for safe publication of the
     * fully constructed instance to other threads.
     */
    @Nullable
    private static volatile TermuxAppSharedPreferences sCachedInstance;

    private TermuxAppSharedPreferences(@NonNull Context context) {
        super(context,
            SharedPreferenceUtils.getPrivateSharedPreferences(context,
                TermuxConstants.TERMUX_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION),
            SharedPreferenceUtils.getPrivateAndMultiProcessSharedPreferences(context,
                TermuxConstants.TERMUX_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION));

        setFontVariables(context);
    }

    /**
     * Get {@link TermuxAppSharedPreferences}.
     *
     * The first successful build is cached per process and reused by later calls, so
     * the {@code createPackageContext} binder IPC is only paid once instead of on
     * every spawned command. A failed build is not cached and returns {@code null}
     * like before.
     *
     * @param context The {@link Context} to use to get the {@link Context} of the
     *                {@link TermuxConstants#TERMUX_PACKAGE_NAME}.
     * @return Returns the {@link TermuxAppSharedPreferences}. This will {@code null} if an exception is raised.
     */
    @Nullable
    public static TermuxAppSharedPreferences build(@NonNull final Context context) {
        TermuxAppSharedPreferences preferences = getCachedInstance(context);
        if (preferences != null)
            return preferences;

        synchronized (sLock) {
            preferences = sCachedInstance;
            if (preferences != null) {
                // Another thread built it while we waited; refresh display-dependent state.
                preferences.setFontVariables(context);
                return preferences;
            }

            Context termuxPackageContext = PackageUtils.getContextForPackage(context, TermuxConstants.TERMUX_PACKAGE_NAME);
            if (termuxPackageContext == null)
                return null;

            preferences = new TermuxAppSharedPreferences(termuxPackageContext);
            sCachedInstance = preferences;
            return preferences;
        }
    }

    /**
     * Get {@link TermuxAppSharedPreferences}.
     *
     * The first successful build is cached per process and reused by later calls, so
     * the {@code createPackageContext} binder IPC is only paid once instead of on
     * every spawned command. A failed build is not cached; the {@code exitAppOnError}
     * behaviour on failure is unchanged.
     *
     * @param context The {@link Context} to use to get the {@link Context} of the
     *                {@link TermuxConstants#TERMUX_PACKAGE_NAME}.
     * @param exitAppOnError If {@code true} and failed to get package context, then a dialog will
     *                       be shown which when dismissed will exit the app.
     * @return Returns the {@link TermuxAppSharedPreferences}. This will {@code null} if an exception is raised.
     */
    public static TermuxAppSharedPreferences build(@NonNull final Context context, final boolean exitAppOnError) {
        TermuxAppSharedPreferences preferences = getCachedInstance(context);
        if (preferences != null)
            return preferences;

        synchronized (sLock) {
            preferences = sCachedInstance;
            if (preferences != null) {
                // Another thread built it while we waited; refresh display-dependent state.
                preferences.setFontVariables(context);
                return preferences;
            }

            Context termuxPackageContext = TermuxUtils.getContextForPackageOrExitApp(context, TermuxConstants.TERMUX_PACKAGE_NAME, exitAppOnError);
            if (termuxPackageContext == null)
                return null;

            preferences = new TermuxAppSharedPreferences(termuxPackageContext);
            sCachedInstance = preferences;
            return preferences;
        }
    }

    /**
     * Get the cached per-process instance, or {@code null} if nothing was built yet.
     * Display-dependent font size bounds are refreshed from the caller's context so a
     * cached instance behaves like a freshly built one when display metrics change at
     * runtime. The writes are guarded by {@link #sLock}; the fields are volatile so
     * readers always see a consistently published triple.
     */
    @Nullable
    private static TermuxAppSharedPreferences getCachedInstance(@NonNull final Context context) {
        TermuxAppSharedPreferences preferences = sCachedInstance;
        if (preferences == null)
            return null;

        synchronized (sLock) {
            preferences.setFontVariables(context);
        }
        return preferences;
    }



    public boolean shouldShowTerminalToolbar() {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_SHOW_TERMINAL_TOOLBAR, TERMUX_APP.DEFAULT_VALUE_SHOW_TERMINAL_TOOLBAR);
    }

    public void setShowTerminalToolbar(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_SHOW_TERMINAL_TOOLBAR, value, false);
    }

    public boolean toogleShowTerminalToolbar() {
        boolean currentValue = shouldShowTerminalToolbar();
        setShowTerminalToolbar(!currentValue);
        return !currentValue;
    }



    public boolean isTerminalMarginAdjustmentEnabled() {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_TERMINAL_MARGIN_ADJUSTMENT, TERMUX_APP.DEFAULT_TERMINAL_MARGIN_ADJUSTMENT);
    }

    public void setTerminalMarginAdjustment(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_TERMINAL_MARGIN_ADJUSTMENT, value, false);
    }



    public boolean isImmersiveModeEnabled() {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_IMMERSIVE_MODE, TERMUX_APP.DEFAULT_IMMERSIVE_MODE);
    }

    public void setImmersiveMode(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_IMMERSIVE_MODE, value, false);
    }

    public boolean isImmersiveHideBarsEnabled() {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_IMMERSIVE_HIDE_BARS, TERMUX_APP.DEFAULT_IMMERSIVE_HIDE_BARS);
    }

    public void setImmersiveHideBars(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_IMMERSIVE_HIDE_BARS, value, false);
    }

    public boolean isKillSessionProcessesEnabled() {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_KILL_SESSION_PROCESSES, TERMUX_APP.DEFAULT_KILL_SESSION_PROCESSES);
    }

    public void setKillSessionProcesses(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_KILL_SESSION_PROCESSES, value, false);
    }



    public boolean isSoftKeyboardEnabled() {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_SOFT_KEYBOARD_ENABLED, TERMUX_APP.DEFAULT_VALUE_KEY_SOFT_KEYBOARD_ENABLED);
    }

    public void setSoftKeyboardEnabled(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_SOFT_KEYBOARD_ENABLED, value, false);
    }

    public boolean isSoftKeyboardEnabledOnlyIfNoHardware() {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_SOFT_KEYBOARD_ENABLED_ONLY_IF_NO_HARDWARE, TERMUX_APP.DEFAULT_VALUE_KEY_SOFT_KEYBOARD_ENABLED_ONLY_IF_NO_HARDWARE);
    }

    public void setSoftKeyboardEnabledOnlyIfNoHardware(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_SOFT_KEYBOARD_ENABLED_ONLY_IF_NO_HARDWARE, value, false);
    }



    public boolean shouldKeepScreenOn() {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_KEEP_SCREEN_ON, TERMUX_APP.DEFAULT_VALUE_KEEP_SCREEN_ON);
    }

    public void setKeepScreenOn(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_KEEP_SCREEN_ON, value, false);
    }



    public static int[] getDefaultFontSizes(Context context) {
        float dipInPixels = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1, context.getResources().getDisplayMetrics());

        int[] sizes = new int[3];

        // This is a bit arbitrary and sub-optimal. We want to give a sensible default for minimum font size
        // to prevent invisible text due to zoom be mistake:
        sizes[1] = (int) (4f * dipInPixels); // min

        // http://www.google.com/design/spec/style/typography.html#typography-line-height
        int defaultFontSize = Math.round(12 * dipInPixels);
        // Make it divisible by 2 since that is the minimal adjustment step:
        if (defaultFontSize % 2 == 1) defaultFontSize--;

        sizes[0] = defaultFontSize; // default

        sizes[2] = 256; // max

        return sizes;
    }

    public void setFontVariables(Context context) {
        int[] sizes = getDefaultFontSizes(context);

        DEFAULT_FONTSIZE = sizes[0];
        MIN_FONTSIZE = sizes[1];
        MAX_FONTSIZE = sizes[2];
    }

    public int getFontSize() {
        int fontSize = SharedPreferenceUtils.getIntStoredAsString(mSharedPreferences, TERMUX_APP.KEY_FONTSIZE, DEFAULT_FONTSIZE);
        return DataUtils.clamp(fontSize, MIN_FONTSIZE, MAX_FONTSIZE);
    }

    public void setFontSize(int value) {
        SharedPreferenceUtils.setIntStoredAsString(mSharedPreferences, TERMUX_APP.KEY_FONTSIZE, value, false);
    }

    public void changeFontSize(boolean increase) {
        int fontSize = getFontSize();

        fontSize += (increase ? 1 : -1) * 2;
        fontSize = Math.max(MIN_FONTSIZE, Math.min(fontSize, MAX_FONTSIZE));

        setFontSize(fontSize);
    }



    public String getCurrentSession() {
        return SharedPreferenceUtils.getString(mSharedPreferences, TERMUX_APP.KEY_CURRENT_SESSION, null, true);
    }

    public void setCurrentSession(String value) {
        SharedPreferenceUtils.setString(mSharedPreferences, TERMUX_APP.KEY_CURRENT_SESSION, value, false);
    }



    public int getLogLevel() {
        return SharedPreferenceUtils.getInt(mSharedPreferences, TERMUX_APP.KEY_LOG_LEVEL, Logger.DEFAULT_LOG_LEVEL);
    }

    public void setLogLevel(Context context, int logLevel) {
        logLevel = Logger.setLogLevel(context, logLevel);
        SharedPreferenceUtils.setInt(mSharedPreferences, TERMUX_APP.KEY_LOG_LEVEL, logLevel, false);
    }



    public int getLastNotificationId() {
        return SharedPreferenceUtils.getInt(mSharedPreferences, TERMUX_APP.KEY_LAST_NOTIFICATION_ID, TERMUX_APP.DEFAULT_VALUE_KEY_LAST_NOTIFICATION_ID);
    }

    public void setLastNotificationId(int notificationId) {
        SharedPreferenceUtils.setInt(mSharedPreferences, TERMUX_APP.KEY_LAST_NOTIFICATION_ID, notificationId, false);
    }


    public int getAndIncrementAppShellNumberSinceBoot() {
        // Keep value at MAX_VALUE on integer overflow and not 0, since not first shell.
        // apply() (async disk write) is enough here: the in-memory update is synchronous
        // and the counter is only read in-process, so command startup is not blocked on I/O.
        // Synchronized on the static lock, not the instance monitor: every spawned command
        // used to get its own instance, so instance locks gave no mutual exclusion and two
        // concurrent spawns could read the same value and double-increment.
        synchronized (sLock) {
            return SharedPreferenceUtils.getAndIncrementInt(mSharedPreferences, TERMUX_APP.KEY_APP_SHELL_NUMBER_SINCE_BOOT,
                TERMUX_APP.DEFAULT_VALUE_APP_SHELL_NUMBER_SINCE_BOOT, false, Integer.MAX_VALUE);
        }
    }

    public void resetAppShellNumberSinceBoot() {
        synchronized (sLock) {
            SharedPreferenceUtils.setInt(mSharedPreferences, TERMUX_APP.KEY_APP_SHELL_NUMBER_SINCE_BOOT,
                TERMUX_APP.DEFAULT_VALUE_APP_SHELL_NUMBER_SINCE_BOOT, true);
        }
    }

    public int getAndIncrementTerminalSessionNumberSinceBoot() {
        // Keep value at MAX_VALUE on integer overflow and not 0, since not first shell.
        // apply() (async disk write) is enough here: the in-memory update is synchronous
        // and the counter is only read in-process, so command startup is not blocked on I/O.
        // Synchronized on the static lock, not the instance monitor: every spawned command
        // used to get its own instance, so instance locks gave no mutual exclusion and two
        // concurrent spawns could read the same value and double-increment.
        synchronized (sLock) {
            return SharedPreferenceUtils.getAndIncrementInt(mSharedPreferences, TERMUX_APP.KEY_TERMINAL_SESSION_NUMBER_SINCE_BOOT,
                TERMUX_APP.DEFAULT_VALUE_TERMINAL_SESSION_NUMBER_SINCE_BOOT, false, Integer.MAX_VALUE);
        }
    }

    public void resetTerminalSessionNumberSinceBoot() {
        synchronized (sLock) {
            SharedPreferenceUtils.setInt(mSharedPreferences, TERMUX_APP.KEY_TERMINAL_SESSION_NUMBER_SINCE_BOOT,
                TERMUX_APP.DEFAULT_VALUE_TERMINAL_SESSION_NUMBER_SINCE_BOOT, true);
        }
    }


    public boolean isTerminalViewKeyLoggingEnabled() {
        return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_TERMINAL_VIEW_KEY_LOGGING_ENABLED, TERMUX_APP.DEFAULT_VALUE_TERMINAL_VIEW_KEY_LOGGING_ENABLED);
    }

    public void setTerminalViewKeyLoggingEnabled(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_TERMINAL_VIEW_KEY_LOGGING_ENABLED, value, false);
    }



    public boolean arePluginErrorNotificationsEnabled(boolean readFromFile) {
        if (readFromFile)
            return SharedPreferenceUtils.getBoolean(mMultiProcessSharedPreferences, TERMUX_APP.KEY_PLUGIN_ERROR_NOTIFICATIONS_ENABLED, TERMUX_APP.DEFAULT_VALUE_PLUGIN_ERROR_NOTIFICATIONS_ENABLED);
        else
            return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_PLUGIN_ERROR_NOTIFICATIONS_ENABLED, TERMUX_APP.DEFAULT_VALUE_PLUGIN_ERROR_NOTIFICATIONS_ENABLED);
    }

    public void setPluginErrorNotificationsEnabled(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_PLUGIN_ERROR_NOTIFICATIONS_ENABLED, value, false);
    }



    public boolean areCrashReportNotificationsEnabled(boolean readFromFile) {
        if (readFromFile)
            return SharedPreferenceUtils.getBoolean(mMultiProcessSharedPreferences, TERMUX_APP.KEY_CRASH_REPORT_NOTIFICATIONS_ENABLED, TERMUX_APP.DEFAULT_VALUE_CRASH_REPORT_NOTIFICATIONS_ENABLED);
       else
            return SharedPreferenceUtils.getBoolean(mSharedPreferences, TERMUX_APP.KEY_CRASH_REPORT_NOTIFICATIONS_ENABLED, TERMUX_APP.DEFAULT_VALUE_CRASH_REPORT_NOTIFICATIONS_ENABLED);
    }

    public void setCrashReportNotificationsEnabled(boolean value) {
        SharedPreferenceUtils.setBoolean(mSharedPreferences, TERMUX_APP.KEY_CRASH_REPORT_NOTIFICATIONS_ENABLED, value, false);
    }

}
