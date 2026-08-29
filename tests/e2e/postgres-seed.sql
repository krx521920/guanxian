INSERT INTO association (id, name, status)
VALUES ('00000000-0000-0000-0000-000000000106', '北京地下管线协会', 'ACTIVE')
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, status = 'ACTIVE';

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

INSERT INTO user_account (
  id, association_id, enterprise_id, external_subject, username, display_name, email, status)
VALUES
  ('20000000-0000-4000-8000-000000000001', '00000000-0000-0000-0000-000000000106', NULL,
   '10000000-0000-4000-8000-000000000001', 'ci-association-admin', 'CI 协会管理员',
   'ci-association-admin@invalid.example', 'ACTIVE'),
  ('20000000-0000-4000-8000-000000000003', '00000000-0000-0000-0000-000000000106',
   '00000000-0000-0000-0000-000000000201', '10000000-0000-4000-8000-000000000003',
   'ci-enterprise-admin', 'CI 企业管理员', 'ci-enterprise-admin@invalid.example', 'ACTIVE'),
  ('20000000-0000-4000-8000-000000000004', '00000000-0000-0000-0000-000000000106',
   '00000000-0000-0000-0000-000000000201', '10000000-0000-4000-8000-000000000004',
   'ci-enterprise-member', 'CI 市场经理', 'ci-enterprise-member@invalid.example', 'ACTIVE')
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
  ('20000000-0000-4000-8000-000000000001', 'ASSOCIATION_ADMIN'),
  ('20000000-0000-4000-8000-000000000003', 'ENTERPRISE_ADMIN'),
  ('20000000-0000-4000-8000-000000000004', 'ENTERPRISE_MEMBER')
ON CONFLICT DO NOTHING;
