# 测试工具快速入口

所有主动请求脚本都会加载 `common/SafeTarget.psm1`，只允许 `localhost`、回环地址和 Compose 内部服务名。请只对本仓库的本地或隔离测试环境执行。

```powershell
# 常规正向、反向、异常和集成测试
./scripts/verify.ps1

# 启动后端与 AI 服务，验证鉴权、匹配、安全头、请求追踪和 ETag 乐观并发
./tests/smoke/runtime-smoke.ps1

# OpenAPI 结构感知模糊测试（AI 服务需监听 18001）
./tests/fuzz/run-schemathesis.ps1 -MaxExamples 50

# k6：smoke / load / stress（业务后端需监听 18080）
./tests/performance/run-k6.ps1 -Profile smoke
./tests/performance/run-k6.ps1 -Profile stress

# ZAP：Web 被动基线 / OpenAPI 主动扫描（需要 Docker）
./tests/security/run-zap.ps1 -Mode baseline -Target http://127.0.0.1:8081
./tests/security/run-zap.ps1 -Mode api -Target http://127.0.0.1:18001/openapi.json

# AI 依赖延迟故障注入（需要 Docker Compose）
./tests/resilience/run-toxiproxy.ps1
```

变异测试分别执行：

```powershell
Push-Location apps/server; mvn -pl bootstrap -am verify -Pmutation; Pop-Location
Push-Location apps/web; npm run mutation; Pop-Location
Push-Location services/ai; ./scripts/run_mutation.ps1; Pop-Location
```

详细前置条件、阈值、覆盖矩阵、报告路径和本轮实测基线见 `docs/testing-guide.md`。
