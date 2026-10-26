package mulin.tvdy.auth;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 抖音官方扫码登录(纯 API 实现，不使用 WebView)。
 * <p>
 * 接口来自官方 web 登录页实际调用：
 * <ul>
 * <li>取码：GET {@code https://login.douyin.com/passport/web/get_qrcode/
 *      ?aid=6383&language=zh&is_new_login=1&need_logo=false}</li>
 * <li>轮询：POST {@code https://login.douyin.com/passport/web/check_qrconnect/
 *      ?aid=6383&token=<token>}</li>
 * <li>状态：1=待扫码 2=已扫码待确认 3=已确认(回 redirect_url) 5=二维码过期</li>
 * </ul>
 * 确认后 GET redirect_url 让服务器下发登录 Cookie，取出后交给
 * {@link CookieImportHelper} 写入全局 CookieManager，数据泵与播放器即可共用
 * 登录态(拿到"为你推荐"个性化流)。
 * <p>
 * 注意：抖音对数据中心/机房 IP 有风控(error_code 4031)，家庭宽带正常。
 */
public final class QrLoginClient {

    private static final String TAG = "QrLogin";

    private static final String GET_QRCODE_URL =
            "https://login.douyin.com/passport/web/get_qrcode/"
                    + "?aid=6383&language=zh&is_new_login=1&need_logo=false";
    private static final String CHECK_URL =
            "https://login.douyin.com/passport/web/check_qrconnect/?aid=6383&token=";

    /** ttwid 注册接口(字节跳动通行设备标识，免签名)。 */
    private static final String TTWID_REGISTER_URL =
            "https://ttwid.bytedance.com/ttwid/union/register/";

    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    /** 二维码状态。 */
    public static final int STATUS_WAITING = 1;
    public static final int STATUS_SCANNED = 2;
    public static final int STATUS_CONFIRMED = 3;
    public static final int STATUS_EXPIRED = 5;

    private QrLoginClient() {
    }

    public static final class QrCode {
        public final String token;
        public final Bitmap bitmap;

        public QrCode(String token, Bitmap bitmap) {
            this.token = token;
            this.bitmap = bitmap;
        }
    }

    public static final class PollResult {
        public final int status;
        public final String redirectUrl;
        public final String tip;

        PollResult(int status, String redirectUrl, String tip) {
            this.status = status;
            this.redirectUrl = redirectUrl;
            this.tip = tip;
        }
    }

    /** 最近一次失败的详情(错误码/描述)，供界面提示定位问题。 */
    private static volatile String lastError = "";

    public static String getLastError() {
        return lastError;
    }

    /** 缓存的 ttwid(设备标识 Cookie)，进程内复用。 */
    private static volatile String cachedTtwid;

    /**
     * 注册并返回 ttwid。抖音的 passport 接口强制要求携带该 Cookie，
     * 缺失会直接返回 error_code 4031 风控拦截。
     */
    private static String ensureTtwid() {
        String cached = cachedTtwid;
        if (cached != null && !cached.isEmpty()) return cached;
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(TTWID_REGISTER_URL).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(8000);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("User-Agent", DESKTOP_UA);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(("{\"region\":\"cn\",\"aid\":1768,\"needFid\":false,"
                        + "\"service\":\"www.ixigua.com\",\"migrate_info\":{\"ticket\":\"\","
                        + "\"source\":\"node\"},\"cbUrlProtocol\":\"https\",\"union\":true}")
                        .getBytes(StandardCharsets.UTF_8));
            }
            List<String> setCookies = conn.getHeaderFields().get("Set-Cookie");
            if (setCookies != null) {
                for (String header : setCookies) {
                    if (header.startsWith("ttwid=")) {
                        String value = header.split(";")[0].substring("ttwid=".length());
                        if (!value.isEmpty()) {
                            cachedTtwid = value;
                            Log.d(TAG, "ttwid acquired len=" + value.length());
                            return value;
                        }
                    }
                }
            }
            Log.w(TAG, "ttwid register: no Set-Cookie");
        } catch (Exception e) {
            Log.w(TAG, "ttwid register failed", e);
        }
        return null;
    }

    private static String randomMsToken() {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        StringBuilder sb = new StringBuilder(116);
        java.util.Random random = new java.util.Random();
        for (int i = 0; i < 116; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private static String buildCookieHeader() {
        StringBuilder sb = new StringBuilder();
        String ttwid = ensureTtwid();
        if (ttwid != null) sb.append("ttwid=").append(ttwid).append("; ");
        sb.append("msToken=").append(randomMsToken());
        return sb.toString();
    }

    /**
     * 拉取一个新的登录二维码。必须在后台线程调用。
     *
     * @return 二维码(token + 可直接显示的位图)，失败返回 {@code null}
     *         (失败详情见 {@link #getLastError()})
     */
    public static QrCode fetchQrCode() {
        lastError = "";
        try {
            HttpURLConnection conn = open(GET_QRCODE_URL, "GET", null);
            int code = conn.getResponseCode();
            if (code != 200) {
                lastError = "HTTP " + code;
                Log.w(TAG, "get_qrcode http " + code);
                return null;
            }
            JSONObject root = new JSONObject(readAll(conn.getInputStream()));
            JSONObject data = root.optJSONObject("data");
            if (data == null) {
                lastError = root.optString("description", "响应无数据");
                Log.w(TAG, "get_qrcode no data: " + lastError);
                return null;
            }
            if (data.optInt("error_code", 0) != 0) {
                lastError = "错误码" + data.optInt("error_code", -1) + " "
                        + data.optString("description", "");
                Log.w(TAG, "get_qrcode risk control: " + lastError);
                return null;
            }
            String token = data.optString("token", "");
            if (token.isEmpty()) {
                lastError = "响应中无 token";
                return null;
            }
            Bitmap bitmap = null;
            // 优先内联 base64，其次远程二维码图片地址。
            String inline = data.optString("qrcode", "");
            if (inline.startsWith("data:image")) {
                bitmap = decodeDataUri(inline);
            }
            if (bitmap == null) {
                String indexUrl = data.optString("qrcode_index_url", "");
                if (!indexUrl.isEmpty()) {
                    bitmap = downloadBitmap(indexUrl);
                }
            }
            if (bitmap == null) {
                lastError = "二维码图片下载失败";
                return null;
            }
            return new QrCode(token, bitmap);
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            Log.w(TAG, "fetchQrCode failed", e);
            return null;
        }
    }

    /**
     * 查询二维码状态。必须先调用 {@link #fetchQrCode} 拿到 token。
     * 必须在后台线程调用。
     */
    public static PollResult poll(String token) {
        if (token == null || token.isEmpty()) return null;
        try {
            HttpURLConnection conn = open(CHECK_URL + token, "POST",
                    "need_logo=false&is_frontier=true&token=" + token
                            + "&is_new_login=1&next=https%3A%2F%2Fwww.douyin.com"
                            + "&need_short_url=true");
            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "check http " + code);
                return null;
            }
            JSONObject root = new JSONObject(readAll(conn.getInputStream()));
            JSONObject data = root.optJSONObject("data");
            if (data == null) return null;
            int status = parseInt(data.optString("status", "1"));
            return new PollResult(status, data.optString("redirect_url", ""),
                    data.optString("description", ""));
        } catch (Exception e) {
            Log.w(TAG, "poll failed", e);
            return null;
        }
    }

    /**
     * 完成登录：访问官方回调地址让服务器下发 Cookie，并返回拼接好的 Cookie 串。
     * 必须在后台线程调用。
     */
    public static String completeLogin(String redirectUrl) {
        if (redirectUrl == null || redirectUrl.isEmpty()) return null;
        try {
            HttpURLConnection conn = open(redirectUrl, "GET", null);
            conn.setInstanceFollowRedirects(false);
            int code = conn.getResponseCode();
            Map<String, String> cookies = new LinkedHashMap<>();
            collectCookies(conn, cookies);
            // 部分情况下真正的写入发生在跳转的下一跳。
            String location = conn.getHeaderField("Location");
            conn.disconnect();
            if ((code == 301 || code == 302) && location != null && !location.isEmpty()) {
                HttpURLConnection next = open(location, "GET", null);
                next.getResponseCode();
                collectCookies(next, cookies);
                next.disconnect();
            }
            if (cookies.isEmpty()) return null;
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : cookies.entrySet()) {
                if (sb.length() > 0) sb.append("; ");
                sb.append(e.getKey()).append("=").append(e.getValue());
            }
            return sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "completeLogin failed", e);
            return null;
        }
    }

    private static void collectCookies(HttpURLConnection conn, Map<String, String> sink) {
        List<String> headers = conn.getHeaderFields().get("Set-Cookie");
        if (headers == null) return;
        for (String header : headers) {
            String pair = header.split(";")[0];
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            sink.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
        }
    }

    private static HttpURLConnection open(String url, String method, String body)
            throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(8000);
        conn.setRequestMethod(method);
        conn.setRequestProperty("User-Agent", DESKTOP_UA);
        conn.setRequestProperty("Referer", "https://www.douyin.com/");
        conn.setRequestProperty("Origin", "https://www.douyin.com");
        conn.setRequestProperty("Accept", "application/json, text/plain, */*");
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        // 伪装浏览器 CORS 请求特征，降低风控概率。
        conn.setRequestProperty("Sec-Fetch-Dest", "empty");
        conn.setRequestProperty("Sec-Fetch-Mode", "cors");
        conn.setRequestProperty("Sec-Fetch-Site", "same-site");
        // passport 接口强制要求 ttwid；msToken 用随机值即可。
        conn.setRequestProperty("Cookie", buildCookieHeader());
        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type",
                    "application/x-www-form-urlencoded; charset=UTF-8");
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        return conn;
    }

    private static Bitmap decodeDataUri(String dataUri) {
        try {
            int comma = dataUri.indexOf(',');
            byte[] bytes = Base64.decode(dataUri.substring(comma + 1), Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception e) {
            Log.w(TAG, "decodeDataUri failed", e);
            return null;
        }
    }

    private static Bitmap downloadBitmap(String url) {
        try {
            HttpURLConnection conn = open(url, "GET", null);
            if (conn.getResponseCode() != 200) return null;
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            try (InputStream input = in) {
                while ((n = input.read(buf)) != -1) bos.write(buf, 0, n);
            }
            conn.disconnect();
            return BitmapFactory.decodeByteArray(bos.toByteArray(), 0, bos.size());
        } catch (Exception e) {
            Log.w(TAG, "downloadBitmap failed", e);
            return null;
        }
    }

    private static int parseInt(String v) {
        try { return Integer.parseInt(v.trim()); } catch (Exception e) { return 1; }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        try (InputStream input = in) {
            while ((n = input.read(buf)) != -1) {
                bos.write(buf, 0, n);
                if (bos.size() > 2 * 1024 * 1024) break;
            }
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
