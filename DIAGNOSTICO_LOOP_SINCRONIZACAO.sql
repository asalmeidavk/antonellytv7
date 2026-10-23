-- ============================================================
-- ANTONELLY TV — DIAGNÓSTICO DE LOOP DE SINCRONIZAÇÃO
-- ============================================================
-- Este script não altera dados.

-- 1) Estado das telas: compara a versão que o servidor exige com
-- a última versão confirmada pelo APK.
select
  s.id,
  s.name as tela,
  s.status,
  s.manifest_version as versao_servidor,
  s.current_version as versao_confirmada_apk,
  s.player_state,
  s.last_error,
  s.sync,
  s.assigned_playlist_id,
  p.name as playlist,
  s.last_seen,
  s.updated_at
from public.screens s
left join public.playlists p on p.id = s.assigned_playlist_id
order by s.updated_at desc;

-- 2) Itens das playlists atribuídas e existência física no Storage.
select
  s.name as tela,
  p.name as playlist,
  pi.position,
  m.id as media_id,
  m.title,
  m.type,
  m.storage_path,
  m.checksum_sha256,
  m.active,
  exists (
    select 1
    from storage.objects o
    where o.bucket_id = 'tv-media'
      and o.name = m.storage_path
  ) as objeto_storage_existe
from public.screens s
join public.playlists p on p.id = s.assigned_playlist_id
join public.playlist_items pi on pi.playlist_id = p.id
join public.media m on m.id = pi.media_id
where s.status = 'PAIRED'
order by s.name, pi.position;

-- 3) Checksums cadastrados. Um checksum antigo/incorreto faz o APK
-- baixar o arquivo, rejeitar a integridade e tentar novamente depois.
select
  id,
  title,
  storage_path,
  checksum_sha256
from public.media
where active = true
  and checksum_sha256 is not null
  and btrim(checksum_sha256) <> ''
order by created_at desc;
