package br.com.antonelly.tv;

import android.content.Context;
import android.content.SharedPreferences;

public final class AppConfig {
    private static final String PREFS = "antonelly_tv";
    private final SharedPreferences prefs;

    public AppConfig(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String getSupabaseUrl() {
        return valueOrDefault("supabase_url", BuildConfig.DEFAULT_SUPABASE_URL);
    }

    public String getPublishableKey() {
        return valueOrDefault("publishable_key", BuildConfig.DEFAULT_PUBLISHABLE_KEY);
    }

    public String getMediaBucket() {
        return valueOrDefault("media_bucket", BuildConfig.DEFAULT_MEDIA_BUCKET == null || BuildConfig.DEFAULT_MEDIA_BUCKET.isEmpty()
                ? "tv-media"
                : BuildConfig.DEFAULT_MEDIA_BUCKET);
    }

    public String getUpdateManifestUrl() {
        return valueOrDefault("update_manifest_url", BuildConfig.DEFAULT_UPDATE_MANIFEST_URL);
    }

    public boolean isUpdateWifiOnly() {
        return prefs.getBoolean("update_wifi_only", true);
    }

    public void saveUpdateSettings(String manifestUrl, boolean wifiOnly) {
        prefs.edit()
                .putString("update_manifest_url", normalizeUrl(manifestUrl))
                .putBoolean("update_wifi_only", wifiOnly)
                .apply();
    }

    public boolean isDemoMode() {
        return prefs.getBoolean("demo_mode", false);
    }

    /**
     * Salva o backend e informa se a identidade do backend mudou.
     * Quando isso acontece, a sessão anônima anterior deixa de ser válida.
     */
    public boolean saveBackend(String url, String key, String bucket) {
        String nextUrl = normalizeUrl(url);
        String nextKey = key == null ? "" : key.trim();
        String nextBucket = bucket == null || bucket.trim().isEmpty() ? "tv-media" : bucket.trim();

        boolean changed = !nextUrl.equals(getSupabaseUrl())
                || !nextKey.equals(getPublishableKey())
                || !nextBucket.equals(getMediaBucket());

        SharedPreferences.Editor edit = prefs.edit()
                .putString("supabase_url", nextUrl)
                .putString("publishable_key", nextKey)
                .putString("media_bucket", nextBucket)
                .putBoolean("demo_mode", false);

        if (changed) {
            edit.remove("access_token")
                    .remove("refresh_token")
                    .remove("expires_at")
                    .remove("current_version");
        }

        edit.apply();
        return changed;
    }

    public void enableDemoMode() {
        prefs.edit().putBoolean("demo_mode", true).apply();
    }

    public void disableDemoMode() {
        prefs.edit().putBoolean("demo_mode", false).apply();
    }

    public boolean isConfigured() {
        String url = getSupabaseUrl();
        return url.startsWith("https://")
                && url.contains(".supabase.co")
                && getPublishableKey().length() > 20;
    }

    public SharedPreferences prefs() {
        return prefs;
    }

    private String valueOrDefault(String key, String fallback) {
        if (prefs.contains(key)) {
            String value = prefs.getString(key, "");
            return value == null ? "" : value.trim();
        }
        return fallback == null ? "" : fallback.trim();
    }

    private static String normalizeUrl(String value) {
        String url = value == null ? "" : value.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }
}
