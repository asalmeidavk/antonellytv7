# Antonelly TV Android v1.3.3 — correção do loop de sincronização

Esta revisão corrige o comportamento em que a tela repetia indefinidamente "Sincronizando conteúdo" quando uma mídia falhava durante o download ou validação.

## Mudanças

- falhas de sincronização agora ficam visíveis na tela;
- o erro real é salvo em `last_error` e em `sync.state = ERROR`;
- a mesma versão com erro não é baixada novamente a cada 15 segundos;
- nova tentativa automática da versão com falha ocorre após 60 segundos;
- se houver uma playlist anterior íntegra em cache, ela continua tocando;
- `current_version` só é confirmado depois que todo o manifesto terminou com sucesso;
- escrita de `current_version` passa a ser síncrona (`commit`) antes do manifesto local ser confirmado;
- sucesso limpa o estado de falha e inicia a playlist normalmente.

## Diagnóstico

Execute `DIAGNOSTICO_LOOP_SINCRONIZACAO.sql` no Supabase para ver:

- `manifest_version` do servidor;
- `current_version` confirmado pelo APK;
- `last_error`;
- estado `sync`;
- objetos ausentes no Storage;
- checksums cadastrados.
