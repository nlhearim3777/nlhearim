package com.nlhearim.sangdam;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.Toast;

import java.net.URISyntaxException;

public class MainActivity extends Activity {

    private static final int REQ_FILE_CHOOSER = 1001;
    private static final int REQ_STORAGE_PERMISSION = 1002;

    private WebView webView;
    private ProgressBar progressBar;
    private View offlineView;

    private ValueCallback<Uri[]> filePathCallback;
    private boolean mainFrameError = false;
    private long lastBackPressedAt = 0L;

    // Android 9 이하에서 저장소 권한을 받은 뒤 이어서 진행할 다운로드
    private String pendingUrl, pendingUserAgent, pendingDisposition, pendingMime;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        progressBar = findViewById(R.id.progress);
        offlineView = findViewById(R.id.offline);
        findViewById(R.id.retry).setOnClickListener(v -> {
            offlineView.setVisibility(View.GONE);
            if (webView.getUrl() == null) {
                webView.loadUrl(getString(R.string.start_url));
            } else {
                webView.reload();
            }
        });

        applySystemBarInsets();

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSupportZoom(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);

        // 로그인 상태 유지를 위한 쿠키 설정
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new AppWebViewClient());
        webView.setWebChromeClient(new AppChromeClient());
        webView.setDownloadListener(this::startDownload);

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        }
        if (webView.getUrl() == null) {
            webView.loadUrl(getString(R.string.start_url));
        }
    }

    /**
     * Android 15(API 35) 이상은 앱 화면이 상태바/내비게이션바 아래까지 그려지므로
     * 웹 화면이 가려지지 않게 여백을 줍니다. 키보드가 올라오면 그만큼 하단 여백을 늘립니다.
     */
    private void applySystemBarInsets() {
        if (Build.VERSION.SDK_INT < 35) return;
        View root = findViewById(R.id.root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return WindowInsets.CONSUMED;
        });
    }

    private class AppWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleUrl(request.getUrl());
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            mainFrameError = false;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (!mainFrameError) offlineView.setVisibility(View.GONE);
            CookieManager.getInstance().flush();
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) {
                mainFrameError = true;
                offlineView.setVisibility(View.VISIBLE);
            }
        }
    }

    /** http/https 는 앱 안에서 열고, 전화·문자·메일·카카오톡 등은 해당 앱으로 넘깁니다. */
    private boolean handleUrl(Uri uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (scheme.equals("http") || scheme.equals("https")) {
            return false;
        }
        try {
            if (scheme.equals("intent")) {
                Intent intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME);
                intent.addCategory(Intent.CATEGORY_BROWSABLE);
                intent.setComponent(null);
                intent.setSelector(null);
                try {
                    startActivity(intent);
                } catch (ActivityNotFoundException e) {
                    String fallback = intent.getStringExtra("browser_fallback_url");
                    if (fallback != null) {
                        webView.loadUrl(fallback);
                    } else if (intent.getPackage() != null) {
                        startActivity(new Intent(Intent.ACTION_VIEW,
                                Uri.parse("market://details?id=" + intent.getPackage())));
                    }
                }
            } else {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            }
        } catch (ActivityNotFoundException | URISyntaxException e) {
            Toast.makeText(this, R.string.no_app_to_open, Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    private class AppChromeClient extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            progressBar.setProgress(newProgress);
            progressBar.setVisibility(newProgress < 100 ? View.VISIBLE : View.GONE);
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(null);
            }
            filePathCallback = callback;
            try {
                startActivityForResult(params.createIntent(), REQ_FILE_CHOOSER);
            } catch (ActivityNotFoundException e) {
                filePathCallback = null;
                return false;
            }
            return true;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE_CHOOSER) {
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(
                        WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                filePathCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void startDownload(String url, String userAgent, String contentDisposition,
                               String mimeType, long contentLength) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            // blob:, data: 형식 다운로드는 DownloadManager 로 처리할 수 없습니다.
            Toast.makeText(this, R.string.no_app_to_open, Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            pendingUrl = url;
            pendingUserAgent = userAgent;
            pendingDisposition = contentDisposition;
            pendingMime = mimeType;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQ_STORAGE_PERMISSION);
            return;
        }
        String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
        req.setMimeType(mimeType);
        String cookie = CookieManager.getInstance().getCookie(url);
        if (cookie != null) req.addRequestHeader("Cookie", cookie);
        req.addRequestHeader("User-Agent", userAgent);
        req.setTitle(fileName);
        req.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        dm.enqueue(req);
        Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        if (requestCode == REQ_STORAGE_PERMISSION && pendingUrl != null) {
            String url = pendingUrl;
            pendingUrl = null;
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startDownload(url, pendingUserAgent, pendingDisposition, pendingMime, -1);
            }
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (offlineView.getVisibility() != View.VISIBLE && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBackPressedAt < 2000) {
            super.onBackPressed();
        } else {
            lastBackPressedAt = now;
            Toast.makeText(this, R.string.press_back_again, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
    }

    @Override
    protected void onPause() {
        CookieManager.getInstance().flush();
        webView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
