package mulin.tvdy.player;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.airbnb.lottie.LottieAnimationView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.ExoPlayer.PreloadConfiguration;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.ui.PlayerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import mulin.tvdy.DeviceUtils;
import mulin.tvdy.DouyinConstants;
import mulin.tvdy.R;
import mulin.tvdy.StartupTrace;
import mulin.tvdy.auth.CookieHandoffServer;
import mulin.tvdy.auth.CookieImportHelper;
import mulin.tvdy.auth.LoginStatusChecker;
import mulin.tvdy.auth.QrCodeGenerator;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import mulin.tvdy.data.CreatorVideoRepository;
import mulin.tvdy.data.FeedRepository;
import mulin.tvdy.data.FeedSnapshotStore;
import mulin.tvdy.data.FeedVideo;
import mulin.tvdy.data.WatchedAwemeStore;
import mulin.tvdy.pump.FeedPumpController;

/**
 * Sole screen of the app: plays videos supplied by {@link FeedRepository}
 * with native ExoPlayer, and owns the hidden {@link FeedPumpController} that
 * keeps that repository fed for the entire lifetime of this Activity (i.e.
 * the entire time the app is in the foreground).
 * <p>
 * Navigation: DPAD up/down moves between videos (with a TikTok-style slide
 * transition); left/right seek {@value #SEEK_STEP_MS}ms back/forward; OK/
 * center is an overloaded "start" button - see {@link #onStartPressed} for
 * its show-overlay/play/pause semantics - and a long-press on it opens a
 * (currently placeholder) feature menu, closed with back. All chrome (title/
 * author up top, time/progress/stats at the bottom) auto-hides after
 * {@value #CONTROLS_AUTO_HIDE_MS}ms - see {@link #showControls}.
 * <p>
 * Playback uses a small ExoPlayer playlist window (see {@link #playlist})
 * rather than swapping a single {@link MediaItem} in place: the next
 * {@value #PRELOAD_AHEAD} video(s) are added to the player ahead of time so
 * ExoPlayer starts buffering them in the background while the current one is
 * still playing, and "next"/"previous" just seek across that already-loaded
 * window instead of cold-starting a new fetch.
 */
public class PlayerActivity extends Activity implements FeedRepository.Listener {

    private enum PlaybackMode {
        FEED,
        CREATOR
    }

    private static final String TAG = "PlayerActivity";

    /** Separate tag so a codec trace can be grepped without the rest of logcat. */
    private static final String CODEC_TAG = "PlayerCodec";
    /** Upcoming items to buffer; kept small so preload does not starve the active stream. */
    private static final int PRELOAD_AHEAD = 2;
    /**
     * Buffer target for the next playlist item while playing (µs).
     * <p>
     * Deliberately a few seconds, not the 90s this used to be. The old value
     * meant up to {@code PRELOAD_AHEAD} × 90s of neighbour video downloading
     * alongside the current item's own {@link #MAX_BUFFER_MS} on a TV box whose
     * link is also carrying the WebView's SPA boot. The current video's track
     * lost that race, so the picture froze while the (much smaller) audio track
     * stayed ahead - the reported "video stutters, sound is fine". A short
     * head-start is still enough to make a transition seamless; it just no
     * longer needs to beat the stream the user is actually watching.
     */
    private static final long PRELOAD_FULL_US = 8_000_000L;
    /** While rebuffering, stop ahead-preload so bandwidth goes to the current item. */
    private static final long PRELOAD_REBUFFER_US = 0L;
    /** Min media duration ExoPlayer tries to keep buffered while playing. */
    private static final int MIN_BUFFER_MS = 15_000;
    /** Max media duration ExoPlayer will buffer ahead of the playhead. */
    private static final int MAX_BUFFER_MS = 30_000;
    /** Min buffered media required before initial start / user seek resume. */
    private static final int BUFFER_FOR_PLAYBACK_MS = 2_000;
    /**
     * Min buffered media required to resume after a network stall. Larger values
     * avoid the "buffer a little → play → stall again" loop on slow links.
     */
    private static final int BUFFER_AFTER_REBUFFER_MS = 6_000;
    private static final int HISTORY_KEEP = 3;
    private static final long SEEK_STEP_MS = 5_000;
    /** Delay before repeated seeks begin while a direction key is held. */
    private static final long SEEK_REPEAT_INITIAL_MS = 400;
    /** Interval between repeated seeks while the key stays down. */
    private static final long SEEK_REPEAT_INTERVAL_MS = 300;
    private static final int SLIDE_DURATION_MS = 280;
    /** Max wait for the incoming item to buffer before starting the slide. */
    private static final long INCOMING_BUFFER_WAIT_MS = 450;
    /** Min buffered ahead on the incoming item before we slide (unless timed out). */
    private static final long MIN_INCOMING_BUFFER_MS = 800;
    /** Max wait for the incoming cover before sliding anyway (black mask until loaded). */
    private static final long INCOMING_COVER_WAIT_MS = 450;
    private static final long CONTROLS_AUTO_HIDE_MS = 3_000;
    /** Window for the "press back again to exit" confirmation. */
    private static final long EXIT_CONFIRM_MS = 2_000;
    private static final long CENTER_LONG_PRESS_MS = 500;
    private static final long PROGRESS_UPDATE_INTERVAL_MS = 500;
    /** If no video after this long, auto-retry (TV needs much longer than emulator). */
    /** Intent extra that turns the pump into a no-injection control run. */
    private static final String EXTRA_SKIP_HOOKS = "tvdy_skip_hooks";
    /** Diagnostic override for the 精选 fallback window; negative disables it. */
    private static final String EXTRA_JINGXUAN_FALLBACK_MS = "tvdy_jingxuan_fallback_ms";
    /** Diagnostic: sample page state every 2s regardless of pump progress. */
    private static final String EXTRA_PERIODIC_PROBE = "tvdy_periodic_probe";
    /** Diagnostic: bypass the media byte cache to A/B a colour problem. */
    private static final String EXTRA_NO_MEDIA_CACHE = "tvdy_no_media_cache";
    private static final long STARTUP_TIMEOUT_PHONE_MS = 45_000;
    private static final long STARTUP_TIMEOUT_TV_MS = 120_000;
    private static final int MAX_STARTUP_RETRIES = 3;
    /** Minimum play time before a skipped video counts as "watched" for filtering. */
    private static final long WATCHED_THRESHOLD_MS = 3_000;
    private static final int HASHTAG_COLOR = Color.parseColor("#7CB2FF");
    private static final Pattern HASHTAG_PATTERN = Pattern.compile("#[^\\s#]+");

    private static final int CREATOR_GRID_COLUMNS = 4;

    private final FeedRepository repository = FeedRepository.getInstance();
    private final CreatorVideoRepository creatorRepository = CreatorVideoRepository.getInstance();
    private final Handler handler = new Handler(Looper.getMainLooper());

    /**
     * Mirrors the ExoPlayer timeline 1:1 - index {@code i} here is
     * {@code MediaItem} index {@code i} in the player. Kept separately
     * because we need the full {@link FeedVideo} (desc/author/counts/etc.),
     * not just the playable uri, for whichever item the player lands on.
     */
    private final List<FeedVideo> playlist = new ArrayList<>();
    private int playlistIndex = -1;

    /**
     * Remembers, per {@code awemeId}, the last playback position a video was
     * left at when the user navigated away from it mid-playback - so scrolling
     * back to a previously-watched video in the same session resumes where it
     * left off instead of restarting at 0 (ExoPlayer's playlist navigation
     * has no such memory on its own: {@code seekToNextMediaItem}/
     * {@code seekToPreviousMediaItem} always land on each window's default
     * position, i.e. 0). Videos that finish naturally (see
     * {@code STATE_ENDED} handling in {@link #advance}) are deliberately not
     * remembered here, so a completed video replays from the start next time.
     * In-memory only - not persisted across app restarts, and entries are
     * dropped once their video ages out of {@link #playlist} (see
     * {@link #refillPlaylist}) since they can no longer be navigated back to.
     */
    private final Map<String, Long> resumePositions = new HashMap<>();
    /** Next index into {@link FeedVideo#playUrlCandidates} to try after CDN 403. */
    private final Map<String, Integer> playUrlCandidateIndex = new HashMap<>();
    /** Next index into {@link FeedVideo#splitCandidates}; -1 means fall back to muxed. */
    private final Map<String, Integer> splitCandidateIndex = new HashMap<>();

    private FeedPumpController pump;
    private DouyinHttpDataSource.Factory httpDataSourceFactory;
    private ProgressiveMediaSource.Factory progressiveSourceFactory;
    private ExoPlayer player;
    private View splashOverlay;
    private LottieAnimationView splashAnimation;
    private TextView splashStatusText;
    /** See {@link #hideSplashIfNeeded} - guards against re-running the fade-out on later videos' first frames. */
    private boolean splashVisible = true;
    private boolean startupTimedOut = false;
    private int startupRetryCount = 0;
    /**
     * Videos in the initial playlist that came off the disk snapshot rather
     * than the pump. Their signed CDN links may already be stale, in which case
     * every candidate 403s and {@link #onPlayerError} would otherwise walk the
     * whole queue skipping failures - a user-visible stutter of black frames
     * before the pump's fresh data arrives. See {@link #onPlayerError}.
     */
    private int snapshotSourcedItems = 0;

    /** 上次会话的快照，仅作断网兜底；正常启动时绝不自动续播。 */
    private java.util.List<mulin.tvdy.data.FeedVideo> offlineFallbackVideos;
    private long startupTimeoutMs;
    private boolean pendingStartupLoginCheck = false;
    private FrameLayout slideHost;
    private VideoSlidePage pageA;
    private VideoSlidePage pageB;
    /** Outgoing page during a slide (always {@link #pageA} – owns the player). */
    private VideoSlidePage pageOut;
    /** Incoming cover page during a slide (always {@link #pageB}, no player). */
    private VideoSlidePage pageIn;
    /** The page that currently owns the ExoPlayer surface. */
    private VideoSlidePage playerPage;
    private boolean slideAnimating = false;
    /** Next/previous press absorbed while a slide is still running. */
    private int queuedAdvanceDuringSlide = 0;
    /** First frame arrived while the slide animation was still running. */
    private boolean pendingCoverDismiss = false;
    /** Convenience alias – always {@link #pageOut}'s views while idle. */
    private PlayerView playerView;
    private ImageView backdropImage;
    private ImageView coverImage;
    private LottieAnimationView loadingSpinner;
    private View pauseIcon;
    private View topPanel;
    private View bottomPanel;
    private TextView titleText;
    private TextView authorText;
    private TextView musicText;
    private ImageView avatarImage;
    private TextView positionText;
    private TextView durationText;
    private ProgressBar seekProgress;
    private ImageView likeIcon;
    private TextView likeCountText;
    private TextView commentCountText;
    private TextView collectCountText;
    private View featureMenu;
    private final List<View> menuItems = new ArrayList<>();
    private int menuSelectedIndex = 0;
    private View featureMenuAccountHeader;
    private View featureMenuAccountDivider;
    private ImageView featureMenuAccountAvatar;
    private TextView featureMenuAccountName;
    private View featureMenuLogin;
    private TextView featureMenuLoginText;
    private View featureMenuLogout;
    private View cookieLoginOverlay;
    private View handoffRow;
    private ImageView handoffQrImage;
    private TextView handoffUrlText;
    private TextView handoffStatusText;
    private boolean cookieLoginVisible = false;
    /** See {@link #applyCookieAndFinish} - blocks re-entrant submissions while one is mid-verification. */
    private boolean verifyingLogin = false;
    private CookieHandoffServer cookieHandoffServer;
    /**
     * The account confirmed by the most recent successful
     * {@link LoginStatusChecker} run (initial login, a manual "检测登录状态",
     * or the silent startup check) - see {@link #setLoginState}. {@code
     * null} fields whenever {@link #loggedIn} is {@code false}.
     */
    private boolean loggedIn = false;
    private String loggedInNickname;
    private String loggedInAvatarUrl;
    private FeedVideo current;
    private boolean waitingForBuffer = false;
    /** When false, newly-arrived feed data is queued/preloaded but not auto-played. */
    private boolean autoStartWhenReady = true;
    /** Set when the user pressed next/previous but the feed buffer was empty. */
    private int pendingAdvance = 0;
    private boolean controlsVisible = false;
    private boolean featureMenuVisible = false;
    private boolean centerLongPressFired = false;
    /** See {@link #dispatchBackKey} for why this needs to survive across a single press's DOWN and UP. */
    private boolean backConsumedSpecially = false;
    private long lastBackPressForExitMs = 0;

    private PlaybackMode playbackMode = PlaybackMode.FEED;
    private boolean creatorGridVisible = false;
    private View creatorGridOverlay;
    private TextView creatorGridTitle;
    private TextView creatorGridCount;
    private View creatorGridContent;
    private View creatorGridLoadingPanel;
    private LottieAnimationView creatorGridLoading;
    private boolean creatorGridAwaitingPumpData;
    private RecyclerView creatorGridList;
    private CreatorGridAdapter creatorGridAdapter;
    private FeedVideo feedAnchorVideo;
    private long feedAnchorPositionMs;
    /** Ignores stale end-of-item events while rebuilding feed anchor playlist. */
    private long ignorePlaybackEndedUntilMs = 0;
    /** True while a natural-end auto-advance is already queued (END_OF_MEDIA_ITEM + STATE_ENDED). */
    private boolean playbackEndedAdvancePending = false;

    private final Runnable hideControlsRunnable = this::hideControls;
    private final Runnable startupTimeoutRunnable = this::onStartupTimeout;
    private final Runnable longPressRunnable = this::openFeatureMenu;
    private long seekRepeatDeltaMs = 0;
    private final Runnable seekRepeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (seekRepeatDeltaMs == 0) return;
            seekBy(seekRepeatDeltaMs);
            showControls();
            handler.postDelayed(this, SEEK_REPEAT_INTERVAL_MS);
        }
    };
    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            updateProgressUi();
            handler.postDelayed(this, PROGRESS_UPDATE_INTERVAL_MS);
        }
    };

    @Override
    @OptIn(markerClass = UnstableApi.class)
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        StartupTrace.mark("PlayerActivity.onCreate enter");
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        enableImmersiveMode();
        setContentView(R.layout.activity_player);
        StartupTrace.mark("onCreate: setContentView done");

        splashOverlay = findViewById(R.id.splashOverlay);
        splashAnimation = findViewById(R.id.splashAnimation);
        splashStatusText = findViewById(R.id.splashStatusText);
        slideHost = findViewById(R.id.slideHost);
        pageA = new VideoSlidePage(findViewById(R.id.slidePageA));
        pageB = new VideoSlidePage(findViewById(R.id.slidePageB));
        pageOut = pageA;
        pageIn = pageB;
        playerPage = pageA;
        playerView = pageA.playerView;
        coverImage = pageA.coverImage;
        backdropImage = pageA.backdropImage;
        initSlidePages();
        StartupTrace.mark("onCreate: slide pages ready");
        loadingSpinner = findViewById(R.id.loadingSpinner);
        pauseIcon = findViewById(R.id.pauseIcon);
        topPanel = findViewById(R.id.topPanel);
        bottomPanel = findViewById(R.id.bottomPanel);
        titleText = findViewById(R.id.titleText);
        authorText = findViewById(R.id.authorText);
        musicText = findViewById(R.id.musicText);
        avatarImage = findViewById(R.id.avatarImage);
        positionText = findViewById(R.id.positionText);
        durationText = findViewById(R.id.durationText);
        seekProgress = findViewById(R.id.seekProgress);
        likeIcon = findViewById(R.id.likeIcon);
        likeCountText = findViewById(R.id.likeCount);
        commentCountText = findViewById(R.id.commentCount);
        collectCountText = findViewById(R.id.collectCount);
        featureMenu = findViewById(R.id.featureMenu);
        featureMenuAccountHeader = findViewById(R.id.featureMenuAccountHeader);
        featureMenuAccountDivider = findViewById(R.id.featureMenuAccountDivider);
        featureMenuAccountAvatar = findViewById(R.id.featureMenuAccountAvatar);
        featureMenuAccountName = findViewById(R.id.featureMenuAccountName);
        featureMenuLogin = findViewById(R.id.featureMenuLogin);
        featureMenuLoginText = findViewById(R.id.featureMenuLoginText);
        featureMenuLogout = findViewById(R.id.featureMenuLogout);

        cookieLoginOverlay = findViewById(R.id.cookieLoginOverlay);
        handoffRow = findViewById(R.id.handoffRow);
        handoffQrImage = findViewById(R.id.handoffQrImage);
        handoffUrlText = findViewById(R.id.handoffUrlText);
        handoffStatusText = findViewById(R.id.handoffStatusText);

        makeCircular(avatarImage);
        makeCircular(featureMenuAccountAvatar);
        rebuildMenuItems();

        setupCreatorGrid();
        StartupTrace.mark("onCreate: creator grid ready");

        WatchedAwemeStore.getInstance().init(this);
        StartupTrace.mark("onCreate: watched store ready");
        primeRepositoryFromSnapshot();

        // Only re-verify a saved session on startup; anonymous use is the
        // default and never prompts the user to log in.
        if (hasSavedSession()) {
            pendingStartupLoginCheck = true;
        }

        startupTimeoutMs = DeviceUtils.isTelevision(this)
                ? STARTUP_TIMEOUT_TV_MS
                : STARTUP_TIMEOUT_PHONE_MS;
        scheduleStartupTimeout();

        // Start the feed pump before ExoPlayer setup so douyin.com begins
        // loading while the player stack is still being constructed (matters
        // on CPU-starved TV boxes where both compete for the same cores).
        // Diagnostic A/B for the pump-slowness investigation: `am start --ez
        // tvdy_skip_hooks true` runs the pump page with no injected JavaScript
        // at all, so its boot can be compared against the normal run. Note the
        // `--ez`: plain `-e` passes a String and getBooleanExtra then parses
        // only "true", so `-e tvdy_skip_hooks 1` silently reads as false.
        FeedPumpController.skipHookInjection =
                getIntent().getBooleanExtra(EXTRA_SKIP_HOOKS, false);
        FeedPumpController.jingxuanFallbackMsOverride =
                getIntent().getIntExtra(EXTRA_JINGXUAN_FALLBACK_MS, 0);
        FeedPumpController.periodicProbe =
                getIntent().getBooleanExtra(EXTRA_PERIODIC_PROBE, false);
        if (FeedPumpController.skipHookInjection) {
            StartupTrace.mark("DIAG: hook injection DISABLED");
        }

        // Must run before the player (and therefore the data source factory) is
        // built, since the factory reads this when it creates each source.
        MediaCacheStore.setDisabled(getIntent().getBooleanExtra(EXTRA_NO_MEDIA_CACHE, false));
        if (MediaCacheStore.isDisabled()) {
            StartupTrace.mark("DIAG: media cache DISABLED");
        }

        pump = new FeedPumpController(this);
        pump.setListener(new FeedPumpController.Listener() {
            @Override
            public void onPumpStatus(String message) {
                updateSplashStatus(message);
            }

            @Override
            public void onPumpError(String message) {
                updateSplashStatus(message);
                Log.w(TAG, "pump error: " + message);
            }

            @Override
            public void onWatchHistoryUpdated() {
                purgeWatchedFromUpcomingPlaylist();
            }
        });
        if (hasSavedSession()) {
            pump.setLoggedIn(true);
            syncLoginUiWithSession();
        }
        pump.start((ViewGroup) findViewById(R.id.playerRoot));

        // Session cookies are injected per-request in DouyinHttpDataSource.
        httpDataSourceFactory = new DouyinHttpDataSource.Factory(this);
        progressiveSourceFactory = new ProgressiveMediaSource.Factory(httpDataSourceFactory);

        player = new ExoPlayer.Builder(this, buildRenderersFactory())
                .setMediaSourceFactory(new DefaultMediaSourceFactory(httpDataSourceFactory))
                .setLoadControl(buildLoadControl())
                .setAudioAttributes(
                        new AudioAttributes.Builder()
                                .setUsage(C.USAGE_MEDIA)
                                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                                .build(),
                        // false: don't let ExoPlayer auto-duck/pause on
                        // transient system audio-focus events. Turning this
                        // on briefly to fight WebView audio bleed instead
                        // caused sound to silently drop out mid-playback
                        // (a focus-loss "duck" that never got reversed) -
                        // net worse than the problem it was meant to fix.
                        // The WebView-side mitigations in FeedHookScripts
                        // (muting <video>/<audio>, suspending AudioContext)
                        // are the real fix for that; this doesn't need to
                        // also fight over system audio focus.
                        /* handleAudioFocus= */ false)
                .build();
        player.setPreloadConfiguration(new PreloadConfiguration(PRELOAD_FULL_US));
        player.setRepeatMode(Player.REPEAT_MODE_OFF);
        player.setPauseAtEndOfMediaItems(true);

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_READY && splashVisible && !playlist.isEmpty()) {
                    hideSplashIfNeeded();
                }
                if (playbackState == Player.STATE_READY && !slideAnimating) {
                    refillPlaylist();
                }
                if (playbackState == Player.STATE_BUFFERING && player.getPlayWhenReady()) {
                    // Mid-playback rebuffer: the video track ran dry while the
                    // audio track kept going (they are separate sources), which
                    // the user sees as "picture freezes, sound keeps playing".
                    // Logged with the buffered span so the cause is visible -
                    // a starving stream shows a buffer that never climbs.
                    Log.i(CODEC_TAG, "REBUFFER at " + player.getCurrentPosition()
                            + "ms buffered=" + (player.getBufferedPosition()
                            - player.getCurrentPosition()) + "ms"
                            + " awemeId=" + (current != null ? current.awemeId : "?"));
                    player.setPreloadConfiguration(new PreloadConfiguration(PRELOAD_REBUFFER_US));
                } else if (playbackState == Player.STATE_READY && player.isPlaying()) {
                    adjustPreloadForPlay();
                }
                if (playbackState == Player.STATE_ENDED) {
                    // Fires for the final playlist item. Mid-playlist ends are
                    // delivered via onPlayWhenReadyChanged(END_OF_MEDIA_ITEM)
                    // instead — pauseAtEndOfMediaItems keeps those in READY.
                    handlePlaybackEnded();
                    return;
                }
                updatePlaybackChrome();
                updateProgressUi();
            }

            @Override
            public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                // With pauseAtEndOfMediaItems, finishing a non-final playlist
                // item only clears playWhenReady — STATE_ENDED is NOT set.
                // That is the normal "video finished" signal for auto-next.
                if (!playWhenReady
                        && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                    handlePlaybackEnded();
                    return;
                }
                updatePlaybackChrome();
            }

            @Override
            public void onMediaItemTransition(MediaItem mediaItem, int reason) {
                // Natural end-of-video is handled via END_OF_MEDIA_ITEM /
                // STATE_ENDED + playNext() so we can run the dual-page slide.
                // Seek-driven transitions are handled in advance().
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                updatePlaybackChrome();
                if (isPlaying) {
                    adjustPreloadForPlay();
                    scheduleCoverDismissFallback();
                }
            }

            @Override
            public void onRenderedFirstFrame() {
                StartupTrace.mark("player: FIRST FRAME rendered");
                if (slideAnimating) {
                    pendingCoverDismiss = true;
                    return;
                }
                dismissCoverOnPlayerPage();
                hideSplashIfNeeded();
                if (pump != null) pump.notifyPlaybackStarted();
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                Log.w(TAG, "playback error for " + (current != null ? current.awemeId : "?")
                        + " mode=" + playbackModeLabel(current)
                        + " url=" + playbackUrlFor(current)
                        + " errorCode=" + error.errorCode, error);
                if (retryCurrentWithNextCandidate()) return;
                // A snapshot item whose candidates are all exhausted is not
                // worth advancing past: the whole snapshot shares one capture
                // time, so its siblings' links are stale too. Dropping the
                // queue and waiting for the pump beats flicking through
                // twenty black frames first.
                if (splashVisible && snapshotSourcedItems > 0) {
                    abandonSnapshotAndWaitForPump();
                    return;
                }
                playNext();
            }
        });

        // Codec-level tracing for the "some videos render solid green after
        // the app has been open a while" report. A green frame is what a
        // decoder writes when its output surface or its chroma planes are not
        // usable, so the question that settles it is *which* decoder produced
        // it: the same hardware decoder that renders every healthy video (a
        // surface/reuse problem), a hardware HEVC decoder (a bad HEVC stream or
        // a broken box decoder), or a software decoder Media3 fell back to
        // because hardware codec instances were exhausted (a resource
        // problem). Those need three different fixes and the player logs none
        // of it by default.
        player.addAnalyticsListener(new AnalyticsListener() {
            @Override
            public void onVideoInputFormatChanged(EventTime eventTime, Format format,
                                                  @Nullable DecoderReuseEvaluation reuse) {
                Log.i(CODEC_TAG, "input format mime=" + format.sampleMimeType
                        + " " + format.width + "x" + format.height
                        + " bitrate=" + format.bitrate
                        + " reuseResult=" + (reuse != null ? reuse.result : -1)
                        + " awemeId=" + (current != null ? current.awemeId : "?"));
            }

            @Override
            public void onVideoDecoderInitialized(EventTime eventTime, String decoderName,
                                                  long initializedTimestampMs,
                                                  long initializationDurationMs) {
                Log.i(CODEC_TAG, "decoder=" + decoderName
                        + " kind=" + decoderKind(decoderName)
                        + " initMs=" + initializationDurationMs
                        + " awemeId=" + (current != null ? current.awemeId : "?"));
            }

            @Override
            public void onVideoDecoderReleased(EventTime eventTime, String decoderName) {
                Log.i(CODEC_TAG, "decoder released=" + decoderName);
            }

            @Override
            public void onDroppedVideoFrames(EventTime eventTime, int droppedFrames,
                                             long elapsedMs) {
                Log.w(CODEC_TAG, "dropped frames=" + droppedFrames + " in " + elapsedMs + "ms");
            }
        });

        playerView.setPlayer(player);

        repository.addListener(this);
        creatorRepository.addListener(this::onCreatorVideosChanged);
        // 原生直连推荐流先走(1-2秒)，数据泵随后补充个性化内容。
        fetchNativeFeedInitial();
        startInitialPlayback();
        StartupTrace.mark("PlayerActivity.onCreate exit");
    }

    /**
     * Classifies a MediaCodec name as hardware or software. Media3 picks from
     * {@link android.media.MediaCodecList} in order, so a fallback after a
     * hardware decoder fails shows up here as a sudden switch to a
     * {@code *.sw.*} / {@code c2.android.*} / {@code OMX.google.*} name.
     */
    private static String decoderKind(String decoderName) {
        if (decoderName == null) return "unknown";
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
                    if (decoderName.equals(info.getName())) {
                        return info.isHardwareAccelerated() ? "hardware" : "software";
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        String lower = decoderName.toLowerCase(Locale.US);
        if (lower.startsWith("c2.android.") || lower.startsWith("omx.google.")
                || lower.contains(".sw.")) {
            return "software";
        }
        return "hardware?";
    }

    /** Keeps the live player on page A; page B is cover-only staging off-screen. */
    private void initSlidePages() {
        pageB.detachPlayer();
        pageA.attachPlayer(player);
        pageA.root.bringToFront();
        Runnable parkIncoming = () -> {
            int height = slideHost.getHeight();
            if (height > 0) {
                pageB.stageForSlide(height);
            }
        };
        if (slideHost.getHeight() > 0) {
            parkIncoming.run();
        } else {
            slideHost.post(parkIncoming);
        }
    }

    private void showActivePage() {
        pageOut.root.bringToFront();
    }

    @OptIn(markerClass = UnstableApi.class)
    private DefaultLoadControl buildLoadControl() {
        return new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        MIN_BUFFER_MS,
                        MAX_BUFFER_MS,
                        BUFFER_FOR_PLAYBACK_MS,
                        BUFFER_AFTER_REBUFFER_MS)
                .build();
    }

    /**
     * Builds a {@link RenderersFactory} that requests a larger-than-default
     * {@code AudioTrack} buffer for PCM audio. Logcat captured during a
     * reported "crackle then silence" episode showed repeated {@code
     * AudioTrack} {@code "device stall time corrected"} warnings - the audio
     * HAL occasionally reporting an unreliable playback timestamp, which
     * points at the audio driver rather than app/decoding logic. A bigger
     * buffer can't fix an unstable HAL outright, but it gives playback more
     * slack to absorb those brief stalls before they turn into an audible
     * crackle/dropout.
     */
    @OptIn(markerClass = UnstableApi.class)
    private RenderersFactory buildRenderersFactory() {
        return new DefaultRenderersFactory(this) {
            @OptIn(markerClass = UnstableApi.class)
            @Override
            protected AudioSink buildAudioSink(
                    Context context, boolean enableFloatOutput, boolean enableAudioTrackPlaybackParams) {
                DefaultAudioTrackBufferSizeProvider bufferSizeProvider =
                        new DefaultAudioTrackBufferSizeProvider.Builder()
                                .setPcmBufferMultiplicationFactor(8) // default is 4
                                .setMinPcmBufferDurationUs(500_000) // default is 250_000us (250ms)
                                .setMaxPcmBufferDurationUs(1_500_000) // default is 750_000us (750ms)
                                .build();
                return new DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .setAudioTrackBufferSizeProvider(bufferSizeProvider)
                        .build();
            }
        };
    }

    /**
     * Fades out and tears down the startup splash the first time a video
     * actually has a frame on screen - see {@link #splashOverlay}'s XML
     * comment for why that (not e.g. a fixed delay) is the trigger. A no-op
     * on every call after the first, since {@code onRenderedFirstFrame}
     * fires again for every later video, not just the initial one.
     */
    private void hideSplashIfNeeded() {
        if (!splashVisible) return;
        splashVisible = false;
        cancelStartupTimeout();
        // Note in the timeline: this is where the third WebView (login check)
        // and the watch-history sync land, i.e. right on top of the first
        // video's initial buffering.
        StartupTrace.mark("startup login check + history sync kick off");
        maybeRunDeferredLoginCheck();
        splashOverlay.animate()
                .alpha(0f)
                .setDuration(400)
                .withEndAction(() -> {
                    splashOverlay.setVisibility(View.GONE);
                    splashAnimation.cancelAnimation();
                })
                .start();
    }

    /**
     * 原生直连推荐流(免 WebView)：onCreate 立即调用，通常 1-2 秒返回，
     * 是「抖音 App 式秒进」的第一内容来源。数据泵(需 10-30 秒启动 SPA)之后
     * 到达的个性化内容会进入同一队列去重追加，两者互不冲突。
     * <p>
     * 拉取成功后 {@link FeedRepository#addVideosBlocking} 会触发
     * {@link #onBufferChanged}，由既有逻辑自动开始播放，无需在此直接开播。
     */
    private void fetchNativeFeedInitial() {
        new Thread(() -> {
            final java.util.List<mulin.tvdy.data.FeedVideo> videos =
                    mulin.tvdy.data.NativeFeedClient.fetch(10);
            if (videos == null || videos.isEmpty()) return;
            handler.post(() -> {
                try {
                    repository.addVideosBlocking(videos);
                    StartupTrace.mark("startup: native feed applied n=" + videos.size()
                            + " buffer=" + repository.bufferSize());
                } catch (Exception e) {
                    Log.w(TAG, "native feed apply failed", e);
                }
            });
        }, "native-feed-initial").start();
    }

    /**
     * 快照不再用于冷启动「秒开续播」，而是留作断网兜底。
     * <p>
     * 之前的行为是把上次会话的视频立刻灌进队列播放，用户每次打开看到的都是
     * 退出前的画面，体验不像抖音「打开就有新推荐」。现在改为：冷启动始终等
     * 数据泵拉取最新推荐流；只有在启动超时（通常意味着断网/抖音不可达）时，
     * 才把快照灌进队列兜底播放。
     */
    private void primeRepositoryFromSnapshot() {
        try {
            java.util.List<mulin.tvdy.data.FeedVideo> videos = FeedSnapshotStore.read(this);
            if (videos == null || videos.isEmpty()) return;
            // 只暂存不灌入：等 onStartupTimeout 判断确实拿不到新数据时再用。
            offlineFallbackVideos = videos;
            StartupTrace.mark("startup: snapshot kept as offline fallback n=" + videos.size());
        } catch (Exception e) {
            Log.w(TAG, "snapshot priming failed", e);
        }
    }

    /**
     * 启动超时时的断网兜底：数据泵一直没能产出视频（断网/抖音不可达），
     * 而本地留有上次会话的快照，就先播快照，聊胜于无。
     *
     * @return {@code true} 表示已用快照兜底启动播放，调用方无需继续重试。
     */
    private boolean tryStartFromOfflineFallback() {
        if (offlineFallbackVideos == null || offlineFallbackVideos.isEmpty()) return false;
        if (repository.bufferSize() > 0) return false;
        Log.w(TAG, "pump timed out, falling back to offline snapshot n="
                + offlineFallbackVideos.size());
        StartupTrace.mark("startup: using offline snapshot fallback");
        try {
            repository.addVideosBlocking(offlineFallbackVideos);
        } catch (Exception e) {
            Log.w(TAG, "offline fallback priming failed", e);
            return false;
        }
        offlineFallbackVideos = null;
        snapshotSourcedItems = repository.bufferSize();
        startInitialPlayback();
        return playlistIndex >= 0;
    }

    /**
     * Gives up on the loaded snapshot and hands the cold start back to the
     * pump: the snapshot's signed links are stale, so nothing in it can play.
     * <p>
     * {@code repository.reset()} is required rather than optional - priming
     * pushed the snapshot's aweme ids into the repository's dedup set, so the
     * pump's fresh capture of the <em>same</em> videos would otherwise be
     * dropped as duplicates and the app would sit on an empty queue forever.
     */
    private void abandonSnapshotAndWaitForPump() {
        Log.w(TAG, "snapshot links unusable, falling back to the pump; dropped="
                + snapshotSourcedItems);
        StartupTrace.mark("startup: snapshot abandoned after player error");
        snapshotSourcedItems = 0;
        repository.reset();
        playlist.clear();
        playlistIndex = -1;
        current = null;
        resumePositions.clear();
        pendingAdvance = 0;
        waitingForBuffer = true;
        autoStartWhenReady = true;
        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
        updateSplashStatus("正在获取最新推荐…");
        // Re-arm the timeout: the splash has been up since onCreate and the
        // pump now has to fetch from scratch.
        scheduleStartupTimeout();
        if (pump != null) {
            // reloadFeed() re-runs the page pipeline in place, reusing the warm
            // renderer and its cached bytecode. hardRestart() throws the
            // WebView away and would pay the ~12.6s SPA boot again for no gain
            // here, since the failure is a stale signature rather than a
            // wedged page.
            pump.reloadFeed();
        }
    }

    private void startInitialPlayback() {
        refillPlaylist();
        if (playlist.isEmpty()) {
            waitingForBuffer = true;
            autoStartWhenReady = true;
            updatePlaybackChrome();
            return;
        }
        playlistIndex = 0;
        refillPlaylist();
        applyCurrentUi();
        Log.d(TAG, "startInitialPlayback awemeId=" + current.awemeId
                + " bufferRemaining=" + repository.bufferSize()
                + " cookieLen=" + DouyinConstants.playbackCookieLength()
                + " candidates=" + current.playUrlCandidates.size()
                + " url=" + playbackUrlFor(current));
        StartupTrace.mark("player: prepare() first item");
        player.prepare();
        player.setPlayWhenReady(true);
        player.play();
        adjustPreloadForPlay();
        updatePlaybackChrome();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enableImmersiveMode();
    }

    @Override
    protected void onResume() {
        super.onResume();
        pump.onResume();
        if (player != null && current != null && player.getPlayWhenReady()) {
            player.play();
        }
        handler.post(progressTick);
    }

    @Override
    protected void onPause() {
        pump.onPause();
        if (player != null) player.pause();
        handler.removeCallbacks(progressTick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        repository.removeListener(this);
        cancelStartupTimeout();
        pump.onDestroy();
        stopCookieHandoffServer();
        handler.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }

    private static void makeCircular(ImageView view) {
        view.setClipToOutline(true);
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setOval(0, 0, v.getWidth(), v.getHeight());
            }
        });
    }

    /** Whether the process-wide CookieManager already has a saved session worth silently re-verifying. */
    private boolean hasSavedSession() {
        String cookie = CookieManager.getInstance().getCookie(DouyinConstants.FEED_URL);
        return cookie != null && cookie.contains("sessionid=");
    }

    /** Silently restores menu state from a cookie saved in a prior session. */
    private void runStartupLoginCheck() {
        new LoginStatusChecker().check(this, (ViewGroup) findViewById(R.id.playerRoot), new LoginStatusChecker.Callback() {
            @Override
            public void onResult(boolean loggedIn, String nickname, String avatarUrl) {
                setLoginState(loggedIn, nickname, avatarUrl);
                if (loggedIn && pump != null) pump.scheduleWatchHistorySync();
            }

            @Override
            public void onCheckFailed() {
                if (hasSavedSession()) {
                    syncLoginUiWithSession();
                }
            }
        });
    }

    /** Keeps the feature menu in sync with saved cookies (no server round-trip). */
    private void syncLoginUiWithSession() {
        if (hasSavedSession()) {
            if (!loggedIn) {
                setLoginState(true, loggedInNickname, loggedInAvatarUrl);
            }
        } else if (loggedIn) {
            setLoginState(false, null, null);
        }
    }

    private void maybeRunDeferredLoginCheck() {
        if (!pendingStartupLoginCheck) return;
        pendingStartupLoginCheck = false;
        runStartupLoginCheck();
    }

    private void scheduleStartupTimeout() {
        handler.removeCallbacks(startupTimeoutRunnable);
        handler.postDelayed(startupTimeoutRunnable, startupTimeoutMs);
    }

    private void cancelStartupTimeout() {
        handler.removeCallbacks(startupTimeoutRunnable);
        startupTimedOut = false;
        startupRetryCount = 0;
    }

    private void onStartupTimeout() {
        if (!splashVisible || repository.bufferSize() > 0) return;
        // 数据泵拿不到新数据时，先用上次会话的快照兜底（断网也能看）。
        if (tryStartFromOfflineFallback()) {
            hideSplashIfNeeded();
            return;
        }
        if (startupRetryCount < MAX_STARTUP_RETRIES) {
            startupRetryCount++;
            updateSplashStatus("加载较慢，自动重试 (" + startupRetryCount + "/" + MAX_STARTUP_RETRIES + ")…");
            retryStartup();
            return;
        }
        startupTimedOut = true;
        updateSplashStatus("加载超时：请更新系统 WebView 或检查网络，按 OK 重试");
        Toast.makeText(this, "加载超时，按 OK 重试", Toast.LENGTH_LONG).show();
    }

    private void retryStartup() {
        startupTimedOut = false;
        updateSplashStatus("正在重新加载…");
        resetPlaybackAndFeed();
    }

    /**
     * Clears all locally buffered feed items and playback state, then
     * rebuilds the hidden pump WebView so the next fetch uses the current
     * CookieManager session (anonymous vs logged-in).
     */
    private void resetPlaybackAndFeed() {
        repository.reset();
        // Whatever arrives from here on comes from the pump, not the disk
        // snapshot, so the stale-link guard must not fire on it.
        snapshotSourcedItems = 0;
        playlist.clear();
        playlistIndex = -1;
        current = null;
        resumePositions.clear();
        pendingAdvance = 0;
        waitingForBuffer = true;
        autoStartWhenReady = true;
        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
        slideAnimating = false;
        pageA.clearSnapshot();
        pageB.clearSnapshot();
        pageA.attachPlayer(player);
        pageB.detachPlayer();
        pageA.root.setTranslationY(0);
        playerPage = pageA;
        playerView = pageA.playerView;
        coverImage = pageA.coverImage;
        backdropImage = pageA.backdropImage;
        showActivePage();
        slideHost.post(() -> pageB.stageForSlide(slideHost.getHeight()));
        coverImage.setAlpha(1f);
        scheduleStartupTimeout();
        pump.setLoggedIn(loggedIn);
        if (loggedIn) {
            pump.softReloadAfterLogin();
            pump.scheduleWatchHistorySync();
        } else {
            pump.hardRestart();
        }
        updatePlaybackChrome();
    }

    private void updateSplashStatus(String message) {
        if (splashStatusText != null && message != null && !message.isEmpty()) {
            splashStatusText.setText(message);
        }
    }

    @SuppressWarnings("deprecation")
    private void enableImmersiveMode() {
        int uiOptions = View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        getWindow().getDecorView().setSystemUiVisibility(uiOptions);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();

        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return dispatchBackKey(event);
        }

        // While splash is up: long-press OK opens the menu; short OK retries
        // after a startup timeout.
        if (splashVisible) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_BUTTON_A) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (event.getRepeatCount() == 0) {
                        centerLongPressFired = false;
                        handler.postDelayed(longPressRunnable, CENTER_LONG_PRESS_MS);
                    }
                } else if (event.getAction() == KeyEvent.ACTION_UP) {
                    handler.removeCallbacks(longPressRunnable);
                    if (!centerLongPressFired && startupTimedOut) {
                        retryStartup();
                    }
                }
                return true;
            }
            return true;
        }

        // Cookie login overlay has no focusable input of its own (see
        // startCookieLogin) - just swallow everything else so it doesn't
        // fall through to this method's video-navigation shortcuts below.
        if (cookieLoginVisible) {
            return true;
        }

        // While the feature menu is up, D-pad up/down move the highlighted
        // item and OK/center activates it; everything else is swallowed so
        // it doesn't also drive video navigation/seeking underneath it.
        if (featureMenuVisible) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                        moveMenuSelection(-1);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        moveMenuSelection(1);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_CENTER:
                    case KeyEvent.KEYCODE_ENTER:
                        if (event.getRepeatCount() == 0) {
                            activateSelectedMenuItem();
                        }
                        return true;
                    default:
                        break;
                }
            }
            return true;
        }

        if (creatorGridVisible) {
            return super.dispatchKeyEvent(event);
        }

        // OK/center is overloaded (short press = start/pause, long press =
        // feature menu), so it needs its own down/up tracking instead of the
        // single ACTION_DOWN switch below.
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    centerLongPressFired = false;
                    handler.postDelayed(longPressRunnable, CENTER_LONG_PRESS_MS);
                }
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                handler.removeCallbacks(longPressRunnable);
                if (!centerLongPressFired) {
                    onStartPressed();
                }
            }
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    startSeekRepeat(keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                            ? -SEEK_STEP_MS : SEEK_STEP_MS);
                }
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                stopSeekRepeat();
                return true;
            }
            return true;
        }

        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    playNext();
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    playPrevious();
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case KeyEvent.KEYCODE_SPACE:
                    togglePlayPause();
                    showControls();
                    return true;
                default:
                    break;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    /**
     * A single physical back press reaches this method twice - once for
     * {@code ACTION_DOWN}, once for {@code ACTION_UP} - and Android's
     * default {@code onBackPressed()} (which finishes the Activity) only
     * fires off an <em>unconsumed</em> {@code ACTION_UP}. Closing an
     * overlay/menu here only on {@code ACTION_DOWN} and returning {@code
     * true} looks locally correct, but by the time the matching {@code
     * ACTION_UP} arrives the state that decision was based on
     * ({@link #cookieLoginVisible}/{@link #featureMenuVisible}/
     * {@link #controlsVisible}) has already flipped back to "nothing
     * open" - so re-checking that state for the {@code ACTION_UP} would
     * wrongly fall through to the default handler and exit the whole app
     * as a side effect of closing an overlay. {@link #backConsumedSpecially}
     * instead remembers *this press's own* verdict across both actions.
     */
    private boolean dispatchBackKey(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            if (cookieLoginVisible) {
                hideCookieLogin();
                backConsumedSpecially = true;
            } else if (featureMenuVisible) {
                closeFeatureMenu();
                backConsumedSpecially = true;
            } else if (creatorGridVisible) {
                closeCreatorGrid();
                backConsumedSpecially = true;
            } else if (playbackMode == PlaybackMode.CREATOR) {
                exitCreatorPlaybackToGrid();
                backConsumedSpecially = true;
            } else if (controlsVisible) {
                hideControls();
                backConsumedSpecially = true;
            } else {
                backConsumedSpecially = true;
                long now = System.currentTimeMillis();
                if (now - lastBackPressForExitMs <= EXIT_CONFIRM_MS) {
                    finish();
                } else {
                    lastBackPressForExitMs = now;
                    Toast.makeText(this, "再按一次返回键退出应用", Toast.LENGTH_SHORT).show();
                }
            }
        }
        if (backConsumedSpecially) {
            if (event.getAction() == KeyEvent.ACTION_UP) backConsumedSpecially = false;
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /**
     * The "start" button's behavior depends on current playback state
     * rather than just toggling: resuming from pause always just resumes
     * (one press), but pausing while already playing takes two presses -
     * the first only reveals the (possibly auto-hidden) overlay, the second
     * actually pauses. While buffering or waiting for the feed, a single
     * press toggles pause/resume without auto-starting when the user chose
     * to wait.
     */
    private void onStartPressed() {
        if (player == null) return;

        if (waitingForBuffer) {
            autoStartWhenReady = !autoStartWhenReady;
            if (autoStartWhenReady && repository.bufferSize() > 0) {
                waitingForBuffer = false;
                if (playlistIndex < 0) {
                    startInitialPlayback();
                } else {
                    playNext();
                }
            } else if (!autoStartWhenReady) {
                adjustPreloadForPause();
            }
            updatePlaybackChrome();
            showControls();
            return;
        }

        int state = player.getPlaybackState();
        if (state == Player.STATE_BUFFERING) {
            if (player.getPlayWhenReady()) {
                userPause();
            } else {
                resumeFromUserPause();
            }
            showControls();
            return;
        }

        if (!player.getPlayWhenReady()) {
            resumeFromUserPause();
            showControls();
        } else if (controlsVisible) {
            userPause();
            showControls();
        } else {
            showControls();
        }
    }

    private void togglePlayPause() {
        if (player == null) return;
        if (waitingForBuffer) {
            onStartPressed();
            return;
        }
        if (player.getPlayWhenReady()) {
            userPause();
        } else {
            resumeFromUserPause();
        }
        updatePlaybackChrome();
    }

    private void userPause() {
        autoStartWhenReady = false;
        if (player != null) {
            player.setPlayWhenReady(false);
        }
        adjustPreloadForPause();
        updatePlaybackChrome();
    }

    private void resumeFromUserPause() {
        autoStartWhenReady = true;
        if (player == null) return;
        adjustPreloadForPlay();
        if (pendingAdvance > 0 && playlistIndex + 1 < playlist.size()) {
            pendingAdvance = 0;
            advance(1);
        } else if (pendingAdvance < 0 && playlistIndex > 0) {
            pendingAdvance = 0;
            advance(-1);
        } else if (isPausedAtEndOfItem()) {
            // Start after a natural finish should go to the next video, not
            // restart/resume the ended frame via ExoPlayer's bare play().
            pendingAdvance = 0;
            playNext();
        } else {
            pendingAdvance = 0;
            player.setPlayWhenReady(true);
            player.play();
        }
        updatePlaybackChrome();
    }

    /** Short head-start for the next item while actively playing. */
    private void adjustPreloadForPlay() {
        if (player != null) {
            player.setPreloadConfiguration(new PreloadConfiguration(PRELOAD_FULL_US));
        }
    }

    /** Full preload of upcoming item(s) while paused and not consuming bandwidth. */
    private void adjustPreloadForPause() {
        if (player == null) return;
        refillPlaylist();
        player.setPreloadConfiguration(new PreloadConfiguration(PRELOAD_FULL_US));
    }

    private void seekBy(long deltaMs) {
        if (player == null) return;
        long duration = player.getDuration();
        long target = player.getCurrentPosition() + deltaMs;
        if (target < 0) target = 0;
        if (duration != C.TIME_UNSET && target > duration) target = duration;
        player.seekTo(target);
    }

    /** First seek fires immediately; further seeks repeat while the key is held. */
    private void startSeekRepeat(long deltaMs) {
        seekRepeatDeltaMs = deltaMs;
        handler.removeCallbacks(seekRepeatRunnable);
        seekBy(deltaMs);
        showControls();
        handler.postDelayed(seekRepeatRunnable, SEEK_REPEAT_INITIAL_MS);
    }

    private void stopSeekRepeat() {
        seekRepeatDeltaMs = 0;
        handler.removeCallbacks(seekRepeatRunnable);
    }

    private void showControls() {
        controlsVisible = true;
        topPanel.setVisibility(View.VISIBLE);
        bottomPanel.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideControlsRunnable);
        handler.postDelayed(hideControlsRunnable, CONTROLS_AUTO_HIDE_MS);
    }

    private void hideControls() {
        controlsVisible = false;
        topPanel.setVisibility(View.GONE);
        bottomPanel.setVisibility(View.GONE);
        handler.removeCallbacks(hideControlsRunnable);
    }

    private void openFeatureMenu() {
        centerLongPressFired = true;
        syncLoginUiWithSession();
        featureMenuVisible = true;
        menuSelectedIndex = 0;
        updateMenuHighlight();
        featureMenu.setVisibility(View.VISIBLE);
        if (splashVisible) {
            splashOverlay.setVisibility(View.GONE);
        }
    }

    private void closeFeatureMenu() {
        featureMenuVisible = false;
        featureMenu.setVisibility(View.GONE);
        if (splashVisible) {
            splashOverlay.setVisibility(View.VISIBLE);
        }
    }

    private void moveMenuSelection(int delta) {
        int size = menuItems.size();
        menuSelectedIndex = ((menuSelectedIndex + delta) % size + size) % size;
        updateMenuHighlight();
    }

    private void updateMenuHighlight() {
        for (int i = 0; i < menuItems.size(); i++) {
            menuItems.get(i).setBackgroundColor(i == menuSelectedIndex ? 0x33FFFFFF : 0x00000000);
        }
    }

    /** Login/logout and creator browse. */
    private void activateSelectedMenuItem() {
        if (menuItems.isEmpty()) return;
        int index = menuSelectedIndex;
        View item = menuItems.get(index);
        closeFeatureMenu();
        int id = item.getId();
        if (id == R.id.featureMenuLogin) {
            startCookieLogin();
        } else if (id == R.id.featureMenuCreator) {
            openCreatorGrid();
        } else if (id == R.id.featureMenuLogout) {
            logout();
        }
    }

    private void rebuildMenuItems() {
        menuItems.clear();
        if (loggedIn) {
            featureMenuAccountHeader.setVisibility(View.VISIBLE);
            featureMenuAccountDivider.setVisibility(View.VISIBLE);
            featureMenuLogin.setVisibility(View.GONE);
            featureMenuLogout.setVisibility(View.VISIBLE);
            menuItems.add(findViewById(R.id.featureMenuCreator));
            menuItems.add(featureMenuLogout);
        } else {
            featureMenuAccountHeader.setVisibility(View.GONE);
            featureMenuAccountDivider.setVisibility(View.GONE);
            featureMenuLogin.setVisibility(View.VISIBLE);
            featureMenuLogout.setVisibility(View.GONE);
            menuItems.add(featureMenuLogin);
            menuItems.add(findViewById(R.id.featureMenuCreator));
        }
        if (menuSelectedIndex >= menuItems.size()) {
            menuSelectedIndex = 0;
        }
    }

    /**
     * Reflects a {@link LoginStatusChecker} result in the feature menu header
     * while {@link #loggedIn}, or the plain "登录账号" row once it isn't.
     */
    private void setLoginState(boolean loggedIn, String nickname, String avatarUrl) {
        this.loggedIn = loggedIn;
        this.loggedInNickname = loggedIn ? nickname : null;
        this.loggedInAvatarUrl = loggedIn ? avatarUrl : null;
        if (pump != null) {
            pump.setLoggedIn(loggedIn);
        }
        WatchedAwemeStore.getInstance().bindSession();
        if (loggedIn) {
            String displayName = loggedInNickname != null && !loggedInNickname.isEmpty()
                    ? loggedInNickname : "已登录";
            featureMenuAccountName.setText(displayName);
            if (loggedInAvatarUrl != null && !loggedInAvatarUrl.isEmpty()) {
                SimpleImageLoader.load(loggedInAvatarUrl, featureMenuAccountAvatar);
            }
        } else {
            featureMenuLoginText.setText("登录账号");
        }
        rebuildMenuItems();
    }

    /**
     * Wipes the Cookie ({@link CookieImportHelper#clear}), reloads the feed
     * so the pump WebView's next fetch goes out as an anonymous session, and
     * clears any videos buffered under the old session.
     */
    private void logout() {
        CookieImportHelper.clear();
        setLoginState(false, null, null);
        resetPlaybackAndFeed();
        Toast.makeText(this, "已退出登录", Toast.LENGTH_SHORT).show();
    }

    /**
     * Shows a QR code pointing at {@link CookieHandoffServer}'s local URL,
     * the only way in: there's no on-TV text field to paste a Cookie into,
     * since typing a 200+ character Cookie header with a remote's
     * on-screen keyboard is painful. Scanning it opens a form on the
     * phone/PC instead, where the user pastes the Cookie header copied out
     * of an already-logged-in browser session and submits it there; this
     * method's callback applies it here via {@link CookieImportHelper}.
     * This is the only login method the app offers at all: douyin's own web
     * login page (both the QR modal and the sso.douyin.com endpoints that
     * used to front it with a plain JSON API) is a JS-rendered page guarded
     * by a slide-to-verify anti-bot captcha that a hidden, never-shown
     * WebView can never solve, so there's no way to automate it from inside
     * this app.
     */
    private void startCookieLogin() {
        cookieLoginVisible = true;
        cookieLoginOverlay.setVisibility(View.VISIBLE);
        handoffStatusText.setTextColor(0xFFFFD54F);
        handoffStatusText.setText("等待手机扫码提交…");

        cookieHandoffServer = new CookieHandoffServer(this::applyCookieAndFinish);
        String url = cookieHandoffServer.start();
        if (url != null) {
            handoffRow.setVisibility(View.VISIBLE);
            handoffUrlText.setText(url);
            handoffQrImage.setImageBitmap(QrCodeGenerator.generate(url, 480));
        } else {
            handoffRow.setVisibility(View.GONE);
            handoffStatusText.setTextColor(0xFFFF5252);
            handoffStatusText.setText("未连接到WiFi/局域网，无法生成登录二维码");
        }
    }

    private void hideCookieLogin() {
        cookieLoginVisible = false;
        cookieLoginOverlay.setVisibility(View.GONE);
        stopCookieHandoffServer();
        handoffQrImage.setImageBitmap(null);
    }

    private void stopCookieHandoffServer() {
        if (cookieHandoffServer != null) {
            cookieHandoffServer.stop();
            cookieHandoffServer = null;
        }
    }

    /**
     * {@link CookieHandoffServer.Callback}, invoked once a phone/PC has
     * POSTed a Cookie. Finding a non-empty {@code sessionid} in that string
     * (all {@link CookieImportHelper} checks) only means the paste looked
     * well-formed - it says nothing about whether douyin's servers still
     * honor that particular session, so a stale/revoked/mistyped cookie
     * would otherwise get reported as a successful login with nothing to
     * show for it later. {@link LoginStatusChecker} closes that gap by
     * asking douyin's own servers directly before this declares success.
     */
    private void applyCookieAndFinish(String raw) {
        if (verifyingLogin) return; // previous submission's check still in flight
        if (!CookieImportHelper.apply(raw)) {
            handoffStatusText.setTextColor(0xFFFF5252);
            handoffStatusText.setText("未找到 sessionid，请确认复制了完整 Cookie 后重新扫码提交");
            return;
        }

        stopCookieHandoffServer();
        verifyingLogin = true;
        handoffStatusText.setTextColor(0xFFFFD54F);
        handoffStatusText.setText("Cookie 已应用，正在向抖音服务器验证登录状态…");

        new LoginStatusChecker().check(this, (ViewGroup) findViewById(R.id.playerRoot), new LoginStatusChecker.Callback() {
            @Override
            public void onResult(boolean loggedIn, String nickname, String avatarUrl) {
                verifyingLogin = false;
                if (loggedIn) {
                    setLoginState(true, nickname, avatarUrl);
                    handoffStatusText.setTextColor(0xFF69F0AE);
                    handoffStatusText.setText("登录成功：" + nickname);
                    resetPlaybackAndFeed();
                    // Leave the confirmed result on screen briefly instead of
                    // yanking the overlay away the instant it appears.
                    handler.postDelayed(PlayerActivity.this::hideCookieLogin, 1500);
                } else {
                    setLoginState(false, null, null);
                    handoffStatusText.setTextColor(0xFFFF5252);
                    handoffStatusText.setText("抖音服务器判定为未登录，Cookie 可能已失效，请重新获取后再扫码提交");
                    reopenCookieHandoff();
                }
            }

            @Override
            public void onCheckFailed() {
                verifyingLogin = false;
                handoffStatusText.setTextColor(0xFFFF5252);
                handoffStatusText.setText("验证超时，请检查网络后重新扫码提交");
                reopenCookieHandoff();
            }
        });
    }

    /** Re-opens submission after a failed verification (same URL/QR - {@link CookieHandoffServer}'s port is fixed). */
    private void reopenCookieHandoff() {
        if (!cookieLoginVisible) return; // user already backed out while this was verifying
        cookieHandoffServer = new CookieHandoffServer(this::applyCookieAndFinish);
        cookieHandoffServer.start();
    }

    private void playNext() {
        if (playlistIndex + 1 >= playlist.size()) {
            if (playbackMode == PlaybackMode.CREATOR) {
                syncCreatorPlaylistTail();
                if (playlistIndex + 1 >= playlist.size()) {
                    waitingForBuffer = true;
                    pendingAdvance = 1;
                    creatorRepository.kickstartPaging();
                    updatePlaybackChrome();
                    return;
                }
            } else {
                FeedVideo next = pollNextFeedVideo();
                if (next == null) {
                    waitingForBuffer = true;
                    pendingAdvance = 1;
                    updatePlaybackChrome();
                    return;
                }
                playlist.add(next);
                enqueueVideo(next);
                SimpleImageLoader.preload(next.coverUrl);
            }
        }
        ensurePlayerQueueSynced();
        waitingForBuffer = false;
        pendingAdvance = 0;
        autoStartWhenReady = true;
        advance(1);
    }

    /** Runs the slide transition after a video finishes naturally. */
    private void handlePlaybackEnded() {
        if (System.currentTimeMillis() < ignorePlaybackEndedUntilMs) {
            updatePlaybackChrome();
            return;
        }
        // Last playlist item fires END_OF_MEDIA_ITEM then STATE_ENDED; keep one advance.
        if (playbackEndedAdvancePending || slideAnimating) {
            if (slideAnimating) queuedAdvanceDuringSlide = 1;
            updatePlaybackChrome();
            return;
        }
        playbackEndedAdvancePending = true;
        refillPlaylist();
        handler.post(() -> {
            playbackEndedAdvancePending = false;
            playNext();
        });
    }

    /** True when pauseAtEndOfMediaItems (or STATE_ENDED) left us frozen on the last frame. */
    private boolean isPausedAtEndOfItem() {
        if (player == null) return false;
        if (player.getPlaybackState() == Player.STATE_ENDED) return true;
        if (player.getPlayWhenReady()) return false;
        long duration = player.getDuration();
        long position = player.getCurrentPosition();
        return duration != C.TIME_UNSET && duration > 0 && position >= duration - 500;
    }

    /** Keeps ExoPlayer's internal queue aligned with {@link #playlist}. */
    private void ensurePlayerQueueSynced() {
        if (player == null) return;
        for (int i = player.getMediaItemCount(); i < playlist.size(); i++) {
            enqueueVideo(playlist.get(i));
        }
    }

    private void playPrevious() {
        if (playlistIndex <= 0) return;
        pendingAdvance = 0;
        advance(-1);
    }

    private void enqueueVideo(FeedVideo video) {
        if (player == null || video == null) return;
        player.addMediaSource(mediaSourceFor(video));
    }

    @OptIn(markerClass = UnstableApi.class)
    private MediaSource mediaSourceFor(FeedVideo video) {
        registerPlaybackUrls(video);
        FeedVideo.SplitPlayUrl split = selectedSplit(video);
        if (split != null) {
            Log.i(TAG, "split play awemeId=" + video.awemeId
                    + " br=" + split.videoBrKbps + " hevc=" + split.hevc
                    + " v=" + FeedVideo.shortUrl(split.videoUrl)
                    + " a=" + FeedVideo.shortUrl(split.audioUrl));
            MediaItem videoItem = MediaItem.fromUri(split.videoUrl)
                    .buildUpon()
                    .setMediaId(video.awemeId)
                    .build();
            MediaItem audioItem = MediaItem.fromUri(split.audioUrl)
                    .buildUpon()
                    .setMediaId(video.awemeId + ":audio")
                    .build();
            return new MergingMediaSource(
                    progressiveSourceFactory.createMediaSource(videoItem),
                    progressiveSourceFactory.createMediaSource(audioItem));
        }
        String url = playbackUrlFor(video);
        Log.i(TAG, "muxed play awemeId=" + video.awemeId + " url=" + FeedVideo.shortUrl(url));
        return progressiveSourceFactory.createMediaSource(
                MediaItem.fromUri(url).buildUpon().setMediaId(video.awemeId).build());
    }

    private void registerPlaybackUrls(FeedVideo video) {
        if (video == null) return;
        for (String url : video.playUrlCandidates) {
            DouyinPlaybackRegistry.register(url, video.awemeId);
        }
        if (video.splitCandidates != null) {
            for (FeedVideo.SplitPlayUrl split : video.splitCandidates) {
                DouyinPlaybackRegistry.register(split.videoUrl, video.awemeId);
                DouyinPlaybackRegistry.register(split.audioUrl, video.awemeId);
            }
        }
    }

    /**
     * Picks a browser-style split pair when available. HEVC (hvc1) pairs require
     * device support; otherwise we fall through to muxed progressive MP4.
     */
    private FeedVideo.SplitPlayUrl selectedSplit(FeedVideo video) {
        if (video == null || !video.hasSplitStream()) return null;
        Integer forced = splitCandidateIndex.get(video.awemeId);
        if (forced != null && forced < 0) return null; // explicitly fell back to muxed
        int start = forced != null ? forced : 0;
        boolean hevcOk = HevcCapability.isSupported();
        // Prefer AVC pairs; only take HEVC when no H.264 split remains (or forced).
        FeedVideo.SplitPlayUrl hevcFallback = null;
        for (int i = start; i < video.splitCandidates.size(); i++) {
            FeedVideo.SplitPlayUrl split = video.splitCandidates.get(i);
            if (split.hevc) {
                if (hevcOk && hevcFallback == null) hevcFallback = split;
                continue;
            }
            if (forced == null) splitCandidateIndex.put(video.awemeId, i);
            return split;
        }
        if (forced != null) {
            for (int i = start; i < video.splitCandidates.size(); i++) {
                FeedVideo.SplitPlayUrl split = video.splitCandidates.get(i);
                if (split.hevc && !hevcOk) continue;
                return split;
            }
            return null;
        }
        if (hevcFallback != null) {
            int idx = video.splitCandidates.indexOf(hevcFallback);
            if (idx >= 0) splitCandidateIndex.put(video.awemeId, idx);
            return hevcFallback;
        }
        return null;
    }

    private String playbackUrlFor(FeedVideo video) {
        if (video == null) return "";
        FeedVideo.SplitPlayUrl split = selectedSplit(video);
        if (split != null) return split.videoUrl;
        int idx = Math.max(playUrlCandidateIndex.getOrDefault(video.awemeId, 0), 0);
        boolean hevcOk = HevcCapability.isSupported();
        // selectedSplit() refuses HEVC when the probe says no HEVC, but this
        // list is plain URLs with the flag already thrown away, so the gate was
        // silently missing here: collectPlayUrls() keeps HEVC entries (sorted
        // last, not dropped) and this returned whichever sat at the index.
        for (int i = idx; i < video.playUrlCandidates.size(); i++) {
            String candidate = video.playUrlCandidates.get(i);
            if (!hevcOk && looksHevc(candidate)) continue;
            if (i != idx) playUrlCandidateIndex.put(video.awemeId, i);
            return candidate;
        }
        return video.playUrl != null ? video.playUrl : "";
    }

    /** Same URL-level sniff {@link FeedVideo} uses when building its candidates. */
    private static boolean looksHevc(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(Locale.US);
        return lower.contains("hvc1") || lower.contains("bytevc1") || lower.contains("hevc");
    }

    private static String playbackModeLabel(FeedVideo video) {
        if (video == null) return "?";
        return video.hasSplitStream() ? "split" : "muxed";
    }

    /** Tries the next split pair, then muxed CDN mirrors, on playback failure. */
    private boolean retryCurrentWithNextCandidate() {
        if (current == null || player == null || playlistIndex < 0) return false;

        int splitIdx = splitCandidateIndex.getOrDefault(current.awemeId, 0);
        if (current.hasSplitStream() && splitIdx >= 0) {
            boolean hevcOk = HevcCapability.isSupported();
            for (int i = splitIdx + 1; i < current.splitCandidates.size(); i++) {
                FeedVideo.SplitPlayUrl next = current.splitCandidates.get(i);
                if (next.hevc && !hevcOk) continue;
                splitCandidateIndex.put(current.awemeId, i);
                Log.w(TAG, "retry split awemeId=" + current.awemeId + " candidate=" + i
                        + "/" + current.splitCandidates.size() + " br=" + next.videoBrKbps);
                replaceCurrentSource();
                return true;
            }
            // Exhausted splits → muxed fallback.
            splitCandidateIndex.put(current.awemeId, -1);
            if (!current.playUrlCandidates.isEmpty()) {
                playUrlCandidateIndex.put(current.awemeId, 0);
                Log.w(TAG, "retry fallback muxed awemeId=" + current.awemeId);
                replaceCurrentSource();
                return true;
            }
        }

        int nextIdx = playUrlCandidateIndex.getOrDefault(current.awemeId, 0) + 1;
        // Same missing HEVC gate as playbackUrlFor() - step over HEVC mirrors
        // when the device said it cannot decode them.
        boolean hevcOk = HevcCapability.isSupported();
        while (nextIdx < current.playUrlCandidates.size()
                && !hevcOk && looksHevc(current.playUrlCandidates.get(nextIdx))) {
            nextIdx++;
        }
        if (nextIdx >= current.playUrlCandidates.size()) return false;
        playUrlCandidateIndex.put(current.awemeId, nextIdx);
        String altUrl = current.playUrlCandidates.get(nextIdx);
        DouyinPlaybackRegistry.register(altUrl, current.awemeId);
        Log.w(TAG, "retry muxed awemeId=" + current.awemeId + " candidate=" + nextIdx
                + "/" + current.playUrlCandidates.size() + " url=" + altUrl);
        replaceCurrentSource();
        return true;
    }

    private void replaceCurrentSource() {
        if (player == null || current == null || playlistIndex < 0) return;
        player.removeMediaItem(playlistIndex);
        player.addMediaSource(playlistIndex, mediaSourceFor(current));
        player.seekTo(playlistIndex, 0);
        player.prepare();
        player.setPlayWhenReady(true);
    }

    /**
     * TikTok-style transition: freeze the outgoing video as a snapshot and
     * slide it off-screen while the incoming video (already playing on the
     * off-screen page) slides into view at the same time.
     *
     * @param direction {@code 1} for next (A exits up, B enters from bottom),
     *                  {@code -1} for previous (A exits down, B enters from top).
     */
    private void advance(int direction) {
        if (slideAnimating) {
            if (direction > 0) {
                queuedAdvanceDuringSlide = 1;
            } else if (direction < 0) {
                queuedAdvanceDuringSlide = -1;
            }
            return;
        }

        int newIndex = playlistIndex + direction;
        if (newIndex < 0 || newIndex >= playlist.size()) return;

        int height = slideHost.getHeight();
        if (height == 0) {
            advanceInstant(direction);
            return;
        }

        slideAnimating = true;
        pendingCoverDismiss = false;
        rememberCurrentPosition();

        if (direction > 0) {
            refillPlaylist();
        }

        FeedVideo incomingTarget = playlist.get(newIndex);
        Long resumeMs = resumePositions.get(incomingTarget.awemeId);

        // Snapshot outgoing video while the player still sits on page A.
        Bitmap frozen = pageOut.capturePageSnapshot();
        pageOut.showSnapshot(frozen);

        // Seek on the same surface so ExoPlayer keeps its preloaded buffer.
        seekToPlaylistItem(direction, newIndex, resumeMs);
        final FeedVideo incoming = playlist.get(playlistIndex);
        current = incoming;

        // Player stays on page A after the seek — swap its cover/backdrop to the
        // incoming item now so clearing the outgoing snapshot never reveals the
        // previous video's artwork.
        applyPageVisuals(pageOut, incoming);

        float entryY = direction * height;
        pageIn.stageForSlide(entryY);
        applySharedUi(incoming);
        player.play();

        Runnable startSlide = () -> applyIncomingCover(
                pageIn, incoming, () -> runDualPageSlide(direction, height));
        if (shouldSlideImmediately(incoming, resumeMs)) {
            startSlide.run();
        } else {
            runWhenIncomingBuffered(startSlide);
        }
    }

    /** Skip the buffer gate when the incoming item is already staged to play. */
    private boolean shouldSlideImmediately(FeedVideo incoming, Long resumeMs) {
        if (resumeMs != null) return true;
        if (SimpleImageLoader.isCached(incoming.coverUrl)) return true;
        return hasIncomingBuffer();
    }

    /**
     * Jumps to a playlist neighbour, preferring {@code seekToNextMediaItem}/
     * {@code seekToPreviousMediaItem} so Media3 can reuse its preload window.
     */
    private void seekToPlaylistItem(int direction, int targetIndex, Long resumeMs) {
        if (resumeMs != null) {
            playlistIndex = targetIndex;
            player.seekTo(playlistIndex, resumeMs);
            return;
        }
        ensurePlayerQueueSynced();
        // From a natural finish (ENDED, or READY+paused at the last frame via
        // pauseAtEndOfMediaItems), seekToNextMediaItem() is unreliable — jump
        // to the target window explicitly and re-arm playWhenReady.
        if (direction > 0 && isPausedAtEndOfItem()) {
            playlistIndex = targetIndex;
            player.setPlayWhenReady(true);
            player.seekTo(playlistIndex, 0);
            return;
        }
        if (direction > 0) {
            player.seekToNextMediaItem();
        } else {
            player.seekToPreviousMediaItem();
        }
        playlistIndex = player.getCurrentMediaItemIndex();
    }

    private void advanceInstant(int direction) {
        rememberCurrentPosition();
        int newIndex = playlistIndex + direction;
        if (newIndex < 0 || newIndex >= playlist.size()) return;
        if (direction > 0) refillPlaylist();
        Long resumeMs = resumePositions.get(playlist.get(newIndex).awemeId);
        seekToPlaylistItem(direction, newIndex, resumeMs);
        applyCurrentUi();
        player.play();
    }

    /**
     * Loads the <em>incoming</em> video's cover + blurred backdrop onto the
     * off-screen page. The page stays black until artwork is ready; the slide
     * may start on timeout even if the cover is still loading.
     */
    private void applyIncomingCover(VideoSlidePage page, FeedVideo video, Runnable whenReady) {
        page.showBlackUntilCover();
        page.coverImage.setTag(video.coverUrl);
        page.backdropImage.setTag(video.coverUrl);

        final boolean[] slideStarted = {false};
        final Runnable[] timeoutHolder = new Runnable[1];
        Runnable startSlide = () -> {
            if (slideStarted[0]) return;
            slideStarted[0] = true;
            if (timeoutHolder[0] != null) {
                handler.removeCallbacks(timeoutHolder[0]);
            }
            whenReady.run();
        };

        if (video.coverUrl == null || video.coverUrl.isEmpty()) {
            startSlide.run();
            return;
        }

        Runnable onArtworkReady = () -> {
            if (video.coverUrl != null && !video.coverUrl.equals(page.coverImage.getTag())) {
                return;
            }
            page.revealCoverMask();
            startSlide.run();
        };

        if (SimpleImageLoader.applyCachedCover(video.coverUrl, page.coverImage, page.backdropImage)) {
            onArtworkReady.run();
            return;
        }
        SimpleImageLoader.loadWithBackdrop(
                video.coverUrl, page.coverImage, page.backdropImage, onArtworkReady);
        timeoutHolder[0] = startSlide;
        handler.postDelayed(timeoutHolder[0], INCOMING_COVER_WAIT_MS);
    }

    private void dismissCoverOnPlayerPage() {
        if (playerPage == null) return;
        handler.removeCallbacks(coverDismissFallbackRunnable);
        playerPage.dismissCoverMask();
        pendingCoverDismiss = false;
    }

    private final Runnable coverDismissFallbackRunnable = () -> {
        if (slideAnimating || playerPage == null || !playerPage.isCoverMaskActive()) return;
        if (player != null && player.isPlaying()) {
            dismissCoverOnPlayerPage();
        }
    };

    private void scheduleCoverDismissFallback() {
        handler.removeCallbacks(coverDismissFallbackRunnable);
        handler.postDelayed(coverDismissFallbackRunnable, 600);
    }

    private void runDualPageSlide(int direction, int height) {
        pageOut.root.animate().cancel();
        pageIn.root.animate().cancel();

        pageOut.root.animate()
                .translationY(-direction * height)
                .setDuration(SLIDE_DURATION_MS)
                .start();

        pageIn.root.animate()
                .translationY(0)
                .setDuration(SLIDE_DURATION_MS)
                .withEndAction(() -> finishDualPageSlide(direction, height))
                .start();
    }

    private void finishDualPageSlide(int direction, int height) {
        pageOut.clearSnapshot();
        pageOut.root.setTranslationY(0);
        pageIn.stageForSlide(direction * height);
        showActivePage();

        slideAnimating = false;
        if (pendingCoverDismiss) {
            dismissCoverOnPlayerPage();
        }
        refillPlaylist();
        updatePlaybackChrome();
        updateProgressUi();

        int queued = queuedAdvanceDuringSlide;
        queuedAdvanceDuringSlide = 0;
        if (queued > 0) {
            // playNext() (not advance) so an empty tail still waits on the feed.
            handler.post(this::playNext);
        } else if (queued < 0) {
            handler.post(this::playPrevious);
        }
    }

    private boolean hasIncomingBuffer() {
        if (player == null) return true;
        long buffered = player.getBufferedPosition();
        long pos = player.getCurrentPosition();
        long duration = player.getDuration();
        if (duration != C.TIME_UNSET && duration > 0 && buffered >= duration - 500) {
            return true;
        }
        return buffered - pos >= MIN_INCOMING_BUFFER_MS;
    }

    /** Gives fast swipes a moment to fill the incoming item before sliding. */
    private void runWhenIncomingBuffered(Runnable action) {
        if (hasIncomingBuffer()) {
            action.run();
            return;
        }
        final long deadlineMs = System.currentTimeMillis() + INCOMING_BUFFER_WAIT_MS;
        Runnable poll = new Runnable() {
            @Override
            public void run() {
                if (hasIncomingBuffer() || System.currentTimeMillis() >= deadlineMs) {
                    action.run();
                    return;
                }
                handler.postDelayed(this, 50);
            }
        };
        handler.post(poll);
    }

    /**
     * Tops up the player's own playlist so {@link #PRELOAD_AHEAD} not-yet-
     * watched videos are always queued (ExoPlayer buffers upcoming playlist
     * items in the background while the current one plays), and trims
     * already-watched items beyond {@link #HISTORY_KEEP} so the playlist
     * doesn't grow unbounded over a long session.
     */
    private void refillPlaylist() {
        if (playbackMode == PlaybackMode.CREATOR) {
            syncCreatorPlaylistTail();
            trimPlaylistHead();
            return;
        }
        while (playlist.size() - (playlistIndex + 1) < PRELOAD_AHEAD) {
            FeedVideo next = pollNextFeedVideo();
            if (next == null) break;
            playlist.add(next);
            enqueueVideo(next);
            SimpleImageLoader.preload(next.coverUrl);
        }
        trimPlaylistHead();
    }

    private void trimPlaylistHead() {
        while (playlistIndex > HISTORY_KEEP) {
            FeedVideo dropped = playlist.remove(0);
            resumePositions.remove(dropped.awemeId);
            player.removeMediaItem(0);
            playlistIndex--;
        }
    }

    /**
     * Saves {@link #current}'s live playback position into
     * {@link #resumePositions} before navigating away from it, unless it
     * just finished naturally (in which case any stale saved position is
     * cleared instead, so a full rewatch starts from 0 rather than the end).
     */
    private void rememberCurrentPosition() {
        if (current == null || player == null) return;
        long duration = player.getDuration();
        long position = player.getCurrentPosition();
        boolean finished = player.getPlaybackState() == Player.STATE_ENDED
                || (duration != C.TIME_UNSET && duration > 0 && position >= duration - 500);
        if (finished) {
            resumePositions.remove(current.awemeId);
            markVideoFullyWatched(current);
        } else {
            if (position >= WATCHED_THRESHOLD_MS
                    || (duration != C.TIME_UNSET && duration > 0 && position >= duration * 0.3)) {
                markVideoPartiallyWatched(current, position);
            }
            resumePositions.put(current.awemeId, position);
        }
    }

    private void markVideoFullyWatched(FeedVideo video) {
        repository.markConsumed(video.awemeId);
        if (loggedIn && player != null) {
            long duration = player.getDuration();
            long playMs = duration != C.TIME_UNSET && duration > 0 ? duration : WATCHED_THRESHOLD_MS;
            pump.reportPlay(video.awemeId, playMs);
        }
    }

    private void markVideoPartiallyWatched(FeedVideo video, long playMs) {
        repository.markConsumed(video.awemeId);
        if (loggedIn) pump.reportPlay(video.awemeId, playMs);
    }

    private void applyCurrentUi() {
        if (playlistIndex < 0 || playlistIndex >= playlist.size()) return;
        FeedVideo video = playlist.get(playlistIndex);
        current = video;
        applyPageVisuals(pageA, video);
        applySharedUi(video);
    }

    private void applyPageVisuals(VideoSlidePage page, FeedVideo video) {
        page.coverImage.animate().cancel();
        page.coverImage.setTag(video.coverUrl);
        page.backdropImage.setTag(video.coverUrl);
        if (page == playerPage) {
            page.resetCoverMaskForPlayback();
        }
        if (SimpleImageLoader.applyCachedCover(video.coverUrl, page.coverImage, page.backdropImage)) {
            page.revealCoverMask();
        } else {
            page.showBlackUntilCover();
            SimpleImageLoader.loadWithBackdrop(
                    video.coverUrl, page.coverImage, page.backdropImage, page::revealCoverMask);
        }
        SimpleImageLoader.preload(video.coverUrl);
    }

    private void applySharedUi(FeedVideo video) {
        ensureFeedPumpForFeedPlayback();
        SimpleImageLoader.load(video.authorAvatarUrl, avatarImage);
        updateInfoPanel(video);
        updateLikeUi(video);
        updateCollectUi(video);
        commentCountText.setText(formatCount(video.commentCount));
        updateProgressUi();
        showControls();
    }

    private void updateInfoPanel(FeedVideo video) {
        boolean hasAuthor = video.authorName != null && !video.authorName.isEmpty();
        authorText.setVisibility(hasAuthor ? View.VISIBLE : View.GONE);
        authorText.setText(hasAuthor ? "@" + video.authorName : "");
        titleText.setText(highlightHashtags(video.desc == null ? "" : video.desc));
        // TikTok TV 风格的 "♫ 音乐名" 行
        boolean hasMusic = video.musicTitle != null && !video.musicTitle.isEmpty();
        musicText.setVisibility(hasMusic ? View.VISIBLE : View.GONE);
        musicText.setText(hasMusic ? "♫ " + video.musicTitle : "");
    }

    /** Colors "#tag" tokens to match douyin's own hashtag styling in the caption/title. */
    private static CharSequence highlightHashtags(String desc) {
        SpannableString spannable = new SpannableString(desc);
        Matcher matcher = HASHTAG_PATTERN.matcher(desc);
        while (matcher.find()) {
            spannable.setSpan(new ForegroundColorSpan(HASHTAG_COLOR),
                    matcher.start(), matcher.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return spannable;
    }

    private void updateLikeUi(FeedVideo video) {
        likeCountText.setText(formatCount(video.diggCount));
        if (likeIcon != null) {
            likeIcon.setColorFilter(0xFFFFFFFF);
        }
    }

    private void updateCollectUi(FeedVideo video) {
        collectCountText.setText(formatCount(video.collectCount));
    }

    /** Matches the "277.3K" / "1.2M" style douyin's own web UI displays counts in. */
    private static String formatCount(long count) {
        if (count >= 1_000_000) {
            return String.format(Locale.US, "%.1fM", count / 1_000_000.0);
        }
        if (count >= 1_000) {
            return String.format(Locale.US, "%.1fK", count / 1_000.0);
        }
        return String.valueOf(count);
    }

    /** Refreshes the position/duration labels, played fill, and buffered/preload fill. */
    private void updateProgressUi() {
        if (player == null) return;
        long position = Math.max(0, player.getCurrentPosition());
        long duration = player.getDuration();
        long buffered = player.getBufferedPosition();
        positionText.setText(formatTime(position));
        if (duration > 0 && duration != C.TIME_UNSET) {
            durationText.setText(formatTime(duration));
            int max = seekProgress.getMax();
            int played = (int) (position * max / duration);
            // Buffered/preloaded span: grows ahead of the playhead while loading.
            // Clamped to the current item's duration (100% = current video fully buffered).
            long bufferedEnd = Math.min(Math.max(buffered, position), duration);
            int bufferedPx = (int) (bufferedEnd * max / duration);
            seekProgress.setProgress(played);
            seekProgress.setSecondaryProgress(Math.max(bufferedPx, played));
        } else {
            durationText.setText("--:--");
            seekProgress.setProgress(0);
            seekProgress.setSecondaryProgress(0);
        }
    }

    /** mm:ss (matches the short-form clips this feed serves - no need for an hour digit). */
    private static String formatTime(long ms) {
        long totalSeconds = ms / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    private void showLoading(boolean loading) {
        if (loading) {
            loadingSpinner.setVisibility(View.VISIBLE);
            if (!loadingSpinner.isAnimating()) loadingSpinner.playAnimation();
        } else {
            loadingSpinner.pauseAnimation();
            loadingSpinner.setVisibility(View.GONE);
        }
    }

    /**
     * Loading spinner vs pause icon: spinner only while actively trying to
     * play through a buffer/feed wait; pause icon when the user chose to
     * wait (including mid-buffer).
     */
    private void updatePlaybackChrome() {
        if (player == null && !waitingForBuffer) return;

        boolean wantsPlay = player == null || player.getPlayWhenReady();
        int state = player != null ? player.getPlaybackState() : Player.STATE_IDLE;
        boolean feedLoading = waitingForBuffer && (playlist.isEmpty() || pendingAdvance != 0);

        if (feedLoading && autoStartWhenReady) {
            showLoading(true);
            pauseIcon.setVisibility(View.GONE);
        } else if (feedLoading && !autoStartWhenReady) {
            showLoading(false);
            pauseIcon.setVisibility(View.VISIBLE);
        } else if (state == Player.STATE_BUFFERING && wantsPlay) {
            showLoading(true);
            pauseIcon.setVisibility(View.GONE);
        } else if (!wantsPlay && (state == Player.STATE_BUFFERING || state == Player.STATE_READY)) {
            showLoading(false);
            pauseIcon.setVisibility(View.VISIBLE);
        } else {
            showLoading(false);
            pauseIcon.setVisibility(View.GONE);
        }
    }

    @Override
    public void onBufferChanged(int size) {
        if (playbackMode == PlaybackMode.CREATOR) {
            onCreatorBufferChanged();
            return;
        }
        if (size <= 0 || !waitingForBuffer) return;

        cancelStartupTimeout();

        if (!autoStartWhenReady) {
            prepareFeedWhilePaused();
            waitingForBuffer = false;
            updatePlaybackChrome();
            return;
        }

        waitingForBuffer = false;
        if (playlistIndex < 0) {
            startInitialPlayback();
        } else if (pendingAdvance != 0) {
            int advanceBy = pendingAdvance;
            pendingAdvance = 0;
            if (advanceBy > 0) {
                playNext();
            } else {
                playPrevious();
            }
        } else {
            refillPlaylist();
            updatePlaybackChrome();
        }
    }

    /**
     * Feed data arrived while the user paused: queue/prepare without starting
     * playback, and fully preload upcoming items in the background.
     */
    private void prepareFeedWhilePaused() {
        if (playlistIndex < 0) {
            refillPlaylist();
            if (playlist.isEmpty()) return;
            playlistIndex = 0;
            refillPlaylist();
            applyCurrentUi();
            player.prepare();
            player.setPlayWhenReady(false);
        } else {
            refillPlaylist();
        }
        adjustPreloadForPause();
    }

    private void setupCreatorGrid() {
        creatorGridOverlay = findViewById(R.id.creatorGridOverlay);
        creatorGridContent = findViewById(R.id.creatorGridContent);
        creatorGridTitle = findViewById(R.id.creatorGridTitle);
        creatorGridCount = findViewById(R.id.creatorGridCount);
        creatorGridLoadingPanel = findViewById(R.id.creatorGridLoadingPanel);
        creatorGridLoading = findViewById(R.id.creatorGridLoading);
        creatorGridList = findViewById(R.id.creatorGridList);
        ImageView creatorGridAvatar = findViewById(R.id.creatorGridAvatar);
        makeCircular(creatorGridAvatar);

        creatorGridAdapter = new CreatorGridAdapter();
        creatorGridAdapter.setOnVideoSelectedListener(this::startCreatorPlayback);
        creatorGridList.setLayoutManager(new GridLayoutManager(this, CREATOR_GRID_COLUMNS));
        creatorGridList.setAdapter(creatorGridAdapter);
        creatorGridList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (!creatorGridVisible) return;
                maybeLoadMoreCreatorVideos(recyclerView);
            }
        });
        creatorGridAdapter.setOnBindNearEndListener(position -> {
            if (!creatorGridVisible) return;
            int total = creatorGridAdapter.getItemCount();
            if (total > 0 && position >= total - CREATOR_GRID_COLUMNS * 2) {
                creatorRepository.kickstartPaging();
            }
        });
    }

    private void maybeLoadMoreCreatorVideos(RecyclerView recyclerView) {
        GridLayoutManager layoutManager = (GridLayoutManager) recyclerView.getLayoutManager();
        if (layoutManager == null || creatorGridAdapter == null) return;
        int total = creatorGridAdapter.getItemCount();
        if (total <= 0) return;
        int lastVisible = layoutManager.findLastVisibleItemPosition();
        // Prefetch when the last ~2 rows are on screen (TV D-pad may report dy=0).
        if (lastVisible >= total - CREATOR_GRID_COLUMNS * 2) {
            creatorRepository.kickstartPaging();
        }
    }

    private void openCreatorGrid() {
        if (creatorGridVisible || current == null) return;
        String secUid = current.authorSecUid;
        if (secUid == null || secUid.isEmpty()) {
            Toast.makeText(this, "无法打开该博主主页", Toast.LENGTH_SHORT).show();
            return;
        }

        if (playbackMode == PlaybackMode.CREATOR) {
            playbackMode = PlaybackMode.FEED;
        }

        creatorGridAdapter.setVideos(Collections.emptyList());
        creatorGridAwaitingPumpData = true;

        saveFeedAnchor();
        if (player != null) {
            player.pause();
        }

        creatorGridVisible = true;
        creatorGridOverlay.setVisibility(View.VISIBLE);

        creatorRepository.setCreator(secUid, current.authorName, current.authorAvatarUrl);
        // Show the current video immediately while /aweme/post loads.
        creatorRepository.addVideoIfMatching(current);
        if (creatorRepository.size() > 0) {
            creatorGridAwaitingPumpData = false;
        }

        String activeSecUid = pump.getActiveCreatorSecUid();
        if (pump.isCreatorMode() && secUid.equals(activeSecUid)) {
            creatorRepository.kickstartPaging();
        } else {
            // Direct signed /aweme/post fetch — do not also kickstart (avoids double request).
            pump.loadCreatorProfile(secUid);
        }

        updateCreatorGridUi();
        creatorGridList.post(() -> {
            if (creatorGridList.getChildCount() > 0) {
                creatorGridList.getChildAt(0).requestFocus();
            } else {
                creatorGridList.requestFocus();
            }
        });
    }

    private void ensureFeedPumpForFeedPlayback() {
        if (playbackMode != PlaybackMode.FEED || creatorGridVisible) return;
        if (pump.isCreatorMode()) {
            creatorRepository.reset();
            pump.restoreFeedPump();
        } else if (creatorRepository.getCreatorSecUid() != null) {
            creatorRepository.reset();
        }
    }

    private void closeCreatorGrid() {
        creatorGridVisible = false;
        creatorGridAwaitingPumpData = false;
        creatorGridOverlay.setVisibility(View.GONE);
        creatorGridAdapter.setVideos(Collections.emptyList());
        creatorRepository.reset();
        pump.restoreFeedPump();
        restoreFeedToAnchor();
    }

    /** Remembers the feed video the user was on when opening a creator profile. */
    private void saveFeedAnchor() {
        feedAnchorVideo = current;
        feedAnchorPositionMs = player != null ? player.getCurrentPosition() : 0;
    }

    /**
     * Returns to {@link #feedAnchorVideo} as playlist index {@code 0} and
     * resumes playback at the saved position. Next/preload items are added
     * only after the user navigates with up/down.
     */
    private void restoreFeedToAnchor() {
        playbackMode = PlaybackMode.FEED;
        waitingForBuffer = false;
        pendingAdvance = 0;
        autoStartWhenReady = true;

        if (feedAnchorVideo == null || player == null) {
            updatePlaybackChrome();
            return;
        }

        rebuildFeedPlaylistFromAnchor();
    }

    private void rebuildFeedPlaylistFromAnchor() {
        ignorePlaybackEndedUntilMs = System.currentTimeMillis() + 800;

        playlist.clear();
        player.clearMediaItems();
        playlist.add(feedAnchorVideo);
        playlistIndex = 0;

        enqueueVideo(feedAnchorVideo);

        current = feedAnchorVideo;
        player.prepare();
        player.seekTo(0, feedAnchorPositionMs);
        applyCurrentUi();
        player.setPlayWhenReady(true);
        updatePlaybackChrome();
    }

    private boolean isDuplicateForFeedPlaylist(FeedVideo video) {
        if (video == null) return true;
        if (playlistContains(video.awemeId)) return true;
        return feedAnchorVideo != null && feedAnchorVideo.awemeId.equals(video.awemeId);
    }

    /** Drains {@link FeedRepository} until a non-duplicate feed item appears. */
    private FeedVideo pollNextFeedVideo() {
        for (int attempt = 0; attempt < 32; attempt++) {
            FeedVideo next = repository.pollNext();
            if (next == null) return null;
            if (WatchedAwemeStore.getInstance().isWatched(next.awemeId)) continue;
            if (!isDuplicateForFeedPlaylist(next)) return next;
        }
        return null;
    }

    /**
     * After account history sync, drop upcoming playlist items that were
     * already watched on phone/web so they never reach ExoPlayer.
     */
    private void purgeWatchedFromUpcomingPlaylist() {
        if (playbackMode == PlaybackMode.CREATOR || player == null) return;
        WatchedAwemeStore store = WatchedAwemeStore.getInstance();
        int removed = 0;
        for (int i = playlist.size() - 1; i > playlistIndex; i--) {
            FeedVideo video = playlist.get(i);
            if (video == null || !store.isWatched(video.awemeId)) continue;
            playlist.remove(i);
            if (i < player.getMediaItemCount()) {
                player.removeMediaItem(i);
            }
            resumePositions.remove(video.awemeId);
            removed++;
        }
        if (removed > 0) {
            Log.i(TAG, "purged " + removed + " watched items from upcoming playlist");
            refillPlaylist();
        }
    }

    private void exitCreatorPlaybackToGrid() {
        if (player != null) {
            player.pause();
        }
        creatorGridVisible = true;
        creatorGridOverlay.setVisibility(View.VISIBLE);
        updateCreatorGridUi();
        creatorGridList.post(() -> {
            int focusIndex = current != null
                    ? creatorRepository.indexOf(current.awemeId) : 0;
            if (focusIndex < 0) focusIndex = 0;
            final int focusPos = focusIndex;
            creatorGridList.scrollToPosition(focusPos);
            creatorGridList.post(() -> {
                View child = creatorGridList.getLayoutManager() != null
                        ? creatorGridList.getLayoutManager().findViewByPosition(focusPos)
                        : null;
                if (child != null) {
                    child.requestFocus();
                } else if (creatorGridList.getChildCount() > 0) {
                    creatorGridList.getChildAt(0).requestFocus();
                }
            });
        });
    }

    private void startCreatorPlayback(int index) {
        List<FeedVideo> videos = creatorRepository.snapshot();
        if (index < 0 || index >= videos.size()) return;

        creatorGridVisible = false;
        creatorGridOverlay.setVisibility(View.GONE);
        playbackMode = PlaybackMode.CREATOR;
        waitingForBuffer = false;
        pendingAdvance = 0;
        pendingCoverDismiss = false;
        handler.removeCallbacks(coverDismissFallbackRunnable);

        playlist.clear();
        slideAnimating = false;
        pageA.clearSnapshot();
        pageB.clearSnapshot();
        pageA.detachPlayer();
        player.stop();
        player.clearMediaItems();
        pageA.attachPlayer(player);
        pageB.detachPlayer();
        pageA.root.setTranslationY(0);
        playerPage = pageA;
        playerView = pageA.playerView;
        coverImage = pageA.coverImage;
        backdropImage = pageA.backdropImage;
        showActivePage();
        slideHost.post(() -> pageB.stageForSlide(slideHost.getHeight()));

        playlist.addAll(videos);
        playlistIndex = index;

        for (FeedVideo video : playlist) {
            enqueueVideo(video);
            SimpleImageLoader.preload(video.coverUrl);
        }

        player.prepare();
        player.seekTo(playlistIndex, 0);
        applyCurrentUi();
        player.setPlayWhenReady(true);
        player.play();
        adjustPreloadForPlay();
        scheduleCoverDismissFallback();
        updatePlaybackChrome();
        refillPlaylist();
    }

    private void updateCreatorGridUi() {
        String nickname = creatorRepository.getCreatorNickname();
        creatorGridTitle.setText(nickname != null && !nickname.isEmpty()
                ? "@" + nickname : "博主作品");
        ImageView avatar = findViewById(R.id.creatorGridAvatar);
        SimpleImageLoader.load(creatorRepository.getCreatorAvatarUrl(), avatar);

        List<FeedVideo> videos = creatorRepository.snapshot();
        creatorGridAdapter.setVideos(videos);
        creatorGridCount.setText(videos.size() + " 个视频");
        setCreatorGridLoading(creatorGridAwaitingPumpData);
    }

    private void setCreatorGridLoading(boolean loading) {
        creatorGridLoadingPanel.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (creatorGridContent != null) {
            creatorGridContent.setVisibility(loading ? View.GONE : View.VISIBLE);
        }
        creatorGridList.setVisibility(loading ? View.INVISIBLE : View.VISIBLE);
        if (loading) {
            creatorGridLoadingPanel.bringToFront();
            creatorGridLoading.setVisibility(View.VISIBLE);
            if (!creatorGridLoading.isAnimating()) {
                creatorGridLoading.playAnimation();
            }
        } else {
            creatorGridLoading.pauseAnimation();
        }
    }

    private void onCreatorVideosChanged(int size) {
        if (creatorGridVisible && creatorGridAwaitingPumpData && size > 0) {
            creatorGridAwaitingPumpData = false;
        }
        if (creatorGridVisible) {
            updateCreatorGridUi();
            if (size > 0 && creatorGridList.getFocusedChild() == null) {
                creatorGridList.post(() -> {
                    if (creatorGridList.getChildCount() > 0) {
                        creatorGridList.getChildAt(0).requestFocus();
                    }
                });
            }
        }
        if (playbackMode == PlaybackMode.CREATOR && waitingForBuffer) {
            onCreatorBufferChanged();
        }
    }

    private void onCreatorBufferChanged() {
        if (!waitingForBuffer) return;
        syncCreatorPlaylistTail();
        if (playlistIndex + 1 < playlist.size()) {
            waitingForBuffer = false;
            pendingAdvance = 0;
            playNext();
        }
    }

    private void syncCreatorPlaylistTail() {
        for (FeedVideo video : creatorRepository.snapshot()) {
            if (!playlistContains(video.awemeId)) {
                playlist.add(video);
                enqueueVideo(video);
                SimpleImageLoader.preload(video.coverUrl);
            }
        }
        if (playlist.size() - (playlistIndex + 1) < PRELOAD_AHEAD && creatorRepository.hasMore()) {
            creatorRepository.kickstartPaging();
        }
    }

    private boolean playlistContains(String awemeId) {
        if (awemeId == null) return false;
        for (FeedVideo video : playlist) {
            if (awemeId.equals(video.awemeId)) return true;
        }
        return false;
    }
}
