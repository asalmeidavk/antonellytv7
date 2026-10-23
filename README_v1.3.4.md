# Antonelly TV Android v1.3.4

Correção de integridade: valores JSON `null` no campo `sha256` não são mais interpretados como a string literal `"null"`.

Isso evita `Falha de integridade em <mídia>` quando a mídia não possui checksum cadastrado no banco.

Versão: `1.3.4` / `versionCode 10304`.
