# 年度公开资料导入：2026-09-08

## 范围与状态

本批为 2025-09-08 至 2026-09-08 期间筛查后保留的 64 条公开证据，不代表互联网全年资料已穷尽。
41 条招采/历史公告，23 条企业活动与动态，涉及 32 个名录主体、81 条有明确角色的关联。
81 条角色关联不等于 81 次合作。另有 17 条待核线索，明确排除，不会写入网站目录。

导入行为：新增 63 条 `platform_source_record`；给原批次 `BID-049` 增加独立 `publicEvidence`。
原项目的 `source`、标题、元数据更正完整保留。记录角色、原文链接、公告日期、活动日期、核验截至日期及金额口径。
候选不等于中标，联合体总额不等于单家收入，发行人披露不等于最终合同，计划不等于已举办。
会员身份、投标准入资格、网站公开发布、跨协会共享、账号、会员供需、匹配和合作状态均不因此改变。

数据源 SHA256：`82973b3bf348b578bd707017ffc51bc5e3a6e907847bb9c9d6defb66d5560e30`。
企业基线 SHA256：`f9c1243ac018e6a323c18f212ecac7f068ac09f84ce3d8fdcae60c250c370e24`。
原生产导入批次：`beijing-platform-20260907`，关联协会 `00000000-0000-0000-0000-000000000106`。
基线文件包含原始业务资料，不提交 Git；只放到用户指定服务器的受限临时目录。

## 发布前后顺序

1. 单独合并目录 API、前端、`V30_1__external_activity_source_kind.sql` 及导入工具的发布变更。
   不夹带工作区仍在开发的 V31 政策分析代码。V30.1 只扩展目录类型约束，不导入任何记录。
2. 通过现有受限维护工具备份并发布 server/web，确认 V30.1 升级及服务健康。
3. 由服务器 admin 对固定源文件执行只读预检，检验 32 个线上主体的准确名称、关联身份、会员证据、未删除状态。
   缺少原批次、基线变化、重复 URL/项目、人工修改或部分导入均停止，禁止强制覆盖。
4. admin 显式执行导入：先获得维护锁，预检，生成 600 权限 PostgreSQL 备份与校验清单，验证归档可读取，
   再在单一事务和表锁下重新检查及写入。出错整体回滚。相同批次原样重跑为 no-op，不重复审计插入。
5. 以导入报告的 `storedRows=63`、`evidenceRows=64` 为成功证据，再核验登录后的页面。
   源目录可通过企业全名或 ORG 编号搜索；企业活动与公开动态单独入口，不混入会员合作需求。

## 命令模板（发布 V30.1 后使用）

将 `--baseline` 改为上传且核对过 SHA256 的服务器路径。不要把本地 Windows 路径直接粘贴到服务器。

```bash
sudo python3 /home/admin/guanxian/tools/operations/import_member_annual_evidence.py \
  --catalog /home/admin/guanxian/data/curated/member-annual-evidence-20260908.json \
  --baseline /home/karax/member-annual-evidence-20260908/platform-dataset-prepared-20260907.json \
  --preflight-server
```

预检后，原命令中的 `--preflight-server` 替换为：

```text
--execute --confirm-source-sha256 82973b3bf348b578bd707017ffc51bc5e3a6e907847bb9c9d6defb66d5560e30
```

备份和报告固定保存在 `/var/backups/guanxian/member-annual-evidence-20260908/`，root 可读。
脚本不会修改 sudoers、开启 root SSH 或尝试从受限部署命令绕过导入权限。
没有生产执行报告，不能把“构建通过、资料已上传、代码已上线”表述为“生产已导入”。

## 回滚与验证

本地 `--output-dir` 生成 plan、只读 SQL、apply SQL、`rollback-after-review.sql`。
回滚 SQL 须管理员单独审核执行；仅当本批全部资料与导入后预期完全一致时删除 63 条新目录并恢复 1 条原项目，
保留原导入审计并新增回滚审计。若有人编辑过任何相关目录，停止人工处理；不删除企业或复原整个生产数据库。

测试覆盖：真实 PostgreSQL（隔离容器）预检、身份变化、重复来源、事务回滚、63+1 条、活动分类、幂等、人工编辑冲突、
原数据不变及定向回滚；Java 目录鉴权/字段投影及旧库升级；前端类型检查、构建、状态文案和移动端浏览器交互。
浏览器测试使用明确标记的虚构接口响应，不是生产数据截图。
