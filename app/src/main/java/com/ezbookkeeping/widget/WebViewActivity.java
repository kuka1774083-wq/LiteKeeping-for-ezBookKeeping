package com.ezbookkeeping.widget;

import android.app.Activity;
import android.annotation.SuppressLint;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.os.Handler;

import org.json.JSONObject;

import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

public class WebViewActivity extends Activity {
    private static WebView activeWebView;
    static void clearWebSession() {
        CookieManager cookies = CookieManager.getInstance();
        cookies.removeAllCookies(null);
        cookies.flush();
        WebStorage.getInstance().deleteAllData();
        if (activeWebView != null) {
            activeWebView.clearCache(true);
            activeWebView.clearHistory();
            activeWebView.clearFormData();
            activeWebView.evaluateJavascript("try{localStorage.clear();sessionStorage.clear();}catch(e){}", null);
        }
    }
    public static final String EXTRA_WEB_PATH = "web_path";
    private WebView webView;
    private ProgressBar progress;
    private TextView errorView;
    private OnBackInvokedCallback backCallback;
    private boolean autoLoginNavigationDone;
    private String initialPath = "/";
    private final Handler touchSyncHandler = new Handler();
    private final Runnable touchSync = () -> BookkeepingWidgetProvider.syncNow(this);

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("ezBookkeeping 网页");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        WindowInsetsHelper.apply(root, 0, 0, 0, 0);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        Button back = new Button(this);
        back.setText(R.string.web_back);
        back.setOnClickListener(view -> finish());
        toolbar.addView(back, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        TextView title = new TextView(this);
        title.setText(R.string.web_title);
        title.setTextSize(18);
        title.setTextColor(Color.rgb(32, 33, 36));
        toolbar.addView(title, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        ));
        Button refresh = new Button(this);
        refresh.setText(R.string.web_refresh);
        refresh.setOnClickListener(view -> webView.reload());
        toolbar.addView(refresh, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        root.addView(toolbar);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        root.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                3
        ));

        errorView = new TextView(this);
        errorView.setGravity(Gravity.CENTER);
        errorView.setTextColor(Color.DKGRAY);
        errorView.setTextSize(16);
        errorView.setPadding(32, 32, 32, 32);
        errorView.setVisibility(View.GONE);
        root.addView(errorView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        webView = new WebView(this);
        activeWebView = webView;
        root.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));
        setContentView(root);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        // The server selects its mobile UI from the browser user agent.
        String defaultAgent = settings.getUserAgentString();
        if (!defaultAgent.contains(" Mobile")) {
            settings.setUserAgentString(defaultAgent + " Mobile");
        }
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }
        });
        webView.setOnTouchListener((v, event) -> {
            touchSyncHandler.removeCallbacks(touchSync);
            touchSyncHandler.postDelayed(touchSync, 200L);
            return false;
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                injectStoredToken(view);
                if (!autoLoginNavigationDone && "/".equals(initialPath) && isServerUrl(url)) {
                    autoLoginNavigationDone = true;
                    // The Vue application reads this key before issuing its
                    // first API request.  Setting it and navigating to the
                    // desktop route makes the embedded page use the same
                    // encrypted-token-backed login as the native UI.
                    view.evaluateJavascript("localStorage.setItem('ebk_user_token',"
                            + JSONObject.quote(SecureSettings.token(WebViewActivity.this))
                            + ");location.replace(" + JSONObject.quote(
                            SecureSettings.origin(WebViewActivity.this) + "/") + ");", null);
                }
                errorView.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                injectStoredToken(view);
                super.onPageStarted(view, url, favicon);
            }

            @Override
            public void onReceivedError(
                    WebView view,
                    WebResourceRequest request,
                    WebResourceError error
            ) {
                if (request.isForMainFrame()) {
                    showError("网页加载失败，请检查网络后点击右上角刷新。\n" + error.getDescription());
                }
            }
        });

        String origin = SecureSettings.origin(this);
        if (origin.isEmpty()) {
            showError("请先在主页面登录服务器。\n返回后点击“登录服务器”即可。\n\n此页面会在应用内打开服务器网页。\n");
        } else {
            autoLoginNavigationDone = false;
            String path = getIntent().getStringExtra(EXTRA_WEB_PATH);
            if (path == null || path.isEmpty()) {
                path = "/";
            }
            initialPath = path;
            webView.loadUrl(origin + path);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backCallback = this::navigateBack;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    backCallback
            );
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_DOWN
                && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return navigateBack();
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        BookkeepingWidgetProvider.syncNow(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        }
        if (webView != null) {
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
            if (activeWebView == webView) activeWebView = null;
        }
        super.onDestroy();
    }

    private boolean navigateBack() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        finish();
        return true;
    }

    private void showError(String message) {
        webView.setVisibility(View.GONE);
        errorView.setText(message);
        errorView.setVisibility(View.VISIBLE);
    }

    private void injectStoredToken(WebView view) {
        String token = SecureSettings.token(this);
        if (token == null || token.isEmpty()) {
            return;
        }
        view.evaluateJavascript("try{localStorage.setItem('ebk_user_token',"
                + JSONObject.quote(token) + ");}catch(e){}", null);
    }

    private boolean isServerUrl(String url) {
        String origin = SecureSettings.origin(this);
        return origin != null && !origin.isEmpty() && url != null && url.startsWith(origin);
    }

}
