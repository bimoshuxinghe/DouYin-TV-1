package mulin.tvdy.data;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * A single playable feed item, distilled from a raw {@code aweme_list} entry
 * returned by douyin's {@code /aweme/v2/web/module/feed/} endpoint. Items
 * without a usable {@code video.play_addr} (ad cards, live cards, etc.) never
 * become a {@link FeedVideo} - see {@link #fromAwemeItem}.
 */
public final class FeedVideo {

    private static final String TAG = "TvdyFeedDump";
    private static final int MAX_DIAGNOSTIC_DUMPS = 0;
    private static int diagnosticDumpsRemaining = MAX_DIAGNOSTIC_DUMPS;

    public final String awemeId;
    public final String desc;
    public final String coverUrl;
    public final String playUrl;
    /** Alternate CDN URLs to try when {@link #playUrl} returns 403. */
    public final List<String> playUrlCandidates;
    /**
     * Browser-style separate video+audio progressive URLs (e.g. media-video-hvc1
     * + media-audio-und-mp4a), ordered lowest video bitrate first.
     */
    public final List<SplitPlayUrl> splitCandidates;
    public final String authorName;
    public final String authorAvatarUrl;
    /** Douyin author.sec_uid — opens {@code /user/{sec_uid}} profile pages. */
    public final String authorSecUid;
    public final String authorUniqueId;

    /**
     * Server-reported counts at the time this item was fetched (display-only).
     */
    public final long diggCount;
    public final long commentCount;
    public final long collectCount;
    public final long shareCount;
    /** Whether the logged-in account had already digged this item in the feed payload. */
    public final boolean userDigged;

    /**
     * Music/track title from the aweme's music object, shown in the
     * TikTok-TV-style "♫" info row. Mutable (not final) so the snapshot
     * round-trip can restore it without changing the constructor signature
     * that the JVM unit test also uses; absent for older snapshots.
     */
    public String musicTitle;

    /** Paired video-only + audio-only progressive CDN URLs. */
    public static final class SplitPlayUrl {
        public final String videoUrl;
        public final String audioUrl;
        /** Approximate video bitrate in kbps ({@code br=} query or bit_rate/1000). */
        public final int videoBrKbps;
        public final boolean hevc;

        public SplitPlayUrl(String videoUrl, String audioUrl, int videoBrKbps, boolean hevc) {
            this.videoUrl = videoUrl;
            this.audioUrl = audioUrl;
            this.videoBrKbps = videoBrKbps;
            this.hevc = hevc;
        }

        @Override
        public String toString() {
            return "SplitPlayUrl{br=" + videoBrKbps + ", hevc=" + hevc
                    + ", video=" + shortUrl(videoUrl) + ", audio=" + shortUrl(audioUrl) + "}";
        }
    }

    private FeedVideo(String awemeId, String desc, String coverUrl, String playUrl,
                      List<String> playUrlCandidates,
                      List<SplitPlayUrl> splitCandidates,
                      String authorName, String authorAvatarUrl,
                      String authorSecUid, String authorUniqueId,
                      long diggCount, long commentCount, long collectCount, long shareCount,
                      boolean userDigged) {
        this.awemeId = awemeId;
        this.desc = desc;
        this.coverUrl = coverUrl;
        this.playUrl = playUrl;
        this.playUrlCandidates = playUrlCandidates;
        this.splitCandidates = splitCandidates;
        this.authorName = authorName;
        this.authorAvatarUrl = authorAvatarUrl;
        this.authorSecUid = authorSecUid;
        this.authorUniqueId = authorUniqueId;
        this.diggCount = diggCount;
        this.commentCount = commentCount;
        this.collectCount = collectCount;
        this.shareCount = shareCount;
        this.userDigged = userDigged;
    }

    /**
     * Returns {@code true} when Douyin's payload marks this item as already
     * consumed on the account (phone/web history), so it should not enter the
     * playback queue even on the first fetch after login.
     */
    public static boolean isMarkedWatchedByServer(JSONObject item) {
        if (item == null) return false;
        if (item.optBoolean("is_watched", false)) return true;
        if (item.optBoolean("watch_status", false)) return true;
        int itemWatchStatus = item.optInt("item_watch_status", -1);
        if (itemWatchStatus == 1 || itemWatchStatus == 2) return true;
        JSONObject video = item.optJSONObject("video");
        if (video != null) {
            if (video.optBoolean("is_watched", false)) return true;
            if (video.optInt("watch_progress", 0) > 0) return true;
        }
        return false;
    }

    /**
     * @return a {@link FeedVideo}, or {@code null} if this item has no
     * {@code aweme_id} or no directly playable url (which is the case for
     * ads, live-stream cards, and a few other non-video card types mixed
     * into the feed).
     */
    public static FeedVideo fromAwemeItem(JSONObject item) {
        if (item == null) return null;

        String awemeId = item.optString("aweme_id", "");
        if (awemeId.isEmpty()) return null;

        JSONObject video = item.optJSONObject("video");
        if (video == null) return null;

        dumpVideoDiagnostics(awemeId, video);

        List<SplitPlayUrl> splits = collectSplitPlayUrls(video);
        List<String> candidates = collectPlayUrls(video);
        if (candidates.isEmpty() && splits.isEmpty()) return null;
        String playUrl = !candidates.isEmpty() ? candidates.get(0) : splits.get(0).videoUrl;

        // Field name for the cover image varies across item types; try the
        // common ones in order of preference. Not load-bearing for playback,
        // so any failure here just leaves coverUrl null.
        String coverUrl = firstUrl(video.optJSONObject("cover"));
        if (coverUrl == null) coverUrl = firstUrl(video.optJSONObject("origin_cover"));
        if (coverUrl == null) coverUrl = firstUrl(video.optJSONObject("dynamic_cover"));

        String desc = item.optString("desc", "");

        JSONObject author = item.optJSONObject("author");
        String authorName = author != null ? author.optString("nickname", "") : "";
        String authorSecUid = author != null ? author.optString("sec_uid", "") : "";
        String authorUniqueId = author != null ? author.optString("unique_id", "") : "";
        String authorAvatarUrl = null;
        if (author != null) {
            authorAvatarUrl = firstUrl(author.optJSONObject("avatar_thumb"));
            if (authorAvatarUrl == null) authorAvatarUrl = firstUrl(author.optJSONObject("avatar_medium"));
            if (authorAvatarUrl == null) authorAvatarUrl = firstUrl(author.optJSONObject("avatar_larger"));
        }

        JSONObject statistics = item.optJSONObject("statistics");
        long diggCount = statistics != null ? statistics.optLong("digg_count", 0) : 0;
        long commentCount = statistics != null ? statistics.optLong("comment_count", 0) : 0;
        long collectCount = statistics != null ? statistics.optLong("collect_count", 0) : 0;
        long shareCount = statistics != null ? statistics.optLong("share_count", 0) : 0;
        boolean userDigged = item.optInt("user_digged", 0) == 1
                || item.optBoolean("user_digged", false);

        FeedVideo v = new FeedVideo(awemeId, desc, coverUrl, playUrl, candidates, splits,
                authorName, authorAvatarUrl, authorSecUid, authorUniqueId,
                diggCount, commentCount, collectCount, shareCount, userDigged);
        JSONObject music = item.optJSONObject("music");
        if (music != null) {
            String musicTitle = music.optString("title", "").trim();
            if (!musicTitle.isEmpty()) v.musicTitle = musicTitle;
        }
        return v;
    }

    /** True when this item has at least one video+audio split pair. */
    public boolean hasSplitStream() {
        return splitCandidates != null && !splitCandidates.isEmpty();
    }

    // --- Snapshot (de)serialization -----------------------------------------
    //
    // A cold start used to re-parse the raw aweme items the pump captured:
    // 2.8MB of JSON that org.json walks in ~2.5s, then ~1.8s more in
    // fromAwemeItem's full-tree URL scans (both measured on the TV). Almost all
    // of those bytes are fields nothing here reads. This pair writes only the
    // distilled fields above, so a later launch rebuilds each FeedVideo by
    // filling in a constructor instead of re-deriving it from the raw payload -
    // no collectPlayUrls, no collectSplitPlayUrls, no tree walk.
    //
    // Field names are short because they dominate the file: a signed CDN URL is
    // 200-400 chars and there can be a dozen per video. See SNAPSHOT_MAX_* for
    // why the candidate lists are trimmed rather than stored whole.

    /**
     * Candidates kept per video in a snapshot. Playback retries these in order
     * when one returns 403, and every candidate for an item shares the same
     * signature issue, so a handful of fallbacks is as good as the full list -
     * while the full list is most of the file. The pump reloads fresh data
     * shortly after any cold start anyway, so this only has to survive the gap.
     */
    private static final int SNAPSHOT_MAX_MUXED = 4;
    private static final int SNAPSHOT_MAX_SPLITS = 4;

    /**
     * Distilled form of this video, for {@code FeedSnapshotStore}.
     *
     * @throws JSONException only if a field name is null; the values written
     *         here cannot fail to serialise.
     */
    public JSONObject toSnapshotJson() throws JSONException {
        JSONObject o = new JSONObject();
        putIfNotEmpty(o, "i", awemeId);
        putIfNotEmpty(o, "p", playUrl);
        putIfNotEmpty(o, "c", coverUrl);
        putIfNotEmpty(o, "d", desc);
        putIfNotEmpty(o, "an", authorName);
        putIfNotEmpty(o, "au", authorAvatarUrl);
        putIfNotEmpty(o, "as", authorSecUid);
        putIfNotEmpty(o, "ai", authorUniqueId);
        if (diggCount != 0) o.put("dg", diggCount);
        if (commentCount != 0) o.put("cm", commentCount);
        if (collectCount != 0) o.put("cl", collectCount);
        if (shareCount != 0) o.put("sh", shareCount);
        if (userDigged) o.put("ud", true);
        putIfNotEmpty(o, "mt", musicTitle);

        if (playUrlCandidates != null && !playUrlCandidates.isEmpty()) {
            JSONArray muxed = new JSONArray();
            for (int i = 0; i < playUrlCandidates.size() && i < SNAPSHOT_MAX_MUXED; i++) {
                muxed.put(playUrlCandidates.get(i));
            }
            o.put("pm", muxed);
        }

        if (splitCandidates != null && !splitCandidates.isEmpty()) {
            JSONArray splits = new JSONArray();
            for (int i = 0; i < splitCandidates.size() && i < SNAPSHOT_MAX_SPLITS; i++) {
                SplitPlayUrl s = splitCandidates.get(i);
                JSONObject e = new JSONObject();
                putIfNotEmpty(e, "v", s.videoUrl);
                putIfNotEmpty(e, "a", s.audioUrl);
                if (s.videoBrKbps > 0) e.put("br", s.videoBrKbps);
                if (s.hevc) e.put("h", true);
                splits.put(e);
            }
            o.put("sp", splits);
        }
        return o;
    }

    /**
     * Rebuilds a video written by {@link #toSnapshotJson}.
     *
     * @return the video, or {@code null} when the entry has no id or nothing
     *         playable - the same bar {@link #fromAwemeItem} applies, so a
     *         snapshot that lost its URLs to expiry is dropped here rather than
     *         handed to the player empty.
     */
    public static FeedVideo fromSnapshotJson(JSONObject o) {
        if (o == null) return null;
        String awemeId = o.optString("i", "");
        if (awemeId.isEmpty()) return null;

        List<String> muxed = new ArrayList<>();
        JSONArray muxedArr = o.optJSONArray("pm");
        if (muxedArr != null) {
            for (int i = 0; i < muxedArr.length(); i++) {
                String url = muxedArr.optString(i, "");
                if (!url.isEmpty()) muxed.add(url);
            }
        }

        List<SplitPlayUrl> splits = new ArrayList<>();
        JSONArray splitArr = o.optJSONArray("sp");
        if (splitArr != null) {
            for (int i = 0; i < splitArr.length(); i++) {
                JSONObject e = splitArr.optJSONObject(i);
                if (e == null) continue;
                String v = e.optString("v", "");
                String a = e.optString("a", "");
                if (v.isEmpty() || a.isEmpty()) continue;
                splits.add(new SplitPlayUrl(v, a, e.optInt("br", 0), e.optBoolean("h", false)));
            }
        }

        String playUrl = o.optString("p", "");
        if (playUrl.isEmpty()) {
            if (!muxed.isEmpty()) playUrl = muxed.get(0);
            else if (!splits.isEmpty()) playUrl = splits.get(0).videoUrl;
        }
        if (playUrl.isEmpty()) return null;

        FeedVideo v = new FeedVideo(awemeId, o.optString("d", ""), o.optString("c", null),
                playUrl, muxed, splits,
                o.optString("an", ""), o.optString("au", null),
                o.optString("as", ""), o.optString("ai", ""),
                o.optLong("dg", 0), o.optLong("cm", 0),
                o.optLong("cl", 0), o.optLong("sh", 0),
                o.optBoolean("ud", false));
        String musicTitle = o.optString("mt", "");
        if (!musicTitle.isEmpty()) v.musicTitle = musicTitle;
        return v;
    }

    private static void putIfNotEmpty(JSONObject o, String key, String value)
            throws JSONException {
        if (value != null && !value.isEmpty()) o.put(key, value);
    }

    /** Builds a video directly, for snapshot round-trip tests only. */
    static FeedVideo forSnapshotTest(String awemeId, String desc, String coverUrl, String playUrl,
                                     List<String> playUrlCandidates, List<SplitPlayUrl> splitCandidates,
                                     String authorName, String authorAvatarUrl,
                                     String authorSecUid, String authorUniqueId,
                                     long diggCount, long commentCount, long collectCount,
                                     long shareCount, boolean userDigged) {
        return new FeedVideo(awemeId, desc, coverUrl, playUrl, playUrlCandidates, splitCandidates,
                authorName, authorAvatarUrl, authorSecUid, authorUniqueId,
                diggCount, commentCount, collectCount, shareCount, userDigged);
    }

    /**
     * Temporary POC dump: video keys, bit_rate summary, and whether url_list
     * entries look like browser split streams. Caps volume so logcat stays usable.
     */
    static void dumpVideoDiagnostics(String awemeId, JSONObject video) {
        if (diagnosticDumpsRemaining <= 0 || video == null) return;
        diagnosticDumpsRemaining--;
        try {
            dumpVideoDiagnosticsUnsafe(awemeId, video);
        } catch (Throwable ignored) {
            // android.util.Log is unavailable in plain JVM unit tests.
        }
    }

    private static void dumpVideoDiagnosticsUnsafe(String awemeId, JSONObject video) {
        StringBuilder keys = new StringBuilder();
        Iterator<String> it = video.keys();
        while (it.hasNext()) {
            if (keys.length() > 0) keys.append(',');
            keys.append(it.next());
        }
        Log.i(TAG, "awemeId=" + awemeId + " video.keys=[" + keys + "]");

        List<String> allUrls = new ArrayList<>();
        collectAllUrls(video, allUrls);
        int mediaVideo = 0;
        int mediaAudio = 0;
        int hvc1 = 0;
        int avc1 = 0;
        for (String url : allUrls) {
            String lower = url.toLowerCase(Locale.US);
            if (lower.contains("media-video-")) mediaVideo++;
            if (lower.contains("media-audio-")) mediaAudio++;
            if (lower.contains("hvc1") || lower.contains("bytevc1")) hvc1++;
            if (lower.contains("avc1") || lower.contains("h264")) avc1++;
        }
        Log.i(TAG, "awemeId=" + awemeId
                + " urlCount=" + allUrls.size()
                + " media-video=" + mediaVideo
                + " media-audio=" + mediaAudio
                + " hvc1=" + hvc1
                + " avc1=" + avc1);

        JSONArray bitRates = video.optJSONArray("bit_rate");
        if (bitRates != null) {
            int n = Math.min(bitRates.length(), 8);
            for (int i = 0; i < n; i++) {
                JSONObject br = bitRates.optJSONObject(i);
                if (br == null) continue;
                String first = firstUrl(br.optJSONObject("play_addr"));
                Log.i(TAG, "awemeId=" + awemeId
                        + " bit_rate[" + i + "]"
                        + " br=" + br.optInt("bit_rate", br.optInt("br", -1))
                        + " gear=" + br.optString("gear_name", "")
                        + " is_h265=" + br.optInt("is_h265", br.optInt("is_bytevc1", -1))
                        + " codec_type=" + br.optString("codec_type", "")
                        + " keys=" + objectKeys(br)
                        + " url=" + shortUrl(first));
            }
        }

        for (String audioKey : new String[]{
                "bit_rate_audio", "bit_rate_audio_list", "audio", "play_addr_h264",
                "play_addr_265", "play_addr_bytevc1", "play_addr_h265", "download_addr"}) {
            if (video.has(audioKey)) {
                Log.i(TAG, "awemeId=" + awemeId + " hasField=" + audioKey
                        + " type=" + typeName(video.opt(audioKey)));
            }
        }
        dumpAudioFieldSamples(awemeId, video);
        for (String url : allUrls) {
            if (url.toLowerCase(Locale.US).contains("media-video-")) {
                Log.i(TAG, "awemeId=" + awemeId + " sampleMediaVideo=" + shortUrl(url));
                break;
            }
        }

        List<SplitPlayUrl> splits = collectSplitPlayUrls(video);
        Log.i(TAG, "awemeId=" + awemeId + " splitPairs=" + splits.size()
                + " splitVideos=" + countSplitVideos(video)
                + " splitAudios=" + countSplitAudios(video)
                + (splits.isEmpty() ? "" : " best=" + splits.get(0)));
    }

    private static void dumpAudioFieldSamples(String awemeId, JSONObject video) {
        JSONArray audioRates = video.optJSONArray("bit_rate_audio");
        if (audioRates != null) {
            int n = Math.min(audioRates.length(), 3);
            for (int i = 0; i < n; i++) {
                JSONObject entry = audioRates.optJSONObject(i);
                if (entry == null) continue;
                String first = firstUrlDeep(entry);
                Log.i(TAG, "awemeId=" + awemeId + " bit_rate_audio[" + i + "]"
                        + " keys=" + objectKeys(entry)
                        + " br=" + bitrateKbps(entry)
                        + " url=" + shortUrl(first));
                Object meta = entry.opt("audio_meta");
                if (meta != null) {
                    Log.i(TAG, "awemeId=" + awemeId + " audio_meta"
                            + " type=" + typeName(meta)
                            + " preview=" + previewValue(meta, 240));
                    if (meta instanceof JSONObject) {
                        JSONObject metaObj = (JSONObject) meta;
                        Log.i(TAG, "awemeId=" + awemeId + " audio_meta.keys="
                                + objectKeys(metaObj)
                                + " br=" + bitrateKbps(metaObj)
                                + " format=" + metaObj.optString("format", "")
                                + " deepUrl=" + shortUrl(firstUrlDeep(metaObj)));
                    }
                }
                Object extra = entry.opt("audio_extra");
                if (extra != null) {
                    Log.i(TAG, "awemeId=" + awemeId + " audio_extra"
                            + " type=" + typeName(extra)
                            + " preview=" + previewValue(extra, 200));
                }
            }
        }
        JSONObject audio = video.optJSONObject("audio");
        if (audio != null) {
            Log.i(TAG, "awemeId=" + awemeId + " audio.keys=" + objectKeys(audio)
                    + " url=" + shortUrl(firstUrlDeep(audio)));
        }
        Object model = video.opt("video_model");
        if (model != null) {
            String preview = previewValue(model, 280);
            boolean hasAudioPath = preview.toLowerCase(Locale.US).contains("media-audio")
                    || preview.toLowerCase(Locale.US).contains("AdaptationSet".toLowerCase(Locale.US))
                    || preview.contains("\"audio\"");
            Log.i(TAG, "awemeId=" + awemeId + " video_model type=" + typeName(model)
                    + " len=" + previewValue(model, Integer.MAX_VALUE).length()
                    + " hintsAudio=" + hasAudioPath
                    + " preview=" + preview);
        }
    }

    private static String previewValue(Object value, int maxChars) {
        if (value == null) return "";
        String s = String.valueOf(value);
        if (s.length() <= maxChars) return s;
        return s.substring(0, maxChars) + "…";
    }

    private static int countSplitVideos(JSONObject video) {
        List<UrlHit> v = new ArrayList<>();
        List<UrlHit> a = new ArrayList<>();
        collectSplitCandidates(video, v, a);
        return v.size();
    }

    private static int countSplitAudios(JSONObject video) {
        List<UrlHit> v = new ArrayList<>();
        List<UrlHit> a = new ArrayList<>();
        collectSplitCandidates(video, v, a);
        return a.size();
    }

    /** Visible for unit tests. */
    static List<SplitPlayUrl> collectSplitPlayUrls(JSONObject video) {
        List<UrlHit> videos = new ArrayList<>();
        List<UrlHit> audios = new ArrayList<>();
        collectSplitCandidates(video, videos, audios);

        LinkedHashSet<String> seenPairs = new LinkedHashSet<>();
        List<SplitPlayUrl> pairs = new ArrayList<>();

        // Prefer fid pairing (browser CDN links share fid=).
        for (UrlHit v : videos) {
            if (v.fid.isEmpty()) continue;
            UrlHit bestAudio = null;
            for (UrlHit a : audios) {
                if (!v.fid.equals(a.fid)) continue;
                if (bestAudio == null || a.brKbps < bestAudio.brKbps) bestAudio = a;
            }
            if (bestAudio == null) continue;
            String key = v.url + "|" + bestAudio.url;
            if (!seenPairs.add(key)) continue;
            pairs.add(new SplitPlayUrl(v.url, bestAudio.url, v.brKbps, v.hevc));
        }

        // Feed often has media-video-* without media-audio-* path names; audio
        // lives in bit_rate_audio / audio with ordinary CDN URLs. Pair those
        // by lowest audio bitrate when fid matching yields nothing.
        if (pairs.isEmpty() && !videos.isEmpty() && !audios.isEmpty()) {
            UrlHit bestAudio = audios.get(0);
            for (UrlHit a : audios) {
                if (a.brKbps < bestAudio.brKbps) bestAudio = a;
            }
            List<UrlHit> sortedVideos = new ArrayList<>(videos);
            sortedVideos.sort(Comparator.comparingInt(h -> h.brKbps));
            for (UrlHit v : sortedVideos) {
                String key = v.url + "|" + bestAudio.url;
                if (!seenPairs.add(key)) continue;
                pairs.add(new SplitPlayUrl(v.url, bestAudio.url, v.brKbps, v.hevc));
            }
        }

        // Prefer H.264 first (emulator/soft HEVC often advertises support but fails
        // Douyin hvc1 profiles); among same codec, lowest br first.
        pairs.sort(Comparator
                .comparingInt((SplitPlayUrl s) -> s.hevc ? 1 : 0)
                .thenComparingInt(s -> s.videoBrKbps));
        if (pairs.size() > 8) {
            return new ArrayList<>(pairs.subList(0, 8));
        }
        return pairs;
    }

    /**
     * Collects demuxed video tracks ({@code media-video-*}) and audio tracks
     * from {@code bit_rate_audio} / {@code audio} (forced) plus path heuristics.
     */
    private static void collectSplitCandidates(JSONObject video,
                                               List<UrlHit> videos, List<UrlHit> audios) {
        if (video == null) return;

        // Path-based discovery across the whole video object.
        List<String> allUrls = new ArrayList<>();
        collectAllUrls(video, allUrls);
        for (String url : allUrls) {
            classifyUrlAuto(url, -1, videos, audios);
        }

        // bit_rate[*].play_addr may embed media-video; play_addr_265 is HEVC.
        JSONArray bitRates = video.optJSONArray("bit_rate");
        if (bitRates != null) {
            for (int i = 0; i < bitRates.length(); i++) {
                JSONObject br = bitRates.optJSONObject(i);
                if (br == null) continue;
                int brKbps = bitrateKbps(br);
                collectAddrUrls(br.optJSONObject("play_addr"), brKbps, TrackKind.AUTO, videos, audios);
                collectAddrUrls(br.optJSONObject("play_addr_265"), brKbps, TrackKind.VIDEO, videos, audios);
                collectAddrUrls(br.optJSONObject("play_addr_bytevc1"), brKbps, TrackKind.VIDEO, videos, audios);
                collectAddrUrls(br.optJSONObject("play_addr_h265"), brKbps, TrackKind.VIDEO, videos, audios);
            }
        }

        // Real feed: audio URLs do NOT contain "media-audio-" — force AUDIO.
        for (String key : new String[]{"bit_rate_audio", "bit_rate_audio_list"}) {
            Object audioList = video.opt(key);
            if (!(audioList instanceof JSONArray)) continue;
            JSONArray arr = (JSONArray) audioList;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject entry = arr.optJSONObject(i);
                if (entry == null) continue;
                int brKbps = bitrateKbps(entry);
                collectAddrUrls(entry.optJSONObject("play_addr"), brKbps, TrackKind.AUDIO, videos, audios);
                collectAddrUrls(entry.optJSONObject("play_addr_lowbr"), brKbps, TrackKind.AUDIO, videos, audios);
                // Real feed: audio_meta.url_list = {main_url, backup_url?} (object).
                Object nested = entry.opt("audio_meta");
                if (nested instanceof JSONObject) {
                    JSONObject meta = (JSONObject) nested;
                    int metaBr = bitrateKbps(meta);
                    if (metaBr <= 0) metaBr = brKbps;
                    collectAddrUrls(meta, metaBr, TrackKind.AUDIO, videos, audios);
                    collectAddrUrls(meta.optJSONObject("play_addr"), metaBr, TrackKind.AUDIO,
                            videos, audios);
                }
                nested = entry.opt("audio");
                if (nested instanceof JSONObject) {
                    JSONObject audio = (JSONObject) nested;
                    collectAddrUrls(audio, brKbps, TrackKind.AUDIO, videos, audios);
                    collectAddrUrls(audio.optJSONObject("play_addr"), brKbps, TrackKind.AUDIO,
                            videos, audios);
                }
                // Last resort: any nested URL under this entry is audio.
                List<String> nestedUrls = new ArrayList<>();
                collectAllUrls(entry, nestedUrls);
                for (String url : nestedUrls) {
                    if (url.toLowerCase(Locale.US).contains("media-video-")) continue;
                    addForced(url, brKbps, TrackKind.AUDIO, videos, audios);
                }
            }
        }

        JSONObject audioObj = video.optJSONObject("audio");
        if (audioObj != null) {
            int brKbps = bitrateKbps(audioObj);
            collectAddrUrls(audioObj.optJSONObject("play_addr"), brKbps, TrackKind.AUDIO, videos, audios);
            List<String> nestedUrls = new ArrayList<>();
            collectAllUrls(audioObj, nestedUrls);
            for (String url : nestedUrls) {
                if (url.toLowerCase(Locale.US).contains("media-video-")) continue;
                addForced(url, brKbps, TrackKind.AUDIO, videos, audios);
            }
        }
    }

    private enum TrackKind { AUTO, VIDEO, AUDIO }

    private static void collectAddrUrls(JSONObject addr, int brKbps, TrackKind kind,
                                        List<UrlHit> videos, List<UrlHit> audios) {
        if (addr == null) return;
        for (String url : urlsFromUrlListField(addr.opt("url_list"))) {
            if (kind == TrackKind.AUTO) {
                classifyUrlAuto(url, brKbps, videos, audios);
            } else {
                addForced(url, brKbps, kind, videos, audios);
            }
        }
    }

    private static void classifyUrlAuto(String url, int fallbackBrKbps,
                                        List<UrlHit> videos, List<UrlHit> audios) {
        String lower = url.toLowerCase(Locale.US);
        boolean isVideo = lower.contains("media-video-");
        boolean isAudio = lower.contains("media-audio-")
                || lower.contains("media-audio")
                || lower.contains("und-mp4a")
                || lower.contains("/media-audio");
        if (!isVideo && !isAudio) return;
        addHit(url, fallbackBrKbps, isVideo, isAudio, videos, audios);
    }

    private static void addForced(String url, int fallbackBrKbps, TrackKind kind,
                                  List<UrlHit> videos, List<UrlHit> audios) {
        if (url == null || url.isEmpty()) return;
        // Never treat cover/image CDN links as A/V tracks.
        String lower = url.toLowerCase(Locale.US);
        if (lower.contains(".jpeg") || lower.contains(".jpg") || lower.contains(".webp")
                || lower.contains(".png") || lower.contains("image")
                || lower.contains("/cover")) {
            return;
        }
        addHit(url, fallbackBrKbps, kind == TrackKind.VIDEO, kind == TrackKind.AUDIO, videos, audios);
    }

    private static void addHit(String url, int fallbackBrKbps, boolean isVideo, boolean isAudio,
                               List<UrlHit> videos, List<UrlHit> audios) {
        int br = parseBrKbps(url);
        if (br <= 0) br = fallbackBrKbps > 0 ? fallbackBrKbps : 99999;
        String lower = url.toLowerCase(Locale.US);
        boolean hevc = lower.contains("hvc1") || lower.contains("bytevc1")
                || lower.contains("hevc") || lower.contains("media-video-hvc");
        UrlHit hit = new UrlHit(url, br, hevc, extractQueryParam(url, "fid"));
        if (isVideo && !containsUrl(videos, url)) videos.add(hit);
        if (isAudio && !containsUrl(audios, url)) audios.add(hit);
    }

    private static boolean containsUrl(List<UrlHit> hits, String url) {
        for (UrlHit h : hits) {
            if (h.url.equals(url)) return true;
        }
        return false;
    }

    private static final class UrlHit {
        final String url;
        final int brKbps;
        final boolean hevc;
        final String fid;

        UrlHit(String url, int brKbps, boolean hevc, String fid) {
            this.url = url;
            this.brKbps = brKbps;
            this.hevc = hevc;
            this.fid = fid != null ? fid : "";
        }
    }

    /**
     * Muxed progressive MP4 candidates. Prefer H.264 + lowest bitrate — emulator
     * / weak TV boxes often choke on HEVC even when a decoder is advertised.
     */
    private static List<String> collectPlayUrls(JSONObject video) {
        LinkedHashSet<String> seenKeys = new LinkedHashSet<>();
        List<ScoredUrl> scored = new ArrayList<>();

        // Explicit H.264 holders first (treated as non-HEVC).
        addMuxed(video.optJSONObject("play_addr_h264"), -1, /*hevc=*/false, seenKeys, scored);
        addMuxed(video.optJSONObject("play_addr_lowbr"), -1, /*hevc=*/false, seenKeys, scored);
        addMuxed(video.optJSONObject("download_addr"), -1, /*hevc=*/false, seenKeys, scored);

        JSONArray bitRates = video.optJSONArray("bit_rate");
        if (bitRates != null) {
            for (int i = 0; i < bitRates.length(); i++) {
                JSONObject bitRate = bitRates.optJSONObject(i);
                if (bitRate == null) continue;
                int brKbps = bitrateKbps(bitRate);
                boolean hevc = bitRate.optInt("is_h265", 0) == 1
                        || bitRate.optInt("is_bytevc1", 0) == 1;
                // Always keep the H.264 mirror when present.
                addMuxed(bitRate.optJSONObject("play_addr_h264"), brKbps, false, seenKeys, scored);
                // Only use primary play_addr for non-HEVC gears (avoid picking
                // a low-br HEVC mux that still needs hvc1 decode).
                if (!hevc) {
                    addMuxed(bitRate.optJSONObject("play_addr"), brKbps, false, seenKeys, scored);
                } else {
                    addMuxed(bitRate.optJSONObject("play_addr"), brKbps, true, seenKeys, scored);
                }
            }
        }

        // Top-level play_addr last — often a default that may be HEVC.
        boolean topHevc = video.optInt("is_h265", 0) == 1;
        addMuxed(video.optJSONObject("play_addr"), -1, topHevc, seenKeys, scored);

        scored.sort(Comparator
                .comparingInt((ScoredUrl s) -> s.hevc ? 1 : 0) // H.264 first
                .thenComparingInt((ScoredUrl s) -> s.brKbps)
                .thenComparing((ScoredUrl s) -> -scorePlayUrl(s.url)));

        List<String> ordered = new ArrayList<>(scored.size());
        for (ScoredUrl s : scored) {
            // Skip pure split tracks here — those go through splitCandidates.
            String lower = s.url.toLowerCase(Locale.US);
            if (lower.contains("media-video-") || lower.contains("media-audio-")) continue;
            ordered.add(s.url);
        }
        if (ordered.size() > 12) {
            return new ArrayList<>(ordered.subList(0, 12));
        }
        return ordered;
    }

    private static void addMuxed(JSONObject urlHolder, int brKbps, boolean hevc,
                                 LinkedHashSet<String> seenKeys, List<ScoredUrl> scored) {
        if (urlHolder == null) return;
        for (String url : urlsFromUrlListField(urlHolder.opt("url_list"))) {
            String key = urlDedupeKey(url);
            if (!seenKeys.add(key)) continue;
            int br = parseBrKbps(url);
            if (br <= 0) br = brKbps > 0 ? brKbps : 50_000;
            String lower = url.toLowerCase(Locale.US);
            boolean urlHevc = hevc || lower.contains("hvc1") || lower.contains("bytevc1");
            scored.add(new ScoredUrl(url, br, urlHevc));
        }
    }

    private static final class ScoredUrl {
        final String url;
        final int brKbps;
        final boolean hevc;

        ScoredUrl(String url, int brKbps, boolean hevc) {
            this.url = url;
            this.brKbps = brKbps;
            this.hevc = hevc;
        }
    }

    /** Ignore cosmetic query reordering (e.g. cquery) when deduping mirrors. */
    private static String urlDedupeKey(String url) {
        if (url.contains("/aweme/v1/play/")) {
            int q = url.indexOf('?');
            return q > 0 ? url.substring(0, q) : url;
        }
        int q = url.indexOf('?');
        String base = q > 0 ? url.substring(0, q) : url;
        String sig = extractQueryParam(url, "signature");
        String br = extractQueryParam(url, "br");
        return base + "|" + br + "|" + sig;
    }

    static String extractQueryParam(String url, String name) {
        if (url == null) return "";
        int start = url.indexOf(name + "=");
        if (start < 0) return "";
        start += name.length() + 1;
        int end = url.indexOf('&', start);
        String raw = end > start ? url.substring(start, end) : url.substring(start);
        int hash = raw.indexOf('#');
        return hash >= 0 ? raw.substring(0, hash) : raw;
    }

    private static int parseBrKbps(String url) {
        String br = extractQueryParam(url, "br");
        if (br.isEmpty()) return -1;
        try {
            return Integer.parseInt(br);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int bitrateKbps(JSONObject obj) {
        if (obj == null) return -1;
        // Feed uses both bit_rate (video gears) and bitrate (audio_meta).
        int bitRate = obj.optInt("bit_rate", -1);
        if (bitRate <= 0) bitRate = obj.optInt("bitrate", -1);
        if (bitRate > 10_000) return bitRate / 1000; // bps → kbps
        if (bitRate > 0) return bitRate;
        int br = obj.optInt("br", -1);
        if (br > 10_000) return br / 1000;
        return br;
    }

    /** Prefer douyin play redirect / CDN hosts as a tiebreaker after bitrate. */
    private static int scorePlayUrl(String url) {
        if (url == null || url.isEmpty()) return 0;
        int score = 0;
        if (url.contains("/aweme/v1/play/")) score += 100;
        if (url.contains("douyin.com")) score += 40;
        if (url.contains("douyinvod.com")) score += 20;
        if (url.contains("v26-web")) score += 8;
        if (url.contains("v3-web")) score += 4;
        if (url.contains("mime_type=video_mp4")) score += 2;
        return score;
    }

    private static String firstUrl(JSONObject urlHolder) {
        if (urlHolder == null) return null;
        List<String> urls = urlsFromUrlListField(urlHolder.opt("url_list"));
        return urls.isEmpty() ? null : urls.get(0);
    }

    /**
     * Douyin uses two url_list shapes:
     * <ul>
     *   <li>JSONArray of URL strings (video play_addr)</li>
     *   <li>JSONObject with {@code main_url}/{@code backup_url} (audio_meta)</li>
     * </ul>
     */
    private static List<String> urlsFromUrlListField(Object urlListField) {
        List<String> out = new ArrayList<>();
        if (urlListField instanceof JSONArray) {
            JSONArray arr = (JSONArray) urlListField;
            for (int i = 0; i < arr.length(); i++) {
                String url = arr.optString(i, "");
                if (!url.isEmpty() && looksLikeHttpUrl(url)) out.add(url);
            }
        } else if (urlListField instanceof JSONObject) {
            JSONObject obj = (JSONObject) urlListField;
            for (String key : new String[]{
                    "main_url", "backup_url", "url", "uri", "play_url", "addr"}) {
                String url = obj.optString(key, "");
                if (!url.isEmpty() && looksLikeHttpUrl(url) && !out.contains(url)) {
                    out.add(url);
                }
            }
            // Any other string value that looks like a media URL.
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String url = obj.optString(keys.next(), "");
                if (looksLikeHttpUrl(url) && !out.contains(url)) out.add(url);
            }
        } else if (urlListField instanceof String) {
            String url = (String) urlListField;
            if (looksLikeHttpUrl(url)) out.add(url);
        }
        return out;
    }

    private static boolean looksLikeHttpUrl(String s) {
        if (s == null || s.length() < 8) return false;
        String lower = s.toLowerCase(Locale.US);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    /** First url_list entry found anywhere under {@code node}. */
    private static String firstUrlDeep(Object node) {
        List<String> urls = new ArrayList<>();
        collectAllUrls(node, urls);
        return urls.isEmpty() ? null : urls.get(0);
    }

    private static void collectAllUrls(Object node, List<String> out) {
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            for (String url : urlsFromUrlListField(obj.opt("url_list"))) {
                if (!out.contains(url)) out.add(url);
            }
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if ("url_list".equals(key)) continue;
                collectAllUrls(obj.opt(key), out);
            }
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                collectAllUrls(arr.opt(i), out);
            }
        }
    }

    private static String objectKeys(JSONObject obj) {
        if (obj == null) return "";
        List<String> keys = new ArrayList<>();
        Iterator<String> it = obj.keys();
        while (it.hasNext()) keys.add(it.next());
        Collections.sort(keys);
        StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            if (sb.length() > 0) sb.append(',');
            sb.append(k);
        }
        return sb.toString();
    }

    private static String typeName(Object value) {
        if (value == null) return "null";
        if (value instanceof JSONObject) return "object";
        if (value instanceof JSONArray) return "array";
        if (value instanceof String) return "string";
        if (value instanceof Number) return "number";
        if (value instanceof Boolean) return "boolean";
        return value.getClass().getSimpleName();
    }

    public static String shortUrl(String url) {
        if (url == null || url.isEmpty()) return "";
        int q = url.indexOf('?');
        String base = q > 0 ? url.substring(0, q) : url;
        if (base.length() > 96) base = base.substring(0, 96) + "…";
        String br = extractQueryParam(url, "br");
        String fid = extractQueryParam(url, "fid");
        return base + (br.isEmpty() ? "" : "?br=" + br)
                + (fid.isEmpty() ? "" : "&fid=" + fid.substring(0, Math.min(8, fid.length())));
    }

    @Override
    public String toString() {
        return "FeedVideo{awemeId=" + awemeId + ", desc=" + desc
                + ", playUrl=" + playUrl
                + ", splits=" + (splitCandidates != null ? splitCandidates.size() : 0) + "}";
    }
}
