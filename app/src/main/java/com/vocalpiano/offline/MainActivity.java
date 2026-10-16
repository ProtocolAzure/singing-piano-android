package com.vocalpiano.offline;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.webkit.JavascriptInterface;
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
import java.util.HashMap;

public class MainActivity extends Activity {
    private static final String TAG = "PianoMic";
    private static final String ORIGIN = "https://appassets.androidplatform.net";
    private static final int MIC_REQUEST = 7001;
    private static final int SAMPLE_RATE = 22050;
    private WebView web;
    private TextView diagnostic;
    private volatile boolean recording = false;
    private volatile AudioRecord recorder;
    private Thread microphoneThread;
    private volatile boolean pendingStart = false;

    private void status(String message) {
        Log.i(TAG, message);
        runOnUiThread(() -> { if (diagnostic != null) diagnostic.setText("麦克风诊断：" + message); });
    }
    private void sendJs(String code) {
        runOnUiThread(() -> { if (web != null) web.evaluateJavascript(code, null); });
    }
    private void micError(String message) {
        status(message);
        sendJs("window.nativeMicError && window.nativeMicError(" + org.json.JSONObject.quote(message) + ")");
    }

    // This bridge is exposed only to the bundled, restricted local app page.
    public class MicBridge {
        @JavascriptInterface public void start() { runOnUiThread(() -> startMic()); }
        @JavascriptInterface public void stop() { runOnUiThread(() -> stopMic()); }
    }

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        FrameLayout layout = new FrameLayout(this);
        web = new WebView(this);
        web.setBackgroundColor(0xff101827);
        layout.addView(web, new FrameLayout.LayoutParams(-1,-1));
        diagnostic = new TextView(this);
        diagnostic.setTextColor(0xffffffff);
        diagnostic.setBackgroundColor(0xe5223248);
        diagnostic.setTextSize(12);
        diagnostic.setPadding(12,7,12,7);
        layout.addView(diagnostic, new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));
        diagnostic.setOnClickListener(v -> diagnostic.setVisibility(View.GONE));
        setContentView(layout);
        status("原生测音准备就绪（点击横幅可隐藏）");
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        web.addJavascriptInterface(new MicBridge(), "AndroidMic");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !((ORIGIN + "/index.html").equals(request.getUrl().toString()));
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                android.net.Uri uri = request.getUrl();
                if (!"https".equals(uri.getScheme()) || !"appassets.androidplatform.net".equals(uri.getHost())) return null;
                if (!"/index.html".equals(uri.getPath())) return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", new HashMap<>(), new java.io.ByteArrayInputStream(new byte[0]));
                try { InputStream in = getAssets().open("index.html"); return new WebResourceResponse("text/html", "UTF-8", in); }
                catch (IOException e) { status("网页读取失败："+e.getMessage()); return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", new HashMap<>(), new java.io.ByteArrayInputStream(new byte[0])); }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage msg) {
                Log.d(TAG,"JS "+msg.messageLevel()+" "+msg.message());
                if (msg.messageLevel() == ConsoleMessage.MessageLevel.ERROR) status("网页脚本错误：" + msg.message());
                return true;
            }
            @Override public void onPermissionRequest(PermissionRequest request) {
                // In this build microphone access is native only; do not grant WebView recording.
                request.deny();
            }
        });
        web.loadUrl(ORIGIN + "/index.html");
    }

    private void startMic() {
        if (recording) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingStart = true;
            status("正在申请安卓麦克风权限");
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_REQUEST);
            return;
        }
        pendingStart = false;
        int bufferMin = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (bufferMin <= 0) { micError("设备不支持当前录音格式：" + bufferMin); return; }
        try {
            recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(bufferMin, 8192));
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                recorder.release(); recorder = null;
                micError("安卓原生录音器初始化失败"); return;
            }
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                recorder.release(); recorder = null;
                micError("安卓原生录音无法启动"); return;
            }
            recording = true;
            status("原生麦克风已启动，等待声音");
            sendJs("window.nativeMicStarted && window.nativeMicStarted()");
            microphoneThread = new Thread(() -> captureLoop(), "PianoNativeMic");
            microphoneThread.start();
        } catch (SecurityException e) { micError("麦克风权限错误："+e.getMessage()); }
          catch (Exception e) { micError("原生录音失败："+e.getClass().getSimpleName()+" "+e.getMessage()); }
    }

    private void captureLoop() {
        final short[] pcm = new short[4096];
        final float[] samples = new float[4096];
        final AudioRecord localRecorder = recorder;
        try {
            while (recording && localRecorder != null) {
                int n = localRecorder.read(pcm, 0, pcm.length);
                if (n <= 0) { micError("麦克风读取错误："+n); break; }
                double rms = 0;
                for (int i=0; i<n; i++) { samples[i]=pcm[i]/32768f; rms+=samples[i]*samples[i]; }
                rms = Math.sqrt(rms/n);
                double hz = rms < 0.012 ? 0 : detectPitch(samples, n);
                final double f = hz, level = rms;
                sendJs("window.nativeMicData && window.nativeMicData("+f+","+level+")");
            }
        } catch (Exception e) { if (recording) micError("麦克风读取异常："+e.getMessage()); }
        finally { if (recording) runOnUiThread(() -> stopMic()); }
    }

    // Normalized autocorrelation with parabolic lag interpolation, suitable for monophonic voice.
    private static double detectPitch(float[] s, int n) {
        int minLag = SAMPLE_RATE / 1400;
        int maxLag = Math.min(SAMPLE_RATE / 65, n / 2);
        double best = 0; int bestLag = -1;
        double[] corr = new double[maxLag+2];
        for (int lag=minLag; lag<=maxLag; lag++) {
            double dot=0, x=0, y=0;
            for(int i=0; i<n-lag; i+=2) {
                double a=s[i], b=s[i+lag];
                dot+=a*b; x+=a*a; y+=b*b;
            }
            double c=dot/(Math.sqrt(x*y)+1e-12); corr[lag]=c;
        }
        // Prefer the first strong local peak, reducing octave-down mistakes.
        for(int lag=minLag+1;lag<maxLag;lag++) {
            if(corr[lag]>0.84 && corr[lag]>=corr[lag-1] && corr[lag]>=corr[lag+1]) { bestLag=lag; break; }
            if(corr[lag]>best) { best=corr[lag]; bestLag=lag; }
        }
        if (bestLag < 1 || corr[bestLag]<0.72) return 0;
        double lag = bestLag;
        if(bestLag>minLag && bestLag<maxLag) {
            double a=corr[bestLag-1], b=corr[bestLag], c=corr[bestLag+1];
            double denom=a-2*b+c;
            if(Math.abs(denom)>1e-9) lag += Math.max(-0.5, Math.min(0.5, 0.5*(a-c)/denom));
        }
        double hz = SAMPLE_RATE/lag;
        return hz>=65 && hz<=1400 ? hz : 0;
    }

    private void stopMic() {
        recording = false; pendingStart = false;
        AudioRecord r = recorder; recorder=null;
        if(r != null) { try { r.stop(); } catch(Exception ignored) {} try { r.release(); } catch(Exception ignored) {} }
        status("原生麦克风已停止");
        sendJs("window.nativeMicStopped && window.nativeMicStopped()");
    }
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(requestCode,permissions,results);
        if(requestCode==MIC_REQUEST && pendingStart) {
            pendingStart=false;
            if(results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED) startMic();
            else micError("安卓系统拒绝录音权限，请在应用信息中允许麦克风");
        }
    }
    @Override protected void onPause() { super.onPause(); if (recording) stopMic(); }
    @Override protected void onDestroy() { stopMic(); web.removeJavascriptInterface("AndroidMic"); web.destroy(); super.onDestroy(); }
}
