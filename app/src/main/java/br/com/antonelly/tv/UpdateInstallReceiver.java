package br.com.antonelly.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/** Recebe o resultado do PackageInstaller e abre a confirmação quando o Android exigir. */
public class UpdateInstallReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;

        int status = intent.getIntExtra(
                PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);

        AppConfig config = new AppConfig(context);

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            config.prefs().edit()
                    .putString("update_last_status", "AGUARDANDO_CONFIRMACAO_USUARIO")
                    .apply();

            Intent confirmation = (Intent) intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirmation != null) {
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(confirmation);
            }
            return;
        }

        if (status == PackageInstaller.STATUS_SUCCESS) {
            String apkPath = intent.getStringExtra("apk_path");
            if (apkPath != null && !apkPath.isEmpty()) {
                try {
                    new java.io.File(apkPath).delete();
                } catch (Exception ignored) {
                }
            }

            String pendingSha = config.prefs().getString(UpdateManager.PREF_PENDING_SHA, "");
            android.content.SharedPreferences.Editor editor = config.prefs().edit()
                    .putString("update_last_status", "INSTALADO")
                    .remove(UpdateManager.PREF_PENDING_APK)
                    .remove(UpdateManager.PREF_PENDING_ID);
            if (pendingSha != null && !pendingSha.isEmpty()) {
                editor.putString(UpdateManager.PREF_APPLIED_SHA, pendingSha);
            }
            editor.remove(UpdateManager.PREF_PENDING_SHA).apply();

            // Em muitos dispositivos o update encerra o processo atual.
            // Tentamos reabrir o player; se o fabricante bloquear o start em
            // background, o app continuará instalado e abrirá normalmente
            // pelo launcher.
            try {
                Intent launch = context.getPackageManager()
                        .getLaunchIntentForPackage(context.getPackageName());
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    context.startActivity(launch);
                }
            } catch (Exception ignored) {
            }
            return;
        }

        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        config.prefs().edit()
                .putString(
                        "update_last_status",
                        "FALHA_INSTALACAO_" + status + (message == null ? "" : ": " + message))
                .apply();
    }
}
