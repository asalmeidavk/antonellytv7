# Antonelly TV Android v1.4.0

Mudanças principais:

- orientação remota por tela, Horizontal ou Vertical;
- `PlayerActivity` deixou de ser travada em landscape no Manifest;
- orientação recebida tanto por `tv_signal()` quanto pelo manifesto;
- intervalo de sinal reduzido de 15 s para 5 s;
- standby suporta vídeo em loop e imagem fixa;
- standby é exibido quando não existe playlist;
- no modo CRITICAL, o player tenta exibir o standby enquanto a nova playlist é baixada;
- mantém cache offline, diagnóstico, auto-update e correções de integridade da v1.3.4.

Versão Android:

- `versionCode 10400`
- `versionName 1.4.0`

O backend deve receber o patch Supabase v1.5.0 antes de usar orientação e sincronização imediata.
