package br.com.antonelly.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Tenta devolver o dispositivo ao player depois que o próprio APK é substituído. */
public class AppUpdatedReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;

        try {
            AppConfig config = new AppConfig(context);
            String pendingSha = config.prefs().getString(UpdateManager.PREF_PENDING_SHA, "");
            if (pendingSha != null && !pendingSha.isEmpty()) {
                config.prefs().edit()
                        .putString(UpdateManager.PREF_APPLIED_SHA, pendingSha)
                        .putString("update_last_status", "INSTALADO")
                        .remove(UpdateManager.PREF_PENDING_SHA)
                        .remove(UpdateManager.PREF_PENDING_APK)
                        .remove(UpdateManager.PREF_PENDING_ID)
                        .apply();
            }

            Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                context.startActivity(launch);
            }
        } catch (Exception ignored) {
        }
    }
}
