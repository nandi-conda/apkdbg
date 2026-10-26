package dev.apkdbg;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Serves the ultra app in web/ (from assets) to a WebView, answers state.json
 * from the {@link Agent}, and hands the page's commands to it through the
 * window.Android bridge.
 */
public class MainActivity extends Activity implements Agent.Listener {
    static final String HOST = "appassets.androidplatform.net";
    static final String ORIGIN = "https://" + HOST + "/";

    private WebView web;
    private Agent agent;
    private boolean ready = false;
    private boolean pending = false;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        agent = Agent.get(this);
        web = new WebView(this);
        web.setBackgroundColor(Color.TRANSPARENT);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new Client());
        // Android 15 draws apps edge to edge; keep the page clear of the bars and the keyboard.
        if (Build.VERSION.SDK_INT >= 30) web.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(
                    android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.ime()
                            | android.view.WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return android.view.WindowInsets.CONSUMED;
        });
        setContentView(web);
        web.loadUrl(ORIGIN + "index.html");
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        agent.addListener(this);
        changed();
    }

    @Override
    protected void onStop() {
        agent.removeListener(this);
        super.onStop();
    }

    /** The transcript changed: tell the page, at most once per frame. */
    @Override
    public void changed() {
        runOnUiThread(() -> {
            if (!ready || pending) return;
            pending = true;
            web.postOnAnimation(() -> {
                pending = false;
                web.evaluateJavascript("window.apkdbg && window.apkdbg.changed()", null);
            });
        });
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true); // keep the agent and the chat around
    }

    final class Bridge {
        @JavascriptInterface public void sendPrompt(String s) { agent.prompt(s); }
        @JavascriptInterface public void saveKey(String k) { agent.saveKey(k); }
        @JavascriptInterface public void clearKey() { agent.clearKey(); }
        @JavascriptInterface public void stop() { agent.abort(); }
        @JavascriptInterface public void newChat() { agent.newChat(); }
        @JavascriptInterface public void openUrl(String u) {
            if (u == null || !u.startsWith("https://")) return;
            runOnUiThread(() -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u))));
        }
    }

    private final class Client extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            if (!HOST.equals(u.getHost())) return null; // the page itself loads nothing remote
            String path = u.getPath() == null ? "/" : u.getPath();
            try {
                if (path.equals("/state.json")) {
                    return respond("application/json",
                            new ByteArrayInputStream(agent.stateJson().getBytes(StandardCharsets.UTF_8)));
                }
                String name = path.equals("/") ? "index.html" : path.substring(1);
                return respond(mime(name), getAssets().open(name));
            } catch (IOException | RuntimeException e) {
                Log.w(Agent.TAG, "serving " + path, e);
                return new WebResourceResponse("text/plain", null, 404, "Not Found", new HashMap<>(),
                        new ByteArrayInputStream(new byte[0]));
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            if ("https".equals(u.getScheme()) && !HOST.equals(u.getHost())) {
                startActivity(new Intent(Intent.ACTION_VIEW, u));
            }
            return true;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            ready = true;
            changed();
        }
    }

    private static WebResourceResponse respond(String mime, InputStream body) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Cache-Control", "no-cache");
        return new WebResourceResponse(mime, mime.startsWith("text/") ? "utf-8" : null, 200, "OK", headers, body);
    }

    private static String mime(String name) {
        if (name.endsWith(".html")) return "text/html";
        if (name.endsWith(".js")) return "text/javascript";
        if (name.endsWith(".css")) return "text/css";
        if (name.endsWith(".wasm")) return "application/wasm";
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }
}
