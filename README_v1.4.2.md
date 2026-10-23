# Antonelly TV Android v1.4.2 — ExoPlayer Fix

Correção focada na reprodução de vídeo em Android TV / Fire OS.

## Alterações

- Substitui `VideoView` pelo AndroidX Media3 / ExoPlayer.
- Usa Media3 estável 1.6.1.
- Mantém o conteúdo/overlay anterior até o primeiro frame real do novo vídeo.
- Usa `onRenderedFirstFrame()` para confirmar que há imagem antes de remover o overlay.
- Exibe erro de decoder/playback em vez de permanecer em tela preta.
- Adiciona timeout de 15 segundos quando nenhum frame é renderizado.
- Mantém reprodução local pelo cache, playlists, standby, orientação e sincronização existentes.
- `versionCode`: 10402
- `versionName`: 1.4.2

## Compatibilidade recomendada para os arquivos

Para maior compatibilidade entre Android TV e Fire OS, prefira:

- Container: MP4
- Vídeo: H.264 / AVC
- Áudio: AAC
- Pixel format convencional: yuv420p

O ExoPlayer continua dependendo dos decoders disponíveis no dispositivo para os codecs de vídeo/áudio.
