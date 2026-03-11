# BotDrop Android → OpenClaw Node — 设计文档

**Date:** 2025-03-11
**Status:** Reviewed
**Author:** 小C + John
**Branch:** `feat/openclaw-node`

## Overview

让 BotDrop Android 作为 OpenClaw Node 连接远程 Gateway，
通过 Termux 内置的 `openclaw node run` 实现，**零代码开发**。

## 动机

- 现有架构要求 Gateway 和手机在同一网络
- Node 模式通过 WebSocket 穿透 NAT，手机在任意网络都能被远程控制
- 统一管理（`openclaw nodes status` 直接看到手机）
- 支持多设备并行

## 方案选择

| | 方案 A: BotDrop 内嵌 Node | **方案 B: Termux openclaw node（✅ 采用）** |
|---|---|---|
| 开发量 | ~4 天 | **0 天** |
| 维护成本 | 需跟进协议更新 | openclaw 自动更新 |
| u2 访问 | 自定义透传命令 | `system.run` 直接调用 |
| 用户体验 | App 内一键连接 | 两份配置文件 |

**选择方案 B**：零开发，只维护配置。

## 架构

```
┌────────────────────────────────────────────┐
│  OpenClaw Gateway (remote server)          │
│                                            │
│  Agent ──► exec host=node                  │
│            → python/curl 调 u2             │
└──────────────────┬─────────────────────────┘
                   │ WebSocket (wss://)
                   ▼
┌────────────────────────────────────────────┐
│  BotDrop Android (Termux)                  │
│                                            │
│  ┌──────────────────────┐                  │
│  │ openclaw node run    │  ← 已有，无需开发 │
│  │ (headless node host) │                  │
│  └──────────┬───────────┘                  │
│             │ system.run                   │
│  ┌──────────▼───────────┐                  │
│  │ u2 Server :9008      │  ← Shizuku 启动  │
│  │ + adb shell          │                  │
│  └──────────────────────┘                  │
└────────────────────────────────────────────┘
```

## 两种运行模式

BotDrop 通过**两份 openclaw 配置**支持两种模式：

### 模式 1: 本地 Gateway（现有）
- BotDrop 在 Termux 里运行完整的 OpenClaw Gateway
- 手机本身就是 Gateway，直接接收 IM 消息、运行 agent
- 配置：`~/.openclaw/config.yaml`（标准 gateway 配置）

### 模式 2: 远程 Node（新增）
- BotDrop 只启动 Shizuku + u2，然后运行 `openclaw node run`
- 连接到远程 Gateway，作为外设被控制
- 配置：`~/.openclaw/node.json`（自动生成）

**两种模式可以同时运行**（不互斥），也可以只用其中一种。

### 配置文件对比

```
~/.openclaw/
├── openclaw.json          ← Gateway 配置（channel、agent、model、auth 等）
├── node.json              ← Node 配置（nodeId、device token、gateway 连接信息）
└── exec-approvals.json    ← 共享（命令审批，两种模式都用）
```

| | Gateway (`openclaw.json`) | Node (`node.json`) |
|---|---|---|
| 内容 | channels、agents、model、auth、tools 等完整配置 | nodeId、displayName、gateway host/port/tls、device token |
| 管理 | 手动编辑或 `openclaw config set` | `openclaw node run` 自动生成，配对后自动写入 token |
| 体积 | 大（完整 agent 配置） | 极小（~6 行） |
| 互相影响 | 无 | 无 |

## 设置步骤

### 手机端（Termux）

```bash
# 1. 确保 openclaw 已安装
openclaw --version

# 2. 启动 u2 服务（BotDrop app 里操作）

# 3. 设置 gateway token
export OPENCLAW_GATEWAY_TOKEN="<remote-gateway-token>"

# 4. 启动 node host
openclaw node run --host <gateway-host> --port 18789 --display-name "BotDrop Phone"

# 或安装为服务（后台持续运行）
openclaw node install --host <gateway-host> --port 18789 --display-name "BotDrop Phone"
openclaw node restart
```

### Gateway 端

```bash
# 1. 配对
openclaw devices list
openclaw devices approve --latest

# 2. 配置 exec 指向 node
openclaw config set tools.exec.host node
openclaw config set tools.exec.node "BotDrop Phone"
openclaw config set tools.exec.security full  # 或 allowlist
```

### 验证

```bash
# Gateway 端测试
openclaw nodes status
openclaw nodes run --node "BotDrop Phone" -- echo "hello from phone"
openclaw nodes run --node "BotDrop Phone" -- curl -s http://127.0.0.1:9008/ping
```

## Agent 使用方式

Agent 通过 `exec host=node` 在手机上执行命令：

```python
# 截屏
exec host=node command="python3 -c \"import u2; d=u2.HTTPDevice('http://127.0.0.1:9008'); d.screenshot('/tmp/screen.png')\""

# 点击
exec host=node command="curl -s http://127.0.0.1:9008/jsonrpc/0 -d '{\"jsonrpc\":\"2.0\",\"method\":\"click\",\"params\":[500,800]}'"

# 设备信息
exec host=node command="getprop ro.product.model"
```

## Skill 更新

更新 `botdrop-automation` SKILL.md，让 agent 知道：
- 通过 `exec host=node` 执行命令（而不是本地 bridge）
- u2 endpoint 仍然是 `http://127.0.0.1:9008`
- 所有 shell 命令直接可用

## 后续优化（可选）

1. **BotDrop app 集成**：在 app 里加个 "Node 模式" 开关，自动在 Termux 启动 `openclaw node run`
2. **方案 A 回归**：如果需要更好的用户体验或去掉 Termux 依赖，再考虑内嵌 Node 到 BotDrop app
3. **Phase 2 原生能力**：通过内嵌 Node 补足 CameraX 拍照、GPS 定位等 u2 无法实现的能力

## 安全考虑

- Gateway Token 通过环境变量或 `~/.openclaw/node.json` 存储
- 建议使用 TLS (wss://) 加密连接
- `exec security` 可设为 `allowlist` 限制可执行命令
- Node host 的 exec approvals 存储在 `~/.openclaw/exec-approvals.json`
