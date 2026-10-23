# Antonelly TV Android v1.4.3

Correção de orientação para TVs instaladas na vertical.

- Mantém Media3/ExoPlayer 1.6.1 e compileSdk 35.
- Usa TextureView para permitir rotação real do vídeo.
- Aplica rotação por software quando Android TV/Fire TV mantém framebuffer landscape.
- LANDSCAPE e PORTRAIT chegam via tv_signal/tv_manifest e são aplicados sem depender de reinício.
- versionCode 10403 / versionName 1.4.3.
