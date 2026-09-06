#!/usr/bin/env bash
# 管线智联 · 本地演示一键启动
set -e
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

cd "$ROOT"
if ! command -v node >/dev/null 2>&1; then
  echo "需要 Node.js（>= 18）。" >&2
  exit 1
fi

# 1) 演示数据服务（8080，可 PORT 覆盖）
if lsof -iTCP:8080 -sTCP:LISTEN >/dev/null 2>&1; then
  echo "[1/2] 8080 已被占用，假设演示数据服务已在运行。"
else
  echo "[1/2] 启动演示数据服务 → http://localhost:8080/api/v1/health"
  node demo/server.mjs > /tmp/guanxian-demo-server.log 2>&1 &
  SERVER_PID=$!
  trap 'kill $SERVER_PID 2>/dev/null || true' EXIT
  sleep 1
fi

# 2) 前端
if [ ! -d apps/web/node_modules ]; then
  echo "[i] 首次运行：安装前端依赖…"
  (cd apps/web && npm install)
fi
echo "[2/2] 启动前端（Vite）…"
open "http://localhost:5173/" || true
(cd apps/web && npm run dev)
