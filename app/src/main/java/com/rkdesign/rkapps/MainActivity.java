package com.rkdesign.rkapps;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

public class MainActivity extends Activity {
    // Ganti alamat ini kalau domain webmu berubah (lalu build ulang APK).
    private static final String HOME = "https://rkapps.netlify.app/";
    private static final String HOST = "rkapps.netlify.app";

    // Anti-macet: dijalankan di setiap halaman. Menekan lama logo/tautan di WebView bisa memulai
    // "seret gambar" (drag-and-drop) yang membuat layar tidak bisa disentuh. Skrip ini membatalkannya,
    // jadi aplikasi tetap aman walaupun versi web di Netlify belum diperbarui.
    private static final String NO_DRAG_JS =
            "(function(){if(window.__rkNoDrag)return;window.__rkNoDrag=1;"
            + "try{var s=document.createElement('style');"
            + "s.textContent='img,a{-webkit-user-drag:none!important;-webkit-touch-callout:none!important}';"
            + "(document.head||document.documentElement).appendChild(s);}catch(e){}"
            + "document.addEventListener('dragstart',function(e){e.preventDefault()},true);"
            + "document.addEventListener('contextmenu',function(e){var t=e.target;"
            + "if(t&&t.closest&&t.closest('input,textarea,[contenteditable]'))return;e.preventDefault()},true);"
            + "})();";

    private WebView web;
    private String[] pending;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        web.setBackgroundColor(0xFFFFFFFF);
        setContentView(web, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Matikan tekan-lama bawaan WebView (pilih teks / menu konteks / seret gambar) agar layar tidak macet.
        web.setOnLongClickListener(v -> true);
        web.setLongClickable(false);
        web.setHapticFeedbackEnabled(false);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setAllowFileAccess(false);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setUserAgentString(s.getUserAgentString() + " RKAppsApp/1.0");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                Uri u = request.getUrl();
                String sc = u.getScheme();
                if (("https".equals(sc) || "http".equals(sc)) && HOST.equals(u.getHost())) return false;
                openExternal(u);
                return true;
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                view.evaluateJavascript(NO_DRAG_JS, null);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript(NO_DRAG_JS, null);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) view.loadUrl("file:///android_asset/offline.html");
            }
        });

        web.setDownloadListener((url, ua, cd, mime, len) -> download(url, ua, cd, mime, null));

        // Dipanggil dari web (RKApp.downloadFile) agar unduhan langsung masuk DownloadManager tanpa membuka browser.
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void downloadFile(String url, String name) {
                runOnUiThread(() -> {
                    Uri u = Uri.parse(url);
                    if ("https".equals(u.getScheme()) && downloadHostAllowed(u.getHost())) {
                        String fn = (name == null || name.trim().isEmpty()) ? null : name.replaceAll("[^A-Za-z0-9._ -]", "").trim() + ".apk";
                        MainActivity.this.download(url, web.getSettings().getUserAgentString(), null, "application/vnd.android.package-archive", fn);
                    } else {
                        openExternal(u);
                    }
                });
            }
        }, "RKApp");

        if (state != null) web.restoreState(state); else web.loadUrl(HOME);
    }

    // Hanya sumber unduhan yang dikenal yang boleh memicu unduhan lewat jembatan JavaScript.
    private static boolean downloadHostAllowed(String h) {
        if (h == null) return false;
        for (String d : new String[]{"aptoide.com", "apkpure.com", "apkpure.net", "f-droid.org", "uptodown.com", "utdstc.com"}) {
            if (h.equals(d) || h.endsWith("." + d)) return true;
        }
        return false;
    }

    private void openExternal(Uri u) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Tidak ada aplikasi untuk membuka tautan ini", Toast.LENGTH_SHORT).show();
        }
    }

    private void download(String url, String ua, String cd, String mime, String forcedName) {
        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pending = new String[]{url, ua, cd, mime, forcedName};
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
            return;
        }
        try {
            String name = forcedName != null ? forcedName : URLUtil.guessFileName(url, cd, mime);
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            if (mime != null) r.setMimeType(mime);
            r.addRequestHeader("User-Agent", ua);
            String ck = CookieManager.getInstance().getCookie(url);
            if (ck != null) r.addRequestHeader("Cookie", ck);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(r);
            Toast.makeText(this, "Mengunduh " + name + " …", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            openExternal(Uri.parse(url));
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        String[] p = pending;
        pending = null;
        if (p != null && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            download(p[0], p[1], p[2], p[3], p[4]);
        } else if (p != null) {
            Toast.makeText(this, "Izin penyimpanan diperlukan untuk mengunduh", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onBackPressed() {
        // Tutup dialog di dalam web dulu; baru mundur halaman; terakhir keluar aplikasi.
        web.evaluateJavascript("(function(){var d=document.querySelector('dialog[open]');if(d){d.close();return 1}return 0})()", v -> {
            if ("1".equals(v)) return;
            if (web.canGoBack()) web.goBack(); else finish();
        });
    }

    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); web.saveState(out); }
    @Override protected void onPause() { web.onPause(); super.onPause(); }
    @Override protected void onResume() { super.onResume(); web.onResume(); }
    @Override protected void onDestroy() { web.destroy(); super.onDestroy(); }
}
