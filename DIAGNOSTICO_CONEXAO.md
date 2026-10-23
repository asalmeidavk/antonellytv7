# Antonelly TV v1.3.2 — Diagnóstico de conexão

Esta versão separa a conexão em 3 etapas para impedir que qualquer falha apareça apenas como `Sem conexão`.

## Etapa 1/3 — GATEWAY
Testa `GET /auth/v1/settings` usando a Project URL e a Publishable Key.

Falhas comuns:
- `DNS`: rede/Private DNS/firewall não resolve o domínio do Supabase;
- `HTTP 401`: Publishable Key inválida para este projeto;
- `TLS/SSL`: relógio/certificados/rede interceptando HTTPS;
- `TIMEOUT`: firewall, proxy ou rota de rede bloqueando o Supabase.

## Etapa 2/3 — AUTH
Cria ou renova a sessão anônima da TV.

Falhas comuns:
- Anonymous Sign-Ins desabilitado;
- criação de novos usuários desabilitada;
- configuração de CAPTCHA/Attack Protection incompatível com o dispositivo.

## Etapa 3/3 — RPC
Executa `tv_bootstrap()` com o JWT da TV.

Falhas comuns:
- SQL ainda não executado;
- função ausente;
- `GRANT EXECUTE` ausente para `authenticated`;
- erro interno na função.

## Alterações de robustez desta versão
- `Sem conexão` mostra o erro técnico real;
- botão `Diagnóstico` sempre visível;
- teste independente de Gateway, Auth e RPC;
- payload de login anônimo alinhado ao cliente oficial Supabase;
- parser de sessão aceita resposta raiz ou `{ session: {...} }`;
- erros HTTP agora incluem endpoint e status;
- botão `Verificar atualização` permanece independente do Supabase.
