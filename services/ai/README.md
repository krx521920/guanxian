# 规则兼容服务

本目录是北京地下管线协会管理协作平台的独立 Python 规则能力服务。它当前不调用外部模型，使用确定性规则完成企业资料结构化和供需匹配，主要用于兼容、联调与自动化测试。

生产知识库唯一正式实现位于 Java `ai-adapter`：支持 PDF/DOCX/XLSX/TXT/CSV 解析、文档审核生命周期、来源附件、分段、可选 OpenAI-compatible Embedding、PostgreSQL JSONB 向量持久化、词法/余弦混合检索、引用追踪、无证据拒答和真实语料评测闸门。外部模型与 Embedding 默认关闭；在真实供应商、协会语料指标、费用和数据出境审批完成前，产品不得对外宣称为“AI 平台”。

## 接口

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/health` | 健康检查及模型连接状态 |
| POST | `/api/v1/extract/company-profile` | 从企业介绍中提取角色、场景、产品、资质等信息 |
| POST | `/api/v1/match/enterprises` | 按场景、能力、资质、案例、地区和数据质量匹配企业 |
| POST | `/api/v1/qa/policy` | 兼容入口；稳定返回 `503 POLICY_QA_NOT_ENABLED`，不生成占位答案 |

启动后可访问 `/docs` 查看 OpenAPI 交互文档。

## 本地运行

需要 Python 3.11 或更高版本：

```bash
python -m venv .venv
# Windows PowerShell: .venv\Scripts\Activate.ps1
python -m pip install -r requirements-dev.txt
python -m app.main
```

运行质量检查：

```bash
pytest
ruff check app tests
```

`pytest` 默认运行常规单元、接口、边界和业务不变量测试，并自动排除高负载标记用例。模糊请求和高压测试不属于当前批准的执行范围，不得加入 CI 或项目自动化。

当前基线以仓库内最近一次可复验的 pytest 报告为准；修改测试数量时必须同步更新 `docs/testing-guide.md`，不得继续引用历史主动测试结果作为当前质量结论。

### 独立变异测试

mutmut 只对 `app/services/` 的规则实现做变异，利用常规测试检查测试集是否能“杀死”错误实现；
该流程耗时较长，不会被默认 `pytest` 触发。

```powershell
./scripts/run_mutation.ps1
# 只跑限定函数（mutmut 名称支持通配符）
./scripts/run_mutation.ps1 -Targets "app.services.text.x_keyword_similarity*"
```

mutmut 3 不支持原生 Windows；Windows 上述 PowerShell 脚本会自动转到 WSL 执行。首次运行前，
需先在配有 Python 3.11+ 的 WSL 本目录执行
`python3 -m pip install -r requirements-dev.txt`。Linux/WSL 中也可直接执行：

```bash
python -m mutmut run --max-children 1
python -m mutmut results
python -m mutmut show <mutant-id>
```

变异结果会产生在本地 `mutants/` 目录中，已由 `.gitignore` 排除。先用 `mutmut results`
定位 surviving mutant，再为其补充最小、有业务意义的断言。

## Docker

在本目录执行：

```bash
docker build -t guanxian-ai-service .
docker run --rm -p 8001:8001 guanxian-ai-service
```

## 配置

配置使用 `AI_` 环境变量前缀，示例见 `.env.example`。服务默认监听 `0.0.0.0:8001`，可用 `AI_HOST` 和 `AI_PORT` 覆盖。
`AI_MAX_REQUEST_BYTES` 默认为 32 MiB。已声明超限 `Content-Length` 的请求会在读取正文前被拒绝；
无长度声明或分块传输则使用纯 ASGI `receive` 包装器逐块累计，超限块及剩余正文不会交给路由，统一返回 `413 PAYLOAD_TOO_LARGE`。
该实现不会一次性预读或缓存整个请求体。生产网关仍建议配置同等或更严格的限制，以便在流量进入应用前拒绝。
当前应保持 `AI_MODEL_PROVIDER=disabled`。该配置仅反映健康接口中的模型连接状态；Python 服务不会据此启用政策问答。`/api/v1/qa/policy` 在所有环境均稳定返回 `503 POLICY_QA_NOT_ENABLED`，避免把规则片段检索误当作真实问答。

## 当前规则说明

- 企业结构化：通过可维护的行业关键词表识别业务角色、应用场景、产品服务、资质和服务地区，同时返回原文证据和置信度。
- 供需匹配：默认权重为场景 35%、能力 25%、资质 15%、案例 10%、地区 10%、资料质量 5%；响应包含分项分数、推荐理由和缺失条件。缺少必需资质的企业会标记为 `eligible=false`。
- 政策问答：Python 服务不提供答案。Web 使用 Java `ai-adapter` 的知识库主链路；在真实语料、模型、费用和安全审批完成前，该主链路也必须保持外部模型默认关闭。
- 标识、标题和标签会去除首尾空白并按 Unicode NFKC 等价关系比较；匹配请求内重复的企业标识返回 `409`，避免结果归属不明确。政策兼容入口不执行业务检索或文档去重。
- 候选企业、政策文档、标签列表和数值字段均有显式上限；全零匹配权重返回 `409`，其余相对权重会归一化为 100%。
- 校验错误最多返回 50 条定位信息，不包含原始输入和内部上下文；未知异常只对外返回稳定的 `INTERNAL_ERROR`。

该 Python 兼容入口不替代 Java 生产知识库，也不得被前端作为降级答案来源。若未来要在 Python 服务实现政策问答，必须先接入 Java 侧同等的知识版本、召回来源、模型版本、费用门禁和全链路审计，并以新的受验收能力替换当前 `503` 契约。

## 请求追踪

服务的所有响应都包含 `X-Request-Id` 响应头，包括校验失败、业务异常、未知异常和请求体超限。
客户端传入的 ID 仅在完全符合 `[A-Za-z0-9._:-]{1,128}` 时原样保留；缺失或非法时服务会生成 UUID。
同一 ID 会放入请求上下文供异常日志关联，请求完成后立即释放，不会在并发请求之间复用。
