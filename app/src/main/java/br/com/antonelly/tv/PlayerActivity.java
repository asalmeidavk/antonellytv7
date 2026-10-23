package br.com.antonelly.tv;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PlayerActivity extends Activity {
    private static final long SIGNAL_POLL_MS = 5_000L;
    private static final long HEARTBEAT_MS = 5 * 60_000L;
    private static final long UPDATE_POLL_MS = 60 * 60_000L;
    private static final long FAILED_SYNC_RETRY_MS = 60_000L;
    private static final String MANIFEST_FILE = "last_manifest.json";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService updateIo = Executors.newSingleThreadExecutor();

    private AppConfig config;
    private SupabaseClient api;
    private UpdateManager updateManager;

    private FrameLayout root;
    private FrameLayout displayLayer;
    private PlayerView videoView;
    private ExoPlayer exoPlayer;
    private Player.Listener activeVideoListener;
    private ImageView image;
    private LinearLayout overlay;
    private TextView title;
    private TextView message;
    private TextView pairing;
    private TextView footer;
    private ProgressBar progress;
    private Button updateButton;
    private Button diagnosticButton;

    private JSONObject session;
    private JSONObject manifest;
    private int currentVersion;
    private int playlistIndex = 0;
    private boolean destroyed = false;
    private boolean bootstrapComplete = false;
    private boolean syncing = false;
    private String playerState = "BOOTING";
    private String lastError = null;
    private JSONObject syncState = new JSONObject();
    private int failedSyncVersion = -1;
    private long failedSyncAt = 0L;
    private int renderGeneration = 0;
    private int backPressCount = 0;
    private String displayOrientation = "LANDSCAPE";
    private boolean forceStandby = false;
    private static final long CRITICAL_STANDBY_IMAGE_MS = 10_000L;
    private long firstBackPressAt = 0L;

    private final Runnable imageAdvanceRunnable = new Runnable() {
        @Override
        public void run() {
            nextItem();
        }
    };

    private final Runnable signalTick = new Runnable() {
        @Override
        public void run() {
            if (destroyed || config.isDemoMode() || !config.isConfigured()) return;

            io.execute(() -> {
                try {
                    pollSignal();
                } catch (Exception e) {
                    markOffline(e);
                } finally {
                    if (!destroyed) main.postDelayed(this, SIGNAL_POLL_MS);
                }
            });
        }
    };

    private final Runnable updateTick = new Runnable() {
        @Override
        public void run() {
            if (destroyed || config == null || config.isDemoMode()) return;

            updateIo.execute(() -> {
                try {
                    updateManager.checkAndUpdate();
                } catch (Exception e) {
                    config.prefs().edit()
                            .putString("update_last_status", "ERRO: " + safeMessage(e))
                            .apply();
                } finally {
                    if (!destroyed) main.postDelayed(this, UPDATE_POLL_MS);
                }
            });
        }
    };

    private final Runnable heartbeatTick = new Runnable() {
        @Override
        public void run() {
            if (destroyed || config.isDemoMode() || !config.isConfigured()) return;

            io.execute(() -> {
                try {
                    if (!bootstrapComplete) return;
                    String token = ensureToken();
                    api.heartbeat(token, playerState, currentVersion, syncState, lastError);
                } catch (Exception e) {
                    lastError = safeMessage(e);
                } finally {
                    if (!destroyed) main.postDelayed(this, HEARTBEAT_MS);
                }
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();

        config = new AppConfig(this);
        api = new SupabaseClient(this, config);
        updateManager = new UpdateManager(this, config, api);
        buildUi();

        if (!config.isConfigured() && !config.isDemoMode()) {
            openSetup();
            return;
        }

        if (config.isDemoMode()) {
            startDemo();
            return;
        }

        manifest = loadManifest();
        currentVersion = config.prefs().getInt("current_version", 0);

        if (manifest != null) {
            applyOrientation(manifest.optString("orientation", "LANDSCAPE"));
            playManifest(manifest);
        } else {
            showOverlay(
                    "Inicializando",
                    "Preparando esta tela para o primeiro pareamento.",
                    null,
                    false);
        }

        // Os ciclos são agendados mesmo se o primeiro acesso estiver offline.
        // Assim a TV se recupera sozinha quando a internet volta.
        main.postDelayed(signalTick, 2_000L);
        main.postDelayed(heartbeatTick, 10_000L);
        io.execute(this::bootOnline);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        if (updateManager != null && config != null && !config.isDemoMode()) {
            updateIo.execute(updateManager::installPendingIfPossible);
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        io.shutdownNow();
        updateIo.shutdownNow();
        try {
            if (exoPlayer != null) {
                if (activeVideoListener != null) exoPlayer.removeListener(activeVideoListener);
                exoPlayer.release();
            }
        } catch (Exception ignored) { }
        super.onDestroy();
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        // A maioria das Android TVs mantém o framebuffer em landscape mesmo quando
        // o painel físico é instalado na vertical. Por isso a orientação do conteúdo
        // é aplicada nesta camada, em vez de depender só de setRequestedOrientation().
        displayLayer = new FrameLayout(this);
        displayLayer.setBackgroundColor(Color.BLACK);
        FrameLayout.LayoutParams displayParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        displayParams.gravity = Gravity.CENTER;
        root.addView(displayLayer, displayParams);
        root.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                applyDisplayTransform());

        exoPlayer = new ExoPlayer.Builder(this).build();
        videoView = (PlayerView) getLayoutInflater().inflate(R.layout.player_video_view, displayLayer, false);
        videoView.setUseController(false);
        videoView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        videoView.setKeepContentOnPlayerReset(true);
        videoView.setShutterBackgroundColor(Color.BLACK);
        videoView.setBackgroundColor(Color.BLACK);
        videoView.setPlayer(exoPlayer);
        videoView.setVisibility(View.GONE);
        displayLayer.addView(videoView);

        image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setBackgroundColor(Color.BLACK);
        image.setVisibility(View.GONE);
        displayLayer.addView(image, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setGravity(Gravity.CENTER);
        overlay.setPadding(dp(48), dp(32), dp(48), dp(32));
        overlay.setBackground(new ColorDrawable(Color.rgb(9, 28, 19)));
        displayLayer.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        TextView brand = text("ANTONELLY TV", 18, Color.rgb(118, 191, 33), true);
        overlay.addView(brand);

        title = text("Inicializando", 38, Color.WHITE, true);
        LinearLayout.LayoutParams titleParams = wrap();
        titleParams.topMargin = dp(12);
        overlay.addView(title, titleParams);

        message = text("", 18, Color.rgb(215, 230, 222), false);
        message.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams messageParams = wrap();
        messageParams.topMargin = dp(12);
        overlay.addView(message, messageParams);

        pairing = text("", 62, Color.WHITE, true);
        pairing.setLetterSpacing(.08f);
        LinearLayout.LayoutParams pairingParams = wrap();
        pairingParams.topMargin = dp(22);
        overlay.addView(pairing, pairingParams);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dp(480), dp(12));
        progressParams.topMargin = dp(24);
        overlay.addView(progress, progressParams);

        footer = text("", 13, Color.rgb(153, 177, 164), false);
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams footerParams = wrap();
        footerParams.topMargin = dp(18);
        overlay.addView(footer, footerParams);

        // Diagnóstico e atualização ficam somente na tela "Conectar ao backend".
        updateButton = null;
        diagnosticButton = null;
    }

    private void runConnectionDiagnostic() {
        if (api == null || destroyed) return;

        main.post(() -> {
            diagnosticButton.setEnabled(false);
            diagnosticButton.setText("Testando...");
        });

        io.execute(() -> {
            JSONObject result = api.diagnoseConnection();
            boolean ok = result.optBoolean("ok", false);
            String stage = result.optString("stage", "CLIENT");
            String error = result.optString("error", "");

            if (ok) {
                main.post(() -> {
                    if (destroyed) return;
                    diagnosticButton.setEnabled(true);
                    diagnosticButton.setText("Diagnóstico");
                    Toast.makeText(
                            PlayerActivity.this,
                            "Supabase conectado. Iniciando pareamento...",
                            Toast.LENGTH_LONG).show();
                });
                bootOnline();
                return;
            }

            final String body = diagnosticStageLabel(stage) + "\n\n" + error;
            main.post(() -> {
                if (destroyed) return;
                diagnosticButton.setEnabled(true);
                diagnosticButton.setText("Diagnóstico");
                showOverlay(
                        "Falha de conexão",
                        body,
                        null,
                        false);
                footer.setText("Diagnóstico Antonelly TV • v" + BuildConfig.VERSION_NAME);
            });
        });
    }

    private String diagnosticStageLabel(String stage) {
        if ("GATEWAY".equals(stage)) return "Etapa 1/3: servidor / Project URL / Publishable Key";
        if ("AUTH".equals(stage)) return "Etapa 2/3: autenticação anônima";
        if ("RPC".equals(stage)) return "Etapa 3/3: funções tv_bootstrap / permissões";
        return "Etapa: " + stage;
    }

    /** Verificação manual mantida apenas para a tela de configuração. */
    private void checkUpdateManually() {
        if (config == null || updateManager == null || destroyed) return;
        setUpdateButtonState(false, "Verificando...");
        updateIo.execute(() -> {
            String feedback;
            try {
                updateManager.checkAndUpdate();
                feedback = updateStatusToMessage(updateManager.getLastStatus());
            } catch (Exception error) {
                feedback = "Falha ao verificar atualização: " + safeMessage(error);
                config.prefs().edit().putString("update_last_status", "ERRO: " + safeMessage(error)).apply();
            }
            final String finalFeedback = feedback;
            main.post(() -> {
                if (destroyed) return;
                setUpdateButtonState(true, "Verificar atualização");
                Toast.makeText(PlayerActivity.this, finalFeedback, Toast.LENGTH_LONG).show();
            });
        });
    }

    private void setUpdateButtonState(boolean enabled, String textValue) {
        main.post(() -> {
            if (updateButton == null) return;
            updateButton.setEnabled(enabled);
            updateButton.setText(textValue);
            updateButton.setAlpha(enabled ? 1.0f : 0.72f);
            updateButton.bringToFront();
        });
    }

    private String updateStatusToMessage(String status) {
        String value = status == null ? "" : status.trim();
        if (value.isEmpty()) return "Verificação concluída.";
        if ("ATUALIZADO".equals(value) || "SEM_ATUALIZACAO".equals(value)) {
            return "O Antonelly TV já está atualizado.";
        }
        if ("AGUARDANDO_WIFI".equals(value)) {
            return "Conecte o aparelho ao Wi-Fi para baixar a atualização.";
        }
        if ("BAIXANDO".equals(value)) {
            return "Baixando atualização...";
        }
        if ("APK_BAIXADO".equals(value) || "INSTALACAO_SOLICITADA".equals(value)) {
            return "Atualização baixada. O Android abrirá a instalação.";
        }
        if ("AGUARDANDO_PERMISSAO_INSTALACAO".equals(value)) {
            return "Autorize a instalação de apps desta fonte para continuar.";
        }
        if (value.startsWith("ERRO")) {
            return value.replace('_', ' ');
        }
        return "Status da atualização: " + value.replace('_', ' ');
    }

    private void bootOnline() {
        try {
            playerState = "CONNECTING";
            String token = ensureToken();
            bootstrapAndApply(token, true);
            if (failedSyncVersion < 0) lastError = null;
        } catch (Exception e) {
            markOffline(e);
        }
    }

    private void pollSignal() throws Exception {
        String token = ensureToken();

        if (!bootstrapComplete) {
            bootstrapAndApply(token, false);
            return;
        }

        JSONObject signal = api.signal(token);

        // Uma tela removida no painel deixa de existir no banco.
        // Nesse caso voltamos ao bootstrap e geramos um novo código de pareamento.
        if (!signal.optBoolean("paired", false)
                && signal.optString("pairing_code", "").trim().isEmpty()) {
            bootstrapComplete = false;
            bootstrapAndApply(token, false);
            return;
        }

        applySignal(signal, false);

        if (failedSyncVersion < 0) {
            lastError = null;
            if (manifest != null && !syncing && !"WAITING_PAIRING".equals(playerState)) {
                playerState = "PLAYING";
            }
        }
    }

    private void bootstrapAndApply(String token, boolean force) throws Exception {
        JSONObject boot = api.bootstrap(token);
        bootstrapComplete = true;

        if (!boot.optBoolean("paired", false)) {
            showPairing(boot.optString("pairing_code", ""));
            return;
        }

        JSONObject signal = api.signal(token);
        applySignal(signal, force);
    }

    private void applySignal(JSONObject signal, boolean force) throws Exception {
        if (!signal.optBoolean("paired", false)) {
            String code = signal.optString("pairing_code", "");
            if (code.trim().isEmpty()) bootstrapComplete = false;
            showPairing(code);
            return;
        }

        int serverVersion = signal.optInt("version", 0);
        String mode = signal.optString("mode", "NORMAL");
        applyOrientation(signal.optString("orientation", "LANDSCAPE"));

        boolean requestedStandby = signal.optBoolean("forceStandby", false);
        boolean standbyChanged = requestedStandby != forceStandby;
        forceStandby = requestedStandby;

        if (forceStandby && !syncing && (standbyChanged || !"STANDBY".equals(playerState))) {
            if (!playStandbyFromManifest(manifest)) {
                playerState = "STANDBY";
                stopMedia();
                showOverlay("Standby forçado", "Aguardando mídia de standby configurada no painel.", null, false);
            }
        }

        // Evita o ciclo infinito quando uma versão específica falha.
        if (!force
                && serverVersion == failedSyncVersion
                && System.currentTimeMillis() - failedSyncAt < FAILED_SYNC_RETRY_MS) {
            return;
        }

        if (force || manifest == null || serverVersion > currentVersion) {
            syncManifest(mode, serverVersion);
            return;
        }

        if (!forceStandby && standbyChanged && manifest != null) {
            main.post(() -> {
                playlistIndex = 0;
                playManifest(manifest, true);
            });
        }
    }

    private void syncManifest(String mode, int expectedVersion) throws Exception {
        if (syncing) return;
        syncing = true;

        boolean critical = "CRITICAL".equalsIgnoreCase(mode);
        boolean hadManifest = manifest != null;
        boolean standbyPlaying = false;
        StandbyGate criticalStandbyGate = null;

        try {
            playerState = "SYNCING";
            syncState = new JSONObject()
                    .put("state", "SYNCING")
                    .put("expectedVersion", expectedVersion)
                    .put("mode", mode);

            if (critical && hadManifest) {
                criticalStandbyGate = beginCriticalStandby(manifest);
                standbyPlaying = criticalStandbyGate != null;
            }

            final boolean[] blockingProgress = new boolean[] { !hadManifest || (critical && !standbyPlaying) };
            if (blockingProgress[0]) {
                stopMedia();
                showOverlay(
                        critical ? "Atualização imediata" : "Preparando conteúdo",
                        "Sincronizando os arquivos desta tela...",
                        null,
                        true);
            }

            String token = ensureToken();
            JSONObject next = api.manifest(token);
            if (!next.optBoolean("paired", false)) {
                showPairing(next.optString("pairing_code", ""));
                return;
            }

            int manifestVersion = next.optInt("version", expectedVersion);
            applyOrientation(next.optString("orientation", "LANDSCAPE"));
            if (critical && !standbyPlaying) {
                criticalStandbyGate = beginCriticalStandby(next);
                standbyPlaying = criticalStandbyGate != null;
                if (standbyPlaying) blockingProgress[0] = false;
            }
            List<JSONObject> media = collectMedia(next);
            Set<String> keepNames = new HashSet<>();
            File mediaDir = new File(getFilesDir(), "media");
            if (!mediaDir.exists()) mediaDir.mkdirs();

            for (int i = 0; i < media.size(); i++) {
                JSONObject item = media.get(i);
                String path = item.optString("storagePath", "");
                if (path.isEmpty()) continue;

                String fileName = SupabaseClient.cacheNameFor(path);
                keepNames.add(fileName);
                File target = new File(mediaDir, fileName);

                boolean valid = target.exists() && target.length() > 0;
                String sha = item.isNull("sha256") ? "" : item.optString("sha256", "").trim();
                if ("null".equalsIgnoreCase(sha)) sha = "";
                if (valid && !sha.isEmpty()) {
                    try {
                        valid = sha.equalsIgnoreCase(SupabaseClient.sha256(target));
                    } catch (Exception ignored) {
                        valid = false;
                    }
                }

                if (!valid) {
                    final int index = i;
                    final int total = media.size();
                    final String itemTitle = item.optString("title", path);

                    syncState = new JSONObject()
                            .put("state", "DOWNLOADING")
                            .put("file", itemTitle)
                            .put("index", i + 1)
                            .put("total", total)
                            .put("expectedVersion", expectedVersion);

                    if (blockingProgress[0]) {
                        main.post(() -> {
                            showOverlay("Sincronizando conteúdo", itemTitle, null, true);
                            progress.setVisibility(View.VISIBLE);
                            progress.setProgress((int) ((index * 100f) / Math.max(1, total)));
                        });
                    }

                    api.downloadMedia(path, token, target, (done, bytesTotal) -> {
                        if (!blockingProgress[0] || bytesTotal <= 0) return;
                        int percent = (int) Math.min(100, Math.round(done * 100f / bytesTotal));
                        main.post(() -> progress.setProgress((int) (
                                ((index + percent / 100f) * 100f) / Math.max(1, total))));
                    });

                    if (!sha.isEmpty() && !sha.equalsIgnoreCase(SupabaseClient.sha256(target))) {
                        target.delete();
                        throw new Exception("Falha de integridade em " + itemTitle);
                    }
                }

                if (critical && !standbyPlaying) {
                    JSONObject standby = next.optJSONObject("standby");
                    if (standby != null && path.equals(standby.optString("storagePath", ""))) {
                        criticalStandbyGate = beginCriticalStandby(next);
                        standbyPlaying = criticalStandbyGate != null;
                        if (standbyPlaying) blockingProgress[0] = false;
                    }
                }
            }

            cleanupCache(mediaDir, keepNames);

            // Só reconhecemos a versão após todos os arquivos serem válidos.
            manifest = next;
            currentVersion = Math.max(manifestVersion, expectedVersion);
            config.prefs().edit().putInt("current_version", currentVersion).commit();
            saveManifestAtomic(next);

            failedSyncVersion = -1;
            failedSyncAt = 0L;
            syncState = new JSONObject()
                    .put("state", "READY")
                    .put("version", currentVersion);
            lastError = null;
            playerState = (criticalStandbyGate != null || forceStandby) ? "STANDBY" : "PLAYING";

            final StandbyGate gateToWait = criticalStandbyGate;
            Runnable switchToPublishedContent = () -> {
                progress.setVisibility(View.GONE);
                playlistIndex = 0;
                if (forceStandby) {
                    if (!playStandbyFromManifest(next)) {
                        stopMedia();
                        showOverlay("Standby forçado", "Aguardando mídia de standby configurada no painel.", null, false);
                    }
                } else {
                    playManifest(next, true);
                }
            };

            if (critical && gateToWait != null) {
                gateToWait.whenFinished(switchToPublishedContent);
            } else {
                main.post(switchToPublishedContent);
            }

        } catch (Exception error) {
            failedSyncVersion = expectedVersion;
            failedSyncAt = System.currentTimeMillis();
            lastError = safeMessage(error);
            syncState = new JSONObject()
                    .put("state", "ERROR")
                    .put("expectedVersion", expectedVersion)
                    .put("error", lastError == null ? "" : lastError);

            final String detail = lastError == null || lastError.trim().isEmpty()
                    ? "Erro não identificado durante a sincronização."
                    : lastError;

            if (hasPlayableManifest(manifest)) {
                playerState = "PLAYING_OFFLINE";
                main.post(() -> {
                    progress.setVisibility(View.GONE);
                    playManifest(manifest, true);
                    Toast.makeText(
                            PlayerActivity.this,
                            "Falha ao atualizar conteúdo. Mantendo a versão anterior. " + detail,
                            Toast.LENGTH_LONG).show();
                });
            } else {
                playerState = "SYNC_ERROR";
                main.post(() -> {
                    progress.setVisibility(View.GONE);
                    showOverlay(
                            "Falha ao sincronizar",
                            detail + "\n\nNova tentativa automática em 60 segundos.",
                            null,
                            false);
                });
            }
        } finally {
            syncing = false;
        }
    }

    private boolean hasPlayableManifest(JSONObject source) {
        if (source == null) return false;
        JSONArray list = source.optJSONArray("playlist");
        if (list == null || list.length() == 0) return false;

        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null) continue;
            String path = item.optString("storagePath", "");
            if (!path.isEmpty() && cachedFile(path).exists()) return true;
        }
        return false;
    }

    private List<JSONObject> collectMedia(JSONObject source) {
        List<JSONObject> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        JSONObject standby = source.optJSONObject("standby");
        if (standby != null && !standby.optString("storagePath", "").isEmpty()) {
            seen.add(standby.optString("storagePath"));
            result.add(standby);
        }

        JSONArray playlist = source.optJSONArray("playlist");
        if (playlist != null) {
            for (int i = 0; i < playlist.length(); i++) {
                JSONObject item = playlist.optJSONObject(i);
                if (item == null) continue;
                String path = item.optString("storagePath", "");
                if (!path.isEmpty() && seen.add(path)) result.add(item);
            }
        }

        return result;
    }

    private void playManifest(JSONObject source) {
        playManifest(source, false);
    }

    /**
     * safeTransition=true é usado após uma sincronização. Nessa situação o player
     * nunca descobre o conteúdo antigo antes de confirmar que o novo bitmap ou
     * vídeo está pronto. Se o Android demorar para preparar a mídia, mostramos o
     * overlay em vez de deixar o Surface preto.
     */
    private void playManifest(JSONObject source, boolean safeTransition) {
        JSONArray list = source == null ? null : source.optJSONArray("playlist");

        if (list == null || list.length() == 0) {
            boolean standby = playStandbyFromManifest(source);
            if (standby) {
                playerState = "STANDBY";
            } else {
                playerState = "NO_CONTENT";
                stopMedia();
                showOverlay(
                        "Aguardando conteúdo",
                        "Esta tela está pareada, mas ainda não possui uma playlist publicada.",
                        null,
                        false);
            }
            return;
        }

        playerState = "PLAYING";
        if (playlistIndex >= list.length()) playlistIndex = 0;

        JSONObject item = list.optJSONObject(playlistIndex);
        if (item == null) {
            nextItem();
            return;
        }

        String path = item.optString("storagePath", "");
        File file = cachedFile(path);

        if (!file.exists() || file.length() <= 0) {
            lastError = "Mídia ausente no cache: " + path;
            nextItem();
            return;
        }

        String type = item.optString("type", "video");
        String itemTitle = item.optString("title", file.getName());
        if ("image".equalsIgnoreCase(type)) {
            playImage(file, item.optInt("durationSeconds", 10), safeTransition, itemTitle);
        } else {
            playVideo(file, false, safeTransition, itemTitle);
        }
    }

    private void playVideo(File file, boolean loop) {
        playVideo(file, loop, false, file == null ? "Vídeo" : file.getName());
    }

    private void playVideo(File file, boolean loop, boolean safeTransition, String label) {
        final int generation = ++renderGeneration;
        main.post(() -> {
            main.removeCallbacks(imageAdvanceRunnable);

            if (safeTransition) {
                showOverlay(
                        loop ? "Preparando standby" : "Preparando conteúdo",
                        label == null ? "Abrindo vídeo..." : label,
                        null,
                        false);
            }

            try {
                if (exoPlayer == null) throw new Exception("Motor de vídeo não inicializado.");

                if (activeVideoListener != null) {
                    exoPlayer.removeListener(activeVideoListener);
                    activeVideoListener = null;
                }

                // Mantém o frame anterior ou o overlay enquanto o novo vídeo prepara.
                // A interface só troca definitivamente quando o primeiro frame é renderizado.
                videoView.setVisibility(View.VISIBLE);
                exoPlayer.stop();
                exoPlayer.clearMediaItems();
                exoPlayer.setRepeatMode(loop ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);

                final boolean[] firstFrameRendered = new boolean[] { false };

                activeVideoListener = new Player.Listener() {
                    @Override
                    public void onRenderedFirstFrame() {
                        if (generation != renderGeneration || firstFrameRendered[0]) return;
                        firstFrameRendered[0] = true;
                        image.setVisibility(View.GONE);
                        videoView.setVisibility(View.VISIBLE);
                        lastError = null;
                        playerState = loop ? "STANDBY" : "PLAYING";
                        hideOverlay();
                    }

                    @Override
                    public void onPlaybackStateChanged(int playbackState) {
                        if (generation != renderGeneration) return;
                        if (playbackState == Player.STATE_ENDED && !loop) {
                            nextItem();
                        }
                    }

                    @Override
                    public void onPlayerError(PlaybackException error) {
                        if (generation != renderGeneration) return;
                        String code = PlaybackException.getErrorCodeName(error.errorCode);
                        String cause = error.getCause() == null ? "" : (" • " + safeThrowable(error.getCause()));
                        lastError = "Erro de vídeo " + code + " em " + label + cause;
                        showOverlay(
                                "Não foi possível reproduzir",
                                lastError + "\n\nUse MP4 com vídeo H.264/AVC e áudio AAC para máxima compatibilidade com Android TV.",
                                null,
                                false);
                        if (!loop && playlistLength() > 1) main.postDelayed(PlayerActivity.this::nextItem, 2500L);
                    }
                };

                exoPlayer.addListener(activeVideoListener);
                exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)));
                exoPlayer.prepare();
                exoPlayer.play();

                // Se o decoder disser que está pronto mas nenhum frame aparecer, não deixamos
                // a TV eternamente preta. O timeout transforma isso em um diagnóstico visível.
                main.postDelayed(() -> {
                    if (generation != renderGeneration || destroyed) return;
                    if (firstFrameRendered[0]) return;
                    PlaybackException playerError = exoPlayer == null ? null : exoPlayer.getPlayerError();
                    if (playerError != null) return; // onPlayerError já cuida disso.
                    lastError = "Vídeo não iniciou em tempo hábil: " + label;
                    showOverlay(
                            "Vídeo sem imagem",
                            lastError + "\n\nO arquivo foi sincronizado, mas o dispositivo não conseguiu iniciar a reprodução. Prefira MP4 H.264/AVC + AAC.",
                            null,
                            false);
                }, 15_000L);

            } catch (Exception error) {
                lastError = "Erro ao abrir vídeo " + label + ": " + safeMessage(error);
                showOverlay("Não foi possível reproduzir", lastError, null, false);
                if (!loop && playlistLength() > 1) main.postDelayed(this::nextItem, 2500L);
            }
        });
    }

    private int playlistLength() {
        if (manifest == null) return 0;
        JSONArray list = manifest.optJSONArray("playlist");
        return list == null ? 0 : list.length();
    }

    private void playImage(File file, int seconds) {
        playImage(file, seconds, false, file == null ? "Imagem" : file.getName());
    }

    private void playImage(File file, int seconds, boolean safeTransition, String label) {
        final int generation = ++renderGeneration;

        if (safeTransition) {
            showOverlay(
                    "Preparando conteúdo",
                    label == null ? "Abrindo imagem..." : label,
                    null,
                    false);
        }

        io.execute(() -> {
            try {
                Bitmap bitmap = decodeImageForScreen(file);
                if (bitmap == null) throw new Exception("Android não conseguiu decodificar a imagem.");

                main.post(() -> {
                    if (generation != renderGeneration) {
                        try { bitmap.recycle(); } catch (Exception ignored) { }
                        return;
                    }
                    main.removeCallbacks(imageAdvanceRunnable);
                    try { if (exoPlayer != null) exoPlayer.stop(); } catch (Exception ignored) { }
                    videoView.setVisibility(View.GONE);
                    image.setImageBitmap(bitmap);
                    image.setVisibility(View.VISIBLE);
                    hideOverlay();
                    main.postDelayed(imageAdvanceRunnable, Math.max(1, seconds) * 1000L);
                });
            } catch (Exception error) {
                lastError = "Erro ao abrir imagem " + label + ": " + safeMessage(error);
                main.post(() -> {
                    if (generation != renderGeneration) return;
                    showOverlay("Não foi possível exibir a imagem", lastError, null, false);
                    main.postDelayed(this::nextItem, 1500L);
                });
            }
        });
    }

    private Bitmap decodeImageForScreen(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        int targetW = Math.max(1, getResources().getDisplayMetrics().widthPixels);
        int targetH = Math.max(1, getResources().getDisplayMetrics().heightPixels);
        int sample = 1;
        while ((bounds.outWidth / sample) > targetW * 2 || (bounds.outHeight / sample) > targetH * 2) {
            sample *= 2;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = Math.max(1, sample);
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    private void nextItem() {
        main.removeCallbacks(imageAdvanceRunnable);
        if (manifest == null || syncing) return;

        JSONArray list = manifest.optJSONArray("playlist");
        if (list == null || list.length() == 0) return;

        playlistIndex = (playlistIndex + 1) % list.length();
        playManifest(manifest);
    }

    private final class StandbyGate {
        private boolean finished = false;
        private Runnable callback;

        synchronized void whenFinished(Runnable next) {
            if (finished) {
                main.post(next);
            } else {
                callback = next;
            }
        }

        synchronized void finish() {
            if (finished) return;
            finished = true;
            Runnable next = callback;
            callback = null;
            if (next != null) main.post(next);
        }
    }

    /**
     * Standby exclusivo da sincronização CRITICAL.
     * Imagem: permanece no mínimo 10 s após aparecer.
     * Vídeo: toca uma única vez, do começo ao fim.
     */
    private StandbyGate beginCriticalStandby(JSONObject source) {
        if (source == null) return null;
        JSONObject standby = source.optJSONObject("standby");
        if (standby == null) return null;
        File file = cachedFile(standby.optString("storagePath", ""));
        if (!file.exists() || file.length() <= 0) return null;

        StandbyGate gate = new StandbyGate();
        String type = standby.optString("type", "video");
        String label = standby.optString("title", "Standby");
        if ("image".equalsIgnoreCase(type)) {
            playCriticalStandbyImage(file, label, gate);
        } else {
            playCriticalStandbyVideo(file, label, gate);
        }
        return gate;
    }

    private void playCriticalStandbyImage(File file, String label, StandbyGate gate) {
        final int generation = ++renderGeneration;
        showOverlay("Preparando standby", label == null ? "Abrindo imagem..." : label, null, false);
        try {
            Bitmap bitmap = decodeImageForScreen(file);
            if (bitmap == null) throw new Exception("Android não conseguiu decodificar a imagem de standby.");
            main.post(() -> {
                if (generation != renderGeneration) {
                    try { bitmap.recycle(); } catch (Exception ignored) { }
                    gate.finish();
                    return;
                }
                main.removeCallbacks(imageAdvanceRunnable);
                try { if (exoPlayer != null) exoPlayer.stop(); } catch (Exception ignored) { }
                videoView.setVisibility(View.GONE);
                image.setImageBitmap(bitmap);
                image.setVisibility(View.VISIBLE);
                playerState = "STANDBY";
                hideOverlay();
                main.postDelayed(gate::finish, CRITICAL_STANDBY_IMAGE_MS);
            });
        } catch (Exception error) {
            lastError = "Erro no standby " + label + ": " + safeMessage(error);
            main.post(() -> {
                showOverlay("Standby indisponível", lastError, null, false);
                gate.finish();
            });
        }
    }

    private void playCriticalStandbyVideo(File file, String label, StandbyGate gate) {
        final int generation = ++renderGeneration;
        main.post(() -> {
            showOverlay("Preparando standby", label == null ? "Abrindo vídeo..." : label, null, false);
            try {
                if (exoPlayer == null) throw new Exception("Motor de vídeo não inicializado.");
                if (activeVideoListener != null) exoPlayer.removeListener(activeVideoListener);
                main.removeCallbacks(imageAdvanceRunnable);
                videoView.setVisibility(View.VISIBLE);
                exoPlayer.stop();
                exoPlayer.clearMediaItems();
                exoPlayer.setRepeatMode(Player.REPEAT_MODE_OFF);

                final boolean[] firstFrame = new boolean[] { false };
                activeVideoListener = new Player.Listener() {
                    @Override
                    public void onRenderedFirstFrame() {
                        if (generation != renderGeneration || firstFrame[0]) return;
                        firstFrame[0] = true;
                        image.setVisibility(View.GONE);
                        videoView.setVisibility(View.VISIBLE);
                        playerState = "STANDBY";
                        hideOverlay();
                    }

                    @Override
                    public void onPlaybackStateChanged(int playbackState) {
                        if (generation != renderGeneration) return;
                        if (playbackState == Player.STATE_ENDED) gate.finish();
                    }

                    @Override
                    public void onPlayerError(PlaybackException error) {
                        if (generation != renderGeneration) return;
                        lastError = "Erro no standby " + label + ": " + PlaybackException.getErrorCodeName(error.errorCode);
                        showOverlay("Standby indisponível", lastError, null, false);
                        gate.finish();
                    }
                };
                exoPlayer.addListener(activeVideoListener);
                exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)));
                exoPlayer.prepare();
                exoPlayer.play();

                main.postDelayed(() -> {
                    if (generation != renderGeneration || firstFrame[0]) return;
                    lastError = "Vídeo de standby não iniciou em tempo hábil: " + label;
                    showOverlay("Standby indisponível", lastError, null, false);
                    gate.finish();
                }, 15_000L);
            } catch (Exception error) {
                lastError = "Erro no standby " + label + ": " + safeMessage(error);
                showOverlay("Standby indisponível", lastError, null, false);
                gate.finish();
            }
        });
    }

    private boolean playStandbyFromManifest(JSONObject source) {
        if (source == null) return false;
        JSONObject standby = source.optJSONObject("standby");
        if (standby == null) return false;

        File file = cachedFile(standby.optString("storagePath", ""));
        if (!file.exists() || file.length() <= 0) return false;

        String type = standby.optString("type", "video");
        String label = standby.optString("title", "Standby");
        if ("image".equalsIgnoreCase(type)) {
            playStandbyImage(file, label);
        } else {
            playVideo(file, true, true, label);
        }
        return true;
    }

    private void playStandbyImage(File file, String label) {
        final int generation = ++renderGeneration;
        showOverlay("Preparando standby", label == null ? "Abrindo imagem..." : label, null, false);

        io.execute(() -> {
            try {
                Bitmap bitmap = decodeImageForScreen(file);
                if (bitmap == null) throw new Exception("Android não conseguiu decodificar a imagem de standby.");

                main.post(() -> {
                    if (generation != renderGeneration) {
                        try { bitmap.recycle(); } catch (Exception ignored) { }
                        return;
                    }
                    main.removeCallbacks(imageAdvanceRunnable);
                    try { if (exoPlayer != null) exoPlayer.stop(); } catch (Exception ignored) { }
                    videoView.setVisibility(View.GONE);
                    image.setImageBitmap(bitmap);
                    image.setVisibility(View.VISIBLE);
                    hideOverlay();
                });
            } catch (Exception error) {
                lastError = "Erro no standby " + label + ": " + safeMessage(error);
                main.post(() -> {
                    if (generation != renderGeneration) return;
                    showOverlay("Standby indisponível", lastError, null, false);
                });
            }
        });
    }

    private void applyOrientation(String value) {
        String orientation = value == null ? "LANDSCAPE" : value.trim().toUpperCase();
        if (!"PORTRAIT".equals(orientation)) orientation = "LANDSCAPE";
        displayOrientation = orientation;
        main.post(this::applyDisplayTransform);
    }

    /**
     * Rotaciona o canvas do player quando a orientação lógica desejada não
     * corresponde à orientação real reportada pelo dispositivo. Isso funciona
     * inclusive em Android TV/Fire TV que ignoram requestedOrientation.
     */
    private void applyDisplayTransform() {
        if (root == null || displayLayer == null) return;

        int width = root.getWidth();
        int height = root.getHeight();
        if (width <= 0 || height <= 0) return;

        boolean wantsPortrait = "PORTRAIT".equals(displayOrientation);
        boolean devicePortrait = height > width;

        FrameLayout.LayoutParams params;
        if (wantsPortrait == devicePortrait) {
            params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            params.gravity = Gravity.CENTER;
            displayLayer.setLayoutParams(params);
            displayLayer.setRotation(0f);
        } else {
            // Troca largura/altura antes da rotação para que o conteúdo use toda
            // a área física da TV após girar 90 graus.
            params = new FrameLayout.LayoutParams(height, width);
            params.gravity = Gravity.CENTER;
            displayLayer.setLayoutParams(params);
            displayLayer.setRotation(wantsPortrait ? 90f : -90f);
        }

        displayLayer.requestLayout();
        displayLayer.invalidate();
    }

    private void stopMedia() {
        renderGeneration++;
        main.post(() -> {
            main.removeCallbacks(imageAdvanceRunnable);
            try {
                if (exoPlayer != null) {
                    if (activeVideoListener != null) {
                        exoPlayer.removeListener(activeVideoListener);
                        activeVideoListener = null;
                    }
                    exoPlayer.stop();
                    exoPlayer.clearMediaItems();
                }
            } catch (Exception ignored) {
            }
            videoView.setVisibility(View.GONE);
            image.setVisibility(View.GONE);
        });
    }

    private File cachedFile(String storagePath) {
        File mediaDir = new File(getFilesDir(), "media");
        if (!mediaDir.exists()) mediaDir.mkdirs();
        return new File(mediaDir, SupabaseClient.cacheNameFor(storagePath));
    }

    private void cleanupCache(File directory, Set<String> keep) {
        File[] files = directory.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (!keep.contains(file.getName()) && !file.getName().endsWith(".part")) {
                file.delete();
            }
        }
    }

    private String ensureToken() throws Exception {
        session = api.ensureSession();
        return session.optString("access_token", "");
    }

    private void showPairing(String code) {
        playerState = "WAITING_PAIRING";
        stopMedia();
        showOverlay(
                "Vincule esta tela",
                "No Painel Antonelly TV, acesse Telas e informe este código:",
                code,
                false);
        main.post(() -> footer.setText(
                "Depois do pareamento, a tela será atualizada automaticamente. • v" + BuildConfig.VERSION_NAME));
    }

    private void startDemo() {
        playerState = "DEMO";
        overlay.setVisibility(View.GONE);
        videoView.setVisibility(View.GONE);
        image.setImageResource(R.drawable.demo_slide);
        image.setVisibility(View.VISIBLE);
    }

    private void showOverlay(String heading, String body, String code, boolean showProgress) {
        main.post(() -> {
            overlay.setVisibility(View.VISIBLE);
            title.setText(heading == null ? "" : heading);
            message.setText(body == null ? "" : body);
            pairing.setText(code == null ? "" : code);
            progress.setVisibility(showProgress ? View.VISIBLE : View.GONE);
            footer.setText("Antonelly TV Android v" + BuildConfig.VERSION_NAME);
            if (updateButton != null) updateButton.bringToFront();
            if (diagnosticButton != null) diagnosticButton.bringToFront();
        });
    }

    private void hideOverlay() {
        main.post(() -> {
            overlay.setVisibility(View.GONE);
            if (updateButton != null) updateButton.bringToFront();
            if (diagnosticButton != null) diagnosticButton.bringToFront();
        });
    }

    private JSONObject loadManifest() {
        File file = new File(getFilesDir(), MANIFEST_FILE);
        if (!file.exists()) return null;

        try (FileInputStream input = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int read = input.read(data);
            if (read <= 0) return null;
            return new JSONObject(new String(data, 0, read, StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return null;
        }
    }

    private void saveManifestAtomic(JSONObject value) throws Exception {
        File target = new File(getFilesDir(), MANIFEST_FILE);
        File temp = new File(getFilesDir(), MANIFEST_FILE + ".tmp");

        try (FileOutputStream output = new FileOutputStream(temp, false)) {
            output.write(value.toString().getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
        }

        if (target.exists() && !target.delete()) {
            throw new Exception("Não foi possível substituir o manifesto local.");
        }
        if (!temp.renameTo(target)) {
            throw new Exception("Não foi possível finalizar o manifesto local.");
        }
    }

    private void markOffline(Exception error) {
        lastError = safeMessage(error);
        playerState = manifest != null ? "PLAYING_OFFLINE" : "OFFLINE";

        if (manifest == null) {
            String detail = lastError == null || lastError.trim().isEmpty()
                    ? "Erro não identificado."
                    : lastError;
            showOverlay(
                    "Sem conexão",
                    "O Android possui rede, mas o app não concluiu a conexão com o backend.\n\n" +
                            "Detalhe: " + detail + "\n\n" +
                            "Use o botão Diagnóstico para testar servidor, autenticação e RPC separadamente.",
                    null,
                    false);
        }
    }

    private static String safeMessage(Exception error) {
        return SupabaseClient.describeException(error);
    }

    private static String safeThrowable(Throwable error) {
        if (error == null) return "erro desconhecido";
        if (error instanceof Exception) return safeMessage((Exception) error);
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null || message.trim().isEmpty() ? "" : ": " + message);
    }

    private void openSetup() {
        startActivity(new Intent(this, SetupActivity.class));
        finish();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            openSetup();
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_BACK) {
            long now = System.currentTimeMillis();
            if (firstBackPressAt == 0L || now - firstBackPressAt > 5000L) {
                firstBackPressAt = now;
                backPressCount = 1;
            } else {
                backPressCount++;
            }

            if (backPressCount >= 5) {
                openSetup();
                return true;
            }
            return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView textView = new TextView(this);
        textView.setText(value);
        textView.setTextSize(sp);
        textView.setTextColor(color);
        textView.setGravity(Gravity.CENTER);
        if (bold) textView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return textView;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
