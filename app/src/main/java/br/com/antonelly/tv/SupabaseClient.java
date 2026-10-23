package br.com.antonelly.tv;

import android.content.Context;
import android.os.Build;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

public final class SupabaseClient {
    private final Context context;
    private final AppConfig config;

    public SupabaseClient(Context context, AppConfig config) {
        this.context = context.getApplicationContext();
        this.config = config;
    }

    public JSONObject ensureSession() throws Exception {
        long now = System.currentTimeMillis() / 1000L;
        String access = config.prefs().getString("access_token", "");
        String refresh = config.prefs().getString("refresh_token", "");
        long expires = config.prefs().getLong("expires_at", 0L);
        if (!access.isEmpty() && expires > now + 60) {
            JSONObject s = new JSONObject();
            s.put("access_token", access);
            s.put("refresh_token", refresh);
            s.put("expires_at", expires);
            return s;
        }
        if (!refresh.isEmpty()) {
            try {
                JSONObject body = new JSONObject().put("refresh_token", refresh);
                JSONObject r = requestJson("/auth/v1/token?grant_type=refresh_token", "POST", body, null);
                return saveSession(r);
            } catch (Exception ignored) { }
        }
        // Espelha o payload usado pelos clientes oficiais do Supabase para
        // signInAnonymously(). Algumas versões do GoTrue aceitam {}, mas esse
        // formato é mais explícito e evita diferenças entre gateways/versões.
        JSONObject anonymousBody = new JSONObject()
                .put("data", new JSONObject())
                .put("gotrue_meta_security", new JSONObject());
        JSONObject anon = requestJson("/auth/v1/signup", "POST", anonymousBody, null);
        return saveSession(anon);
    }

    private JSONObject saveSession(JSONObject r) throws Exception {
        // GoTrue normalmente devolve a sessão no nível raiz. Mantemos também
        // suporte ao formato { session: {...} } para tolerar proxies/wrappers.
        JSONObject payload = r.optJSONObject("session");
        if (payload == null) payload = r;

        String access = payload.optString("access_token", "");
        if (access.isEmpty()) {
            throw new IOException("AUTH: Supabase não criou a sessão anônima. Verifique Anonymous Sign-Ins e Allow new users to sign up.");
        }
        String refresh = payload.optString("refresh_token", "");
        long expiresAt = payload.optLong("expires_at", 0L);
        if (expiresAt <= 0L) {
            expiresAt = (System.currentTimeMillis() / 1000L) + payload.optLong("expires_in", 3600L);
        }
        config.prefs().edit()
                .putString("access_token", access)
                .putString("refresh_token", refresh)
                .putLong("expires_at", expiresAt)
                .apply();
        JSONObject s = new JSONObject();
        s.put("access_token", access);
        s.put("refresh_token", refresh);
        s.put("expires_at", expiresAt);
        return s;
    }

    public JSONObject rpc(String name, JSONObject params, String token) throws Exception {
        return requestJson("/rest/v1/rpc/" + encodeSegment(name), "POST", params == null ? new JSONObject() : params, token);
    }

    public JSONObject bootstrap(String token) throws Exception {
        JSONObject caps = new JSONObject();
        caps.put("platform", isFireTv() ? "fire_tv" : "android");
        caps.put("manufacturer", Build.MANUFACTURER);
        caps.put("model", Build.MODEL);
        caps.put("device", Build.DEVICE);
        caps.put("androidApi", Build.VERSION.SDK_INT);
        caps.put("appVersion", BuildConfig.VERSION_NAME);
        return rpc("tv_bootstrap", new JSONObject().put("p_capabilities", caps), token);
    }

    public JSONObject signal(String token) throws Exception {
        return rpc("tv_signal", new JSONObject(), token);
    }

    public JSONObject manifest(String token) throws Exception {
        return rpc("tv_manifest", new JSONObject(), token);
    }

    public JSONObject heartbeat(String token, String state, int currentVersion, JSONObject sync, String lastError) throws Exception {
        JSONObject caps = new JSONObject();
        caps.put("platform", isFireTv() ? "fire_tv" : "android");
        caps.put("manufacturer", Build.MANUFACTURER);
        caps.put("model", Build.MODEL);
        caps.put("device", Build.DEVICE);
        caps.put("androidApi", Build.VERSION.SDK_INT);
        caps.put("appVersion", BuildConfig.VERSION_NAME);
        JSONObject p = new JSONObject();
        p.put("p_player_state", state);
        p.put("p_current_version", currentVersion);
        p.put("p_sync", sync == null ? new JSONObject() : sync);
        p.put("p_last_error", lastError == null ? JSONObject.NULL : lastError);
        p.put("p_capabilities", caps);
        return rpc("tv_heartbeat", p, token);
    }

    public File downloadMedia(String storagePath, String token, File destination, Progress progress) throws Exception {
        return downloadStorageObject(config.getMediaBucket(), storagePath, token, destination, progress);
    }

    public JSONObject latestAppUpdate(String token) throws Exception {
        return rpc("tv_latest_app_update", new JSONObject(), token);
    }

    public File downloadUpdate(String bucket, String storagePath, String token, File destination, Progress progress) throws Exception {
        return downloadStorageObject(bucket == null || bucket.trim().isEmpty() ? "tv-updates" : bucket.trim(), storagePath, token, destination, progress);
    }

    private File downloadStorageObject(String bucket, String storagePath, String token, File destination, Progress progress) throws Exception {
        String url = config.getSupabaseUrl() + "/storage/v1/object/authenticated/" + encodeSegment(bucket) + "/" + encodePath(storagePath);
        HttpURLConnection c = open(url, "GET", token);
        c.setReadTimeout(120000);
        c.setRequestProperty("Accept", "application/octet-stream,*/*");
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) throw new IOException("Download HTTP " + code + " em " + storagePath + ": " + readText(c.getErrorStream()));
        long total = c.getContentLengthLong();
        File tmp = new File(destination.getAbsolutePath() + ".part");
        if (tmp.exists()) tmp.delete();
        try (InputStream in = new BufferedInputStream(c.getInputStream()); OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            long done = 0L;
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                done += n;
                if (progress != null) progress.onProgress(done, total);
            }
        } finally {
            c.disconnect();
        }
        if (destination.exists()) destination.delete();
        if (!tmp.renameTo(destination)) throw new IOException("Não foi possível finalizar o arquivo local: " + destination.getName());
        return destination;
    }

    public JSONObject requestJson(String path, String method, JSONObject body, String token) throws Exception {
        HttpURLConnection c = open(config.getSupabaseUrl() + path, method, token);
        if (body != null) {
            byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(data.length);
            try (OutputStream out = c.getOutputStream()) { out.write(data); }
        }
        int code = c.getResponseCode();
        String text = readText(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
        c.disconnect();
        if (code < 200 || code >= 300) {
            String msg = text;
            try {
                JSONObject e = new JSONObject(text);
                msg = e.optString("message", e.optString("msg", e.optString("error_description", e.optString("error", text))));
            } catch (Exception ignored) { }
            String detail = (msg == null || msg.trim().isEmpty()) ? "sem corpo de resposta" : msg.trim();
            throw new IOException(method + " " + path + " -> HTTP " + code + ": " + detail);
        }
        try {
            return text == null || text.trim().isEmpty() ? new JSONObject() : new JSONObject(text);
        } catch (Exception parseError) {
            throw new IOException(method + " " + path + " -> resposta JSON inválida: " + compact(text), parseError);
        }
    }

    private HttpURLConnection open(String value, String method, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(value).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15000);
        c.setReadTimeout(45000);
        c.setUseCaches(false);
        c.setRequestProperty("apikey", config.getPublishableKey());
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Cache-Control", "no-cache");
        c.setRequestProperty("X-Client-Info", "antonelly-tv-android/" + BuildConfig.VERSION_NAME);
        if (token != null && !token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
        return c;
    }

    /**
     * Diagnóstico em três etapas: gateway/chave, Auth e RPC da TV.
     * Não depende do WebApp ou do Cloudflare.
     */
    public JSONObject diagnoseConnection() {
        JSONObject out = new JSONObject();
        try {
            out.put("projectUrl", config.getSupabaseUrl());
            out.put("keyPrefix", maskKey(config.getPublishableKey()));

            try {
                requestJson("/auth/v1/settings", "GET", null, null);
                out.put("gateway", "OK");
            } catch (Exception e) {
                out.put("ok", false);
                out.put("stage", "GATEWAY");
                out.put("error", describeException(e));
                return out;
            }

            JSONObject s;
            try {
                s = ensureSession();
                String token = s.optString("access_token", "");
                if (token.isEmpty()) throw new IOException("AUTH: token vazio após login anônimo.");
                out.put("auth", "OK");
            } catch (Exception e) {
                out.put("ok", false);
                out.put("stage", "AUTH");
                out.put("error", describeException(e));
                return out;
            }

            try {
                String token = s.optString("access_token", "");
                JSONObject boot = bootstrap(token);
                out.put("rpc", "OK");
                out.put("paired", boot.optBoolean("paired", false));
                out.put("pairingCode", boot.optString("pairing_code", ""));
            } catch (Exception e) {
                out.put("ok", false);
                out.put("stage", "RPC");
                out.put("error", describeException(e));
                return out;
            }

            out.put("ok", true);
            out.put("stage", "READY");
            return out;
        } catch (Exception impossible) {
            try {
                out.put("ok", false);
                out.put("stage", "CLIENT");
                out.put("error", describeException(impossible));
            } catch (Exception ignored) { }
            return out;
        }
    }

    public static String describeException(Throwable error) {
        if (error == null) return "Erro desconhecido";
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();

        String msg = root.getMessage();
        if (msg == null || msg.trim().isEmpty()) msg = root.getClass().getSimpleName();

        String cls = root.getClass().getSimpleName();
        if (root instanceof java.net.UnknownHostException) {
            return "DNS: não foi possível localizar o servidor Supabase (" + msg + ")";
        }
        if (root instanceof java.net.SocketTimeoutException) {
            return "TIMEOUT: o servidor não respondeu no tempo esperado (" + msg + ")";
        }
        if (root instanceof javax.net.ssl.SSLHandshakeException) {
            return "TLS/SSL: falha ao validar a conexão segura (" + msg + ")";
        }
        if (root instanceof java.net.ConnectException) {
            return "REDE: conexão recusada pelo servidor ou pela rede (" + msg + ")";
        }
        return cls + ": " + msg;
    }

    private static String maskKey(String key) {
        String value = key == null ? "" : key.trim();
        if (value.length() <= 12) return value;
        return value.substring(0, 12) + "…" + value.substring(value.length() - 4);
    }

    private static String compact(String value) {
        if (value == null) return "";
        String out = value.replace('\n', ' ').replace('\r', ' ').trim();
        return out.length() > 240 ? out.substring(0, 240) + "…" : out;
    }

    public static String cacheNameFor(String storagePath) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(storagePath.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format(Locale.US, "%02x", b));
            String ext = "";
            int dot = storagePath.lastIndexOf('.');
            if (dot >= 0 && storagePath.length() - dot <= 6) ext = storagePath.substring(dot).replaceAll("[^A-Za-z0-9.]", "");
            return sb + ext;
        } catch (Exception e) {
            return Integer.toHexString(storagePath.hashCode()) + ".bin";
        }
    }

    public static String sha256(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new java.io.FileInputStream(file))) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format(Locale.US, "%02x", b));
        return sb.toString();
    }

    public boolean isFireTv() {
        String manufacturer = (Build.MANUFACTURER == null ? "" : Build.MANUFACTURER).toLowerCase(Locale.US);
        String model = (Build.MODEL == null ? "" : Build.MODEL).toLowerCase(Locale.US);
        return manufacturer.contains("amazon") || model.startsWith("aft");
    }

    private static String readText(InputStream in) throws IOException {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = br.read(buf)) >= 0) sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    private static String encodeSegment(String s) throws Exception {
        return URLEncoder.encode(s == null ? "" : s, "UTF-8").replace("+", "%20");
    }

    private static String encodePath(String path) throws Exception {
        String[] parts = (path == null ? "" : path).split("/");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) out.append('/');
            out.append(encodeSegment(parts[i]));
        }
        return out.toString();
    }

    public interface Progress { void onProgress(long done, long total); }
}
