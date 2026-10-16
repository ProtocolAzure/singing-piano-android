package com.vocalpiano.offline;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.webkit.ConsoleMessage;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import java.io.InputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;

public class MainActivity extends Activity {
    private static final String TAG = "PianoMic";
    private static final String ORIGIN = "https://appassets.androidplatform.net";
    private static final int MIC_REQUEST = 7001;
    private WebView web;
    private TextView diagnostic;
    private PermissionRequest pendingRequest;

    private void status(String message) {
        Log.i(TAG, message);
        runOnUiThread(() -> { if (diagnostic != null) diagnostic.setText("麦克风诊断：" + message); });
    }

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        FrameLayout layout = new FrameLayout(this);
        web = new WebView(this);
        web.setBackgroundColor(0xff101827);
        layout.addView(web, new FrameLayout.LayoutParams(-1, -1));
        diagnostic = new TextView(this);
        diagnostic.setTextColor(0xffffffff);
        diagnostic.setBackgroundColor(0xe5223248);
        diagnostic.setTextSize(12);
        diagnostic.setPadding(12, 7, 12, 7);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        layout.addView(diagnostic, params);
        diagnostic.setOnClickListener(v -> diagnostic.setVisibility(View.GONE));
        setContentView(layout);
        status("等待网页请求（点此横幅可隐藏）");

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                android.net.Uri uri = request.getUrl();
                if (!"https".equals(uri.getScheme()) || !"appassets.androidplatform.net".equals(uri.getHost())) return null;
                if (!"/index.html".equals(uri.getPath())) return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", new HashMap<>(), new java.io.ByteArrayInputStream(new byte[0]));
                try {
                    InputStream in = getAssets().open("index.html");
                    return new WebResourceResponse("text/html", "UTF-8", in);
                } catch (IOException e) {
                    status("网页文件丢失：" + e.getMessage());
                    return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", new HashMap<>(), new java.io.ByteArrayInputStream(new byte[0]));
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> {
                    String origin = request.getOrigin() == null ? "(null)" : request.getOrigin().toString().replaceAll("/$", "");
                    Log.i(TAG, "Origin=" + origin + " resources=" + Arrays.toString(request.getResources()));
                    status("收到网页麦克风请求：" + origin);
                    if (!ORIGIN.equals(origin)) { status("拒绝：网页来源不匹配 " + origin); request.deny(); return; }
                    boolean audioRequested = false;
                    for (String resource : request.getResources()) {
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) audioRequested = true;
                        else { status("拒绝：请求了其他权限 " + resource); request.deny(); return; }
                    }
                    if (!audioRequested) { status("拒绝：未请求音频采集"); request.deny(); return; }
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        status("安卓权限已允许，向网页授予麦克风");
                        request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                    } else {
                        if (pendingRequest != null) pendingRequest.deny();
                        pendingRequest = request;
                        status("正在请求安卓系统麦克风权限");
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_REQUEST);
                    }
  });
            }
            @Override public void onPermissionRequestCanceled(PermissionRequest request) {
                runOnUiThread(() -> {
                    if (pendingRequest == request) pendingRequest = null;
                    status("网页取消麦克风请求");
                });
            }
            @Override public boolean onConsoleMessage(ConsoleMessage message) {
                Log.d(TAG, "JS " + message.messageLevel() + " " + message.message() + " @" + message.lineNumber());
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    status("网页脚本错误：" + message.message());
                }
                return true;
            }
        });
        web.loadUrl(ORIGIN + "/index.html");
    }

    @Override public void onRequestPermissionsResult(int req, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(req, permissions, results);
        if (req == MIC_REQUEST && pendingRequest != null) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                status("系统已授权，向网页授予麦克风");
                pendingRequest.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            } else {
                status("安卓系统拒绝麦克风权限");
                pendingRequest.deny();
            }
            pendingRequest = null;
        }
    }
    @Override protected void onDestroy() {
        if (pendingRequest != null) { pendingRequest.deny(); pendingRequest = null; }
        web.destroy();
        super.onDestroy();
    }
}

                              
