package com.deenwallet.app;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;

import androidx.activity.OnBackPressedCallback;

import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebViewClient;

public class MainActivity extends BridgeActivity {

    private static final String OFFLINE_PAGE =
            "file:///android_asset/public/offline.html";

    private OnBackPressedCallback offlineBackCallback;
    private DeenWalletUpdateManager updateManager;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(DeenWalletBiometricPlugin.class);
        super.onCreate(savedInstanceState);

        updateManager = new DeenWalletUpdateManager(this);

        Bridge bridge = getBridge();
        WebView webView = bridge.getWebView();

        webView.setBackgroundColor(Color.parseColor("#0D0D0D"));

        offlineBackCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                moveTaskToBack(true);
            }
        };

        getOnBackPressedDispatcher().addCallback(
                this,
                offlineBackCallback
        );

        bridge.setWebViewClient(new BridgeWebViewClient(bridge) {

            @Override
            public void onPageStarted(
                    WebView view,
                    String url,
                    Bitmap favicon
            ) {
                super.onPageStarted(view, url, favicon);

                offlineBackCallback.setEnabled(
                        isOfflinePage(url)
                );
            }

            @Override
            public void onReceivedError(
                    WebView view,
                    WebResourceRequest request,
                    WebResourceError error
            ) {
                super.onReceivedError(view, request, error);

                if (request.isForMainFrame()) {
                    showOfflinePage(
                            view,
                            request.getUrl().toString(),
                            false
                    );
                }
            }

            @Override
            public void onReceivedHttpError(
                    WebView view,
                    WebResourceRequest request,
                    WebResourceResponse errorResponse
            ) {
                super.onReceivedHttpError(
                        view,
                        request,
                        errorResponse
                );

                if (request.isForMainFrame()
                        && errorResponse.getStatusCode() >= 500) {

                    showOfflinePage(
                            view,
                            request.getUrl().toString(),
                            true
                    );
                }
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();

        if (updateManager != null) {
            updateManager.onResume();
            updateManager.checkForUpdates();
        }
    }

    @Override
    public void onDestroy() {
        if (updateManager != null) {
            updateManager.onDestroy();
        }

        super.onDestroy();
    }

    private boolean isOfflinePage(String url) {
        return url != null
                && url.startsWith(OFFLINE_PAGE);
    }

    private void showOfflinePage(
            WebView view,
            String failedUrl,
            boolean serverProblem
    ) {
        if (failedUrl == null
                || isOfflinePage(failedUrl)) {
            return;
        }

        String scheme =
                Uri.parse(failedUrl).getScheme();

        if (!"http".equals(scheme)
                && !"https".equals(scheme)) {
            return;
        }

        String target =
                OFFLINE_PAGE
                        + "?retry="
                        + Uri.encode(failedUrl)
                        + (serverProblem
                        ? "&reason=server"
                        : "");

        view.loadUrl(target);
    }
}