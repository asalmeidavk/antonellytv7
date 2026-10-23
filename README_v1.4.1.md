# Antonelly TV Android v1.4.1 — Black Screen Fix

Correção focada na transição visual após sincronização.

- o overlay não é removido antes da mídia estar realmente pronta;
- imagens são decodificadas e validadas antes de substituir a mídia anterior;
- vídeos removem o overlay somente após `onPrepared`;
- standby em imagem/vídeo segue a mesma regra;
- callbacks antigos são ignorados por geração de renderização;
- erro de renderização passa a aparecer na tela em vez de resultar em tela preta;
- mantém cache, orientação, sync, update e demais fluxos da v1.4.0.

Version code: 10401
Version name: 1.4.1
