package softm.android.policyfundfinder;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
import android.util.Base64;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JsPromptResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.OutputStream;

public class MainActivity extends Activity {
    private static final String HOME = "https://policyfundfinder.designboard.net/";
    private static final String TRUSTED_HOST = "policyfundfinder.designboard.net";
    private static final int FILE_CHOOSER_REQUEST = 10;
    private static final int CREATE_DOCUMENT_REQUEST = 11;
    private static final int MAX_NATIVE_DOWNLOAD_BYTES = 10 * 1024 * 1024;

    private WebView web;
    private ProgressBar progress;
    private LinearLayout error;
    private AlertDialog exitDialog;
    private ValueCallback<Uri[]> fileChooserCallback;
    private PendingDownload pendingDownload;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xff087d6b);
        getWindow().setNavigationBarColor(Color.WHITE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xfff6f8f7);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(
                insets.getSystemWindowInsetLeft(),
                insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(),
                insets.getSystemWindowInsetBottom()
            );
            return insets;
        });

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.getProgressDrawable().setTint(0xff087d6b);
        root.addView(progress, new LinearLayout.LayoutParams(-1, 6));

        error = new LinearLayout(this);
        error.setOrientation(LinearLayout.VERTICAL);
        error.setPadding(32, 64, 32, 32);

        TextView message = new TextView(this);
        message.setText("지원한눈에 연결할 수 없습니다.\n인터넷 연결을 확인한 뒤 다시 시도해 주세요.");
        message.setTextSize(18);
        message.setTextColor(Color.DKGRAY);
        error.addView(message);

        Button retry = new Button(this);
        retry.setText("다시 시도");
        retry.setOnClickListener(view -> retry());
        error.addView(retry);
        root.addView(error);
        error.setVisibility(View.GONE);

        web = new WebView(this);
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        configureWebView();
        if (state == null || web.restoreState(state) == null) web.loadUrl(HOME);

        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                this::handleBack
            );
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebView.setWebContentsDebuggingEnabled(
            (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0
        );
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setUserAgentString(settings.getUserAgentString() + " PolicyFundFinderAndroid/1");
        if (Build.VERSION.SDK_INT >= 26) settings.setSafeBrowsingEnabled(true);

        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        web.setDownloadListener((url, userAgent, disposition, mimeType, size) -> {
            Uri uri = Uri.parse(url);
            if ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) {
                openExternal(uri);
            } else if (!"blob".equals(uri.getScheme())) {
                Toast.makeText(this, "이 파일은 외부 브라우저에서 내려받아 주세요.", Toast.LENGTH_LONG).show();
            }
        });

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return route(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return route(Uri.parse(url));
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                error.setVisibility(View.GONE);
                web.setVisibility(View.VISIBLE);
                progress.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
                if (trusted(Uri.parse(url))) injectNativeBridge(view);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError failure) {
                if (request.isForMainFrame()) showError();
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (request.isForMainFrame() && response.getStatusCode() >= 400) showError();
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, JsPromptResult result) {
                if (!trusted(Uri.parse(url)) || view.getUrl() == null || !trusted(Uri.parse(view.getUrl()))) {
                    return super.onJsPrompt(view, url, message, defaultValue, result);
                }
                if ("SOFTM_SHARE_LINK".equals(message)) return share(defaultValue, result);
                if ("SOFTM_SAVE_BLOB".equals(message)) return saveBlob(defaultValue, result);
                return super.onJsPrompt(view, url, message, defaultValue, result);
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileChooserCallback != null) fileChooserCallback.onReceiveValue(null);
                fileChooserCallback = callback;
                Intent intent;
                try {
                    intent = params.createIntent();
                } catch (Exception ignored) {
                    intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json");
                }
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                } catch (ActivityNotFoundException failure) {
                    fileChooserCallback.onReceiveValue(null);
                    fileChooserCallback = null;
                    Toast.makeText(MainActivity.this, "파일을 선택할 앱이 없습니다.", Toast.LENGTH_LONG).show();
                }
                return true;
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                WebView popup = new WebView(MainActivity.this);
                popup.setWebViewClient(new WebViewClient() {
                    private boolean handled;

                    private void open(Uri uri) {
                        if (handled) return;
                        handled = true;
                        if (trusted(uri)) web.loadUrl(uri.toString()); else openExternal(uri);
                        popup.destroy();
                    }

                    @Override
                    public boolean shouldOverrideUrlLoading(WebView child, WebResourceRequest request) {
                        open(request.getUrl());
                        return true;
                    }

                    @Override
                    public boolean shouldOverrideUrlLoading(WebView child, String url) {
                        open(Uri.parse(url));
                        return true;
                    }
                });
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(popup);
                resultMsg.sendToTarget();
                return true;
            }

            @Override
            public void onProgressChanged(WebView view, int value) {
                progress.setProgress(value);
                if (value == 100) progress.setVisibility(View.GONE);
            }
        });
    }

    private void injectNativeBridge(WebView view) {
        view.evaluateJavascript(
            "(function(){if(window.__policyFundFinderAndroidBridge)return;" +
            "window.__policyFundFinderAndroidBridge=true;" +
            "if(!navigator.share){Object.defineProperty(navigator,'share',{configurable:true,value:function(data){" +
            "return new Promise(function(resolve,reject){if(!data||!data.url){reject(new TypeError('공유 링크가 없습니다.'));return;}" +
            "var status=window.prompt('SOFTM_SHARE_LINK',JSON.stringify({title:String(data.title||''),url:String(data.url)}));" +
            "if(status==='shared')resolve();else reject(new Error('공유창을 열지 못했습니다.'));});}});}" +
            "var nativeClick=HTMLAnchorElement.prototype.click;" +
            "HTMLAnchorElement.prototype.click=function(){var link=this;" +
            "if(link.download&&link.href&&link.href.indexOf('blob:')===0){" +
            "fetch(link.href).then(function(response){return Promise.all([response.arrayBuffer(),Promise.resolve(response.type||'application/octet-stream')]);})" +
            ".then(function(parts){var bytes=new Uint8Array(parts[0]),binary='',step=32768;" +
            "for(var i=0;i<bytes.length;i+=step)binary+=String.fromCharCode.apply(null,bytes.subarray(i,i+step));" +
            "var status=window.prompt('SOFTM_SAVE_BLOB',JSON.stringify({name:String(link.download||'download'),type:String(parts[1]),data:btoa(binary)}));" +
            "if(status!=='accepted')throw new Error('cancelled');})" +
            ".catch(function(failure){if(failure&&failure.message!=='cancelled')console.error(failure);});return;}" +
            "return nativeClick.apply(link,arguments);};})()",
            null
        );
    }

    private boolean share(String payload, JsPromptResult result) {
        try {
            JSONObject data = new JSONObject(payload);
            String link = data.getString("url");
            if (!trusted(Uri.parse(link))) {
                result.cancel();
                return true;
            }
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_TEXT, link);
            share.putExtra(Intent.EXTRA_SUBJECT, data.optString("title", "지원한눈"));
            startActivity(Intent.createChooser(share, "지원사업 링크 공유"));
            result.confirm("shared");
        } catch (Exception failure) {
            result.cancel();
            Toast.makeText(this, "공유창을 열지 못했습니다.", Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    private boolean saveBlob(String payload, JsPromptResult result) {
        try {
            JSONObject data = new JSONObject(payload);
            String encoded = data.getString("data");
            if (encoded.length() > MAX_NATIVE_DOWNLOAD_BYTES * 2) throw new IllegalArgumentException("파일이 너무 큽니다.");
            byte[] bytes = Base64.decode(encoded, Base64.DEFAULT);
            if (bytes.length > MAX_NATIVE_DOWNLOAD_BYTES) throw new IllegalArgumentException("파일이 너무 큽니다.");
            String name = safeFilename(data.optString("name", "jiwonhannun-backup.json"));
            String type = data.optString("type", "application/octet-stream");
            pendingDownload = new PendingDownload(name, type, bytes);
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(type)
                .putExtra(Intent.EXTRA_TITLE, name);
            startActivityForResult(intent, CREATE_DOCUMENT_REQUEST);
            result.confirm("accepted");
        } catch (Exception failure) {
            pendingDownload = null;
            result.cancel();
            Toast.makeText(this, failure.getMessage() == null ? "파일을 저장하지 못했습니다." : failure.getMessage(), Toast.LENGTH_LONG).show();
        }
        return true;
    }

    private String safeFilename(String value) {
        String name = value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (name.isEmpty()) name = "jiwonhannun-download";
        return name.length() > 120 ? name.substring(0, 120) : name;
    }

    private boolean trusted(Uri uri) {
        return "https".equals(uri.getScheme())
            && TRUSTED_HOST.equals(uri.getHost())
            && (uri.getPort() == -1 || uri.getPort() == 443);
    }

    private boolean route(Uri uri) {
        if (trusted(uri)) return false;
        openExternal(uri);
        return true;
    }

    private void openExternal(Uri uri) {
        String scheme = uri.getScheme();
        if (!"https".equals(scheme) && !"http".equals(scheme) && !"mailto".equals(scheme)
            && !"tel".equals(scheme) && !"geo".equals(scheme)) {
            Toast.makeText(this, "지원하지 않는 링크입니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = "tel".equals(scheme)
            ? new Intent(Intent.ACTION_DIAL, uri)
            : new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException failure) {
            Toast.makeText(this, "연결할 앱이 설치되어 있지 않습니다.", Toast.LENGTH_LONG).show();
        }
    }

    private void showError() {
        error.setVisibility(View.VISIBLE);
        web.setVisibility(View.GONE);
        progress.setVisibility(View.GONE);
    }

    private void retry() {
        String url = web.getUrl();
        web.setVisibility(View.VISIBLE);
        error.setVisibility(View.GONE);
        web.loadUrl(url != null && trusted(Uri.parse(url)) ? url : HOME);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST && fileChooserCallback != null) {
            fileChooserCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileChooserCallback = null;
            return;
        }
        if (requestCode == CREATE_DOCUMENT_REQUEST) {
            PendingDownload download = pendingDownload;
            pendingDownload = null;
            if (resultCode != RESULT_OK || data == null || data.getData() == null || download == null) return;
            try (OutputStream output = getContentResolver().openOutputStream(data.getData())) {
                if (output == null) throw new IllegalStateException("저장 위치를 열 수 없습니다.");
                output.write(download.bytes);
                Toast.makeText(this, download.name + " 파일을 저장했습니다.", Toast.LENGTH_LONG).show();
            } catch (Exception failure) {
                Toast.makeText(this, "파일을 저장하지 못했습니다.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void handleBack() {
        if (exitDialog != null && exitDialog.isShowing()) return;
        if (web.canGoBack()) {
            web.goBack();
            return;
        }
        exitDialog = new AlertDialog.Builder(this)
            .setTitle("앱을 종료하시겠습니까?")
            .setMessage("지원한눈을 종료하고 홈 화면으로 돌아갑니다.")
            .setNegativeButton("계속 이용", null)
            .setPositiveButton("종료", (dialog, which) -> finish())
            .create();
        exitDialog.setOnDismissListener(dialog -> exitDialog = null);
        exitDialog.show();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (Build.VERSION.SDK_INT >= 33) {
            super.onBackPressed();
        } else {
            handleBack();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        web.saveState(state);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onPause() {
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onDestroy() {
        if (fileChooserCallback != null) fileChooserCallback.onReceiveValue(null);
        if (exitDialog != null) {
            exitDialog.setOnDismissListener(null);
            exitDialog.dismiss();
        }
        web.stopLoading();
        web.loadUrl("about:blank");
        web.setWebChromeClient(null);
        web.setWebViewClient(null);
        web.destroy();
        super.onDestroy();
    }

    private static final class PendingDownload {
        final String name;
        final String type;
        final byte[] bytes;

        PendingDownload(String name, String type, byte[] bytes) {
            this.name = name;
            this.type = type;
            this.bytes = bytes;
        }
    }
}
