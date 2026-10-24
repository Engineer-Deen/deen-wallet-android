package com.deenwallet.app;

import android.app.AlertDialog;
import android.app.ProgressDialog;
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

import com.google.android.play.core.appupdate.AppUpdateManager;
import com.google.android.play.core.appupdate.AppUpdateManagerFactory;
import com.google.android.play.core.appupdate.AppUpdateOptions;
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
 * 2. Direct APK installs -> DeenWallet's own update metadata + APK installer.
 *
 * Update checking:
 * - Both build types use the same production update server.
 * - The server is checked once per app session.
 * - Repeated onResume calls do not repeatedly hit the server.
 * - A fresh app launch checks the server again.
 *
 * Watch it work with:
 * adb logcat -s DeenWalletUpdate
 *
 * This class never changes the WebView URL or the web application version.
 */
public final class DeenWalletUpdateManager {

    // =====================================================================
    // Constants
    // =====================================================================

    private static final String TAG = "DeenWalletUpdate";

    private static final String UPDATE_URL =
            "https://api.deenwallapp.com/api/app/android-update";

    private static final String PLAY_STORE_PACKAGE = "com.android.vending";
    private static final int PLAY_UPDATE_REQUEST_CODE = 2407;

    private static final String DEFAULT_NOTES =
            "A newer version of DeenWallet is available with improvements and fixes.";

    // =====================================================================
    // State
    // =====================================================================

    private final MainActivity activity;
    private final boolean debugBuild;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // Google Play flow
    private AppUpdateManager playUpdateManager;

    // Direct APK flow
    private volatile boolean checking;
    private boolean updateDialogVisible;
    private JSONObject pendingUpdate;
    private ProgressDialog progressDialog;

    // Prevent repeated checks caused by multiple onResume callbacks
    // during the same app session.
    private boolean hasCheckedThisSession;

    public DeenWalletUpdateManager(MainActivity activity) {
        this.activity = activity;

        this.debugBuild =
                (activity.getApplicationInfo().flags
                        & ApplicationInfo.FLAG_DEBUGGABLE) != 0;

        Log.i(TAG, "Update manager created. debugBuild=" + debugBuild
                + ", endpoint=" + UPDATE_URL);
    }

    // =====================================================================
    // Public lifecycle API
    // =====================================================================

    public void checkForUpdates() {
        if (activityGone() || updateDialogVisible) return;

        if (checking) {
            Log.d(TAG, "Check skipped: another check is running");
            return;
        }

        if (hasCheckedThisSession) {
            Log.d(TAG, "Check skipped: already checked this app session");
            return;
        }

        hasCheckedThisSession = true;
        checking = true;

        if (isInstalledFromGooglePlay()) {
            Log.i(TAG, "Installed from Google Play -> Play in-app update");
            checkGooglePlayUpdate();
        } else {
            Log.i(TAG, "Direct install -> checking " + UPDATE_URL);
            checkDirectApkUpdate();
        }
    }

    public void onResume() {
        resumePlayUpdateIfInProgress();

        // A pending update stays pending until the user taps "Later"
        // or the app is updated. This also covers returning from the
        // Android "install unknown apps" settings screen.
        if (pendingUpdate != null
                && !updateDialogVisible
                && progressDialog == null) {

            mainHandler.post(this::showUpdateDialog);
        }
    }

    public void onDestroy() {
        hideProgress();
        executor.shutdownNow();
    }

    // =====================================================================
    // Google Play flow
    // =====================================================================

    private void checkGooglePlayUpdate() {
        playUpdateManager = AppUpdateManagerFactory.create(activity);

        playUpdateManager.getAppUpdateInfo()
                .addOnSuccessListener(info -> {
                    finishChecking();

                    if (info.updateAvailability()
                            != UpdateAvailability.UPDATE_AVAILABLE) {
                        return;
                    }

                    if (!info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
                        return;
                    }

                    startPlayImmediateUpdate(info);
                })
                .addOnFailureListener(error -> {
                    finishChecking();
                    Log.w(TAG, "Google Play update check failed", error);
                });
    }

    private void resumePlayUpdateIfInProgress() {
        if (playUpdateManager == null) return;

        playUpdateManager.getAppUpdateInfo().addOnSuccessListener(info -> {
            if (info.updateAvailability()
                    == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {

                startPlayImmediateUpdate(info);
            }
        });
    }

    private void startPlayImmediateUpdate(
            com.google.android.play.core.appupdate.AppUpdateInfo info) {

        try {
            playUpdateManager.startUpdateFlowForResult(
                    info,
                    activity,
                    AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build(),
                    PLAY_UPDATE_REQUEST_CODE
            );
        } catch (Exception e) {
            Log.e(TAG, "Failed to start Google Play update", e);
        }
    }

    // =====================================================================
    // Direct APK flow - 1. check the server
    // =====================================================================

    /**
     * Expected server response:
     *
     * {
     *   "versionCode": 4,
     *   "versionName": "1.3",
     *   "downloadUrl": "https://deenwallapp.com/downloads/DeenWallet-1.3.apk",
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
            JSONObject update = null;

            try {
                update = fetchAvailableUpdate();
            } catch (Exception e) {
                Log.w(
                        TAG,
                        "Direct APK update check failed (" + UPDATE_URL + ")",
                        e
                );
            }

            final JSONObject result = update;

            // ALWAYS release the "checking" flag.
            mainHandler.post(() -> {
                finishChecking();

                if (result != null) {
                    Log.i(TAG, "Showing update pop up");

                    pendingUpdate = result;

                    showUpdateDialog();
                }
            });
        });
    }

    /**
     * Returns the update JSON if a newer, safely downloadable version exists;
     * otherwise null.
     */
    private JSONObject fetchAvailableUpdate() throws Exception {
        HttpURLConnection connection = null;

        try {
            connection = openConnection(
                    UPDATE_URL,
                    8000,
                    10000,
                    "application/json"
            );

            int status = connection.getResponseCode();

            if (status < 200 || status >= 300) {
                Log.w(
                        TAG,
                        "Direct update endpoint returned HTTP " + status
                );
                return null;
            }

            JSONObject update =
                    new JSONObject(readFully(connection.getInputStream()));

            long latest =
                    update.optLong("versionCode", 0L);

            long installed =
                    installedVersionCode();

            Log.i(
                    TAG,
                    "Server versionCode=" + latest
                            + ", installed versionCode=" + installed
            );

            if (latest <= installed) {
                Log.i(
                        TAG,
                        "App is up to date - no pop up"
                );
                return null;
            }

            String downloadUrl =
                    update.optString("downloadUrl", "").trim();

            if (!isAllowedDownloadUrl(downloadUrl)) {
                Log.w(
                        TAG,
                        "Ignoring unsafe/empty direct update URL: '"
                                + downloadUrl
                                + "'"
                );
                return null;
            }

            return update;

        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private boolean isAllowedDownloadUrl(String url) {
        return url != null && url.startsWith("https://");
    }

    // =====================================================================
    // Direct APK flow - 2. update dialog
    // =====================================================================

    private void showUpdateDialog() {
        if (pendingUpdate == null
                || updateDialogVisible
                || activityGone()) {
            return;
        }

        updateDialogVisible = true;

        String versionName =
                pendingUpdate.optString(
                        "versionName",
                        "new version"
                );

        String notes =
                buildReleaseNotes(
                        pendingUpdate.optJSONArray("releaseNotes")
                );

        boolean force =
                pendingUpdate.optBoolean(
                        "forceUpdate",
                        false
                );

        AlertDialog.Builder builder =
                new AlertDialog.Builder(activity)
                        .setTitle("DeenWallet update available")
                        .setMessage(
                                "Version "
                                        + versionName
                                        + " is available.\n\n"
                                        + notes
                        )
                        .setPositiveButton(
                                "Update now",
                                (dialog, which) -> {
                                    updateDialogVisible = false;
                                    downloadAndInstall();
                                }
                        );

        if (!force) {
            builder.setNegativeButton(
                    "Later",
                    (dialog, which) -> {
                        updateDialogVisible = false;
                        pendingUpdate = null;
                    }
            );
        }

        AlertDialog dialog = builder.create();

        // Only the action buttons may dismiss it:
        // no outside tap and no Back button.
        dialog.setCanceledOnTouchOutside(false);
        dialog.setCancelable(false);
        dialog.show();
    }

    private String buildReleaseNotes(JSONArray notes) {
        if (notes == null || notes.length() == 0) {
            return DEFAULT_NOTES;
        }

        StringBuilder result = new StringBuilder();

        for (int i = 0; i < notes.length(); i++) {
            String note =
                    notes.optString(i, "").trim();

            if (!note.isEmpty()) {
                result.append("• ")
                        .append(note)
                        .append('\n');
            }
        }

        return result.length() == 0
                ? DEFAULT_NOTES
                : result.toString().trim();
    }

    // =====================================================================
    // Direct APK flow - 3. download
    // =====================================================================

    private void downloadAndInstall() {
        if (pendingUpdate == null) return;

        String downloadUrl =
                pendingUpdate
                        .optString("downloadUrl", "")
                        .trim();

        if (!isAllowedDownloadUrl(downloadUrl)) {
            Log.w(
                    TAG,
                    "Blocked unsafe APK download URL: '"
                            + downloadUrl
                            + "'"
            );

            pendingUpdate = null;
            return;
        }

        if (executor.isShutdown()) return;

        showProgress();

        executor.execute(() -> {
            File apk =
                    new File(
                            activity.getCacheDir(),
                            "DeenWallet-update.apk"
                    );

            try {
                downloadApk(downloadUrl, apk);

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

                    if (!activityGone()) {
                        showMessage(
                                "Update failed",
                                "DeenWallet could not download the update. "
                                        + "Please try again later."
                        );
                    }
                });
            }
        });
    }

    private void downloadApk(
            String downloadUrl,
            File target) throws Exception {

        if (target.exists() && !target.delete()) {
            throw new IllegalStateException(
                    "Could not replace old update file"
            );
        }

        HttpURLConnection connection = null;

        try {
            connection = openConnection(
                    downloadUrl,
                    10000,
                    20000,
                    "application/vnd.android.package-archive"
            );

            int status = connection.getResponseCode();

            if (status < 200 || status >= 300) {
                throw new IllegalStateException(
                        "Download failed with HTTP " + status
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
                            new FileOutputStream(target)
            ) {
                byte[] buffer = new byte[8192];
                int read;

                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    downloaded += read;

                    if (total > 0) {
                        int progress =
                                (int) (
                                        (downloaded * 100L)
                                                / total
                                );

                        mainHandler.post(
                                () -> updateProgress(progress)
                        );
                    }
                }
            }

            if (downloaded <= 0) {
                throw new IllegalStateException(
                        "Downloaded APK is empty"
                );
            }

        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    // =====================================================================
    // Direct APK flow - 4. progress dialog
    // =====================================================================

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

    private void updateProgress(int progress) {
        if (progressDialog == null) return;

        if (progressDialog.isIndeterminate()) {
            progressDialog.setIndeterminate(false);
        }

        progressDialog.setProgress(progress);
    }

    private void hideProgress() {
        if (progressDialog == null) return;

        try {
            progressDialog.dismiss();
        } catch (Exception ignored) {
            // Activity may already be gone.
        }

        progressDialog = null;
    }

    // =====================================================================
    // Direct APK flow - 5. install
    // =====================================================================

    private void installApk(File apk) {
        if (activityGone()) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager()
                .canRequestPackageInstalls()) {

            askForInstallPermission();
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
                    new Intent(Intent.ACTION_VIEW);

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

            showMessage(
                    "Update could not start",
                    "Android could not open the downloaded update."
            );
        }
    }

    private void askForInstallPermission() {
        new AlertDialog.Builder(activity)
                .setTitle("Allow DeenWallet updates")
                .setMessage(
                        "Android needs permission to install updates downloaded "
                                + "directly from DeenWallet. Enable 'Allow from this source', "
                                + "then return to DeenWallet and tap Update again."
                )
                .setPositiveButton(
                        "Open settings",
                        (dialog, which) ->
                                activity.startActivity(
                                        new Intent(
                                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                                Uri.parse(
                                                        "package:"
                                                                + activity.getPackageName()
                                                )
                                        )
                                )
                )
                .setNegativeButton(
                        "Cancel",
                        null
                )
                .show();
    }

    // =====================================================================
    // Shared helpers
    // =====================================================================

    private boolean activityGone() {
        return activity.isFinishing()
                || activity.isDestroyed();
    }

    private void finishChecking() {
        checking = false;
    }

    private void showMessage(
            String title,
            String message) {

        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    private HttpURLConnection openConnection(
            String url,
            int connectTimeoutMs,
            int readTimeoutMs,
            String accept) throws Exception {

        HttpURLConnection connection =
                (HttpURLConnection)
                        new URL(url).openConnection();

        connection.setConnectTimeout(
                connectTimeoutMs
        );

        connection.setReadTimeout(
                readTimeoutMs
        );

        connection.setRequestMethod("GET");

        connection.setRequestProperty(
                "Accept",
                accept
        );

        connection.setUseCaches(false);

        return connection;
    }

    private String readFully(
            InputStream stream) throws Exception {

        ByteArrayOutputStream body =
                new ByteArrayOutputStream();

        try (
                InputStream input =
                        new BufferedInputStream(stream)
        ) {
            byte[] buffer = new byte[8192];
            int read;

            while ((read = input.read(buffer)) != -1) {
                body.write(buffer, 0, read);
            }
        }

        return body.toString(
                StandardCharsets.UTF_8.name()
        );
    }

    @SuppressWarnings("deprecation")
    private long installedVersionCode()
            throws Exception {

        PackageInfo info =
                activity.getPackageManager()
                        .getPackageInfo(
                                activity.getPackageName(),
                                0
                        );

        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? info.getLongVersionCode()
                : info.versionCode;
    }

    @SuppressWarnings("deprecation")
    private boolean isInstalledFromGooglePlay() {
        try {
            String installer;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                installer =
                        activity.getPackageManager()
                                .getInstallSourceInfo(
                                        activity.getPackageName()
                                )
                                .getInstallingPackageName();
            } else {
                installer =
                        activity.getPackageManager()
                                .getInstallerPackageName(
                                        activity.getPackageName()
                                );
            }

            return PLAY_STORE_PACKAGE.equals(installer);

        } catch (Exception e) {
            Log.w(
                    TAG,
                    "Could not determine installation source",
                    e
            );

            return false;
        }
    }
}