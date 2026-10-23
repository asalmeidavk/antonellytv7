package br.com.antonelly.tv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SetupActivity extends Activity {
    private AppConfig config;
    private EditText url;
    private EditText key;
    private EditText bucket;
    private Button diagnosticButton;
    private Button updateButton;
    private TextView toolsStatus;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        config = new AppConfig(this);
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (config != null && config.isConfigured()) {
            try {
                SupabaseClient api = new SupabaseClient(this, config);
                new UpdateManager(this, config, api).installPendingIfPossible();
            } catch (Exception ignored) { }
        }
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(247, 250, 248));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(40), dp(28), dp(40), dp(28));
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(text("ANTONELLY TV • CONFIGURAÇÃO", 13, Color.rgb(11, 122, 74), true), matchWrap());
        TextView title = text("Conectar ao backend", 30, Color.rgb(16, 33, 26), true);
        title.setPadding(0, dp(4), 0, dp(8));
        root.addView(title, matchWrap());

        TextView helper = text(
                "Informe somente a Project URL e a Publishable Key do Supabase. Diagnóstico e atualização do APK ficam disponíveis apenas nesta tela.",
                15, Color.rgb(100, 115, 108), false);
        helper.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(helper, matchWrap());

        url = field("https://seu-projeto.supabase.co");
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setText(config.getSupabaseUrl());
        addLabel(root, "Project URL", url);

        key = field("sb_publishable_...");
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        key.setText(config.getPublishableKey());
        addLabel(root, "Publishable Key", key);

        bucket = field("tv-media");
        bucket.setText(config.getMediaBucket());
        addLabel(root, "Bucket de mídias", bucket);

        diagnosticButton = button("Diagnóstico de conexão", Color.rgb(50, 65, 58), Color.WHITE);
        diagnosticButton.setOnClickListener(v -> runDiagnostic());
        LinearLayout.LayoutParams diagParams = new LinearLayout.LayoutParams(dp(340), dp(48));
        diagParams.topMargin = dp(22);
        root.addView(diagnosticButton, diagParams);

        updateButton = button("Verificar atualização", Color.rgb(42, 94, 51), Color.WHITE);
        updateButton.setOnClickListener(v -> checkUpdate());
        LinearLayout.LayoutParams updParams = new LinearLayout.LayoutParams(dp(340), dp(48));
        updParams.topMargin = dp(10);
        root.addView(updateButton, updParams);

        toolsStatus = text("", 13, Color.rgb(100, 115, 108), false);
        toolsStatus.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams statusParams = matchWrap();
        statusParams.topMargin = dp(10);
        root.addView(toolsStatus, statusParams);

        Button save = button("Salvar e iniciar", Color.rgb(11, 122, 74), Color.WHITE);
        save.setOnClickListener(v -> saveAndStart());
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(dp(340), dp(52));
        saveParams.topMargin = dp(18);
        root.addView(save, saveParams);

        Button demo = button("Testar sem servidor", Color.WHITE, Color.rgb(11, 122, 74));
        demo.setOnClickListener(v -> { config.enableDemoMode(); startPlayer(); });
        LinearLayout.LayoutParams demoParams = new LinearLayout.LayoutParams(dp(340), dp(48));
        demoParams.topMargin = dp(10);
        root.addView(demo, demoParams);

        TextView note = text(
                "As atualizações são consultadas diretamente no Supabase. O Android tentará instalar a atualização automaticamente; alguns aparelhos ainda podem exigir confirmação do sistema.",
                13, Color.rgb(100, 115, 108), false);
        note.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams noteParams = matchWrap();
        noteParams.topMargin = dp(18);
        root.addView(note, noteParams);

        return scroll;
    }

    private boolean persistBackendFields() {
        String nextUrl = url.getText().toString().trim();
        String nextKey = key.getText().toString().trim();
        String nextBucket = bucket.getText().toString().trim();

        if (!nextUrl.startsWith("https://") || !nextUrl.contains(".supabase.co")) {
            Toast.makeText(this, "Project URL inválida.", Toast.LENGTH_LONG).show();
            return false;
        }
        if (nextKey.length() < 20) {
            Toast.makeText(this, "Publishable Key inválida.", Toast.LENGTH_LONG).show();
            return false;
        }

        boolean backendChanged = config.saveBackend(nextUrl, nextKey, nextBucket);
        if (backendChanged) clearPlayerCache();
        return true;
    }

    private void runDiagnostic() {
        if (!persistBackendFields()) return;
        diagnosticButton.setEnabled(false);
        toolsStatus.setText("Testando conexão...");
        io.execute(() -> {
            SupabaseClient api = new SupabaseClient(this, config);
            JSONObject result = api.diagnoseConnection();
            runOnUiThread(() -> {
                diagnosticButton.setEnabled(true);
                if (result.optBoolean("ok", false)) {
                    toolsStatus.setText("Backend conectado com sucesso.");
                    Toast.makeText(this, "Supabase conectado.", Toast.LENGTH_LONG).show();
                } else {
                    String msg = result.optString("stage", "CLIENT") + ": " + result.optString("error", "Falha desconhecida");
                    toolsStatus.setText(msg);
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void checkUpdate() {
        if (!persistBackendFields()) return;
        updateButton.setEnabled(false);
        toolsStatus.setText("Verificando atualização no Supabase...");
        io.execute(() -> {
            try {
                SupabaseClient api = new SupabaseClient(this, config);
                UpdateManager manager = new UpdateManager(this, config, api);
                manager.checkAndUpdate();
                String status = manager.getLastStatus();
                runOnUiThread(() -> {
                    updateButton.setEnabled(true);
                    toolsStatus.setText(updateStatusToMessage(status));
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    updateButton.setEnabled(true);
                    toolsStatus.setText("Erro: " + SupabaseClient.describeException(e));
                });
            }
        });
    }

    private String updateStatusToMessage(String status) {
        String value = status == null ? "" : status.trim();
        if (value.isEmpty()) return "Verificação concluída.";
        if ("ATUALIZADO".equals(value) || "SEM_ATUALIZACAO".equals(value)) return "Nenhuma atualização nova disponível.";
        if ("AGUARDANDO_WIFI".equals(value)) return "Conecte o aparelho ao Wi-Fi para baixar a atualização.";
        if ("BAIXANDO".equals(value)) return "Baixando atualização...";
        if ("APK_BAIXADO".equals(value) || "INSTALACAO_SOLICITADA".equals(value)) return "APK baixado. Instalação solicitada ao Android.";
        if ("AGUARDANDO_PERMISSAO_INSTALACAO".equals(value)) return "Autorize a instalação de apps desta fonte para continuar.";
        if (value.startsWith("ERRO")) return value.replace('_', ' ');
        return "Status: " + value.replace('_', ' ');
    }

    private void saveAndStart() {
        if (!persistBackendFields()) return;
        startPlayer();
    }

    private void clearPlayerCache() {
        new File(getFilesDir(), "last_manifest.json").delete();
        deleteRecursively(new File(getFilesDir(), "media"));
    }

    private void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }

    private void startPlayer() {
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }

    private void addLabel(LinearLayout root, String label, EditText field) {
        TextView text = text(label, 13, Color.rgb(66, 82, 74), true);
        LinearLayout.LayoutParams labelParams = matchWrap();
        labelParams.topMargin = dp(18);
        root.addView(text, labelParams);
        LinearLayout.LayoutParams fieldParams = new LinearLayout.LayoutParams(
                Math.min(dp(760), getResources().getDisplayMetrics().widthPixels - dp(80)), dp(52));
        fieldParams.topMargin = dp(6);
        root.addView(field, fieldParams);
    }

    private EditText field(String hint) {
        EditText editText = new EditText(this);
        editText.setHint(hint);
        editText.setTextSize(15);
        editText.setSingleLine(true);
        editText.setPadding(dp(14), 0, dp(14), 0);
        editText.setBackgroundColor(Color.WHITE);
        return editText;
    }

    private Button button(String label, int background, int foreground) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(foreground);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setBackgroundColor(background);
        return button;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView textView = new TextView(this);
        textView.setText(value);
        textView.setTextSize(sp);
        textView.setTextColor(color);
        if (bold) textView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return textView;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
