package mulin.tvdy.data;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 抖音 App 端老版推荐流接口({@code api.amemv.com/aweme/v1/feed/})的直连客户端。
 * <p>
 * 该接口实测免签名、免 Cookie、免 WebView，一次请求约 1 秒即返回与网页版同构的
 * {@code aweme_list}(aweme_id/desc/video.play_addr/author/statistics/music)，
 * 因此可直接复用 {@link FeedVideo#fromAwemeItem} 解析，播放直链也无需特殊请求头。
 * <p>
 * 在本应用中承担两个角色：
 * <ol>
 * <li><b>冷启动秒进</b> — onCreate 立即拉取，先于数据泵(需 10-30 秒启动 SPA)提供
 *     可播视频，体验对齐抖音 App 的打开即播；</li>
 * <li><b>数据泵失效兜底</b> — 部分电视的系统 WebView 过旧，douyin.com 的 SPA
 *     根本跑不起来("WebView 未捕获到抖音 API 请求")，此时由本客户端持续供片。</li>
 * </ol>
 * 注意：这是泛推荐流，登录后的个性化推荐仍以数据泵捕获的网页 feed 为准；本客户端
 * 的产出会进入同一队列去重，两者互不冲突。
 */
public final class NativeFeedClient {

    private static final String TAG = "NativeFeed";

    private static final String FEED_URL = "https://api.amemv.com/aweme/v1/feed/";

    /** 实测有效的移动端 UA；该接口按 App 端客户端对待。 */
    private static final String MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Version/4.0 Chrome/90.0.4430.210 Mobile Safari/537.36";

    /**
     * 两次拉取之间的最小间隔。数据泵的 kickstart 每 2 秒就会尝试触发一次供片，
     * 没有节流的话原生客户端会在泵每次尝试时都被打一遍，白白消耗风控额度。
     */
    private static final long MIN_INTERVAL_MS = 8000L;

    private static volatile long lastFetchAt;

    private NativeFeedClient() {
    }

    /**
     * 同步拉取一页推荐流。必须在后台线程调用；{@code minIntervalMs} 内的重复
     * 调用直接返回 {@code null}，由调用方继续走原有流程。
     *
     * @param count 期望的视频条数(接口单次上限实测 6 条左右，多要也无妨)
     * @return 解析后的可播视频列表；无内容/失败/节流期内返回 {@code null}
     */
    public static List<FeedVideo> fetch(int count) {
        long now = System.currentTimeMillis();
        long last = lastFetchAt;
        if (now - last < MIN_INTERVAL_MS) return null;
        lastFetchAt = now;

        // max_time 取当前秒：实测带上即可拿到与上一批不同的下一页内容。
        long ts = now / 1000L;
        String url = FEED_URL + "?count=" + count
                + "&aid=1128&os_api=18&device_platform=android&max_time=" + ts;

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(8000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", MOBILE_UA);
            conn.setRequestProperty("Accept", "application/json");

            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "native feed http " + code);
                return null;
            }

            String body = readAll(conn.getInputStream());
            JSONObject root = new JSONObject(body);
            if (root.optInt("status_code", -1) != 0) {
                Log.w(TAG, "native feed status_code=" + root.optInt("status_code", -1)
                        + " msg=" + root.optString("status_msg", ""));
                return null;
            }

            JSONArray list = root.optJSONArray("aweme_list");
            if (list == null || list.length() == 0) {
                Log.w(TAG, "native feed empty aweme_list");
                return null;
            }

            List<FeedVideo> out = new ArrayList<>(list.length());
            for (int i = 0; i < list.length(); i++) {
                try {
                    FeedVideo video = FeedVideo.fromAwemeItem(list.optJSONObject(i));
                    if (video != null) out.add(video);
                } catch (Exception e) {
                    Log.w(TAG, "native feed item parse failed at " + i, e);
                }
            }
            Log.d(TAG, "native feed ok raw=" + list.length() + " parsed=" + out.size());
            return out.isEmpty() ? null : out;
        } catch (Exception e) {
            Log.w(TAG, "native feed fetch failed", e);
            return null;
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        try (InputStream input = in) {
            while ((n = input.read(buf)) != -1) {
                bos.write(buf, 0, n);
                if (bos.size() > 4 * 1024 * 1024) break; // 单页实测 ~300KB，防御性上限
            }
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
