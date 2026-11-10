package com.cinedor.app;

import android.annotation.SuppressLint;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/** WebView-based Player for third-party embed links (vidsrc.cc, autoembed, etc.) */
public class PlayerActivity extends AppCompatActivity {

    public static final String EXTRA_URL = "video_url";
    public static final String EXTRA_TYPE = "video_type";
    public static final String EXTRA_TITLE = "video_title";
    public static final String EXTRA_ITEM = "item";

    private WebView webView;
    private ProgressBar progressBar;
    private TextView tvTitle;
    private String url;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // ফুল স্ক্রিন এবং ল্যান্ডস্কেপ মোড ফিক্স করা
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);

        setContentView(R.layout.activity_player);

        webView = findViewById(R.id.webView); // লেআউটে webView থাকতে হবে
        progressBar = findViewById(R.id.progressBar); // যদি প্রোগ্রেসবার থাকে
        tvTitle = findViewById(R.id.tvPlayerTitle);

        url = getIntent().getStringExtra(EXTRA_URL);
        String title = getIntent().getStringExtra(EXTRA_TITLE);

        if (url == null || url.isEmpty()) {
            finish();
            return;
        }

        if (tvTitle != null && title != null) {
            tvTitle.setText(title);
        }

        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setLoadsImagesAutomatically(true);
        webSettings.setMediaPlaybackRequiresUserGesture(false);
        webSettings.setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String loadUrl) {
                super.onPageFinished(view, loadUrl);
                if (progressBar != null) {
                    progressBar.setVisibility(View.GONE);
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient());

        // ফাইনাল থার্ড-পার্টি বা সার্ভার লিংক লোড করা
        webView.loadUrl(url);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.loadUrl("about:blank");
            webView.destroy();
        }
        super.onDestroy();
    }
}
