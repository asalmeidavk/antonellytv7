# Antonelly TV Android v1.3.2 — diagnóstico de conexão

Esta revisão parte da v1.3.1 e mantém o player, cache offline e auto-update. A principal mudança é de diagnóstico: o app agora diferencia falha de Gateway, Auth e RPC e mostra o erro técnico em tela.

Consulte `DIAGNOSTICO_CONEXAO.md` e `DIAGNOSTICO_SUPABASE.sql`.

# Antonelly TV Android / Fire TV v1.3.1

Versão com atualização própria via Wi-Fi e botão manual de atualização sempre visível.

## O que mudou

- consulta automática de `/updates/manifest.json` a cada 60 minutos;
- só baixa atualização por Wi-Fi por padrão;
- download do APK em segundo plano;
- validação obrigatória SHA-256;
- validação do package name e da assinatura do APK;
- instalação via `PackageInstaller` do Android;
- se o Android exigir, abre automaticamente a tela de permissão "Instalar apps desconhecidos" e/ou a confirmação final;
- URL do servidor de updates configurável na tela de manutenção;
- build de produção pode receber `ATV_UPDATE_MANIFEST_URL` por variável/secreto.

## Botão de atualização sempre disponível

O player mostra `Verificar atualização` no canto inferior direito em qualquer estado, inclusive quando o backend do player estiver exibindo **Sem conexão**. A verificação manual usa diretamente o `manifest.json` do Cloudflare e não depende da sessão do Supabase.

Se a URL do manifesto ainda não estiver configurada, o botão abre a tela de manutenção para permitir o preenchimento.

## Muito importante sobre atualização silenciosa

Em Android comum, um app de terceiros não pode garantir instalação 100% silenciosa. O app baixa o APK e solicita a instalação automaticamente, mas o sistema pode exigir confirmação do usuário. Instalação totalmente silenciosa exige um cenário gerenciado, como Device Owner/MDM, app privilegiado/system ou outra política corporativa compatível.

## Primeira migração

A v1.2.1 não possui o atualizador, então a v1.3.1 precisa ser instalada manualmente uma vez. A partir da v1.3.1, o app passa a consultar o Cloudflare sozinho.

Todas as versões atualizáveis precisam ser assinadas com o MESMO certificado. Não misture APK debug com APK release e não troque o keystore de produção.

## Manifesto Cloudflare

Exemplo:

```json
{
  "enabled": true,
  "versionCode": 10301,
  "versionName": "1.3.1",
  "apk": "Antonelly-TV-v1.3.1-release.apk",
  "sha256": "SHA256_DO_ARQUIVO",
  "wifiOnly": true,
  "mandatory": true,
  "notes": "Correções do player"
}
```

A URL configurada no app deve ser, por exemplo:

`https://SEU-PROJETO.pages.dev/updates/manifest.json`

O campo `apk` pode ser relativo ao próprio `manifest.json`.

## Publicação

1. Gere o APK release com o mesmo keystore.
2. Copie o APK para `/updates/` no projeto Cloudflare Pages.
3. Atualize `manifest.json` com versionCode maior e SHA-256 correto.
4. Faça o deploy do Pages.
5. A TV consulta o manifesto e baixa o APK quando estiver no Wi-Fi.

O workflow `build-release-apk.yml` também gera um artifact `cloudflare-update` com APK + manifesto já preenchido com SHA-256.
