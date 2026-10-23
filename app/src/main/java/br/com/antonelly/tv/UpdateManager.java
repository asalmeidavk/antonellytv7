package br.com.antonelly.tv;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Locale;

/** Atualizador do Antonelly TV usando Supabase como origem oficial do APK. */
public final class UpdateManager {
    private static final String UPDATE_DIR = "updates";
    public static final String PREF_PENDING_APK = "update_pending_apk";
    public static final String PREF_PENDING_SHA = "update_pending_sha256";
    public static final String PREF_PENDING_ID = "update_pending_id";
    public static final String PREF_APPLIED_SHA = "update_applied_sha256";
    private static final String PREF_LAST_STATUS = "update_last_status";
    private static final String PREF_LAST_CHECK = "update_last_check";

    private final Context context;
    private final AppConfig config;
    private final SupabaseClient api;
    private final Handler main = new Handler(Looper.getMainLooper());

    public UpdateManager(Context context, AppConfig config, SupabaseClient api) {
        this.context = context.getApplicationContext();
        this.config = config;
        this.api = api;
    }

    public void checkAndUpdate() throws Exception {
        if (config.isUpdateWifiOnly() && !isOnWifi()) {
            setStatus("AGUARDANDO_WIFI");
            return;
        }

        JSONObject session = api.ensureSession();
        String token = session.optString("access_token", "");
        if (token.isEmpty()) throw new IllegalStateException("Sessão Supabase sem token.");

        // Garante que o usuário anônimo já tenha uma linha de tela no backend.
        try { api.bootstrap(token); } catch (Exception ignored) { }

        JSONObject release = api.latestAppUpdate(token);
        config.prefs().edit().putLong(PREF_LAST_CHECK, System.currentTimeMillis()).apply();

        if (!release.optBoolean("available", false)) {
            setStatus("SEM_ATUALIZACAO");
            return;
        }

        String releaseId = release.optString("id", "").trim();
        String storagePath = release.optString("storagePath", "").trim();
        String bucket = release.optString("bucket", "tv-updates").trim();
        String expectedSha256 = release.optString("sha256", "").trim().toLowerCase(Locale.ROOT);
        String appliedSha = config.prefs().getString(PREF_APPLIED_SHA, "");

        if (storagePath.isEmpty()) throw new IllegalStateException("Release sem storagePath.");
        if (!expectedSha256.matches("^[a-f0-9]{64}$")) throw new IllegalStateException("Release sem SHA-256 válido.");

        if (expectedSha256.equalsIgnoreCase(appliedSha == null ? "" : appliedSha)) {
            setStatus("ATUALIZADO");
            cleanupDownloadedUpdates();
            return;
        }

        File updateDir = new File(context.getFilesDir(), UPDATE_DIR);
        if (!updateDir.exists() && !updateDir.mkdirs()) throw new IllegalStateException("Não foi possível criar a pasta de atualização.");

        File apk = new File(updateDir, "antonelly-tv-" + expectedSha256.substring(0, 12) + ".apk");
        boolean reusable = apk.exists() && apk.length() > 0 && expectedSha256.equalsIgnoreCase(sha256(apk));

        if (!reusable) {
            setStatus("BAIXANDO");
            api.downloadUpdate(bucket, storagePath, token, apk, null);
            String actualSha = sha256(apk);
            if (!expectedSha256.equalsIgnoreCase(actualSha)) {
                apk.delete();
                throw new SecurityException("SHA-256 do APK não confere com o release publicado.");
            }
        }

        try {
            verifyApkIdentityAndVersion(apk);
        } catch (SecurityException versionOrIdentity) {
            String message = versionOrIdentity.getMessage() == null ? "" : versionOrIdentity.getMessage();
            if (message.startsWith("O APK enviado não possui versionCode maior")) {
                config.prefs().edit().putString(PREF_APPLIED_SHA, expectedSha256).apply();
                setStatus("ATUALIZADO");
                try { apk.delete(); } catch (Exception ignored) { }
                return;
            }
            throw versionOrIdentity;
        }

        config.prefs().edit()
                .putString(PREF_PENDING_APK, apk.getAbsolutePath())
                .putString(PREF_PENDING_SHA, expectedSha256)
                .putString(PREF_PENDING_ID, releaseId)
                .apply();

        setStatus("APK_BAIXADO");
        installPendingIfPossible();
    }

    /** Chamado quando o app volta do painel "Permitir desta fonte". */
    public void installPendingIfPossible() {
        String path = config.prefs().getString(PREF_PENDING_APK, "");
        if (path == null || path.trim().isEmpty()) return;

        File apk = new File(path);
        if (!apk.exists() || apk.length() == 0) {
            clearPending();
            return;
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && !context.getPackageManager().canRequestPackageInstalls()) {
                setStatus("AGUARDANDO_PERMISSAO_INSTALACAO");
                openUnknownSourcesSettings();
                return;
            }

            verifyApkIdentityAndVersion(apk);
            installWithPackageInstaller(apk);
            setStatus("INSTALACAO_SOLICITADA");
        } catch (Exception e) {
            setStatus("ERRO_INSTALACAO: " + safeMessage(e));
        }
    }

    public String getLastStatus() {
        return config.prefs().getString(PREF_LAST_STATUS, "");
    }

    private void verifyApkIdentityAndVersion(File apk) throws Exception {
        PackageManager pm = context.getPackageManager();
        PackageInfo archive;
        PackageInfo installed;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
            installed = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
        } else {
            archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNATURES);
            installed = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES);
        }

        if (archive == null || archive.packageName == null) throw new SecurityException("O arquivo baixado não é um APK Android válido.");
        if (!context.getPackageName().equals(archive.packageName)) throw new SecurityException("O APK pertence a outro pacote: " + archive.packageName);

        long archiveVersion = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? archive.getLongVersionCode() : archive.versionCode;
        long installedVersion = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? installed.getLongVersionCode() : installed.versionCode;
        if (archiveVersion <= installedVersion) {
            throw new SecurityException("O APK enviado não possui versionCode maior que a versão instalada (" + archiveVersion + " <= " + installedVersion + ").");
        }

        Signature[] installedSigners = signatures(installed);
        Signature[] archiveSigners = signatures(archive);
        if (installedSigners.length == 0 || archiveSigners.length == 0) throw new SecurityException("Não foi possível validar a assinatura do APK.");

        boolean match = false;
        for (Signature current : installedSigners) {
            for (Signature candidate : archiveSigners) {
                if (MessageDigest.isEqual(current.toByteArray(), candidate.toByteArray())) { match = true; break; }
            }
            if (match) break;
        }
        if (!match) throw new SecurityException("A assinatura do APK não é a mesma da versão instalada.");
    }

    private Signature[] signatures(PackageInfo info) {
        if (info == null) return new Signature[0];
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (info.signingInfo == null) return new Signature[0];
            if (info.signingInfo.hasMultipleSigners()) return info.signingInfo.getApkContentsSigners();
            Signature[] history = info.signingInfo.getSigningCertificateHistory();
            return history == null ? new Signature[0] : history;
        }
        return info.signatures == null ? new Signature[0] : info.signatures;
    }

    private void installWithPackageInstaller(File apk) throws Exception {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        params.setSize(apk.length());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }

        int sessionId = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(sessionId);
        try (InputStream input = new FileInputStream(apk);
             OutputStream output = session.openWrite("base.apk", 0, apk.length())) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
            session.fsync(output);
        }

        Intent result = new Intent(context, UpdateInstallReceiver.class)
                .setAction("br.com.antonelly.tv.UPDATE_INSTALL_RESULT")
                .putExtra("apk_path", apk.getAbsolutePath());

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, sessionId, result, flags);
        session.commit(pendingIntent.getIntentSender());
        session.close();
    }

    private void openUnknownSourcesSettings() {
        main.post(() -> {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            } catch (Exception e) {
                setStatus("ERRO_PERMISSAO_INSTALACAO: " + safeMessage(e));
            }
        });
    }

    private boolean isOnWifi() {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network network = cm.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
            return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        }
        @SuppressWarnings("deprecation") android.net.NetworkInfo info = cm.getActiveNetworkInfo();
        return info != null && info.isConnected() && info.getType() == ConnectivityManager.TYPE_WIFI;
    }

    private void cleanupDownloadedUpdates() {
        File directory = new File(context.getFilesDir(), UPDATE_DIR);
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) try { file.delete(); } catch (Exception ignored) { }
    }

    private void clearPending() {
        String path = config.prefs().getString(PREF_PENDING_APK, "");
        if (path != null && !path.isEmpty()) try { new File(path).delete(); } catch (Exception ignored) { }
        config.prefs().edit()
                .remove(PREF_PENDING_APK)
                .remove(PREF_PENDING_SHA)
                .remove(PREF_PENDING_ID)
                .apply();
    }

    private void setStatus(String status) {
        config.prefs().edit().putString(PREF_LAST_STATUS, status == null ? "" : status).apply();
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return hex.toString();
    }

    private static String safeMessage(Throwable throwable) {
        if (throwable == null) return "erro desconhecido";
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty() ? throwable.getClass().getSimpleName() : message.trim();
    }
}
