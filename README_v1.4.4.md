# Antonelly TV Android v1.4.4

- versionCode: 10404
- versionName: 1.4.4
- Media3/ExoPlayer: 1.6.1
- compileSdk / targetSdk: 35

## Novidades
- Standby forçado por tela, controlado no WebApp.
- Sincronização CRITICAL respeita o standby:
  - imagem: mínimo de 10 segundos depois de aparecer;
  - vídeo: toca uma vez do início ao fim antes de entrar a nova playlist.
- Diagnóstico e Verificar atualização não aparecem mais no player. Ficam somente em "Conectar ao backend".
- Atualização passa a ser baixada do bucket privado `tv-updates` do Supabase.
- O APK baixado é validado por SHA-256, package name, assinatura e versionCode antes da instalação.
- A instalação usa PackageInstaller com USER_ACTION_NOT_REQUIRED quando disponível. O Android ainda pode exigir confirmação em alguns aparelhos.

## GitHub Actions
Use `.github/workflows/build-release-apk.yml`.
O artifact `Antonelly-TV-release-apk` contém o APK release assinado e um TXT com o SHA-256.
Depois faça o upload do APK no WebApp > Configurações > Upload de atualização.
