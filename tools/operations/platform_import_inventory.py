"""Fixed, read-only production inventory for the approved platform-data import.

Run from the server's admin session. No passwords, phone numbers, email addresses,
profile bodies or credential tables are returned. No permissions are modified.
"""
import os
import subprocess
import sys

SQL = r"""
BEGIN READ ONLY;
SET LOCAL statement_timeout = '30s';
SET LOCAL lock_timeout = '5s';
SELECT jsonb_build_object('section','database','database',current_database(),
  'inspectedAt', current_timestamp, 'schemaVersions',
  (SELECT jsonb_agg(version ORDER BY installed_rank) FROM public.flyway_schema_history WHERE success));
SELECT jsonb_build_object('section','associations','rows',coalesce(jsonb_agg(jsonb_build_object(
  'id',id,'name',name,'status',status) ORDER BY name,id),'[]'::jsonb)) FROM public.association;
SELECT jsonb_build_object('section','enterprises','rows',coalesce(jsonb_agg(jsonb_build_object(
  'id', e.id, 'associationId',e.association_id,'name',e.name,'status',e.status,'deleted',e.deleted_at IS NOT NULL,
  'version',e.version,'category',e.category,
  'hasDescription',coalesce(btrim(e.description),'')<>'',
  'hasAddress',coalesce(btrim(e.address),'')<>'',
  'hasPhone',coalesce(btrim(e.contact_phone),'')<>'',
  'hasCreditCode',coalesce(btrim(e.unified_social_credit_code),'')<>'',
  'boundAccounts',(SELECT count(*) FROM public.user_account u WHERE u.enterprise_id=e.id),
  'hasProfileWorkflow',EXISTS(SELECT 1 FROM public.enterprise_profile_workflow p WHERE p.enterprise_id=e.id),
  'createdAt',e.created_at) ORDER BY e.name,e.id),'[]'::jsonb)) FROM public.enterprise e;
SELECT jsonb_build_object('section','memberImports','rows',coalesce(jsonb_agg(jsonb_build_object(
  'id',id,'associationId',association_id,'filename',original_filename,'status',status,
  'rows',total_rows,'sourceSha256',source_sha256) ORDER BY created_at),'[]'::jsonb)) FROM public.member_import_batch;
SELECT jsonb_build_object('section','policies','rows',coalesce(jsonb_agg(jsonb_build_object(
  'id',id,'associationId',association_id,'title',title,'documentNumber',document_number,
  'sourceUrl',source_url,'creator',created_by_subject,'status',status,'version',version) ORDER BY id),'[]'::jsonb))
FROM public.policy_document;
SELECT jsonb_build_object('section','demoCandidates','rows',coalesce(jsonb_agg(x ORDER BY x->>'table',x->>'id'),'[]'::jsonb)) FROM (
  SELECT jsonb_build_object('table','product_service','id',id,'title',name,'enterpriseId',enterprise_id,
    'creator',created_by_subject,'updater',updated_by_subject,'version',version) x FROM public.product_service
  WHERE created_by_subject='showcase-seed:2026-09-04' OR name ~ '(【演示】|验收测试|虚构|模拟|示例)'
  UNION ALL SELECT jsonb_build_object('table','cooperation_demand','id',id,'title',title,'enterpriseId',enterprise_id,
    'creator',created_by_subject,'updater',updated_by_subject,'version',version) FROM public.cooperation_demand
  WHERE created_by_subject='showcase-seed:2026-09-04' OR title ~ '(【演示】|验收测试|虚构|模拟|示例)'
  UNION ALL SELECT jsonb_build_object('table','knowledge_document','id',id,'title',title,
    'creator',created_by_subject,'version',lifecycle_version) FROM public.knowledge_document
  WHERE created_by_subject='showcase-seed:2026-09-04' OR title ~ '(【演示】|验收测试|虚构|模拟|示例)'
) candidates;
SELECT jsonb_build_object('section','relationships','matches',
 (SELECT coalesce(jsonb_agg(jsonb_build_object('id',id,'demandId',demand_id,'candidateId',candidate_enterprise_id,
   'state',state,'version',version) ORDER BY id),'[]'::jsonb) FROM public.ecosystem_match),
 'collaborations',(SELECT coalesce(jsonb_agg(jsonb_build_object('id',id,'matchId',match_id,'enterpriseId',enterprise_id,
   'title',title,'status',status,'version',version) ORDER BY id),'[]'::jsonb) FROM public.collaboration_task),
 'policyImpacts',(SELECT coalesce(jsonb_agg(jsonb_build_object('id',id,'policyId',policy_document_id,'enterpriseId',enterprise_id,
   'status',status,'version',version) ORDER BY id),'[]'::jsonb) FROM public.policy_impact_analysis));
SELECT jsonb_build_object('section','foreignKeys','rows',coalesce(jsonb_agg(jsonb_build_object(
 'table',conrelid::regclass::text,'target',confrelid::regclass::text,'constraint',conname,
 'definition',pg_get_constraintdef(oid)) ORDER BY conrelid::regclass::text,conname),'[]'::jsonb))
FROM pg_constraint WHERE contype='f' AND confrelid IN (
 'public.enterprise'::regclass,'public.product_service'::regclass,'public.cooperation_demand'::regclass,
 'public.ecosystem_match'::regclass,'public.collaboration_task'::regclass,'public.policy_document'::regclass,
 'public.knowledge_document'::regclass);
ROLLBACK;
"""


def main() -> None:
    if len(sys.argv) != 1:
        raise SystemExit("No arguments accepted; this inventory has fixed read-only targets.")
    if not hasattr(os, "geteuid") or os.geteuid() != 0:
        raise SystemExit("Run this reviewed inventory with sudo in the server's admin session.")
    command = ["/usr/bin/docker", "compose", "--env-file", "/opt/guanxian-single/deploy.env",
               "-f", "/home/admin/guanxian/compose.single-host.yml", "exec", "-T",
               "-e", "PGOPTIONS=-c default_transaction_read_only=on", "postgres", "psql",
               "--username", "postgres", "--dbname", "guanxian", "--no-psqlrc",
               "--set", "ON_ERROR_STOP=1", "--no-align", "--tuples-only", "--quiet"]
    subprocess.run(command, input=SQL, encoding="utf-8", check=True, timeout=90)


if __name__ == "__main__":
    main()
