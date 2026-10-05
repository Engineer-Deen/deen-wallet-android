package com.deenwallet.app;
 
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
 
import androidx.core.content.FileProvider;
 
import com.google.android.play.core.appupdate.AppUpdateInfo;
import com.google.android.play.core.appupdate.AppUpdateManager;
import com.google.android.play.core.appupdate.AppUpdateManagerFactory;
import com.google.android.play.core.appupdate.AppUpdateOptions;
import com.google.android.play.core.install.InstallStateUpdatedListener;
import com.google.android.play.core.install.model.AppUpdateType;
import com.google.android.play.core.install.model.UpdateAvailability;
 
import org.json.JSONArray;
import org.json.JSONObject;
 
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
 
/**
 * Handles Android application updates for both distribution channels:
 *
 * 1. Google Play installs -> Google Play In-App Updates.
 * 2. Direct APK installs  -> DeenWallet's own update metadata + APK installer.
 *
 * DEV vs PRODUCTION
 * -----------------
 * The build type decides everything automatically (no manual edit needed
 * when you ship):
 *
 *  - Debug build  (what you run from Android Studio / adb install app-debug.apk)
 *      * uses DEV_UPDATE_URL (your PC on the LAN)
 *      * NO 6-hour throttle (only a short anti-spam gap), so a freshly
 *        bumped server version shows the pop up on the next app resume
 *      * plain http:// is allowed, but ONLY for the same host as DEV_UPDATE_URL
 *
 *  - Release build
 *      * uses PROD_UPDATE_URL
 *      * 6-hour throttle between checks
 *      * only https:// download URLs are accepted
 *
 * Watch it work with:   adb logcat -s DeenWalletUpdate
 *
 * This class never changes the WebView URL or the web application version.
 */
public final class DeenWalletUpdateManager {
 
    private static final String TAG = "DeenWalletUpdate";
 
    private static final int PLAY_UPDATE_REQUEST_CODE = 2407;
 
    /* ------------------------------------------------------------------ */
    /* Endpoints                                                          */
    /* ------------------------------------------------------------------ */
 
    /**
     * Your development PC on the LAN. If your PC's IP changes (DHCP), change
     * ONLY this line. The APK download URL returned by the server must be on
     * the same host.
     */
    private static final String DEV_UPDATE_URL =
            "http://192.168.100.127:8082/api/app/android-update";
 
    private static final String PROD_UPDATE_URL =
            "https://api.deenwallapp.com/api/app/android-update";
 
    /* ------------------------------------------------------------------ */
    /* Throttling                                                         */
    /* ------------------------------------------------------------------ */
 
    private static final long RELEASE_CHECK_INTERVAL_MS =
            6L * 60L * 60L * 1000L;
 
    /** Debug builds: just enough to stop resume-loops from spamming. */
    private static final long DEBUG_CHECK_INTERVAL_MS = 5_000L;
 
    private static final String PREFS = "deenwallet_app_updates";
 
    private static final String PREF_LAST_CHECK = "last_check_at";
 
    private final MainActivity activity;
 
    private final Handler mainHandler =
            new Handler(Looper.getMainLooper());
 
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();
 
    private final boolean debugBuild;
 
    private AppUpdateManager playUpdateManager;
 
    private InstallStateUpdatedListener installStateListener;
 
    private volatile boolean checking;
 
    private boolean directUpdateDialogVisible;
 
    private boolean directForceUpdate;
 
    private JSONObject pendingDirectUpdate;
 
    private ProgressDialog progressDialog;
 
    public DeenWalletUpdateManager(MainActivity activity) {
        this.activity = activity;
        this.debugBuild =
                (activity.getApplicationInfo().flags
                        & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
 
        Log.i(TAG, "Update manager created. debugBuild=" + debugBuild
                + ", endpoint=" + updateUrl());
    }
 
    /* ------------------------------------------------------------------ */
    /* Public lifecycle API (called from MainActivity)                     */
    /* ------------------------------------------------------------------ */
 
    public void checkForUpdates() {
 
        if (activity.isFinishing()
                || activity.isDestroyed()) {
            return;
        }
 
        // An update dialog is already waiting for the user.
        if (directUpdateDialogVisible) {
            return;
        }
 
        if (checking) {
            Log.d(TAG, "Check skipped: another check is running");
            return;
        }
 
        if (!shouldCheck()) {
            Log.d(TAG, "Check skipped: checked recently");
            return;
        }
 
        checking = true;
 
        if (isInstalledFromGooglePlay()) {
            Log.i(TAG, "Installed from Google Play -> Play in-app update");
            checkGooglePlayUpdate();
        } else {
            Log.i(TAG, "Direct install -> checking " + updateUrl());
            checkDirectApkUpdate();
        }
    }
 
    public void onResume() {
 
        if (playUpdateManager != null) {
 
            playUpdateManager
                    .getAppUpdateInfo()
                    .addOnSuccessListener(info -> {
 
                        if (info.updateAvailability()
                                == UpdateAvailability
                                .DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
 
                            try {
 
                                playUpdateManager
                                        .startUpdateFlowForResult(
                                                info,
                                                activity,
                                                AppUpdateOptions
                                                        .newBuilder(
                                                                AppUpdateType
                                                                        .IMMEDIATE
                                                        )
                                                        .build(),
                                                PLAY_UPDATE_REQUEST_CODE
                                        );
 
                            } catch (Exception e) {
 
                                Log.w(
                                        TAG,
                                        "Could not resume Play update",
                                        e
                                );
                            }
                        }
                    });
        }
 
        // A pending update stays pending until the user taps "Later" or the
        // app is updated. This also covers returning from the Android
        // "install unknown apps" settings screen.
        if (pendingDirectUpdate != null
                && !directUpdateDialogVisible
                && progressDialog == null) {
 
            mainHandler.post(
                    this::showDirectUpdateDialog
            );
        }
    }
 
    public void onDestroy() {
 
        if (playUpdateManager != null
                && installStateListener != null) {
 
            playUpdateManager.unregisterListener(
                    installStateListener
            );
        }
 
        hideProgress();
 
        executor.shutdownNow();
    }
 
    /* ------------------------------------------------------------------ */
    /* Helpers                                                            */
    /* ------------------------------------------------------------------ */
 
    private String updateUrl() {
        return debugBuild ? DEV_UPDATE_URL : PROD_UPDATE_URL;
    }
 
    private long checkIntervalMs() {
        return debugBuild
                ? DEBUG_CHECK_INTERVAL_MS
                : RELEASE_CHECK_INTERVAL_MS;
    }
 
    private boolean shouldCheck() {
 
        long last =
                activity
                        .getSharedPreferences(
                                PREFS,
                                Context.MODE_PRIVATE
                        )
                        .getLong(
                                PREF_LAST_CHECK,
                                0L
                        );
 
        long now = System.currentTimeMillis();
 
        // Clock changed backwards -> never get stuck waiting.
        if (last > now) {
            return true;
        }
 
        return now - last >= checkIntervalMs();
    }
 
    /**
     * Only called once the server (or Google Play) actually answered.
     * A failed/offline check must NOT burn the 6-hour window, otherwise
     * the user is locked out of seeing the update.
     */
    private void markChecked() {
 
        activity
                .getSharedPreferences(
                        PREFS,
                        Context.MODE_PRIVATE
                )
                .edit()
                .putLong(
                        PREF_LAST_CHECK,
                        System.currentTimeMillis()
                )
                .apply();
    }
 
    private void finishChecking() {
        checking = false;
    }
 
    private long currentVersionCode() throws Exception {
 
        PackageInfo info =
                activity
                        .getPackageManager()
                        .getPackageInfo(
                                activity.getPackageName(),
                                0
                        );
 
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return info.getLongVersionCode();
        }
 
        //noinspection deprecation
        return info.versionCode;
    }
 
    private boolean isInstalledFromGooglePlay() {
 
        try {
 
            if (Build.VERSION.SDK_INT
                    >= Build.VERSION_CODES.R) {
 
                String installer =
                        activity
                                .getPackageManager()
                                .getInstallSourceInfo(
                                        activity.getPackageName()
                                )
                                .getInstallingPackageName();
 
                return "com.android.vending"
                        .equals(installer);
            }
 
            //noinspection deprecation
            String installer =
                    activity
                            .getPackageManager()
                            .getInstallerPackageName(
                                    activity.getPackageName()
                            );
 
            return "com.android.vending"
                    .equals(installer);
 
        } catch (Exception e) {
 
            Log.w(
                    TAG,
                    "Could not determine installation source",
                    e
            );
 
            return false;
        }
    }
 
    /* ------------------------------------------------------------------ */
    /* Google Play path                                                    */
    /* ------------------------------------------------------------------ */
 
    private void checkGooglePlayUpdate() {
 
        playUpdateManager =
                AppUpdateManagerFactory.create(activity);
 
        playUpdateManager
                .getAppUpdateInfo()
                .addOnSuccessListener(info -> {
 
                    finishChecking();
                    markChecked();
 
                    if (info.updateAvailability()
                            != UpdateAvailability
                            .UPDATE_AVAILABLE) {
                        return;
                    }
 
                    if (!info.isUpdateTypeAllowed(
                            AppUpdateType.IMMEDIATE)) {
                        return;
                    }
 
                    try {
 
                        playUpdateManager
                                .startUpdateFlowForResult(
                                        info,
                                        activity,
                                        AppUpdateOptions
                                                .newBuilder(
                                                        AppUpdateType
                                                                .IMMEDIATE
                                                )
                                                .build(),
                                        PLAY_UPDATE_REQUEST_CODE
                                );
 
                    } catch (Exception e) {
 
                        Log.e(
                                TAG,
                                "Failed to start Google Play update",
                                e
                        );
                    }
                })
                .addOnFailureListener(error -> {
 
                    finishChecking();
 
                    Log.w(
                            TAG,
                            "Google Play update check failed",
                            error
                    );
                });
    }
 
    /* ------------------------------------------------------------------ */
    /* Direct APK path                                                     */
    /* ------------------------------------------------------------------ */
 
    /**
     * Direct APK update response:
     *
     * {
     *   "versionCode": 2,
     *   "versionName": "1.1",
     *   "downloadUrl": "https://.../DeenWallet-1.1.apk",
     *   "forceUpdate": false,
     *   "releaseNotes": ["Bug fixes", "Security improvements"]
     * }
     */
    private void checkDirectApkUpdate() {
 
        if (executor.isShutdown()) {
            finishChecking();
            return;
        }
 
        executor.execute(() -> {
 
            HttpURLConnection connection = null;
 
            // Result computed on the worker thread, applied on the main thread.
            JSONObject updateToShow = null;
 
            try {
 
                URL url = new URL(updateUrl());
 
                connection =
                        (HttpURLConnection)
                                url.openConnection();
 
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(10000);
                connection.setRequestMethod("GET");
 
                connection.setRequestProperty(
                        "Accept",
                        "application/json"
                );
 
                connection.setUseCaches(false);
 
                int status =
                        connection.getResponseCode();
 
                if (status < 200 || status >= 300) {
 
                    Log.w(
                            TAG,
                            "Direct update endpoint returned HTTP "
                                    + status
                    );
 
                    return;
                }
 
                ByteArrayOutputStream body =
                        new ByteArrayOutputStream();
 
                try (InputStream input =
                             new BufferedInputStream(
                                     connection.getInputStream())) {
 
                    byte[] buffer = new byte[8192];
 
                    int read;
 
                    while ((read = input.read(buffer)) != -1) {
                        body.write(buffer, 0, read);
                    }
                }
 
                JSONObject update =
                        new JSONObject(
                                body.toString(
                                        StandardCharsets.UTF_8.name()
                                )
                        );
 
                // The server answered correctly -> the check counts.
                markChecked();
 
                long latestVersionCode =
                        update.optLong(
                                "versionCode",
                                0L
                        );
 
                long installedVersionCode =
                        currentVersionCode();
 
                Log.i(
                        TAG,
                        "Server versionCode=" + latestVersionCode
                                + ", installed versionCode="
                                + installedVersionCode
                );
 
                if (latestVersionCode
                        <= installedVersionCode) {
 
                    Log.i(TAG, "App is up to date - no pop up");
 
                    return;
                }
 
                String downloadUrl =
                        update
                                .optString(
                                        "downloadUrl",
                                        ""
                                )
                                .trim();
 
                if (!isAllowedDownloadUrl(
                        downloadUrl)) {
 
                    Log.w(
                            TAG,
                            "Ignoring unsafe/empty direct update URL: '"
                                    + downloadUrl + "'"
                    );
 
                    return;
                }
 
                updateToShow = update;
 
            } catch (Exception e) {
 
                Log.w(
                        TAG,
                        "Direct APK update check failed ("
                                + updateUrl() + ")",
                        e
                );
 
            } finally {
 
                if (connection != null) {
                    connection.disconnect();
                }
 
                final JSONObject result = updateToShow;
 
                // ALWAYS release the "checking" flag - on every path.
                mainHandler.post(() -> {
 
                    finishChecking();
 
                    if (result != null) {
 
                        Log.i(TAG, "Showing update pop up");
 
                        pendingDirectUpdate = result;
 
                        directForceUpdate =
                                result.optBoolean(
                                        "forceUpdate",
                                        false
                                );
 
                        showDirectUpdateDialog();
                    }
                });
            }
        });
    }
 
    /**
     * Production: HTTPS only.
     *
     * Debug builds: HTTP is accepted only when it points at the same host as
     * DEV_UPDATE_URL (your development PC).
     */
    private boolean isAllowedDownloadUrl(
            String downloadUrl
    ) {
 
        if (downloadUrl == null
                || downloadUrl.isEmpty()) {
            return false;
        }
 
        if (downloadUrl.startsWith("https://")) {
            return true;
        }
 
        if (!debugBuild) {
            return false;
        }
 
        try {
 
            String devHost =
                    Uri.parse(DEV_UPDATE_URL).getHost();
 
            Uri candidate = Uri.parse(downloadUrl);
 
            return "http".equals(candidate.getScheme())
                    && devHost != null
                    && devHost.equals(candidate.getHost());
 
        } catch (Exception e) {
            return false;
        }
    }
 
    private void showDirectUpdateDialog() {
 
        if (pendingDirectUpdate == null
                || directUpdateDialogVisible) {
            return;
        }
 
        if (activity.isFinishing()
                || activity.isDestroyed()) {
            return;
        }
 
        directUpdateDialogVisible = true;
 
        String versionName =
                pendingDirectUpdate.optString(
                        "versionName",
                        "new version"
                );
 
        String notes =
                buildReleaseNotes(
                        pendingDirectUpdate
                                .optJSONArray(
                                        "releaseNotes"
                                )
                );
 
        boolean force =
                pendingDirectUpdate.optBoolean(
                        "forceUpdate",
                        false
                );
 
        AlertDialog.Builder builder =
                new AlertDialog.Builder(activity)
                        .setTitle(
                                "DeenWallet update available"
                        )
                        .setMessage(
                                "Version "
                                        + versionName
                                        + " is available.\n\n"
                                        + notes
                        )
                        .setPositiveButton(
                                "Update now",
                                (dialog, which) -> {
 
                                    directUpdateDialogVisible =
                                            false;
 
                                    downloadAndInstallDirectUpdate();
                                }
                        );
 
        if (!force) {
 
            builder.setNegativeButton(
                    "Later",
                    (dialog, which) -> {
                        directUpdateDialogVisible = false;
                        pendingDirectUpdate = null;
                    }
            );
        }
 
        AlertDialog dialog =
                builder.create();
 
        dialog.setOnDismissListener(d -> {
 
            if (!force) {
                directUpdateDialogVisible = false;
            }
        });
 
        dialog.setCanceledOnTouchOutside(
                !force
        );
 
        dialog.setCancelable(!force);
 
        dialog.show();
    }
 
    private String buildReleaseNotes(
            JSONArray notes
    ) {
 
        if (notes == null
                || notes.length() == 0) {
 
            return "A newer version of DeenWallet is available "
                    + "with improvements and fixes.";
        }
 
        StringBuilder result =
                new StringBuilder();
 
        for (int i = 0;
             i < notes.length();
             i++) {
 
            String note =
                    notes.optString(
                            i,
                            ""
                    ).trim();
 
            if (!note.isEmpty()) {
 
                result
                        .append("• ")
                        .append(note)
                        .append('\n');
            }
        }
 
        return result.length() == 0
                ? "A newer version of DeenWallet is available "
                  + "with improvements and fixes."
                : result.toString().trim();
    }
 
    private void downloadAndInstallDirectUpdate() {
 
        if (pendingDirectUpdate == null) {
            return;
        }
 
        String downloadUrl =
                pendingDirectUpdate
                        .optString(
                                "downloadUrl",
                                ""
                        )
                        .trim();
 
        if (downloadUrl.isEmpty()) {
 
            pendingDirectUpdate = null;
 
            return;
        }
 
        if (!isAllowedDownloadUrl(
                downloadUrl)) {
 
            Log.w(
                    TAG,
                    "Blocked unsafe APK download URL: "
                            + downloadUrl
            );
 
            pendingDirectUpdate = null;
 
            return;
        }
 
        if (executor.isShutdown()) {
            return;
        }
 
        showProgress();
 
        executor.execute(() -> {
 
            HttpURLConnection connection = null;
 
            File apk =
                    new File(
                            activity.getCacheDir(),
                            "DeenWallet-update.apk"
                    );
 
            try {
 
                if (apk.exists()
                        && !apk.delete()) {
 
                    throw new IllegalStateException(
                            "Could not replace old update file"
                    );
                }
 
                URL url =
                        new URL(downloadUrl);
 
                connection =
                        (HttpURLConnection)
                                url.openConnection();
 
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(20000);
                connection.setRequestMethod("GET");
 
                connection.setRequestProperty(
                        "Accept",
                        "application/vnd.android.package-archive"
                );
 
                int status =
                        connection.getResponseCode();
 
                if (status < 200 || status >= 300) {
 
                    throw new IllegalStateException(
                            "Download failed with HTTP "
                                    + status
                    );
                }
 
                int total =
                        connection.getContentLength();
 
                long downloaded = 0;
 
                try (
                        InputStream input =
                                new BufferedInputStream(
                                        connection.getInputStream()
                                );
 
                        FileOutputStream output =
                                new FileOutputStream(apk)
                ) {
 
                    byte[] buffer =
                            new byte[8192];
 
                    int read;
 
                    while ((read =
                            input.read(buffer)) != -1) {
 
                        output.write(
                                buffer,
                                0,
                                read
                        );
 
                        downloaded += read;
 
                        if (total > 0) {
 
                            int progress =
                                    (int) (
                                            (downloaded * 100L)
                                                    / total
                                    );
 
                            mainHandler.post(
                                    () -> updateProgress(
                                            progress
                                    )
                            );
                        }
                    }
                }
 
                if (downloaded <= 0) {
                    throw new IllegalStateException(
                            "Downloaded APK is empty"
                    );
                }
 
                mainHandler.post(() -> {
 
                    hideProgress();
 
                    installApk(apk);
                });
 
            } catch (Exception e) {
 
                Log.e(
                        TAG,
                        "Direct APK download failed",
                        e
                );
 
                mainHandler.post(() -> {
 
                    hideProgress();
 
                    if (activity.isFinishing()
                            || activity.isDestroyed()) {
                        return;
                    }
 
                    new AlertDialog.Builder(activity)
                            .setTitle(
                                    "Update failed"
                            )
                            .setMessage(
                                    "DeenWallet could not download "
                                            + "the update. Please try "
                                            + "again later."
                            )
                            .setPositiveButton(
                                    "OK",
                                    null
                            )
                            .show();
                });
 
            } finally {
 
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }
 
    private void showProgress() {
 
        progressDialog =
                new ProgressDialog(activity);
 
        progressDialog.setTitle(
                "Updating DeenWallet"
        );
 
        progressDialog.setMessage(
                "Downloading update…"
        );
 
        progressDialog.setProgressStyle(
                ProgressDialog.STYLE_HORIZONTAL
        );
 
        progressDialog.setIndeterminate(true);
        progressDialog.setCancelable(false);
        progressDialog.show();
    }
 
    private void updateProgress(
            int progress
    ) {
 
        if (progressDialog == null) {
            return;
        }
 
        if (progressDialog.isIndeterminate()) {
            progressDialog.setIndeterminate(false);
        }
 
        progressDialog.setProgress(progress);
    }
 
    private void hideProgress() {
 
        if (progressDialog != null) {
 
            try {
                progressDialog.dismiss();
            } catch (Exception ignored) {
                // Activity may already be gone.
            }
 
            progressDialog = null;
        }
    }
 
    private void installApk(File apk) {
 
        if (activity.isFinishing()
                || activity.isDestroyed()) {
            return;
        }
 
        if (Build.VERSION.SDK_INT
                >= Build.VERSION_CODES.O
                && !activity
                .getPackageManager()
                .canRequestPackageInstalls()) {
 
            new AlertDialog.Builder(activity)
                    .setTitle(
                            "Allow DeenWallet updates"
                    )
                    .setMessage(
                            "Android needs permission to install "
                                    + "updates downloaded directly "
                                    + "from DeenWallet. Enable "
                                    + "'Allow from this source', "
                                    + "then return to DeenWallet "
                                    + "and tap Update again."
                    )
                    .setPositiveButton(
                            "Open settings",
                            (dialog, which) -> {
 
                                Intent intent =
                                        new Intent(
                                                Settings
                                                        .ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                                Uri.parse(
                                                        "package:"
                                                                + activity
                                                                .getPackageName()
                                                )
                                        );
 
                                activity.startActivity(
                                        intent
                                );
                            }
                    )
                    .setNegativeButton(
                            "Cancel",
                            null
                    )
                    .show();
 
            // Keep the downloaded file so "Update now" can be tapped again
            // after the permission is granted.
            return;
        }
 
        try {
 
            Uri uri =
                    FileProvider.getUriForFile(
                            activity,
                            activity.getPackageName()
                                    + ".fileprovider",
                            apk
                    );
 
            Intent intent =
                    new Intent(
                            Intent.ACTION_VIEW
                    );
 
            intent.setDataAndType(
                    uri,
                    "application/vnd.android.package-archive"
            );
 
            intent.addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_ACTIVITY_NEW_TASK
            );
 
            activity.startActivity(intent);
 
        } catch (Exception e) {
 
            Log.e(
                    TAG,
                    "Could not launch APK installer",
                    e
            );
 
            new AlertDialog.Builder(activity)
                    .setTitle(
                            "Update could not start"
                    )
                    .setMessage(
                            "Android could not open the "
                                    + "downloaded update."
                    )
                    .setPositiveButton(
                            "OK",
                            null
                    )
                    .show();
        }
    }
}
 


