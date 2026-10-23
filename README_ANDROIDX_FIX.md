# Antonelly TV v1.4.2 - AndroidX Build Fix

Correção aplicada ao `gradle.properties` para permitir o uso do AndroidX Media3 / ExoPlayer.

Antes:

```properties
android.useAndroidX=false
```

Depois:

```properties
android.useAndroidX=true
```

O projeto continua com `versionName 1.4.2` e `versionCode 10402`.

No GitHub Actions, execute novamente:

```bash
gradle :app:assembleDebug --no-daemon
```

ou o workflow de release:

```bash
gradle :app:assembleRelease --no-daemon
```
