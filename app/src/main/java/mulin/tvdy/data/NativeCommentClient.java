package mulin.tvdy.data;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 抖音评论列表的直连客户端。
 * <p>
 * 使用 {@code www.iesdouyin.com/web/api/v2/comment/list/} 老版接口，实测免签名、
 * 免 Cookie，按 aweme_id 返回热门评论(text/user.nickname/user.avatar_thumb/
 * digg_count/createTime/ip_label)，供播放器的评论面板直接消费。
 * 该接口无分页字段，单次返回一批热门评论(约 10 条)，够 TV 端浏览场景使用。
 */
public final class NativeCommentClient {

    private static final String TAG = "NativeComment";

    private static final String COMMENT_URL =
            "https://www.iesdouyin.com/web/api/v2/comment/list/";

    /** 与 NativeFeedClient 相同的移动端 UA。 */
    private static final String MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Version/4.0 Chrome/90.0.4430.210 Mobile Safari/537.36";

    private NativeCommentClient() {
    }

    /** 一条评论的展示数据。 */
    public static final class Comment {
        public final String cid;
        public final String nick;
        public final String avatarUrl;
        public final String text;
        public final long diggCount;
        /** "09-21" 形式的发布日期(由 createTime 换算)。 */
        public final String dateText;
        /** "广东" 形式的 IP 属地，可能为空。 */
        public final String ipLabel;

        Comment(String cid, String nick, String avatarUrl, String text,
                long diggCount, String dateText, String ipLabel) {
            this.cid = cid;
            this.nick = nick;
            this.avatarUrl = avatarUrl;
            this.text = text;
            this.diggCount = diggCount;
            this.dateText = dateText;
            this.ipLabel = ipLabel;
        }
    }

    /**
     * 同步拉取指定视频的热门评论。必须在后台线程调用。
     *
     * @return 评论列表；无评论/失败时返回空列表(区分于 {@code null}=请求失败)
     */
    public static List<Comment> fetch(String awemeId, int count) {
        if (awemeId == null || awemeId.isEmpty()) return new ArrayList<>();
        String url = COMMENT_URL + "?aweme_id=" + awemeId
                + "&count=" + count + "&aid=1128";
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
                Log.w(TAG, "comment http " + code);
                return null;
            }

            JSONObject root = new JSONObject(readAll(conn.getInputStream()));
            if (root.optJSONObject("status_code") != null
                    && root.optJSONObject("status_code").optInt("StatusCode", -1) != 0) {
                Log.w(TAG, "comment status != 0");
                return null;
            }

            JSONArray list = root.optJSONArray("comments");
            List<Comment> out = new ArrayList<>();
            if (list == null) return out;
            SimpleDateFormat dayFormat =
                    new SimpleDateFormat("MM-dd", Locale.CHINA);
            for (int i = 0; i < list.length(); i++) {
                JSONObject c = list.optJSONObject(i);
                if (c == null) continue;
                try {
                    JSONObject user = c.optJSONObject("user");
                    String nick = user != null ? user.optString("nickname", "") : "";
                    String avatarUrl = firstUrl(user != null
                            ? user.optJSONObject("avatar_thumb") : null);
                    long createTime = parseLong(c.opt("createTime"));
                    String dateText = createTime > 0
                            ? dayFormat.format(new Date(createTime * 1000L)) : "";
                    out.add(new Comment(
                            c.optString("cid", ""),
                            nick,
                            avatarUrl,
                            c.optString("text", "").trim(),
                            c.optLong("digg_count", 0),
                            dateText,
                            c.optString("ip_label", "")));
                } catch (Exception e) {
                    Log.w(TAG, "comment item parse failed at " + i, e);
                }
            }
            Log.d(TAG, "comments ok n=" + out.size());
            return out;
        } catch (Exception e) {
            Log.w(TAG, "comment fetch failed", e);
            return null;
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }
    }

    private static long parseLong(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) {
            try { return Long.parseLong((String) v); } catch (Exception ignored) {}
        }
        return 0;
    }

    private static String firstUrl(JSONObject addr) {
        if (addr == null) return null;
        JSONArray list = addr.optJSONArray("url_list");
        if (list == null || list.length() == 0) return null;
        String url = list.optString(0, "");
        return url.isEmpty() ? null : url;
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        try (InputStream input = in) {
            while ((n = input.read(buf)) != -1) {
                bos.write(buf, 0, n);
                if (bos.size() > 4 * 1024 * 1024) break;
            }
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
