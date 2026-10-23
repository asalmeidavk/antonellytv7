-- ANTONELLY TV — diagnóstico do backend
-- Execute no SQL Editor do MESMO projeto configurado no APK.

select
  to_regprocedure('public.tv_bootstrap(jsonb)') is not null as tv_bootstrap_existe,
  to_regprocedure('public.tv_signal()') is not null as tv_signal_existe,
  to_regprocedure('public.tv_manifest()') is not null as tv_manifest_existe,
  to_regprocedure('public.tv_heartbeat(text,integer,jsonb,text,jsonb)') is not null as tv_heartbeat_existe;

select
  has_function_privilege('authenticated', 'public.tv_bootstrap(jsonb)', 'EXECUTE') as authenticated_tv_bootstrap,
  has_function_privilege('authenticated', 'public.tv_signal()', 'EXECUTE') as authenticated_tv_signal,
  has_function_privilege('authenticated', 'public.tv_manifest()', 'EXECUTE') as authenticated_tv_manifest,
  has_function_privilege('authenticated', 'public.tv_heartbeat(text,integer,jsonb,text,jsonb)', 'EXECUTE') as authenticated_tv_heartbeat;

select id, name, public, file_size_limit, allowed_mime_types
from storage.buckets
where id = 'tv-media';

select count(*) as telas_criadas from public.screens;
select id, pairing_code, status, last_seen, created_at
from public.screens
order by created_at desc
limit 10;
