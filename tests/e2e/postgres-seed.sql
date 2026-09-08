INSERT INTO association (id, name, status)
VALUES ('00000000-0000-0000-0000-000000000106', '北京地下管线协会', 'ACTIVE')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, status = 'ACTIVE';

INSERT INTO association (id, name, status)
VALUES ('00000000-0000-0000-0000-000000000107', 'E2E友好管网协会', 'ACTIVE')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, status = 'ACTIVE';

INSERT INTO association_relationship (
  source_association_id, target_association_id, status, allow_member_data,
  expires_at, suspended_at, suspended_by_association_id, suspended_by_subject,
  revoked_at, revoked_by_subject, revoke_reason, version)
VALUES (
  '00000000-0000-0000-0000-000000000106',
  '00000000-0000-0000-0000-000000000107',
  'ACTIVE', TRUE, now() + interval '30 days', NULL, NULL, NULL, NULL, NULL, NULL, 0)
ON CONFLICT (source_association_id, target_association_id) DO UPDATE SET
  status = 'ACTIVE', allow_member_data = TRUE, expires_at = now() + interval '30 days',
  suspended_at = NULL, suspended_by_association_id = NULL, suspended_by_subject = NULL,
  revoked_at = NULL, revoked_by_subject = NULL, revoke_reason = NULL;

INSERT INTO association_share_policy (
  id, source_association_id, target_association_id, resource_type, visible_fields,
  status, valid_from, expires_at, created_by_subject, version)
VALUES (
  '40000000-0000-4000-8000-000000000001',
  '00000000-0000-0000-0000-000000000106',
  '00000000-0000-0000-0000-000000000107',
  'PRODUCT', '["enterpriseName","name","description","scenarios","qualifications"]'::jsonb,
  'ACTIVE', now() - interval '1 day', now() + interval '29 days',
  '10000000-0000-4000-8000-000000000001', 0)
ON CONFLICT (source_association_id, target_association_id, resource_type) DO UPDATE SET
  visible_fields = EXCLUDED.visible_fields, status = 'ACTIVE',
  valid_from = now() - interval '1 day', expires_at = now() + interval '29 days';

INSERT INTO enterprise (
  id, association_id, unified_social_credit_code, name, short_name, description,
  enterprise_roles, service_scenarios, visibility, status, version, category,
  address, contact_name, contact_phone, capabilities, products, cooperation_needs)
VALUES (
  '00000000-0000-0000-0000-000000000201',
  '00000000-0000-0000-0000-000000000106',
  '91110000E2E0000001', 'E2E京城管网科技有限公司', 'E2E京城管网',
  '真实依赖浏览器端到端验收企业', '["需求方","服务商"]'::jsonb,
  '["燃气","供热","智慧管网"]'::jsonb, 'MEMBERS', 'ACTIVE', 0, '智慧管网',
  '北京市海淀区', 'E2E管理员', '13800000001',
  '["管线监测","泄漏预警"]'::jsonb, '["监测平台"]'::jsonb,
  '["寻找燃气场景合作方"]'::jsonb)
ON CONFLICT (id) DO UPDATE SET status = 'ACTIVE', deleted_at = NULL,
  deleted_by_subject = NULL, status_before_delete = NULL;

INSERT INTO enterprise (
  id, association_id, unified_social_credit_code, name, short_name, description,
  enterprise_roles, service_scenarios, visibility, status, version, category,
  address, contact_name, contact_phone, capabilities, products, cooperation_needs)
VALUES (
  '00000000-0000-0000-0000-000000000202',
  '00000000-0000-0000-0000-000000000106',
  '91110000E2E0000002', 'E2E北辰管线服务有限公司', 'E2E北辰管线',
  '真实依赖浏览器端到端验收供给企业', '["供应商","服务商"]'::jsonb,
  '["燃气","供热","智慧管网"]'::jsonb, 'MEMBERS', 'ACTIVE', 0, '管线服务',
  '北京市朝阳区', 'E2E供给方管理员', '13800000002',
  '["管线监测","泄漏预警","现场联调"]'::jsonb, '["监测终端","联调服务"]'::jsonb,
  '["寻找智慧管网合作项目"]'::jsonb)
ON CONFLICT (id) DO UPDATE SET status = 'ACTIVE', deleted_at = NULL,
  deleted_by_subject = NULL, status_before_delete = NULL;

INSERT INTO user_account (
  id, association_id, enterprise_id, external_subject, username, display_name, email, status)
VALUES
  ('20000000-0000-4000-8000-000000000002', NULL, NULL,
   '10000000-0000-4000-8000-000000000002', 'ci-load-admin', 'CI 系统管理员',
   'ci-load-admin@invalid.example', 'ACTIVE'),
  ('20000000-0000-4000-8000-000000000001', '00000000-0000-0000-0000-000000000106', NULL,
   '10000000-0000-4000-8000-000000000001', 'ci-association-admin', 'CI 协会管理员',
   'ci-association-admin@invalid.example', 'ACTIVE'),
  ('20000000-0000-4000-8000-000000000003', '00000000-0000-0000-0000-000000000106',
   '00000000-0000-0000-0000-000000000201', '10000000-0000-4000-8000-000000000003',
   'ci-enterprise-admin', 'CI 企业管理员', 'ci-enterprise-admin@invalid.example', 'ACTIVE'),
  ('20000000-0000-4000-8000-000000000004', '00000000-0000-0000-0000-000000000106',
   '00000000-0000-0000-0000-000000000201', '10000000-0000-4000-8000-000000000004',
   'ci-enterprise-member', 'CI 市场经理', 'ci-enterprise-member@invalid.example', 'ACTIVE'),
  ('20000000-0000-4000-8000-000000000005', '00000000-0000-0000-0000-000000000106',
   '00000000-0000-0000-0000-000000000202', '10000000-0000-4000-8000-000000000005',
   'ci-supplier-admin', 'CI 供给方管理员', 'ci-supplier-admin@invalid.example', 'ACTIVE')
ON CONFLICT (id) DO UPDATE SET
  association_id = EXCLUDED.association_id,
  enterprise_id = EXCLUDED.enterprise_id,
  external_subject = EXCLUDED.external_subject,
  username = EXCLUDED.username,
  display_name = EXCLUDED.display_name,
  email = EXCLUDED.email,
  status = 'ACTIVE';

INSERT INTO user_role (user_id, role_code)
VALUES
  ('20000000-0000-4000-8000-000000000002', 'SYSTEM_ADMIN'),
  ('20000000-0000-4000-8000-000000000001', 'ASSOCIATION_ADMIN'),
  ('20000000-0000-4000-8000-000000000003', 'ENTERPRISE_ADMIN'),
  ('20000000-0000-4000-8000-000000000004', 'ENTERPRISE_MEMBER'),
  ('20000000-0000-4000-8000-000000000005', 'ENTERPRISE_ADMIN')
ON CONFLICT DO NOTHING;

INSERT INTO notification_message (
  id, user_id, association_id, notification_type, title, body,
  resource_type, resource_id, status, idempotency_key, delivered_at, read_at)
VALUES (
  '30000000-0000-4000-8000-000000000001',
  '20000000-0000-4000-8000-000000000001',
  '00000000-0000-0000-0000-000000000106',
  'MEMBER_REVIEW', 'E2E 会员资料待核验', '请核验 E2E 会员企业资料。',
  'MEMBER', '00000000-0000-0000-0000-000000000201',
  'DELIVERED', 'e2e:member-review:0001', now(), NULL)
ON CONFLICT (id) DO UPDATE SET
  title = EXCLUDED.title,
  body = EXCLUDED.body,
  status = 'DELIVERED',
  delivered_at = now(),
  read_at = NULL;

-- Synthetic, local/CI-only source receipts. This file is mounted exclusively by compose.e2e.yaml.
-- Never import these examples, fixture accounts or fixture credentials into production.
INSERT INTO platform_dataset_import(id, association_id, source_sha256, source_filename, actor_subject, report)
SELECT 'e2e-source-evidence-' || suffix, association_id, repeat('e', 64), 'synthetic-e2e-only', 'ci-fixture', '{}'::jsonb
FROM (VALUES ('own', '00000000-0000-0000-0000-000000000106'::uuid),
             ('foreign', '00000000-0000-0000-0000-000000000107'::uuid)) AS f(suffix, association_id)
ON CONFLICT (id) DO NOTHING;

INSERT INTO platform_source_record(id, import_id, association_id, kind, source_id, title, payload)
SELECT id::uuid, 'e2e-source-evidence-' || scope, association_id::uuid, kind, source_id, title,
       jsonb_build_object('source', jsonb_build_object('联系人', 'DO_NOT_EXPOSE_E2E_PRIVATE'),
         'publicEvidence', jsonb_build_object('record', jsonb_build_object('id', 'EV-' || source_id, 'title', title,
           'checkedOn', current_date::text, 'sourceUrl', 'https://example.test/e2e/' || source_id),
         'fields', jsonb_build_object('发布日期', current_date::text, '记录状态', state,
           '证据摘要', '本地验收虚构公开来源资料，不是真实公告', '关联企业及角色', role,
           '金额及口径', '项目总额100万元；企业份额未披露'), 'supportingUrls', '[]'::jsonb))
FROM (VALUES
  ('60000000-0000-4000-8000-000000000001', 'own', '00000000-0000-0000-0000-000000000106', 'TENDER', 'E2E-SRC-TENDER', 'E2E证据联调·虚构候选公示', '候选公示', 'E2E京城管网：候选单位，不等于中标'),
  ('60000000-0000-4000-8000-000000000002', 'own', '00000000-0000-0000-0000-000000000106', 'ACTIVITY', 'E2E-SRC-ACTIVITY', 'E2E证据联调·虚构企业活动', '企业自述', 'E2E京城管网：报道主体，不是平台确认合作'),
  ('60000000-0000-4000-8000-000000000003', 'foreign', '00000000-0000-0000-0000-000000000107', 'TENDER', 'E2E-SRC-FOREIGN', 'E2E证据联调·不可跨协会展示', '历史公告', '外协会企业'))
AS f(id, scope, association_id, kind, source_id, title, state, role)
ON CONFLICT (id) DO UPDATE SET payload = EXCLUDED.payload;
