package mulin.tvdy.pump;



import android.app.Activity;

import android.graphics.Bitmap;

import android.os.Build;

import android.os.Handler;

import android.os.Looper;

import android.os.SystemClock;

import android.util.Log;

import android.view.View;

import android.view.ViewGroup;

import android.webkit.ConsoleMessage;

import android.webkit.CookieManager;

import android.webkit.JavascriptInterface;

import android.webkit.RenderProcessGoneDetail;

import android.webkit.WebChromeClient;

import android.webkit.WebResourceRequest;

import android.webkit.WebResourceResponse;

import android.webkit.WebSettings;

import android.webkit.WebView;

import android.webkit.WebViewClient;



import androidx.webkit.ServiceWorkerClientCompat;

import androidx.webkit.ServiceWorkerControllerCompat;

import androidx.webkit.WebViewCompat;

import androidx.webkit.WebViewFeature;



import org.json.JSONArray;

import org.json.JSONObject;



import java.util.ArrayList;

import java.util.Collections;

import java.util.List;



import mulin.tvdy.DeviceUtils;

import mulin.tvdy.DouyinConstants;

import mulin.tvdy.StartupTrace;

import mulin.tvdy.data.CreatorVideoRepository;

import mulin.tvdy.data.FeedRepository;

import mulin.tvdy.data.FeedVideo;

import mulin.tvdy.data.PageRequester;

import mulin.tvdy.data.WatchedAwemeStore;



/**

 * Hidden WebView "data pump": loads douyin.com, hooks fetch/XHR to capture feed

 * JSON the page fetches with valid Argus signatures, and buffers items for native

 * ExoPlayer playback.

 * <p>

 * Pagination never rebuilds or re-signs feed URLs — that path is rejected by

 * Douyin (404/403). When the buffer runs low we nudge the page (scroll / next

 * button) so its own client requests the next page.

 */

public final class FeedPumpController implements PageRequester {



    private static final String TAG = "FeedPump";

    private static final int MAX_RENDERER_REBUILDS = 3;

    private static final long[] KICKSTART_DELAYS_PHONE_MS = {800, 2_000, 5_000, 10_000};

    // The first entry is a bare floor before the very first nudge, not a
    // retry: a fast box can have douyin's page runtime up in a few hundred
    // ms, and waiting a fixed 800ms just added that to the cold start.
    private static final long[] KICKSTART_DELAYS_TV_MS = {300, 1_000, 2_500, 6_000, 12_000, 20_000, 30_000};

    private static final long PAGE_INTERACTIVE_FALLBACK_MS = 20_000;

    // 精选 usually boots straight into the feed, which is why the pump starts
    // there (home reaches interactive and then redirects itself to 精选, so it
    // pays the whole SPA boot twice). For anonymous sessions it occasionally
    // serves only the page shell and never runs the SPA at all - measured, 1
    // of 3 cold starts sat at bodyLen=760 with zero API calls for 40s. Home
    // always converges on 精选, so a reload to it costs the double boot we were
    // avoiding but does recover.
    private static final long JINGXUAN_FALLBACK_MS = 5_000;

    /**
     * First check for the 精选 guard. The check re-arms while the document is
     * still loading, so this is when the guard starts looking, not when it
     * gives up - see {@link #scheduleJingxuanFallback()}.
     */
    private static final long JINGXUAN_FALLBACK_RECHECK_MS = 2_000;

    /** Ceiling on the re-arming 精选 guard: past this, reload home regardless. */
    private static final long JINGXUAN_FALLBACK_HARD_CAP_MS = 30_000;

    /**
     * Silence required after the document finishes loading before the 精选
     * guard may conclude the page will never call. Reloading the moment
     * {@code onPageFinished} lands would abort pages that call just after it.
     */
    private static final long JINGXUAN_FALLBACK_GRACE_MS = 5_000;

    private static final long PAGE_FETCH_TIMEOUT_MS = 20_000;

    private static final long PAGE_FETCH_TIMEOUT_ACTIVE_MS = 45_000;

    private static final int MAX_PAGE_FETCH_RETRIES = 3;

    private static final int MAX_PAGE_FETCH_TIMEOUT_EXTENSIONS = 4;

    private static final long PAGE_FETCH_RETRY_DELAY_MS = 2_000;

    // How long we trust the page to follow its binary feed response with a
    // JSON one before nudging again. While this is pending, nudgePageForFeed()
    // refuses to act, so this value is a hard floor on how long a cold start
    // can sit doing nothing if that follow-up never comes.
    //
    // Tried cutting this to 2.5s on the theory that the window was dead time.
    // Measured on the TV: the nudge did move earlier (lite binary +24.0s, nudge
    // +25.3s instead of +32.0s) but the feed still arrived at +33.8s against a
    // +33.4s baseline. The nudge was not the gate - the page needs ~7s on this
    // CPU to answer the pagination nudge, and that latency is not ours to
    // tune. Left at 8s rather than changed for no measured gain.
    private static final long LITE_JSON_FALLBACK_MS = 8_000;

    private static final int PUMP_LOW_WATER_MARK = 5;



    public interface Listener {

        void onPumpStatus(String message);



        void onPumpError(String message);



        /** Watch-history store grew; purge already-queued watched items. */
        default void onWatchHistoryUpdated() {
        }

    }



    private final Activity host;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final FeedRepository repository = FeedRepository.getInstance();

    private final boolean television;

    private final long[] kickstartDelays;

    private final List<Runnable> kickstartRunnables = new ArrayList<>();

    private ViewGroup container;

    private WebView webView;

    private Listener listener;

    private int rendererRebuilds = 0;

    private int kickstartGeneration = 0;

    private int apiCallsSeen = 0;

    /** Count of images/fonts never fetched because the pump view is invisible. */

    private int blockedResourceCount = 0;

    /**
     * Diagnostic A/B switch. When set, the pump injects no JavaScript into the
     * page at all - no document-start hooks, no service-worker interception,
     * no synthetic nudge events. The page then behaves exactly as it would in
     * a plain WebView, which is what separates "our injection breaks douyin's
     * SPA" from "this box cannot run douyin's SPA".
     * <p>
     * Set from {@code PlayerActivity} via {@code am start -e tvdy_skip_hooks 1}.
     * Static because the pump is built during {@code onCreate}, before there is
     * anywhere else to carry the flag.
     */
    public static boolean skipHookInjection = false;

    /**
     * Diagnostic override for {@link #JINGXUAN_FALLBACK_MS}: {@code 0} keeps the
     * constant, a positive value changes the window, and a negative value
     * disables the fallback so the first page load is never interrupted. Set
     * from {@code PlayerActivity} via {@code -e tvdy_jingxuan_fallback_ms N}.
     */
    public static long jingxuanFallbackMsOverride = 0;

    /** Reset per document so {@link #injectHooks()} sends INSTALL_HOOKS once. */

    private boolean hooksInjectedForDocument = false;

    private boolean pageInteractive = false;

    /**
     * When the current document reached {@code onPageFinished}, or 0 while it is
     * still loading. {@link #pageInteractive} is not this signal: it flips at
     * 50% progress, which a TV box reports while the document is still empty.
     * The 精选 guard needs the real one - see {@link #scheduleJingxuanFallback()}.
     */
    private long documentLoadedAtMs = 0;

    private final FeedPaginationState paginationState = new FeedPaginationState();

    private final FeedPaginationState creatorPaginationState = new FeedPaginationState();

    private final CreatorVideoRepository creatorRepository = CreatorVideoRepository.getInstance();

    private boolean creatorMode = false;

    /** True only when we fell back to loading {@code /user/{sec_uid}} in the WebView. */
    private boolean creatorNavigatedToProfile = false;

    private String activeCreatorSecUid;

    private Runnable hookTicker;

    private Runnable pageInteractiveFallback;

    private Runnable jingxuanFallback;

    /** One fallback per pump, so a reload that also stalls cannot loop. */
    private boolean jingxuanFallbackUsed = false;

    private Runnable pageFetchTimeout;

    private boolean pageFetchInFlight = false;

    private int pageFetchRetryCount = 0;

    private int apiCallsSeenAtNudge = 0;

    private int pageFetchTimeoutExtensions = 0;

    private boolean awaitingLiteJsonFeed = false;

    private Runnable liteJsonFallback;

    private boolean historyFetchInFlight = false;

    private boolean loggedIn = false;

    private boolean feedApiSeen = false;

    private boolean historySyncRequested = false;

    private boolean pendingHistorySync = false;

    private boolean playbackStartedNotified = false;

    private long historyCursor = 0;

    private int historyPagesFetched = 0;

    private int historyFetchFailures = 0;

    private String lastHistoryBatchKey = "";

    // Startup-time cost control: these pages are fetched through the same
    // WebView that is still bringing the feed up, right as the first video
    // starts buffering, so the cap trades "older history is filtered" for
    // "the first video is not competing with 10 page fetches".
    private static final int MAX_HISTORY_PAGES = 5;

    /** Gap between history pages so they interleave instead of bursting. */
    private static final long HISTORY_PAGE_GAP_MS = 600;

    private static final int MAX_HISTORY_FETCH_FAILURES = 8;



    public FeedPumpController(Activity host) {

        this.host = host;

        this.television = DeviceUtils.isTelevision(host);

        this.kickstartDelays = television ? KICKSTART_DELAYS_TV_MS : KICKSTART_DELAYS_PHONE_MS;

    }



    public void setListener(Listener listener) {

        this.listener = listener;

    }



    public void setLoggedIn(boolean loggedIn) {

        this.loggedIn = loggedIn;

        if (loggedIn) {

            WatchedAwemeStore.getInstance().bindSession();

            // Sync history as soon as the pump has a chance to sign — do not
            // wait for first-frame playback (feed often fills the queue first).
            pendingHistorySync = true;

            tryStartWatchHistorySync(1_200);

        }

        injectLoggedInFlag();

    }



    public void syncWatchHistory() {

        historySyncRequested = true;

        historyCursor = 0;

        historyPagesFetched = 0;

        historyFetchFailures = 0;

        lastHistoryBatchKey = "";

        pendingHistorySync = false;

    }



    public void scheduleWatchHistorySync() {

        pendingHistorySync = true;

        tryStartWatchHistorySync(800);

    }



    private void tryStartWatchHistorySync(long delayMs) {

        if (!loggedIn || historySyncRequested) return;

        handler.postDelayed(() -> {

            if (!loggedIn || historySyncRequested || webView == null) return;

            syncWatchHistory();

            fetchNextHistoryPage();

        }, Math.max(0, delayMs));

    }



    public void reportPlay(String awemeId, long playMs) {

        if (webView == null || awemeId == null || awemeId.isEmpty()) return;

        webView.evaluateJavascript(FeedHookScripts.buildReportPlayScript(awemeId, playMs), null);

    }



    public void start(ViewGroup container) {

        if (webView != null) return;

        this.container = container;

        repository.setPageRequester(this);

        creatorRepository.setPageRequester(this::requestCreatorNextPage);

        notifyStatus(television ? "正在加载抖音页面（TV 模式）…" : "正在加载抖音页面…");

        createWebView();

        if (periodicProbe) {

            handler.post(periodicProbeRunnable);

        }

    }



    public void onResume() {

        if (webView != null) webView.onResume();

    }



    public void onPause() {

        if (webView != null) webView.onPause();

    }



    public void onDestroy() {

        cancelKickstart();

        stopHookTicker();

        cancelPageInteractiveFallback();

        cancelPageFetchTimeout();

        cancelLiteJsonFallback();

        handler.removeCallbacksAndMessages(null);

        if (webView != null) {

            container.removeView(webView);

            webView.destroy();

            webView = null;

        }

    }



    @Override

    public void requestNextPage() {

        if (creatorMode) {

            requestCreatorNextPage();

            return;

        }

        if (webView == null) return;

        if (repository.bufferSize() >= PUMP_LOW_WATER_MARK) {

            repository.releasePageRequest();

            return;

        }

        nudgePageForFeed();

    }



    /** Ask the loaded douyin.com page to fetch the next feed batch itself. */

    private void nudgePageForFeed() {

        nudgePageForFeed(false);

    }



    private void nudgePageForFeed(boolean forceInitial) {

        if (skipHookInjection) return;

        if (webView == null || pageFetchInFlight || creatorMode) return;

        if (awaitingLiteJsonFeed) {

            Log.d(TAG, "awaiting lite JSON follow-up, skipping nudge");

            return;

        }

        boolean paginate = paginationState.isReady();

        if (!paginate && apiCallsSeen == 0 && !forceInitial) {

            Log.d(TAG, "deferring initial nudge until page runtime is alive");

            return;

        }

        pageFetchInFlight = true;

        apiCallsSeenAtNudge = apiCallsSeen;

        pageFetchTimeoutExtensions = 0;

        schedulePageFetchTimeout();



        String script = paginate
                ? FeedHookScripts.TRIGGER_PAGE_FEED
                : FeedHookScripts.TRIGGER_INITIAL_FEED;

        if (paginate) {

            Log.d(TAG, "nudge page pagination refresh_index=" + paginationState.getRefreshIndex());

            notifyStatus("正在加载更多视频…");

        } else {

            Log.d(TAG, "nudge page initial feed");

        }

        webView.evaluateJavascript(script, null);

    }



    private void handleLiteFeedBinary(String url) {

        if (repository.bufferSize() > 0) return;

        feedApiSeen = true;

        paginationState.seedFromUrl(url);

        awaitingLiteJsonFeed = true;

        StartupTrace.mark("feed: lite binary, awaiting JSON follow-up");

        // Deliberately NOT cancelKickstart() any more. Killing the whole
        // ladder here also killed the status updates and every recovery
        // attempt, so a page that never sent the JSON follow-up left the
        // splash sitting silent for the entire LITE_JSON_FALLBACK_MS window.

        cancelLiteJsonFallback();

        cancelPageFetchTimeout();

        pageFetchInFlight = false;

        pageFetchRetryCount = 0;

        pageFetchTimeoutExtensions = 0;

        repository.releasePageRequest();

        liteJsonFallback = () -> {

            if (!awaitingLiteJsonFeed || repository.bufferSize() > 0) return;

            Log.w(TAG, "lite JSON follow-up slow, allowing pagination nudge");

            awaitingLiteJsonFeed = false;

            requestNextPage();

        };

        handler.postDelayed(liteJsonFallback, LITE_JSON_FALLBACK_MS);

        Log.d(TAG, "lite binary feed seeded refresh_index=" + paginationState.getRefreshIndex()

                + ", page will send JSON feed next");

    }



    private void cancelLiteJsonFallback() {

        if (liteJsonFallback != null) {

            handler.removeCallbacks(liteJsonFallback);

            liteJsonFallback = null;

        }

    }



    public void notifyPlaybackStarted() {

        if (playbackStartedNotified) return;

        playbackStartedNotified = true;

        // Fallback if early login sync never ran (signer not ready yet).
        if (loggedIn && !historySyncRequested) {

            tryStartWatchHistorySync(500);

        }

    }



    public void reloadFeed() {

        if (webView == null) return;

        resetPumpSession();

        notifyStatus("正在重新加载…");

        webView.reload();

    }



    public void hardRestart() {

        cancelKickstart();

        stopHookTicker();

        cancelPageInteractiveFallback();

        resetPumpSession();

        rendererRebuilds = 0;

        if (webView != null) {

            container.removeView(webView);

            webView.destroy();

            webView = null;

        }

        notifyStatus("正在重新初始化…");

        createWebView();

    }



    /**
     * After cookie login: reload home in-place so the same anonymous feed
     * pipeline runs with session cookies attached.
     */
    public void softReloadAfterLogin() {

        cancelKickstart();

        stopHookTicker();

        cancelPageInteractiveFallback();

        resetPumpSession();

        feedApiSeen = false;

        notifyStatus("登录成功，正在刷新推荐…");

        if (webView != null) {

            injectLoggedInFlag();

            webView.loadUrl(DouyinConstants.pumpStartUrl(loggedIn));

        } else {

            createWebView();

        }

    }



    private void resetPumpSession() {

        apiCallsSeen = 0;

        pageInteractive = false;

        documentLoadedAtMs = 0;

        paginationState.reset();

        cancelPageFetchTimeout();

        cancelLiteJsonFallback();

        pageFetchInFlight = false;

        pageFetchRetryCount = 0;

        apiCallsSeenAtNudge = 0;

        pageFetchTimeoutExtensions = 0;

        awaitingLiteJsonFeed = false;

        feedApiSeen = false;

        historyFetchInFlight = false;

        historySyncRequested = false;

        historyCursor = 0;

        historyPagesFetched = 0;

        historyFetchFailures = 0;

        lastHistoryBatchKey = "";

        pendingHistorySync = false;

        playbackStartedNotified = false;

    }



    private void fetchNextHistoryPage() {

        if (!historySyncRequested || webView == null) return;

        if (historyPagesFetched >= MAX_HISTORY_PAGES) {

            historySyncRequested = false;

            return;

        }

        Log.d(TAG, "fetching watch history page cursor=" + historyCursor);

        historyFetchInFlight = true;

        webView.evaluateJavascript(FeedHookScripts.buildFetchHistoryScript(historyCursor), null);

    }



    @SuppressWarnings("SetJavaScriptEnabled")

    private void createWebView() {

        hooksInjectedForDocument = false;

        long webViewConstructStartMs = SystemClock.uptimeMillis();

        webView = new WebView(host);

        StartupTrace.markDelta("pump new WebView()",
                SystemClock.uptimeMillis() - webViewConstructStartMs);

        webView.setAlpha(0f);

        webView.setFocusable(false);

        webView.setFocusableInTouchMode(false);

        webView.setClickable(false);

        webView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        if (television) {

            webView.setLayerType(View.LAYER_TYPE_NONE, null);

        } else {

            webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        }

        container.addView(webView, 0, new ViewGroup.LayoutParams(

                DouyinConstants.PUMP_VIEWPORT_WIDTH,

                DouyinConstants.PUMP_VIEWPORT_HEIGHT));



        WebSettings settings = webView.getSettings();

        settings.setJavaScriptEnabled(true);

        settings.setDomStorageEnabled(true);

        // Tried flipping this to true to keep the pump's <video> elements from
        // allocating decoders: measured, it changed nothing. The page feeds
        // video to the element through MediaSource, which Chromium prepares a
        // decoder for on load() regardless of the autoplay gesture rule. Left
        // as the original false rather than kept as an unproven change.
        settings.setMediaPlaybackRequiresUserGesture(false);

        settings.setUserAgentString(DouyinConstants.DESKTOP_USER_AGENT);

        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        settings.setUseWideViewPort(true);

        settings.setLoadWithOverviewMode(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {

            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        }



        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            webView.setRendererPriorityPolicy(

                    WebView.RENDERER_PRIORITY_IMPORTANT, /* waivedWhenNotVisible= */ false);

        }



        CookieManager cookieManager = CookieManager.getInstance();

        cookieManager.setAcceptCookie(true);

        cookieManager.setAcceptThirdPartyCookies(webView, true);

        cookieManager.flush();



        webView.addJavascriptInterface(new JsBridge(), "AndroidBridge");

        installDocumentStartHooks();

        setupServiceWorkerInterception();



        webView.setWebChromeClient(new WebChromeClient() {

            @Override

            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {

                if (consoleMessage != null) {

                    Log.d(TAG, "console: " + consoleMessage.message());

                }

                return true;

            }



            @Override

            public void onProgressChanged(WebView view, int newProgress) {

                if (newProgress >= 50) {

                    markPageInteractive("页面加载 " + newProgress + "%，等待视频…");

                }

            }

        });



        webView.setWebViewClient(new WebViewClient() {

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request == null || !request.isForMainFrame()) {
                    return false;
                }
                android.net.Uri uri = request.getUrl();
                if (uri == null) return false;
                String path = uri.getPath() != null ? uri.getPath() : "";
                // Soft SPA pushes may still slip through; hooks pin those.
                if (loggedIn && !creatorMode && path.contains("/user/self")) {
                    Log.d(TAG, "blocked pump navigation to user/self");
                    return true;
                }
                return false;
            }

            @Override

            public void onPageStarted(WebView view, String url, Bitmap favicon) {

                super.onPageStarted(view, url, favicon);

                StartupTrace.mark("pump onPageStarted");

                pageInteractive = false;

                documentLoadedAtMs = 0;

                // New document: its window (and therefore its hook flags) reset.
                hooksInjectedForDocument = false;

                injectHooks();

                startHookTicker();

                schedulePageInteractiveFallback();

            }



            @Override

            public void onPageFinished(WebView view, String url) {

                super.onPageFinished(view, url);

                StartupTrace.mark("pump onPageFinished");

                documentLoadedAtMs = SystemClock.uptimeMillis();

                injectHooks();

                probePageState();

                markPageInteractive("页面已加载，等待视频…");

                if (loggedIn && pendingHistorySync && !historySyncRequested) {

                    tryStartWatchHistorySync(400);

                }

                if (loggedIn && repository.bufferSize() == 0 && !feedApiSeen) {

                    scrapeEmbeddedFeed();

                }

            }



            @Override

            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {

                // Splits the loadUrl -> onPageStarted gap into "Chromium took
                // this long to issue the request" vs "the server took this
                // long to answer", which need completely different fixes.
                if (request != null && request.isForMainFrame()) {
                    StartupTrace.mark("main document request issued");
                }

                // Images/fonts are fetched only to be painted into a view that
                // is never visible; drop them before they eat bandwidth the
                // feed request and the first video segment need.
                WebResourceResponse noise = PumpResourceFilter.blockIfNoise(request);
                if (noise != null) {
                    blockedResourceCount++;
                    // Log the URL, not just a count - when this went wrong it
                    // silently ate the main document and the count alone gave
                    // no way to see that.
                    if (blockedResourceCount <= 10 || blockedResourceCount % 100 == 0) {
                        Log.d(TAG, "blocked invisible subresource #" + blockedResourceCount
                                + " " + request.getUrl());
                    }
                    return noise;
                }

                WebResourceResponse mirrored = FeedResponseInterceptor.mirror(
                        request,
                        (url, status, body) -> handler.post(() -> {
                            apiCallsSeen++;
                            Log.d(TAG, "mirrored feed api: " + url + " status=" + status
                                    + " bytes=" + (body != null ? body.length() : 0));
                            if (status == 200 && body != null && !body.isEmpty()) {
                                handleFeedData(url, body);
                            }
                        }));
                if (mirrored != null) {
                    return mirrored;
                }

                return super.shouldInterceptRequest(view, request);

            }



            @SuppressWarnings("deprecation")

            @Override

            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {

                super.onReceivedError(view, errorCode, description, failingUrl);

                if (failingUrl == null || !failingUrl.contains("douyin.com")) return;

                Log.w(TAG, "main frame load error: " + description);

                notifyError("页面加载失败：" + description);

            }



            @Override

            public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {

                super.onReceivedError(view, request, error);

                if (request == null || !request.isForMainFrame()) return;

                String desc = error != null ? error.getDescription().toString() : "unknown";

                Log.w(TAG, "main frame load error: " + desc);

                notifyError("页面加载失败：" + desc);

            }



            @Override

            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {

                super.onReceivedHttpError(view, request, errorResponse);

                if (request == null || !request.isForMainFrame()) return;

                int code = errorResponse != null ? errorResponse.getStatusCode() : -1;

                Log.w(TAG, "main frame HTTP error: " + code);

                if (code >= 400) {

                    notifyError("页面请求失败 (HTTP " + code + ")");

                }

            }



            @Override

            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {

                Log.w(TAG, "renderer process gone (crashed=" + detail.didCrash() + "), rebuilding pump");

                container.removeView(view);

                view.destroy();

                webView = null;

                rendererRebuilds++;

                if (rendererRebuilds > MAX_RENDERER_REBUILDS) {

                    notifyError("WebView 多次崩溃，请更新系统 WebView");

                    return true;

                }

                notifyStatus("WebView 重启中…");

                handler.post(() -> createWebView());

                return true;

            }

        });



        StartupTrace.mark("pump loadUrl " + DouyinConstants.pumpStartUrl(loggedIn));

        webView.loadUrl(DouyinConstants.pumpStartUrl(loggedIn));

        scheduleJingxuanFallback();

    }



    private void schedulePageInteractiveFallback() {

        cancelPageInteractiveFallback();

        pageInteractiveFallback = () -> {

            if (!pageInteractive && webView != null && repository.bufferSize() == 0) {

                Log.w(TAG, "forcing interactive kickstart (onPageFinished slow or missing)");

                markPageInteractive("页面加载较慢，继续等待视频…");

            }

        };

        handler.postDelayed(pageInteractiveFallback, PAGE_INTERACTIVE_FALLBACK_MS);

    }



    private void cancelPageInteractiveFallback() {

        if (pageInteractiveFallback != null) {

            handler.removeCallbacks(pageInteractiveFallback);

            pageInteractiveFallback = null;

        }

    }



    private void schedulePageFetchTimeout() {

        cancelPageFetchTimeout();

        pageFetchTimeout = () -> handlePageFetchTimeout();

        long timeoutMs = (apiCallsSeen > apiCallsSeenAtNudge)

                ? PAGE_FETCH_TIMEOUT_ACTIVE_MS : PAGE_FETCH_TIMEOUT_MS;

        handler.postDelayed(pageFetchTimeout, timeoutMs);

    }



    private void cancelPageFetchTimeout() {

        if (pageFetchTimeout != null) {

            handler.removeCallbacks(pageFetchTimeout);

            pageFetchTimeout = null;

        }

    }



    private void handlePageFetchTimeout() {

        if (creatorMode) {

            handleCreatorPageFetchTimeout();

            return;

        }

        if (repository.bufferSize() >= PUMP_LOW_WATER_MARK) {

            pageFetchInFlight = false;

            pageFetchRetryCount = 0;

            pageFetchTimeoutExtensions = 0;

            repository.releasePageRequest();

            return;

        }

        if (apiCallsSeen > apiCallsSeenAtNudge

                && pageFetchTimeoutExtensions < MAX_PAGE_FETCH_TIMEOUT_EXTENSIONS) {

            pageFetchTimeoutExtensions++;

            Log.d(TAG, "page active after nudge (apiCallsSeen=" + apiCallsSeen

                    + "), extending fetch timeout " + pageFetchTimeoutExtensions

                    + "/" + MAX_PAGE_FETCH_TIMEOUT_EXTENSIONS);

            schedulePageFetchTimeout();

            return;

        }

        pageFetchInFlight = false;

        if (pageFetchRetryCount < MAX_PAGE_FETCH_RETRIES) {

            pageFetchRetryCount++;

            Log.w(TAG, "page feed nudge timeout, retry " + pageFetchRetryCount

                    + "/" + MAX_PAGE_FETCH_RETRIES);

            repository.releasePageRequest();

            handler.postDelayed(this::requestNextPage, PAGE_FETCH_RETRY_DELAY_MS);

            return;

        }

        pageFetchRetryCount = 0;

        pageFetchTimeoutExtensions = 0;

        repository.releasePageRequest();

        Log.w(TAG, "page feed nudge timed out, will retry when buffer drains");

    }



    private void handleCreatorPageFetchTimeout() {

        pageFetchInFlight = false;

        creatorRepository.releasePageRequest();

        if (creatorRepository.size() >= 8) {

            pageFetchRetryCount = 0;

            return;

        }

        if (pageFetchRetryCount < MAX_PAGE_FETCH_RETRIES) {

            pageFetchRetryCount++;

            Log.w(TAG, "creator post fetch timeout, retry " + pageFetchRetryCount

                    + "/" + MAX_PAGE_FETCH_RETRIES);

            handler.postDelayed(this::requestCreatorNextPage, PAGE_FETCH_RETRY_DELAY_MS);

            return;

        }

        pageFetchRetryCount = 0;

        if (!creatorNavigatedToProfile && activeCreatorSecUid != null) {

            Log.w(TAG, "creator post fetch timed out, falling back to profile page");

            fallbackLoadCreatorProfilePage(activeCreatorSecUid);

        } else {

            Log.w(TAG, "creator post fetch timed out");

        }

    }



    private void markPageInteractive(String status) {

        if (pageInteractive) return;

        pageInteractive = true;

        cancelPageInteractiveFallback();

        notifyStatus(status);

        scheduleFeedKickstart();

    }



    private void startHookTicker() {

        stopHookTicker();

        hookTicker = new Runnable() {

            @Override

            public void run() {

                if (repository.bufferSize() > 0 || webView == null) return;

                injectHooks();

                handler.postDelayed(this, 2_000);

            }

        };

        handler.postDelayed(hookTicker, 2_000);

    }



    private void stopHookTicker() {

        if (hookTicker != null) {

            handler.removeCallbacks(hookTicker);

            hookTicker = null;

        }

    }



    private static boolean isFeedApiUrl(String url) {

        if (url == null || !url.contains("douyin.com")) return false;

        if (url.contains("/history/")) return false;

        return url.contains("/tab/feed")

                || url.contains("/module/feed")

                || (url.contains("/aweme/") && url.contains("/feed"));

    }



    /**
     * Only real recommend/feed APIs fill the for-you queue. Favorites / posts /
     * collections are intentionally excluded — they are already-seen content and
     * used to pollute cold-start playback when the WebView visits profile pages.
     */
    private static boolean acceptsAwemeListCapture(String url) {

        return isFeedApiUrl(url);

    }



    private static boolean isHistoryApiUrl(String url) {

        return url != null && url.contains("douyin.com") && url.contains("history/read");

    }



    private void installDocumentStartHooks() {

        if (skipHookInjection) return;

        try {

            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {

                WebViewCompat.addDocumentStartJavaScript(

                        webView,

                        FeedHookScripts.INSTALL_HOOKS,

                        Collections.singleton("*"));

                Log.d(TAG, "document-start hooks installed");

            }

        } catch (Exception e) {

            Log.w(TAG, "document-start hooks unavailable", e);

        }

    }



    private void scheduleFeedKickstart() {

        cancelKickstart();

        final int generation = kickstartGeneration;

        for (int i = 0; i < kickstartDelays.length; i++) {

            final int attempt = i + 1;

            Runnable runnable = () -> runFeedKickstart(generation, attempt);

            kickstartRunnables.add(runnable);

            handler.postDelayed(runnable, kickstartDelays[i]);

        }

    }



    private void cancelKickstart() {

        kickstartGeneration++;

        for (Runnable runnable : kickstartRunnables) {

            handler.removeCallbacks(runnable);

        }

        kickstartRunnables.clear();

    }



    /**
     * Guards the 精选 fast path: if the page never gets as far as issuing any
     * API call, reload home, which redirects itself back to 精选 once its SPA
     * is up. Skipped when a feed is already arriving, and fires at most once.
     * <p>
     * The check re-arms while the first document is still in flight instead of
     * firing on a bare wall clock. A fixed 5s window is meaningless on a TV box,
     * where the SPA is not interactive until 10-18s: it aborted the very load
     * that was about to succeed, and the reload never recovered inside the
     * startup timeout. Waiting for {@link #pageInteractive} means the reload
     * only happens once the page has settled and still said nothing.
     */
    private void scheduleJingxuanFallback() {

        cancelJingxuanFallback();

        if (jingxuanFallbackUsed) return;

        final long delayMs = jingxuanFallbackMsOverride != 0
                ? jingxuanFallbackMsOverride
                : JINGXUAN_FALLBACK_MS;
        if (delayMs < 0) {
            Log.d(TAG, "jingxuan fallback disabled by diagnostic override");
            return;
        }

        final long armedAtMs = SystemClock.uptimeMillis();
        final long deadlineMs = armedAtMs + JINGXUAN_FALLBACK_HARD_CAP_MS;

        jingxuanFallback = new Runnable() {
            @Override
            public void run() {

                if (webView == null || creatorMode) return;

                if (apiCallsSeen > 0 || repository.bufferSize() > 0) return;

                // Nothing can be concluded while the document is still loading,
                // and a page that just finished deserves the grace period before
                // being written off.
                if (SystemClock.uptimeMillis() < deadlineMs) {

                    boolean stillLoading = documentLoadedAtMs == 0;

                    boolean withinGrace = !stillLoading
                            && SystemClock.uptimeMillis() - documentLoadedAtMs
                                    < JINGXUAN_FALLBACK_GRACE_MS;

                    if (stillLoading || withinGrace) {

                        Log.d(TAG, "精选 still loading, deferring fallback check");

                        handler.postDelayed(this, JINGXUAN_FALLBACK_RECHECK_MS);

                        return;

                    }

                }

                jingxuanFallbackUsed = true;

                Log.w(TAG, "精选 issued no API call after "
                        + (SystemClock.uptimeMillis() - armedAtMs)
                        + "ms, reloading home as fallback");

                StartupTrace.mark("pump fallback -> home");

                notifyStatus("正在重新加载…");

                webView.loadUrl(DouyinConstants.FEED_URL);

            }
        };

        handler.postDelayed(jingxuanFallback, delayMs);

    }



    private void cancelJingxuanFallback() {

        if (jingxuanFallback != null) {

            handler.removeCallbacks(jingxuanFallback);

            jingxuanFallback = null;

        }

    }



    /**
     * 原生直连推荐流兜底：页面完全没发出 API 请求时，用
     * {@link mulin.tvdy.data.NativeFeedClient} 持续供片。拉取在后台线程，
     * 入队在主线程(addVideosBlocking 的监听器要求主线程)，并自动触发
     * PlayerActivity 的 onBufferChanged 开播逻辑。
     */
    private void kickstartNativeFeed() {
        if (creatorMode) return;
        new Thread(() -> {
            final java.util.List<mulin.tvdy.data.FeedVideo> videos =
                    mulin.tvdy.data.NativeFeedClient.fetch(8);
            if (videos == null || videos.isEmpty()) return;
            handler.post(() -> {
                try {
                    if (repository.bufferSize() < 12) {
                        repository.addVideosBlocking(videos);
                        Log.d(TAG, "native feed kickstart applied n=" + videos.size());
                    }
                } catch (Exception e) {
                    Log.w(TAG, "native feed kickstart failed", e);
                }
            });
        }, "native-feed-kickstart").start();
    }

    private void runFeedKickstart(int generation, int attempt) {

        if (generation != kickstartGeneration || webView == null) return;

        if (creatorMode) return;

        if (repository.bufferSize() > 0) return;



        Log.d(TAG, "feed kickstart attempt " + attempt + " apiCallsSeen=" + apiCallsSeen
                + " feedApiSeen=" + feedApiSeen + " loggedIn=" + loggedIn);

        injectHooks();

        if (apiCallsSeen == 0 && !pageFetchInFlight && !awaitingLiteJsonFeed) {

            // 页面始终没发出任何 /aweme/ 请求(常见于电视系统 WebView 过旧、
            // SPA 启动失败)。原生直连推荐流接口不依赖 WebView，用它兜底供片，
            // 避免用户陷入无限重试；NativeFeedClient 自带 8 秒节流。
            kickstartNativeFeed();

            repository.releasePageRequest();

            nudgePageForFeed(true);

        } else if (apiCallsSeen > 0 && !paginationState.isReady() && !awaitingLiteJsonFeed) {

            repository.releasePageRequest();

            requestNextPage();

        }

        probePageState();

        if (loggedIn && !feedApiSeen && repository.bufferSize() == 0) {

            scrapeEmbeddedFeed();

        }



        if (attempt == 2) {

            notifyStatus("仍在等待视频，尝试触发加载…");

        } else if (attempt == kickstartDelays.length) {

            if (apiCallsSeen == 0) {

                // 原生直连推荐流已接管供片(见 kickstartNativeFeed)，不再报错。
                notifyStatus("网页加载不可用，已切换直连推荐流");

            } else {

                notifyStatus("API 已响应但无视频，等待自动重试…");

                notifyError("长时间未获取到视频");

            }

        }

    }



    /**
     * Diagnostic only: samples page state every 2s so a verification/block page
     * that only appears long after the probes stop can still be observed.
     * Enabled with {@code -e tvdy_periodic_probe true}.
     */
    public static boolean periodicProbe = false;

    private final Runnable periodicProbeRunnable = new Runnable() {
        @Override
        public void run() {
            if (!periodicProbe || webView == null) return;
            probePageState();
            handler.postDelayed(this, 2_000);
        }
    };

    private void probePageState() {

        if (webView != null) {

            webView.evaluateJavascript(FeedHookScripts.PROBE_PAGE_STATE, null);

        }

    }



    private void scrapeEmbeddedFeed() {

        if (webView == null || feedApiSeen || repository.bufferSize() > 0) return;

        webView.evaluateJavascript(FeedHookScripts.SCRAPE_EMBEDDED_FEED, null);

    }



    private void setupServiceWorkerInterception() {

        if (skipHookInjection) return;

        try {

            if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE)) {

                ServiceWorkerControllerCompat swController = ServiceWorkerControllerCompat.getInstance();

                swController.setServiceWorkerClient(new ServiceWorkerClientCompat() {

                    @Override

                    public WebResourceResponse shouldInterceptRequest(WebResourceRequest request) {

                        return null;

                    }

                });

            }

        } catch (Exception e) {

            Log.w(TAG, "failed to install service worker interception", e);

        }

    }



    private void injectLoggedInFlag() {

        if (webView != null) {

            webView.evaluateJavascript("window.__tvdyPumpLoggedIn=" + loggedIn + ";", null);

        }

    }



    private void injectHooks() {

        if (skipHookInjection) return;

        if (webView == null) return;

        // The logged-in flag must ride along every time - it is plain state the
        // page reads, not an install-once hook.
        injectLoggedInFlag();

        // INSTALL_HOOKS is ~15 KB of JS and its own guard already no-ops on a
        // repeat, so re-sending it on the 2s tick / every kickstart attempt
        // only costs a main-thread string marshal across the JNI boundary.
        // One send per document is enough.
        if (hooksInjectedForDocument) return;

        hooksInjectedForDocument = true;

        webView.evaluateJavascript(FeedHookScripts.INSTALL_HOOKS, null);

    }



    private void notifyStatus(String message) {

        if (listener != null) listener.onPumpStatus(message);

    }



    private void notifyError(String message) {

        if (listener != null) listener.onPumpError(message);

    }



    private void onFeedCaptureComplete() {

        cancelPageFetchTimeout();

        cancelLiteJsonFallback();

        pageFetchInFlight = false;

        pageFetchRetryCount = 0;

        pageFetchTimeoutExtensions = 0;

        awaitingLiteJsonFeed = false;

        repository.releasePageRequest();

        creatorRepository.releasePageRequest();

    }



    private class JsBridge {

        @JavascriptInterface

        public void onFeedData(final String url, final String json) {

            handler.post(() -> handleFeedData(url, json));

        }



        @JavascriptInterface

        public void onUrlSeen(final String url, final String note) {

            handler.post(() -> {

                apiCallsSeen++;

                if (apiCallsSeen == 1) {
                    StartupTrace.mark("pump: first douyin api call seen");
                }

                if (isFeedApiUrl(url)) {
                    feedApiSeen = true;
                }

                Log.d(TAG, "saw api call: " + url + " (" + note + ")");

                if (pageFetchInFlight) {

                    schedulePageFetchTimeout();

                } else if (apiCallsSeen == 1 && repository.bufferSize() == 0

                        && pageInteractive && webView != null) {

                    repository.releasePageRequest();

                    requestNextPage();

                }

            });

        }



        @JavascriptInterface

        public void onPageProbe(final String json) {

            handler.post(() -> handlePageProbe(json));

        }



        @JavascriptInterface

        public void onLiteFeedBinary(final String url) {

            handler.post(() -> handleLiteFeedBinary(url));

        }



        @JavascriptInterface

        public void onHistoryData(final String url, final String json) {

            handler.post(() -> handleHistoryData(url, json));

        }



        @JavascriptInterface

        public void onHistoryFetchFailed(final String reason) {

            handler.post(() -> {

                historyFetchInFlight = false;

                historyFetchFailures++;

                Log.w(TAG, "history fetch failed: " + reason

                        + " (" + historyFetchFailures + "/" + MAX_HISTORY_FETCH_FAILURES + ")");

                if (!loggedIn || !historySyncRequested) return;

                if (historyFetchFailures >= MAX_HISTORY_FETCH_FAILURES) {

                    historySyncRequested = false;

                    Log.w(TAG, "history sync gave up after repeated failures");

                    return;

                }

                // Signer often appears a beat after page load — retry soon.
                handler.postDelayed(() -> {

                    if (!loggedIn || !historySyncRequested || historyFetchInFlight) return;

                    fetchNextHistoryPage();

                }, 1_500);

            });

        }



        @JavascriptInterface

        public void onCreatorPostFetchFailed(final String reason) {

            handler.post(() -> {

                pageFetchInFlight = false;

                creatorRepository.releasePageRequest();

                Log.w(TAG, "creator post fetch failed: " + reason);

                if (!creatorMode || activeCreatorSecUid == null) return;

                if (!creatorNavigatedToProfile) {

                    fallbackLoadCreatorProfilePage(activeCreatorSecUid);

                }

            });

        }

    }



    private void handleHistoryData(String url, String json) {

        historyFetchInFlight = false;

        if (json == null || json.isEmpty() || !json.trim().startsWith("{")) return;

        try {

            handleHistoryData(url, new JSONObject(json.trim()));

        } catch (Exception e) {

            Log.w(TAG, "history data parse failed", e);

        }

    }



    private void handlePageProbe(String json) {

        if (json == null || json.isEmpty()) return;

        try {

            JSONObject obj = new JSONObject(json);

            int w = obj.optInt("w", 0);

            // title/loginHint/captchaHint are what tell apart "douyin served us
            // its real SPA and it just hasn't fetched yet" from "we were handed
            // a verification or blocking page that will never fetch".
            Log.d(TAG, "page probe: " + w + "x" + obj.optInt("h", 0)

                    + " videos=" + obj.optInt("videos", 0)

                    + " hooked=" + obj.optBoolean("hooked", false)

                    + " title=\"" + obj.optString("title", "") + "\""

                    + " loginHint=" + obj.optBoolean("loginHint", false)

                    + " captchaHint=" + obj.optBoolean("captchaHint", false)

                    + " bodyLen=" + obj.optInt("bodyLen", -1)

                    + " snip=\"" + obj.optString("snip", "") + "\""

                    + " err=" + obj.optString("error", ""));

        StartupTrace.mark("probe: href=\"" + obj.optString("href", "")
                + "\" ready=" + obj.optString("ready", "")
                + " title=\"" + obj.optString("title", "")
                + "\" videos=" + obj.optInt("videos", 0)
                + " bodyLen=" + obj.optInt("bodyLen", -1)
                + " captcha=" + obj.optBoolean("captchaHint", false));

            if (w > 0 && w < 320) {

                notifyStatus("视口过小(" + w + "px)，正在尝试修复…");

                repository.releasePageRequest();

                requestNextPage();

            }

        } catch (Exception e) {

            Log.w(TAG, "page probe parse failed: " + json, e);

        }

    }



    private static JSONArray extractAwemeList(JSONObject root) throws org.json.JSONException {

        JSONArray list = root.optJSONArray("aweme_list");

        if (list != null && list.length() > 0) {

            return list;

        }

        JSONArray collections = root.optJSONArray("collection_list");

        if (collections != null && collections.length() > 0) {

            JSONArray merged = new JSONArray();

            for (int i = 0; i < collections.length(); i++) {

                JSONObject item = collections.optJSONObject(i);

                if (item == null) continue;

                JSONArray inner = item.optJSONArray("aweme_list");

                if (inner == null) continue;

                for (int j = 0; j < inner.length(); j++) {

                    merged.put(inner.get(j));

                }

            }

            if (merged.length() > 0) {

                return merged;

            }

        }

        return list;

    }



    private void handleFeedData(String url, String json) {

        if (json == null || json.isEmpty()) return;

        if (isFeedApiUrl(url)) {
            feedApiSeen = true;
        }

        String trimmed = json.trim();

        if (!trimmed.startsWith("{")) {

            if (isFeedApiUrl(url) && repository.bufferSize() == 0) {

                handleLiteFeedBinary(url);

            }

            return;

        }

        try {

            JSONObject root = new JSONObject(trimmed);



            if (isHistoryApiUrl(url)) {

                handleHistoryData(url, root);

                return;

            }



            JSONArray list = extractAwemeList(root);

            // Creator profile posts are not for-you feed; handle before the feed gate.
            if (creatorMode && isCreatorPostUrl(url)) {

                handleCreatorPostData(url, root, list);

                return;

            }

            if (!acceptsAwemeListCapture(url)) {

                return;

            }

            if (creatorMode) {

                return;

            }

            // History/favorite bodies used to be sticky-attributed to module/feed.
            // While history sync is running, drop all-watched "feed" captures so they
            // cannot rewrite for-you pagination cursors.
            if (historyFetchInFlight && list != null && list.length() >= 5) {

                int watchedMarks = 0;

                for (int i = 0; i < list.length(); i++) {

                    JSONObject item = list.optJSONObject(i);

                    if (item == null) continue;

                    String id = item.optString("aweme_id", "");

                    if (FeedVideo.isMarkedWatchedByServer(item)

                            || WatchedAwemeStore.getInstance().isWatched(id)) {

                        watchedMarks++;

                    }

                }

                if (watchedMarks >= list.length()) {

                    Log.d(TAG, "ignoring all-watched feed capture during history fetch");

                    return;

                }

            }

            if (list == null || list.length() == 0) {

                if (isFeedApiUrl(url)) {

                    paginationState.updateFromCapture(url, root);

                    Log.d(TAG, "empty aweme_list from " + url

                            + " status_code=" + root.optInt("status_code", -1));

                    onFeedCaptureComplete();

                    if (paginationState.hasMore() && repository.bufferSize() < PUMP_LOW_WATER_MARK) {

                        handler.postDelayed(this::requestNextPage, 1_000);

                    }

                }

                return;

            }



            paginationState.updateFromCapture(url, root);



            onFeedCaptureComplete();

            cancelKickstart();

            stopHookTicker();

            notifyStatus("已获取视频，准备播放…");

            StartupTrace.mark("feed: first aweme_list captured n=" + list.length()
                    + " apiCallsSeen=" + apiCallsSeen
                    + " blockedSubresources=" + blockedResourceCount);

            // The snapshot for the next cold start is persisted by
            // FeedRepository from the accepted videos, so the disk form is
            // written from data already parsed rather than re-parsing the raw
            // body - see FeedRepository#persistSnapshotForColdStart.
            repository.addAwemeList(list);

        } catch (Exception e) {

            Log.e(TAG, "failed to parse feed json from " + url, e);

            onFeedCaptureComplete();

        }

    }



    private void handleHistoryData(String url, JSONObject root) {

        JSONArray list = root.optJSONArray("aweme_list");

        if (list == null) list = root.optJSONArray("awemeList");



        String batchKey = historyBatchKey(root, list);

        if (!batchKey.isEmpty() && batchKey.equals(lastHistoryBatchKey)) {

            Log.d(TAG, "history sync: duplicate page ignored");

            return;

        }

        if (!batchKey.isEmpty()) lastHistoryBatchKey = batchKey;



        historyFetchFailures = 0;

        int added = 0;

        if (list != null && list.length() > 0) {

            added = WatchedAwemeStore.getInstance().ingestHistoryList(list);

            Log.d(TAG, "history sync: +" + added + "/" + list.length()

                    + " ids, storeSize=" + WatchedAwemeStore.getInstance().size());

            if (added > 0) {

                int purged = repository.purgeWatchedFromQueue();

                if (listener != null) listener.onWatchHistoryUpdated();

                Log.d(TAG, "history purge: queueRemoved=" + purged);

            }

        }

        historyPagesFetched++;

        if (root.has("max_cursor")) {

            long next = root.optLong("max_cursor", 0);

            boolean hasMore = root.optInt("has_more", 0) != 0;

            if (hasMore && next > 0 && next != historyCursor

                    && historyPagesFetched < MAX_HISTORY_PAGES) {

                historyCursor = next;

                // Space the pages out: on a cold start these land in the same
                // window as the first video's initial buffering, and firing
                // them back to back saturated the pump WebView's one network
                // path right when playback needed it.
                handler.postDelayed(this::fetchNextHistoryPage, HISTORY_PAGE_GAP_MS);

                return;

            }

        }

        historySyncRequested = false;

        Log.d(TAG, "history sync complete, pages=" + historyPagesFetched

                + " storeSize=" + WatchedAwemeStore.getInstance().size());

    }



    private static String historyBatchKey(JSONObject root, JSONArray list) {

        long cursor = root.optLong("max_cursor", 0);

        int len = list != null ? list.length() : 0;

        String first = "";

        if (list != null && len > 0) {

            JSONObject item = list.optJSONObject(0);

            if (item != null) {

                first = item.optString("aweme_id", "");

                if (first.isEmpty()) {

                    JSONObject nested = item.optJSONObject("aweme");

                    if (nested == null) nested = item.optJSONObject("aweme_info");

                    if (nested != null) first = nested.optString("aweme_id", "");

                }

            }

        }

        return cursor + ":" + len + ":" + first;

    }



    /**
     * Captures creator works via signed {@code /aweme/post} on the current pump
     * page. Avoids loading the full user SPA (profile/other + hydration) first.
     * Falls back to navigating {@code /user/{sec_uid}} if signing is unavailable
     * (e.g. pump wandered to {@code /user/self}).
     */
    public void loadCreatorProfile(String secUid) {

        if (webView == null || secUid == null || secUid.isEmpty()) return;

        creatorMode = true;

        activeCreatorSecUid = secUid;

        creatorNavigatedToProfile = false;

        creatorPaginationState.reset();

        pageFetchInFlight = false;

        pageFetchRetryCount = 0;

        notifyStatus("正在加载博主作品…");

        // Require a real sign (a_bogus/X-Bogus), not just an acrawler object.
        webView.evaluateJavascript(
                "window.__tvdyCreatorMode=true;"
                        + FeedHookScripts.SIGN_HELPERS
                        + FeedHookScripts.URL_HELPERS
                        + "(function(){try{"
                        + "var p=location.pathname||'';"
                        + "var can=typeof tvdyCanSign==='function'&&tvdyCanSign();"
                        + "var ac=typeof tvdyFindAcrawler==='function'&&!!tvdyFindAcrawler();"
                        + "var onFeed=p==='/'||p.indexOf('jingxuan')>=0||p.indexOf('recommend')>=0;"
                        + "var onSelf=p.indexOf('/user/self')>=0;"
                        + "return JSON.stringify({can:!!can,has:!!ac,onFeed:onFeed,onSelf:onSelf,p:p});"
                        + "}catch(e){return '{}';}})();",
                value -> {
                    if (!creatorMode || activeCreatorSecUid == null) return;
                    boolean canSign = false;
                    boolean hasSigner = false;
                    boolean onFeed = false;
                    boolean onSelf = false;
                    try {
                        String raw = value;
                        if (raw != null && raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
                            raw = new JSONObject("{\"v\":" + value + "}").getString("v");
                        }
                        JSONObject o = new JSONObject(raw != null ? raw : "{}");
                        canSign = o.optBoolean("can", false);
                        hasSigner = o.optBoolean("has", false);
                        onFeed = o.optBoolean("onFeed", false);
                        onSelf = o.optBoolean("onSelf", false);
                        Log.d(TAG, "creator probe path=" + o.optString("p", "")
                                + " canSign=" + canSign + " hasSigner=" + hasSigner
                                + " onFeed=" + onFeed + " onSelf=" + onSelf);
                    } catch (Exception e) {
                        Log.d(TAG, "creator probe parse failed: " + value);
                    }
                    if (canSign) {
                        requestCreatorNextPage();
                    } else {
                        Log.d(TAG, "creator: sign unavailable, load profile SPA");
                        fallbackLoadCreatorProfilePage(activeCreatorSecUid);
                    }
                });

    }



    /** Returns the pump to the global recommend feed after leaving creator browse. */
    public void restoreFeedPump() {

        if (webView == null) return;

        boolean wasOnProfile = creatorNavigatedToProfile;

        creatorMode = false;

        activeCreatorSecUid = null;

        creatorNavigatedToProfile = false;

        creatorPaginationState.reset();

        pageFetchInFlight = false;

        pageFetchRetryCount = 0;

        webView.evaluateJavascript("window.__tvdyCreatorMode=false;", null);

        // Drop any watched items that snuck into the for-you buffer while the
        // WebView visited profile/favorite APIs during creator browse.
        int purged = repository.purgeWatchedFromQueue();

        if (purged > 0 && listener != null) {

            listener.onWatchHistoryUpdated();

        }

        // Only reload feed if we left jingxuan/home for the profile SPA.
        if (wasOnProfile) {

            webView.loadUrl(DouyinConstants.pumpStartUrl(loggedIn));

            scheduleJingxuanFallback();

        }

    }



    public boolean isCreatorMode() {

        return creatorMode;

    }



    public String getActiveCreatorSecUid() {

        return activeCreatorSecUid;

    }



    private void requestCreatorNextPage() {

        if (webView == null || !creatorMode) {
            creatorRepository.releasePageRequest();
            return;
        }

        if (activeCreatorSecUid == null || activeCreatorSecUid.isEmpty()) {
            creatorRepository.releasePageRequest();
            return;
        }

        // Already fetching; repo in-flight flag will clear when the current page completes.
        if (pageFetchInFlight) return;

        pageFetchInFlight = true;

        apiCallsSeenAtNudge = apiCallsSeen;

        pageFetchTimeoutExtensions = 0;

        schedulePageFetchTimeout();

        long cursor = creatorPaginationState.getMaxCursor();

        if (creatorNavigatedToProfile) {

            Log.d(TAG, "nudge creator profile pagination cursor=" + cursor);

            webView.evaluateJavascript(FeedHookScripts.TRIGGER_PAGE_FEED, null);

            return;

        }

        Log.d(TAG, "fetch creator post api cursor=" + cursor);

        webView.evaluateJavascript(

                FeedHookScripts.buildFetchCreatorPostScript(activeCreatorSecUid, cursor),

                null);

    }



    private void fallbackLoadCreatorProfilePage(String secUid) {

        if (webView == null || secUid == null || secUid.isEmpty()) return;

        creatorNavigatedToProfile = true;

        pageFetchInFlight = false;

        pageFetchRetryCount = 0;

        notifyStatus("正在打开博主主页…");

        Log.d(TAG, "fallback load creator profile page");

        webView.loadUrl(DouyinConstants.creatorProfileUrl(secUid));

    }



    private void handleCreatorPostData(String url, JSONObject root, JSONArray list) {

        if (!isCreatorPostForActiveUser(url)) {

            Log.d(TAG, "ignoring creator post for inactive user from " + url);

            onFeedCaptureComplete();

            return;

        }

        // Reject mis-attributed bodies (e.g. favorites JSON.parse under last post URL).
        if (list != null && list.length() > 0 && !listMatchesActiveCreator(list)) {

            Log.d(TAG, "ignoring post body author mismatch from " + url);

            onFeedCaptureComplete();

            return;

        }

        creatorPaginationState.updateFromCapture(url, root);

        creatorRepository.setHasMore(creatorPaginationState.hasMore());

        onFeedCaptureComplete();

        if (list != null && list.length() > 0) {

            Log.d(TAG, "creator post list from " + url + " count=" + list.length());

            notifyStatus("已加载博主作品…");

            creatorRepository.addAwemeList(list);

        } else if (creatorPaginationState.hasMore()) {

            handler.postDelayed(this::requestCreatorNextPage, 1_000);

        }

    }

    private boolean listMatchesActiveCreator(JSONArray list) {
        if (activeCreatorSecUid == null || activeCreatorSecUid.isEmpty() || list == null) {
            return false;
        }
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null) continue;
            JSONObject author = item.optJSONObject("author");
            if (author == null) continue;
            String sec = author.optString("sec_uid", author.optString("sec_user_id", ""));
            if (activeCreatorSecUid.equals(sec)) {
                return true;
            }
        }
        return false;
    }



    private static boolean isCreatorPostUrl(String url) {

        return url != null && url.contains("/aweme/post");

    }



    private boolean isCreatorPostForActiveUser(String url) {

        if (!creatorMode || activeCreatorSecUid == null || activeCreatorSecUid.isEmpty()) {

            return false;

        }

        if (url == null) return false;

        try {

            android.net.Uri uri = android.net.Uri.parse(url);

            String secUserId = uri.getQueryParameter("sec_user_id");

            if (secUserId != null && !secUserId.isEmpty()) {

                return activeCreatorSecUid.equals(secUserId);

            }

        } catch (Exception ignored) {

        }

        return true;

    }

}


