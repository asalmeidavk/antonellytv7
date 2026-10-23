# Antonelly TV v1.4.2 — ExoPlayer / SDK 35 Fix

Correção de compatibilidade do build no GitHub Actions.

- Mantém `compileSdk 35` e `targetSdk 35`.
- Mantém Android Gradle Plugin `8.7.3`.
- Mantém Gradle `8.11.1`.
- Mantém `android.useAndroidX=true`.
- Ajusta AndroidX Media3 / ExoPlayer de `1.11.1` para `1.6.1`, versão compatível com compileSdk 35.
- Não altera `versionCode 10402` nem `versionName 1.4.2`, porque a versão anterior não chegou a compilar/publicar.
- Não altera lógica de Supabase, cache, atualização OTA, standby ou orientação.

## Build debug

```bash
gradle :app:assembleDebug --no-daemon
```

## Build release

Use o workflow `.github/workflows/build-release-apk.yml` já incluído no projeto.
