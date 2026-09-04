\set ON_ERROR_STOP on

BEGIN;

SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

CREATE TEMP TABLE gx_requested_database (name text NOT NULL) ON COMMIT DROP;
INSERT INTO gx_requested_database VALUES (:'expected_database');

DO $guard$
DECLARE
  association_count integer;
  enterprise_count integer;
BEGIN
  IF current_database() <> (SELECT name FROM gx_requested_database) THEN
    RAISE EXCEPTION 'showcase seed database mismatch (current=%, expected=%)',
      current_database(), (SELECT name FROM gx_requested_database);
  END IF;

  IF NOT EXISTS (
    SELECT 1
      FROM flyway_schema_history
     WHERE version = '23' AND success
  ) THEN
    RAISE EXCEPTION 'showcase seed requires the complete V23 production schema';
  END IF;

  SELECT count(*)
    INTO association_count
    FROM association
   WHERE name = '北京地下管线协会'
     AND status = 'ACTIVE';
  IF association_count <> 1 THEN
    RAISE EXCEPTION 'expected exactly one active 北京地下管线协会, found %', association_count;
  END IF;

  SELECT count(*)
    INTO enterprise_count
    FROM enterprise e
    JOIN association a ON a.id = e.association_id
   WHERE a.name = '北京地下管线协会'
     AND e.name IN (
       '验收测试企业01（虚构）',
       '验收测试企业02（虚构）',
       '验收测试企业03（虚构）',
       '验收测试企业04（虚构）',
       '验收测试企业05（虚构）'
     )
     AND e.status = 'ACTIVE'
     AND e.deleted_at IS NULL;
  IF enterprise_count <> 5 THEN
    RAISE EXCEPTION 'expected the five active acceptance-test enterprises, found %', enterprise_count;
  END IF;

  IF EXISTS (
    SELECT 1 FROM product_service
     WHERE id BETWEEN 'd3000000-0000-0000-0000-000000000101'::uuid
                  AND 'd3000000-0000-0000-0000-000000000105'::uuid
       AND created_by_subject IS DISTINCT FROM 'showcase-seed:2026-09-04'
  ) OR EXISTS (
    SELECT 1 FROM cooperation_demand
     WHERE id BETWEEN 'd3000000-0000-0000-0000-000000000201'::uuid
                  AND 'd3000000-0000-0000-0000-000000000204'::uuid
       AND created_by_subject IS DISTINCT FROM 'showcase-seed:2026-09-04'
  ) OR EXISTS (
    SELECT 1 FROM policy_document
     WHERE id BETWEEN 'd3000000-0000-0000-0000-000000000301'::uuid
                  AND 'd3000000-0000-0000-0000-000000000303'::uuid
       AND created_by_subject IS DISTINCT FROM 'showcase-seed:2026-09-04'
  ) OR EXISTS (
    SELECT 1 FROM knowledge_document
     WHERE id BETWEEN 'd3000000-0000-0000-0000-000000000601'::uuid
                  AND 'd3000000-0000-0000-0000-000000000602'::uuid
       AND created_by_subject IS DISTINCT FROM 'showcase-seed:2026-09-04'
  ) THEN
    RAISE EXCEPTION 'one or more reserved showcase UUIDs are already owned by non-showcase data';
  END IF;
END
$guard$;

CREATE TEMP TABLE gx_showcase_context (
  association_id uuid PRIMARY KEY
) ON COMMIT DROP;

INSERT INTO gx_showcase_context
SELECT a.id
  FROM association a
 WHERE a.name = '北京地下管线协会'
   AND a.status = 'ACTIVE'
;

WITH offerings(id, enterprise_name, name, kind, description, scenarios, qualifications) AS (
  VALUES
    ('d3000000-0000-0000-0000-000000000101'::uuid, '验收测试企业01（虚构）',
     '【演示】地下管线数据治理与台账校核服务', 'SERVICE',
     '面向城市地下管线普查成果，提供坐标纠偏、属性清洗、拓扑检查和设施台账一致性校核。内容仅用于系统功能演示。',
     '["地下空间数据治理","设施台账校核","数据质量检查"]'::jsonb,
     '["测绘能力（演示）","ISO 9001（演示）"]'::jsonb),
    ('d3000000-0000-0000-0000-000000000102'::uuid, '验收测试企业02（虚构）',
     '【演示】老旧管网风险评估与改造咨询', 'SERVICE',
     '提供风险分级、更新优先级测算、投资估算和实施路线建议。内容仅用于系统功能演示。',
     '["老旧管网改造","风险识别","项目咨询"]'::jsonb,
     '["工程咨询能力（演示）","项目评估经验（演示）"]'::jsonb),
    ('d3000000-0000-0000-0000-000000000103'::uuid, '验收测试企业03（虚构）',
     '【演示】管网压力与泄漏监测终端', 'PRODUCT',
     '集成压力、流量与声学监测，可用于燃气和供水管网的异常识别及远程告警。产品参数均为演示数据。',
     '["状态监测","泄漏预警","设备联调"]'::jsonb,
     '["防护等级 IP68（演示）","设备检测报告（演示）"]'::jsonb),
    ('d3000000-0000-0000-0000-000000000104'::uuid, '验收测试企业04（虚构）',
     '【演示】非开挖修复施工及质量检测', 'SERVICE',
     '提供内衬修复、局部修复、施工组织和过程质量检查的一体化服务。内容仅用于系统功能演示。',
     '["非开挖修复","现场组织","质量检查"]'::jsonb,
     '["市政施工能力（演示）","安全生产能力（演示）"]'::jsonb),
    ('d3000000-0000-0000-0000-000000000105'::uuid, '验收测试企业05（虚构）',
     '【演示】地下管线数字孪生协同平台', 'PRODUCT',
     '汇聚台账、物联监测和工单信息，支持二维三维联动、风险看板与跨单位协同。产品参数均为演示数据。',
     '["系统集成","接口测试","数字孪生"]'::jsonb,
     '["信息安全能力（演示）","软件著作权（演示）"]'::jsonb)
)
INSERT INTO product_service (
  id, enterprise_id, name, kind, description, scenarios, qualifications,
  visibility, status, version, created_by_subject, updated_by_subject,
  approved_by_subject, approved_at)
SELECT o.id, e.id, o.name, o.kind, o.description, o.scenarios, o.qualifications,
       'MEMBERS', 'ACTIVE', 0, 'showcase-seed:2026-09-04',
       'showcase-seed:2026-09-04', 'showcase-seed:2026-09-04', now()
  FROM offerings o
  JOIN enterprise e ON e.name = o.enterprise_name
  JOIN gx_showcase_context c ON c.association_id = e.association_id
ON CONFLICT (id) DO NOTHING;

WITH demands(id, enterprise_name, title, description, scenarios, capabilities, budget_min, budget_max) AS (
  VALUES
    ('d3000000-0000-0000-0000-000000000201'::uuid, '验收测试企业04（虚构）',
     '【演示】老旧街区地下管线综合探测需求',
     '拟对示范街区开展多专业地下管线探测、成果校核与入库，需形成可供后续改造使用的统一数据成果。',
     '["老旧街区改造","地下空间数据治理"]'::jsonb,
     '["地下空间数据治理","风险识别","成果入库"]'::jsonb, 180000::numeric, 260000::numeric),
    ('d3000000-0000-0000-0000-000000000202'::uuid, '验收测试企业01（虚构）',
     '【演示】燃气管道在线监测设备采购需求',
     '计划建设一套演示性在线监测系统，覆盖压力、流量和泄漏告警，并与既有平台完成接口联调。',
     '["燃气安全","状态监测"]'::jsonb,
     '["状态监测","设备联调","接口测试"]'::jsonb, 320000::numeric, 480000::numeric),
    ('d3000000-0000-0000-0000-000000000203'::uuid, '验收测试企业02（虚构）',
     '【演示】管线数字孪生平台集成需求',
     '将设施台账、巡检工单和物联数据接入统一数字孪生底座，形成协会演示驾驶舱。',
     '["数字孪生","系统集成"]'::jsonb,
     '["系统集成","接口测试","设施台账校核"]'::jsonb, 650000::numeric, 900000::numeric),
    ('d3000000-0000-0000-0000-000000000204'::uuid, '验收测试企业03（虚构）',
     '【演示】非开挖修复试验段实施需求',
     '选择一段老旧管道开展非开挖修复试验，要求提交施工方案、过程记录和质量检测报告。',
     '["非开挖修复","质量检查"]'::jsonb,
     '["现场组织","非开挖施工","质量检测"]'::jsonb, 420000::numeric, 600000::numeric)
)
INSERT INTO cooperation_demand (
  id, enterprise_id, title, description, scenarios, required_capabilities,
  visibility, budget_min, budget_max, response_deadline, status, version,
  created_by_subject, updated_by_subject, approved_by_subject, approved_at)
SELECT d.id, e.id, d.title, d.description, d.scenarios, d.capabilities,
       'MEMBERS', d.budget_min, d.budget_max, now() + interval '365 days',
       'OPEN', 0, 'showcase-seed:2026-09-04', 'showcase-seed:2026-09-04',
       'showcase-seed:2026-09-04', now()
  FROM demands d
  JOIN enterprise e ON e.name = d.enterprise_name
  JOIN gx_showcase_context c ON c.association_id = e.association_id
ON CONFLICT (id) DO NOTHING;

WITH policies(id, title, authority, document_number, level, category, summary, tags, scenarios) AS (
  VALUES
    ('d3000000-0000-0000-0000-000000000301'::uuid,
     '【演示】城市地下管线更新改造工作指引（示例）', '示例主管部门', '演示〔2026〕01号',
     '市级', '更新改造', '用于展示政策发布、检索及企业影响分析流程，不代表任何真实政策文件。',
     '["更新改造","安全运行","项目管理"]'::jsonb,
     '["老旧管网改造","风险识别"]'::jsonb),
    ('d3000000-0000-0000-0000-000000000302'::uuid,
     '【演示】地下管线数据共享管理办法（示例）', '示例主管部门', '演示〔2026〕02号',
     '行业', '数据治理', '用于展示资料归集、数据共享和权限审核流程，不代表任何真实政策文件。',
     '["数据治理","共享交换","信息安全"]'::jsonb,
     '["地下空间数据治理","设施台账校核"]'::jsonb),
    ('d3000000-0000-0000-0000-000000000303'::uuid,
     '【演示】管线工程施工质量评价规范（示例）', '示例标准机构', 'GX-DEMO-2026-03',
     '团体标准', '质量管理', '用于展示标准检索和企业适用性分析，不代表任何正式标准。',
     '["施工质量","验收评价","非开挖修复"]'::jsonb,
     '["现场组织","质量检查"]'::jsonb)
)
INSERT INTO policy_document (
  id, association_id, title, issuing_authority, document_number, policy_level,
  category, published_on, effective_on, summary, tags, affected_scenarios,
  visibility, status, version, created_by_subject, updated_by_subject,
  approved_by_subject, approved_at)
SELECT p.id, c.association_id, p.title, p.authority, p.document_number, p.level,
       p.category, current_date - 30, current_date - 15, p.summary, p.tags,
       p.scenarios, 'MEMBERS', 'PUBLISHED', 0, 'showcase-seed:2026-09-04',
       'showcase-seed:2026-09-04', 'showcase-seed:2026-09-04', now()
  FROM policies p
 CROSS JOIN gx_showcase_context c
ON CONFLICT (id) DO NOTHING;

WITH documents(id, title, document_type, content) AS (
  VALUES
    ('d3000000-0000-0000-0000-000000000601'::uuid,
     '【演示】地下管线风险分级与处置知识手册', 'GUIDE',
     '风险分级应综合考虑管线重要性、运行年限、环境敏感度和历史事件。高风险点位应优先安排现场复核，并形成闭环处置记录。'),
    ('d3000000-0000-0000-0000-000000000602'::uuid,
     '【演示】多单位协同实施要点', 'MANUAL',
     '协同项目应明确牵头单位、参与单位、里程碑、交付物和验收口径。关键变更应保留过程记录，便于复盘和审计。')
)
INSERT INTO knowledge_document (
  id, association_id, title, document_type, source_type, visibility, status,
  current_version, content_hash, created_by_subject, lifecycle_version,
  reviewed_by_subject, reviewed_at, review_comment)
SELECT d.id, c.association_id, d.title, d.document_type, 'MANUAL_TEXT',
       'ASSOCIATION', 'PUBLISHED', 1,
       encode(digest(d.content, 'sha256'), 'hex'), 'showcase-seed:2026-09-04',
       2, 'showcase-seed:2026-09-04', now(), '演示资料审核通过'
  FROM documents d
 CROSS JOIN gx_showcase_context c
ON CONFLICT (id) DO NOTHING;

WITH versions(id, document_id) AS (
  VALUES
    ('d3000000-0000-0000-0000-000000000611'::uuid, 'd3000000-0000-0000-0000-000000000601'::uuid),
    ('d3000000-0000-0000-0000-000000000612'::uuid, 'd3000000-0000-0000-0000-000000000602'::uuid)
)
INSERT INTO knowledge_document_version (
  id, document_id, version, parser_name, parser_version, status, created_by_subject)
SELECT v.id, v.document_id, 1, 'showcase-seed', '1', 'READY', 'showcase-seed:2026-09-04'
  FROM versions v
 WHERE EXISTS (SELECT 1 FROM knowledge_document d WHERE d.id = v.document_id)
ON CONFLICT (id) DO NOTHING;

WITH chunks(id, version_id, chunk_index, content) AS (
  VALUES
    ('d3000000-0000-0000-0000-000000000621'::uuid, 'd3000000-0000-0000-0000-000000000611'::uuid, 0,
     '风险识别：结合运行年限、介质危险性、周边人口密度、第三方施工频率和历史隐患，形成分级清单。'),
    ('d3000000-0000-0000-0000-000000000622'::uuid, 'd3000000-0000-0000-0000-000000000611'::uuid, 1,
     '闭环处置：明确责任人、整改期限、复核证据和关闭条件，高风险问题应保留现场照片与检测记录。'),
    ('d3000000-0000-0000-0000-000000000623'::uuid, 'd3000000-0000-0000-0000-000000000612'::uuid, 0,
     '协同启动：确定牵头单位、参与单位、技术联系人、里程碑和统一的成果交付目录。'),
    ('d3000000-0000-0000-0000-000000000624'::uuid, 'd3000000-0000-0000-0000-000000000612'::uuid, 1,
     '过程管理：会议结论、需求变更、接口联调和验收问题均应形成可追踪记录，并指定下一步行动。')
)
INSERT INTO knowledge_chunk (
  id, document_version_id, chunk_index, content, content_hash, token_count,
  metadata, embedding_status)
SELECT ch.id, ch.version_id, ch.chunk_index, ch.content,
       encode(digest(ch.content, 'sha256'), 'hex'), length(ch.content),
       jsonb_build_object('source', 'showcase-seed', 'demo', true), 'NOT_CONFIGURED'
  FROM chunks ch
 WHERE EXISTS (SELECT 1 FROM knowledge_document_version v WHERE v.id = ch.version_id)
ON CONFLICT (id) DO NOTHING;

WITH impacts(id, policy_id, enterprise_name, impact_level, summary, evidence_ids, status, reviewer) AS (
  VALUES
    ('d3000000-0000-0000-0000-000000000701'::uuid,
     'd3000000-0000-0000-0000-000000000301'::uuid, '验收测试企业02（虚构）',
     'HIGH', '企业需要补充风险分级方法、年度改造计划和项目复核机制。该结论仅用于演示。',
     '["d3000000-0000-0000-0000-000000000621"]'::jsonb, 'APPROVED', 'showcase-seed:2026-09-04'),
    ('d3000000-0000-0000-0000-000000000702'::uuid,
     'd3000000-0000-0000-0000-000000000302'::uuid, '验收测试企业01（虚构）',
     'MEDIUM', '企业的数据治理和台账校核服务与共享要求高度相关，需强化数据分级和留痕。该结论仅用于演示。',
     '["d3000000-0000-0000-0000-000000000622"]'::jsonb, 'APPROVED', 'showcase-seed:2026-09-04'),
    ('d3000000-0000-0000-0000-000000000703'::uuid,
     'd3000000-0000-0000-0000-000000000303'::uuid, '验收测试企业04（虚构）',
     'HIGH', '企业应将施工过程记录、质量检测和验收口径纳入统一管理。该结论仅用于演示。',
     '["d3000000-0000-0000-0000-000000000624"]'::jsonb, 'PENDING_REVIEW', NULL),
    ('d3000000-0000-0000-0000-000000000704'::uuid,
     'd3000000-0000-0000-0000-000000000302'::uuid, '验收测试企业05（虚构）',
     'MEDIUM', '平台接口和数据共享功能需要完善访问控制、操作留痕和异常告警。该结论仅用于演示。',
     '["d3000000-0000-0000-0000-000000000623"]'::jsonb, 'PENDING_REVIEW', NULL)
)
INSERT INTO policy_impact_analysis (
  id, policy_document_id, enterprise_id, association_id, impact_level, summary,
  evidence_chunk_ids, status, reviewed_by_subject, reviewed_at, version)
SELECT i.id, i.policy_id, e.id, c.association_id, i.impact_level,
       i.summary, i.evidence_ids, i.status, i.reviewer,
       CASE WHEN i.reviewer IS NULL THEN NULL ELSE now() END,
       CASE WHEN i.reviewer IS NULL THEN 0 ELSE 1 END
  FROM impacts i
  JOIN enterprise e ON e.name = i.enterprise_name
 CROSS JOIN gx_showcase_context c
 WHERE e.association_id = c.association_id
ON CONFLICT (id) DO NOTHING;

WITH match_rows(
  id, demand_id, candidate_enterprise_name, score, solution, reasons,
  state, review_status, recommended, demand_confirmed, candidate_confirmed, closed_reason
) AS (
  VALUES
    ('d3000000-0000-0000-0000-000000000401'::uuid,
     'd3000000-0000-0000-0000-000000000201'::uuid, '验收测试企业01（虚构）', 92.50::numeric,
     '由数据治理团队完成探测成果清洗、拓扑检查和统一入库。',
     '["场景高度匹配","具备台账校核能力","同属当前协会"]'::jsonb,
     'PENDING_CONFIRMATION', 'PENDING', false, false, false, NULL),
    ('d3000000-0000-0000-0000-000000000402'::uuid,
     'd3000000-0000-0000-0000-000000000201'::uuid, '验收测试企业02（虚构）', 86.00::numeric,
     '由咨询团队提供风险分区、实施路线和投资测算支持。',
     '["风险识别能力匹配","可补充项目咨询","同属当前协会"]'::jsonb,
     'RECOMMENDED', 'APPROVED', true, false, false, NULL),
    ('d3000000-0000-0000-0000-000000000403'::uuid,
     'd3000000-0000-0000-0000-000000000202'::uuid, '验收测试企业03（虚构）', 94.00::numeric,
     '采用压力、流量与声学监测终端，完成设备部署和告警联调。',
     '["设备能力直接匹配","支持状态监测","具备联调场景"]'::jsonb,
     'PARTIALLY_CONFIRMED', 'APPROVED', true, true, false, NULL),
    ('d3000000-0000-0000-0000-000000000404'::uuid,
     'd3000000-0000-0000-0000-000000000202'::uuid, '验收测试企业05（虚构）', 82.00::numeric,
     '由数字孪生平台承接监测数据接入、接口映射和可视化展示。',
     '["接口能力匹配","可提供展示平台","支持系统集成"]'::jsonb,
     'CONFIRMED', 'APPROVED', true, true, true, NULL),
    ('d3000000-0000-0000-0000-000000000405'::uuid,
     'd3000000-0000-0000-0000-000000000203'::uuid, '验收测试企业05（虚构）', 96.00::numeric,
     '以数字孪生协同平台汇聚台账、工单和物联数据，形成演示驾驶舱。',
     '["产品与需求高度一致","系统集成能力匹配","接口测试经验匹配"]'::jsonb,
     'RECOMMENDED', 'APPROVED', true, false, false, NULL),
    ('d3000000-0000-0000-0000-000000000406'::uuid,
     'd3000000-0000-0000-0000-000000000204'::uuid, '验收测试企业04（虚构）', 89.00::numeric,
     '原候选方案已结束评估，保留记录用于演示关闭流程。',
     '["施工场景匹配","质量检查能力匹配"]'::jsonb,
     'CLOSED', 'CLOSED', false, false, false, '演示项目范围调整，匹配流程关闭')
)
INSERT INTO ecosystem_match (
  id, demand_id, candidate_enterprise_id, score, explanation, review_status,
  demand_company_snapshot, demand_title_snapshot, scene_snapshot,
  supplier_company_snapshot, solution, reasons, state,
  recommended_by_subject, recommended_at,
  demand_confirmed_by_subject, demand_confirmed_at,
  candidate_confirmed_by_subject, candidate_confirmed_at,
  closed_reason, version)
SELECT m.id, m.demand_id, ce.id, m.score,
       jsonb_build_object('reasons', m.reasons, 'demo', true), m.review_status,
       de.name, d.title, d.scenarios->>0, ce.name, m.solution, m.reasons, m.state,
       CASE WHEN m.recommended THEN 'showcase-seed:2026-09-04' END,
       CASE WHEN m.recommended THEN now() - interval '2 days' END,
       CASE WHEN m.demand_confirmed THEN 'showcase-seed:2026-09-04' END,
       CASE WHEN m.demand_confirmed THEN now() - interval '1 day' END,
       CASE WHEN m.candidate_confirmed THEN 'showcase-seed:2026-09-04' END,
       CASE WHEN m.candidate_confirmed THEN now() - interval '12 hours' END,
       m.closed_reason, 0
  FROM match_rows m
  JOIN cooperation_demand d ON d.id = m.demand_id
  JOIN enterprise de ON de.id = d.enterprise_id
  JOIN enterprise ce ON ce.name = m.candidate_enterprise_name
  JOIN gx_showcase_context c ON c.association_id = ce.association_id
ON CONFLICT (id) DO NOTHING;

WITH collaborations(id, enterprise_name, match_id, title, participants, owner, status, priority, next_action, progress, due_at) AS (
  VALUES
    ('d3000000-0000-0000-0000-000000000501'::uuid, '验收测试企业04（虚构）', NULL::uuid,
     '【演示】老旧街区综合探测项目启动',
     '["验收测试企业04（虚构）","验收测试企业01（虚构）"]'::jsonb,
     '协会项目组', 'OPEN', 'HIGH', '确认现场踏勘时间和资料清单', 20, now() + interval '14 days'),
    ('d3000000-0000-0000-0000-000000000502'::uuid, '验收测试企业01（虚构）',
     'd3000000-0000-0000-0000-000000000404'::uuid,
     '【演示】燃气监测设备与平台接口联调',
     '["验收测试企业01（虚构）","验收测试企业05（虚构）"]'::jsonb,
     '技术协同组', 'IN_PROGRESS', 'HIGH', '完成告警数据接口联调并上传测试记录', 65, now() + interval '7 days'),
    ('d3000000-0000-0000-0000-000000000503'::uuid, '验收测试企业02（虚构）', NULL::uuid,
     '【演示】数字孪生驾驶舱需求确认',
     '["验收测试企业02（虚构）","验收测试企业05（虚构）"]'::jsonb,
     '产品方案组', 'PENDING_REVIEW', 'MEDIUM', '协会确认首期展示指标范围', 10, now() + interval '21 days'),
    ('d3000000-0000-0000-0000-000000000504'::uuid, '验收测试企业03（虚构）', NULL::uuid,
     '【演示】非开挖修复试验段方案评审',
     '["验收测试企业03（虚构）","验收测试企业04（虚构）"]'::jsonb,
     '质量评审组', 'COMPLETED', 'LOW', '归档评审结论和演示材料', 100, now() - interval '2 days')
)
INSERT INTO collaboration_task (
  id, association_id, enterprise_id, match_id, owner_subject, title,
  participants, priority, next_action, progress, status, due_at, completed_at, version)
SELECT co.id, c.association_id, e.id, co.match_id, co.owner, co.title,
       co.participants, co.priority, co.next_action, co.progress, co.status, co.due_at,
       CASE WHEN co.status = 'COMPLETED' THEN now() - interval '1 day' END, 0
  FROM collaborations co
  JOIN enterprise e ON e.name = co.enterprise_name
 CROSS JOIN gx_showcase_context c
 WHERE e.association_id = c.association_id
ON CONFLICT (id) DO NOTHING;

WITH activities(collaboration_id, activity_type, detail) AS (
  VALUES
    ('d3000000-0000-0000-0000-000000000501'::uuid, 'CREATE', '已建立演示协作事项并分配参与单位。'),
    ('d3000000-0000-0000-0000-000000000502'::uuid, 'MILESTONE', '设备协议字段已确认，进入接口联调。'),
    ('d3000000-0000-0000-0000-000000000503'::uuid, 'SUBMIT', '首期驾驶舱指标清单已提交协会审核。'),
    ('d3000000-0000-0000-0000-000000000504'::uuid, 'COMPLETE', '试验段方案评审完成，演示结论已归档。')
)
INSERT INTO collaboration_activity (collaboration_id, activity_type, detail, actor_subject)
SELECT a.collaboration_id, a.activity_type, a.detail, 'showcase-seed:2026-09-04'
  FROM activities a
 WHERE EXISTS (SELECT 1 FROM collaboration_task c WHERE c.id = a.collaboration_id)
   AND NOT EXISTS (
     SELECT 1 FROM collaboration_activity existing
      WHERE existing.collaboration_id = a.collaboration_id
        AND existing.actor_subject = 'showcase-seed:2026-09-04'
        AND existing.detail = a.detail
   );

INSERT INTO knowledge_document_history (
  document_id, association_id, lifecycle_version, action, actor_subject, snapshot)
SELECT d.id, d.association_id, d.lifecycle_version, 'KNOWLEDGE_APPROVE',
       'showcase-seed:2026-09-04',
       jsonb_build_object('title', d.title, 'status', d.status, 'demo', true)
  FROM knowledge_document d
 WHERE d.id IN (
   'd3000000-0000-0000-0000-000000000601'::uuid,
   'd3000000-0000-0000-0000-000000000602'::uuid)
   AND NOT EXISTS (
     SELECT 1 FROM knowledge_document_history h
      WHERE h.document_id = d.id
        AND h.actor_subject = 'showcase-seed:2026-09-04'
   );

WITH resources AS (
  SELECT e.association_id, p.enterprise_id, 'PRODUCT_SERVICE'::varchar AS resource_type,
         p.id AS resource_id, p.version AS resource_version, p.name AS title, p.status
    FROM product_service p JOIN enterprise e ON e.id = p.enterprise_id
   WHERE p.id BETWEEN 'd3000000-0000-0000-0000-000000000101'::uuid
                  AND 'd3000000-0000-0000-0000-000000000105'::uuid
  UNION ALL
  SELECT e.association_id, d.enterprise_id, 'COOPERATION_DEMAND', d.id, d.version, d.title, d.status
    FROM cooperation_demand d JOIN enterprise e ON e.id = d.enterprise_id
   WHERE d.id BETWEEN 'd3000000-0000-0000-0000-000000000201'::uuid
                  AND 'd3000000-0000-0000-0000-000000000204'::uuid
  UNION ALL
  SELECT p.association_id, NULL::uuid, 'POLICY_DOCUMENT', p.id, p.version, p.title, p.status
    FROM policy_document p
   WHERE p.id BETWEEN 'd3000000-0000-0000-0000-000000000301'::uuid
                  AND 'd3000000-0000-0000-0000-000000000303'::uuid
  UNION ALL
  SELECT e.association_id, d.enterprise_id, 'ECOSYSTEM_MATCH', m.id, m.version,
         d.title || ' → ' || candidate.name, m.state
    FROM ecosystem_match m
    JOIN cooperation_demand d ON d.id = m.demand_id
    JOIN enterprise e ON e.id = d.enterprise_id
    JOIN enterprise candidate ON candidate.id = m.candidate_enterprise_id
   WHERE m.id BETWEEN 'd3000000-0000-0000-0000-000000000401'::uuid
                  AND 'd3000000-0000-0000-0000-000000000406'::uuid
  UNION ALL
  SELECT c.association_id, c.enterprise_id, 'COLLABORATION_TASK', c.id, c.version, c.title, c.status
    FROM collaboration_task c
   WHERE c.id BETWEEN 'd3000000-0000-0000-0000-000000000501'::uuid
                  AND 'd3000000-0000-0000-0000-000000000504'::uuid
  UNION ALL
  SELECT i.association_id, i.enterprise_id, 'POLICY_IMPACT_ANALYSIS', i.id, i.version,
         p.title || ' → ' || e.name, i.status
    FROM policy_impact_analysis i
    JOIN policy_document p ON p.id = i.policy_document_id
    JOIN enterprise e ON e.id = i.enterprise_id
   WHERE i.id BETWEEN 'd3000000-0000-0000-0000-000000000701'::uuid
                  AND 'd3000000-0000-0000-0000-000000000704'::uuid
)
INSERT INTO business_entity_history (
  association_id, enterprise_id, resource_type, resource_id,
  resource_version, action, actor_subject, snapshot)
SELECT r.association_id, r.enterprise_id, r.resource_type, r.resource_id,
       r.resource_version, 'SHOWCASE_SEED', 'showcase-seed:2026-09-04',
       jsonb_build_object('title', r.title, 'status', r.status, 'demo', true)
  FROM resources r
 WHERE NOT EXISTS (
   SELECT 1 FROM business_entity_history h
    WHERE h.resource_type = r.resource_type
      AND h.resource_id = r.resource_id
      AND h.action = 'SHOWCASE_SEED'
 );

INSERT INTO audit_log (
  actor_subject, actor_username, association_id, action, resource_type,
  resource_id, resource_version, outcome, details, request_id)
SELECT 'showcase-seed:2026-09-04', 'showcase-seed', c.association_id,
       'SHOWCASE_SEED', 'SHOWCASE_DATASET', 'showcase-20260904', 0,
       'SUCCESS',
       jsonb_build_object(
         'label', '【演示】',
         'offerings', 5,
         'demands', 4,
         'matches', 6,
         'collaborations', 4,
         'policies', 3,
         'policyImpacts', 4,
         'knowledgeDocuments', 2),
       'showcase-seed-20260904'
  FROM gx_showcase_context c
 WHERE NOT EXISTS (
   SELECT 1 FROM audit_log a
    WHERE a.request_id = 'showcase-seed-20260904'
      AND a.action = 'SHOWCASE_SEED'
 );

COMMIT;

SELECT jsonb_build_object(
  'status', 'verified',
  'operation', 'showcase-seed',
  'label', '【演示】',
  'offerings', (SELECT count(*) FROM product_service
                 WHERE id BETWEEN 'd3000000-0000-0000-0000-000000000101'::uuid
                              AND 'd3000000-0000-0000-0000-000000000105'::uuid),
  'demands', (SELECT count(*) FROM cooperation_demand
               WHERE id BETWEEN 'd3000000-0000-0000-0000-000000000201'::uuid
                            AND 'd3000000-0000-0000-0000-000000000204'::uuid),
  'matches', (SELECT count(*) FROM ecosystem_match
               WHERE id BETWEEN 'd3000000-0000-0000-0000-000000000401'::uuid
                            AND 'd3000000-0000-0000-0000-000000000406'::uuid),
  'collaborations', (SELECT count(*) FROM collaboration_task
                      WHERE id BETWEEN 'd3000000-0000-0000-0000-000000000501'::uuid
                                   AND 'd3000000-0000-0000-0000-000000000504'::uuid),
  'policies', (SELECT count(*) FROM policy_document
                WHERE id BETWEEN 'd3000000-0000-0000-0000-000000000301'::uuid
                             AND 'd3000000-0000-0000-0000-000000000303'::uuid),
  'policyImpacts', (SELECT count(*) FROM policy_impact_analysis
                     WHERE id BETWEEN 'd3000000-0000-0000-0000-000000000701'::uuid
                                  AND 'd3000000-0000-0000-0000-000000000704'::uuid),
  'knowledgeDocuments', (SELECT count(*) FROM knowledge_document
                          WHERE id BETWEEN 'd3000000-0000-0000-0000-000000000601'::uuid
                                       AND 'd3000000-0000-0000-0000-000000000602'::uuid)
)::text;
