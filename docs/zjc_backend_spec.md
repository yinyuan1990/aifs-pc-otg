# 子进程带宽控制 - 后端接口规范

## 一、概述

PC 端主程序 (`Phoenix.exe`) 关闭后，子进程 (`zjc_worker.exe`) 自动启动。  
子进程独立登录、连接 WebSocket，等待服务器下发「带宽爆发」指令。  
主程序打开时子进程自动终止。

### 两个进程的身份区分

| 进程 | clientType | 说明 |
|------|-----------|------|
| Phoenix.exe（主程序） | `main` | 正常使用中，用户在操作软件 |
| zjc_worker.exe（子进程） | `subprocess` | 主程序关闭，后台守护运行 |

> 同一个 `pcDeviceId`，同一时刻只有一个进程在线（主程序和子进程互斥）。

---

## 二、子进程登录

子进程启动后，使用主程序保存的账号密码重新登录获取独立 Token。

### 请求

```
POST https://api.147258yql.cn/api/auth/login/control
Content-Type: application/json

{
    "username": "user123",
    "password": "pass456",
    "pcDeviceId": "ABCD-1234-EFGH",
    "pcLevel": 1,
    "clientType": "subprocess"    // ⭐ 新增字段，标识子进程登录
}
```

### 响应

与现有登录接口一致，关键提取 `token` 字段即可。

> **注意**：后端收到 `clientType: "subprocess"` 时，不应踢掉主进程的会话。两者共用同一账号但身份不同。

---

## 三、WebSocket 连接

### 连接地址（通用格式）

```
wss://ws.147258yql.cn/ws?token={token}&username={username}&pcDeviceId={pcDeviceId}&clientType={main|subprocess}
```

参数说明：

| 参数 | 说明 |
|------|------|
| `token` | 登录获取的 Token |
| `username` | 登录账号 |
| `pcDeviceId` | PC 设备唯一标识（**必传**，后端用来写/清 Redis 在线标记） |
| `clientType` | `main`（主程序） 或 `subprocess`（子进程） |

> ⭐ **关键**：`pcDeviceId` 和 `clientType` 两个参数**主进程和子进程都必须传**。  
> - 后端握手拦截器从 URL 提取 `pcDeviceId` 存到 session，连接时写 Redis `pc:online:{pcDeviceId} = clientType`  
> - 断线时后端通过 session 中的 `pcDeviceId` 清除 Redis 在线标记  
> - 如果 URL 没带 `pcDeviceId`，断线时**无法自动清除在线状态**

### 主进程连接示例

```
wss://ws.147258yql.cn/ws?token=eyJhb...&username=user123&pcDeviceId=ABCD-1234-EFGH&clientType=main
```

### 子进程连接示例

```
wss://ws.147258yql.cn/ws?token=eyJhb...&username=user123&pcDeviceId=ABCD-1234-EFGH&clientType=subprocess
```

### STOMP 协议

连接后发送 STOMP CONNECT 帧，与主程序一致：

```
CONNECT
accept-version:1.0,1.1,2.0
heart-beat:10000,10000

\0
```

### 订阅频道

子进程订阅**专用爆发指令频道**：

```
SUBSCRIBE
id:sub-1
destination:/topic/pc/{pcDeviceId}/burst

\0
```

> 这是**新频道**，与主程序订阅的 `/user/queue/binding` 和 `/topic/device/{deviceId}/config` 完全隔离，不冲突。

---

## 四、爆发指令消息

### 服务器 → 子进程（触发爆发）

通过 STOMP 发送到 `/topic/pc/{pcDeviceId}/burst`：

```json
{
    "action": "BURST",
    "duration": 5,
    "taskId": "task_20260402_001",
    "timestamp": 1743580800000
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `action` | String | ✅ | 固定值 `BURST` |
| `duration` | Integer | ✅ | 爆发持续秒数（默认 5） |
| `taskId` | String | ✅ | 任务唯一 ID（用于回报结果） |
| `timestamp` | Long | ✅ | 服务器下发时间戳 |

### 广播（全部设备）

发送到通用频道（所有子进程都订阅）：

```
SUBSCRIBE
id:sub-2
destination:/topic/burst/all

\0
```

> 子进程同时订阅两个频道：
> - `/topic/pc/{pcDeviceId}/burst` — 单点发送
> - `/topic/burst/all` — 全部发送

---

## 五、状态上报

子进程连接成功后，**立即上报一次状态**，之后**每 60 秒上报一次**。

### 子进程 → 服务器

通过 STOMP SEND 发送到 `/app/subprocess/status`：

```json
{
    "pcDeviceId": "ABCD-1234-EFGH",
    "username": "user123",
    "clientType": "subprocess",
    "status": "idle",
    "lastBurstMbps": 72.22,
    "lastBurstTime": "2026-04-02 11:11:42",
    "timestamp": 1743580860000
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `pcDeviceId` | String | PC 设备唯一标识 |
| `username` | String | 登录账号 |
| `clientType` | String | `subprocess` 或 `main` |
| `status` | String | `idle`（空闲）/ `bursting`（爆发中）/ `connecting`（连接中） |
| `lastBurstMbps` | Double | 上次爆发速度（Mbps），无则为 0 |
| `lastBurstTime` | String | 上次爆发时间，无则为空 |
| `timestamp` | Long | 当前时间戳 |

> **主程序也应上报状态**（`clientType: "main"`），这样后台能看到设备当前是主进程在线还是子进程在线。

### 爆发结果回报

爆发完成后，通过 STOMP SEND 发送到 `/app/subprocess/burst-result`：

```json
{
    "pcDeviceId": "ABCD-1234-EFGH",
    "username": "user123",
    "taskId": "task_20260402_001",
    "totalMB": 45.27,
    "speedMbps": 72.22,
    "durationSec": 5.0,
    "threads": 48,
    "timestamp": 1743580805000
}
```

---

## 六、总代理后台管理

### 6.1 设备列表页

显示所有已注册的 PC 设备：

| 列 | 说明 |
|----|------|
| 用户名 | 登录账号 |
| PC设备ID | pcDeviceId |
| 当前状态 | 🟢 主进程在线 / 🔵 子进程在线 / ⚫ 离线 |
| 上次爆发 | 速度 + 时间 |
| 操作 | 「立即触发」「定时配置」 |

### 6.2 立即触发（单点 / 全部）

#### 单点发送
```
POST /api/admin/burst/send
Authorization: Bearer {adminToken}

{
    "pcDeviceId": "ABCD-1234-EFGH",
    "duration": 5
}
```

#### 全部发送
```
POST /api/admin/burst/broadcast
Authorization: Bearer {adminToken}

{
    "duration": 5
}
```

### 6.3 定时任务配置

#### 数据模型

```sql
CREATE TABLE burst_schedule (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    name            VARCHAR(100)    NOT NULL COMMENT '任务名称',
    target_type     VARCHAR(20)     NOT NULL COMMENT 'single=单设备, all=全部设备',
    target_device   VARCHAR(100)    NULL     COMMENT '目标 pcDeviceId（target_type=single 时必填）',
    hour_start      INT             NOT NULL COMMENT '开始小时（0-23）',
    hour_end        INT             NOT NULL COMMENT '结束小时（0-23，不含）',
    burst_count     INT             NOT NULL COMMENT '该时间段内触发次数',
    burst_duration  INT             NOT NULL DEFAULT 5 COMMENT '每次爆发秒数',
    enabled         BOOLEAN         NOT NULL DEFAULT TRUE COMMENT '是否启用',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
```

示例数据：

| name | target_type | hour_start | hour_end | burst_count | burst_duration | enabled |
|------|-------------|------------|----------|-------------|----------------|---------|
| 凌晨任务 | all | 0 | 1 | 6 | 5 | ✅ |
| 午夜测试 | single | 23 | 24 | 3 | 5 | ✅ |
| 指定设备白天 | single | 9 | 18 | 10 | 5 | ❌ |

#### 执行逻辑

1. 后端定时任务（每分钟检查一次）扫描 `burst_schedule` 表
2. 当前小时在 `[hour_start, hour_end)` 范围内 且 `enabled = true`
3. 计算该小时内应触发的间隔：`间隔 = 3600 / burst_count` 秒
4. 按间隔均匀触发，通过 WebSocket 发送爆发指令

#### 管理接口

##### 查询定时任务列表
```
GET /api/admin/burst/schedules
Authorization: Bearer {adminToken}

Response:
{
    "code": 0,
    "data": [
        {
            "id": 1,
            "name": "凌晨任务",
            "targetType": "all",
            "targetDevice": null,
            "hourStart": 0,
            "hourEnd": 1,
            "burstCount": 6,
            "burstDuration": 5,
            "enabled": true
        }
    ]
}
```

##### 新增定时任务
```
POST /api/admin/burst/schedules
Authorization: Bearer {adminToken}

{
    "name": "凌晨任务",
    "targetType": "all",
    "hourStart": 0,
    "hourEnd": 1,
    "burstCount": 6,
    "burstDuration": 5,
    "enabled": true
}
```

##### 修改定时任务
```
PUT /api/admin/burst/schedules/{id}
Authorization: Bearer {adminToken}

{
    "name": "凌晨任务-改",
    "burstCount": 10,
    "enabled": false
}
```

##### 删除定时任务
```
DELETE /api/admin/burst/schedules/{id}
Authorization: Bearer {adminToken}
```

---

## 七、完整流程图

```
用户关闭 Phoenix.exe
        │
        ▼
Phoenix 保存凭证 → zjc_auth.json
        │
        ▼
Phoenix 启动 zjc_worker.exe（分离进程）
        │
        ▼
zjc_worker 读取 zjc_auth.json
        │
        ▼
zjc_worker POST /api/auth/login/control (clientType=subprocess)
        │
        ▼
zjc_worker 连接 WebSocket (clientType=subprocess)
        │
        ▼
zjc_worker 订阅:
  ├─ /topic/pc/{pcDeviceId}/burst  （单点指令）
  └─ /topic/burst/all               （广播指令）
        │
        ▼
zjc_worker 上报状态: status=idle
        │
        ▼
  ┌─────────── 等待服务器指令 ───────────┐
  │                                       │
  │  收到 {"action":"BURST","duration":5}│
  │        │                              │
  │        ▼                              │
  │  上报 status=bursting                │
  │        │                              │
  │        ▼                              │
  │  执行爆发（64线程, 5秒）             │
  │        │                              │
  │        ▼                              │
  │  上报 burst-result                   │
  │  上报 status=idle                    │
  │        │                              │
  └────────┘                              │
                                          │
用户打开 Phoenix.exe ─────────────────────┘
        │
        ▼
Phoenix 发送停止信号 → zjc_worker 收到
        │
        ▼
zjc_worker 上报 status=offline
zjc_worker 发送 STOMP DISCONNECT
zjc_worker 退出
        │
        ▼
Phoenix 强杀残留 zjc_worker（兜底）
Phoenix 连接 WebSocket (clientType=main, pcDeviceId=xxx)
Phoenix 上报状态: status=main
```

> **优雅退出**：主进程通过 Named Event (`Local\zjc_worker_stop`) 通知子进程退出。  
> 子进程收到后立刻发送 `status=offline` + STOMP DISCONNECT，后端**立即**感知下线。  
> 如果子进程未响应（卡住），500ms 后主进程仍会 `TerminateProcess` 强杀。

---

## 八、注意事项

1. **互斥**：同一 `pcDeviceId` 的主进程和子进程不会同时在线，服务端无需处理冲突
2. **心跳**：子进程 WebSocket 心跳间隔 10 秒（与主程序一致）
3. **断线重连**：子进程 WebSocket 断线后自动重连（最多 10 次，间隔 5 秒）
4. **Token 过期**：子进程检测到 Token 过期时自动重新登录
5. **日志**：子进程所有操作日志写入 `zjc.txt`（与 exe 同目录）
6. **安全**：`zjc_auth.json` 存储明文密码，仅保存在本地运行目录
7. **在线状态判断**：后端应通过 Redis `pc:online:{pcDeviceId}` 的值（`main` / `subprocess`）来判断当前是主进程还是子进程在线。WebSocket 连接时写入，断开时清除
8. **优雅退出**：主进程启动时先通过 Named Event 通知子进程优雅退出（发 STOMP DISCONNECT），后端可立即感知状态变化，无需等心跳超时