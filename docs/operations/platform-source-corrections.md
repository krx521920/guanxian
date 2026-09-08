# 北京平台资料元数据更正（待上线）

本工具纳入剩余运维代码发布；**发布代码不代表已执行生产更正**。现有计划仍需新备份、现场预检和本批数据写入批准。不能把元数据更正解释为企业资质、投标条件或政策适用性已通过核验。

## 固定范围与依据

仅支持源 ZIP SHA-256 `03b0929d31d163f51461eb7f8011b14c356c02d4f7ccb5bdf2040103920f9daa`，对应导入批次 `beijing-platform-20260907`。

| 来源 ID | 本批更正 | 不作出的结论 |
|---|---|---|
| STD-005 | 标准编号由 GB/T 50838-2024 更正为 GB/T 50838-2015；本次局部修订实施日期改为 2025-04-01；纠正“整本替代”的版本说明 | 不代表原版实施日期改变，也不证明全部条款现行或适用某家企业 |
| POL-005 | 将没有独立依据的实施日期置空，保留成文/发布日期，补充日期说明 | 不表示文件失效 |
| BID-041 | 把原资料拼在一起的原公告与更正公告 URL 拆为两条链接 | 未取到公告内容，不能确认更正内容、预算、资格、截止期或当前可参与性 |

STD-005 的依据为[中国建筑出版传媒的公开试读本](https://ebook.chinabuilding.com.cn/zbooklib/bookpdf/probation?SiteID=1&bookID=155860)中住建部 2024 年第 214 号公告。出版目录自身也可能包含编号差错，应核对试读公告，不只读目录标题。试读公告表示局部修订、名称及编号调整；不得误写为 GB 55028-2022 整部废止。

POL-005 的[北京市规划和自然资源委员会官方信息页](https://ghzrzyw.beijing.gov.cn/zhengwuxinxi/zcwj/qtwj/202608/t20260811_4818247.html)实施日期栏为空，成文/发布日期为 2026-07-24。“现行有效”是页面的文件状态信息，不是企业适用判断。

BID-041 两条链接均来自原始 ZIP，不是补造：原公告 `https://ggzyfw.beijing.gov.cn/jyxxggjtbyqs/20260901/5691903.html`；更正公告 `https://ggzyfw.beijing.gov.cn/jyxxgcjsgzgg/20260902/5694110.html`。本批更正记录的 `evidenceUrls` 保持空数组，原因明确写明未取到内容。

## 准备流程（不连接数据库）

```sh
python tools/operations/prepare_platform_corrections.py \
  --archive /path/to/reviewed-source.zip \
  --output-dir /path/to/new-review-directory
```

工具没有 `--execute` 参数；输出目录必须不存在，不能覆盖已审阅的计划。生成：

- `plan.json`：精确前后值、来源 ID、原始资料、更正理由和证据链接。
- `preflight.sql`：`BEGIN READ ONLY`，验证后 `ROLLBACK`，不写业务数据。
- `apply-after-backup-and-approval.sql`：有写入行为的独立文件，仅供审批与后续执行，不自动运行。

更正前应先部署并验证 V31、审阅上述差异、核对新备份及恢复能力、取得本批明确批准，然后才能通过生产既有维护流程执行。执行 SQL 的客户端必须启用 `ON_ERROR_STOP=1`。本文件没有授予生产写权限；不要为绕过预检去删保护条件，也不要重跑原始导入工具或演示数据清理流程。

## 数据保护和适配

1. 仅更正 3 个 `platform_source_record.payload`，以及对应 2 条 `policy_document` 的编号、实施日期、摘要、版本和修改人/时间。不删除、不重新导入；不创建账号，不修改企业、资料公开授权或已有影响分析。
2. `payload.source` 原始字典逐字段保留。更正值位于规范化字段及 `payload.correction.fields`，附原因、日期、证据和 `applicability=UNVERIFIED`。
3. SQL 只接受固定数据库、协会、批次、源 SHA、记录 ID、来源 ID、类型、政策外键与精确原始快照。政策实际字段、版本或来源快照已被人工改动时，整批停止，交由人工协调。已经全部执行且数据未再变化则无操作；部分执行或执行后又被修改也会停止。
4. 单事务、表写锁、5 秒锁等待和 30 秒语句超时。任意失败全部回滚；成功增加 3 条来源历史、2 条政策历史及 3 条审计，保留前后值。重跑不重复写入审计。
5. 来源 API 对原值和更正值均使用同一展示字段白名单，不能由更正记录泄露内部字段。前端分开渲染原公告和更正公告，禁止混合 URL 被当作一个地址；不渲染任意 HTML、脚本链接或带凭据的 URL。
6. 政策规范化快照与实际记录同步更新，保持候选匹配及分析来源读取一致；企业适用状态仍未核实。政策版本递增供带版本快照的新分析审核校验使用；旧分析不会被自动重写、批准或批量撤销。

回退必须依据本批审计/历史或批准的新备份设计反向方案，并先检查后续人工修改。不得直接用旧 ZIP 覆盖、重跑导入脚本，或为仅 3 条元数据无条件恢复整库。

## 本地验证

```sh
python -m unittest discover -s tests/operations -p 'test_*.py'
docker pull postgres:16-alpine
python tests/operations/platform_corrections_postgres_check.py
```

独立 SQL 测试只新建具名、带标签的自有 Docker 容器，无宿主端口/数据卷/生产连接；完成后停止并由 `--rm` 移除。它按版本执行仓库迁移 SQL，使用合成的 3 条来源夹具，验证预检只读、版本/字段/来源冲突、部分批次拒绝、原子回滚、更正值、匹配快照契约、原文保留、审计、幂等及账号/企业/旧分析不变。它本身不是 Flyway 驱动测试。

另以 Testcontainers + Flyway 执行 `SourceDirectoryPostgresTest`、`PolicyCandidatesPostgresTest`、`PostgresPolicyImpactIntegrationTest`、`PostgresPolicyEvidenceUpgradeIntegrationTest` 共 14 项，0 失败/错误/跳过；包括 V30 存量数据升级至 V31 的保护。前端 14 项单元测试、12 项本地 Edge 浏览器用例及 vue-tsc/Vite 构建通过。浏览器身份和业务响应使用测试夹具，不是生产端到端验收。

CI 包含隔离 PostgreSQL 更正测试步骤，发布须全部通过。生成文件可能包含源资料，在 Unix 上以私有目录及文件权限创建；Windows 上须使用本人受保护目录。不会上传计划、原始 ZIP 或备份。上述检查证明代码与保护逻辑，不是 116 家企业真实资质或政策匹配准确率的证明。
