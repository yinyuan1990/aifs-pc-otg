# 用户注册接口文档

本文档详细说明了AI设备控制系统的用户注册相关API接口。

## 目录
- [1. 设备端注册](#1-设备端注册)
- [2. 控制端注册](#2-控制端注册)
- [3. 用户登录](#3-用户登录)
- [4. 重置二级密码](#4-重置二级密码)
- [5. 获取默认密保问题](#5-获取默认密保问题)

---

## 1. 设备端注册

### 接口信息
- **URL**: `/api/auth/register/device`
- **方法**: `POST`
- **Content-Type**: `application/json`
- **说明**: 设备端用户注册，需要提供设备ID、用户名、密码、二级密码和三个密保问题及答案

### 请求参数

| 参数名 | 类型 | 必填 | 长度限制 | 说明 |
|--------|------|------|----------|------|
| username | String | 是 | 6位 | 用户名，必须是6位字母或数字组合，全局唯一 |
| deviceId | String | 是 | 最多64位 | 设备唯一标识（iOS可用IDFV），由前端提供 |
| password | String | 是 | 6-20位 | 登录密码 |
| secondaryPassword | String | 是 | 6-20位 | 二级密码，用于设备绑定验证 |
| securityQuestion1 | String | 是 | 最多255位 | 密保问题1 |
| securityAnswer1 | String | 是 | 最多255位 | 密保问题1的答案 |
| securityQuestion2 | String | 是 | 最多255位 | 密保问题2 |
| securityAnswer2 | String | 是 | 最多255位 | 密保问题2的答案 |
| securityQuestion3 | String | 是 | 最多255位 | 密保问题3 |
| securityAnswer3 | String | 是 | 最多255位 | 密保问题3的答案 |

### 请求示例

```json
{
  "username": "dev001",
  "deviceId": "ABC123-DEF456-GHI789",
  "password": "password123",
  "secondaryPassword": "secpass456",
  "securityQuestion1": "您的出生年月日是？",
  "securityAnswer1": "19900101",
  "securityQuestion2": "您的老家是哪里？",
  "securityAnswer2": "北京",
  "securityQuestion3": "您最喜欢干的事是？",
  "securityAnswer3": "编程"
}
```

### 响应示例

**成功响应 (200 OK)**:
```json
{
  "username": "dev001",
  "deviceId": "ABC123-DEF456-GHI789",
  "message": "设备端注册成功"
}
```

**失败响应 (400 Bad Request)**:
```json
{
  "error": "用户名已存在"
}
```

或

```json
{
  "error": "设备ID已注册，一个设备只能注册一个设备端账号"
}
```

### iOS Swift 示例代码

```swift
struct DeviceRegisterRequest: Codable {
    let username: String
    let deviceId: String
    let password: String
    let secondaryPassword: String
    let securityQuestion1: String
    let securityAnswer1: String
    let securityQuestion2: String
    let securityAnswer2: String
    let securityQuestion3: String
    let securityAnswer3: String
}

func registerDevice(username: String, deviceId: String, password: String, secondaryPassword: String,
                   q1: String, a1: String, q2: String, a2: String, q3: String, a3: String) {
    let url = URL(string: "http://your-server.com/api/auth/register/device")!
    var request = URLRequest(url: url)
    request.httpMethod = "POST"
    request.setValue("application/json", forHTTPHeaderField: "Content-Type")
    
    let requestBody = DeviceRegisterRequest(
        username: username,
        deviceId: deviceId,
        password: password,
        secondaryPassword: secondaryPassword,
        securityQuestion1: q1,
        securityAnswer1: a1,
        securityQuestion2: q2,
        securityAnswer2: a2,
        securityQuestion3: q3,
        securityAnswer3: a3
    )
    
    request.httpBody = try? JSONEncoder().encode(requestBody)
    
    URLSession.shared.dataTask(with: request) { data, response, error in
        if let data = data {
            // 处理响应
            print(String(data: data, encoding: .utf8) ?? "")
        }
    }.resume()
}

// 获取iOS设备ID (IDFV)
let deviceId = UIDevice.current.identifierForVendor?.uuidString ?? ""
```

---

## 2. 控制端注册

### 接口信息
- **URL**: `/api/auth/register/control`
- **方法**: `POST`
- **Content-Type**: `application/json`
- **说明**: 控制端用户注册，只需要用户名和密码

### 请求参数

| 参数名 | 类型 | 必填 | 长度限制 | 说明 |
|--------|------|------|----------|------|
| username | String | 是 | 6位 | 用户名，必须是6位字母或数字组合，全局唯一 |
| password | String | 是 | 6-20位 | 登录密码 |

### 请求示例

```json
{
  "username": "ctrl01",
  "password": "password123"
}
```

### 响应示例

**成功响应 (200 OK)**:
```json
{
  "username": "ctrl01",
  "message": "控制端注册成功"
}
```

**失败响应 (400 Bad Request)**:
```json
{
  "error": "用户名已存在"
}
```

### JavaScript 示例代码

```javascript
async function registerControl(username, password) {
  const response = await fetch('http://your-server.com/api/auth/register/control', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({
      username: username,
      password: password
    })
  });
  
  const data = await response.json();
  
  if (response.ok) {
    console.log('注册成功:', data.message);
    return data;
  } else {
    console.error('注册失败:', data.error);
    throw new Error(data.error);
  }
}

// 使用示例
registerControl('ctrl01', 'password123')
  .then(data => console.log(data))
  .catch(error => console.error(error));
```

---

## 3. 用户登录

### 接口信息
- **URL**: `/api/auth/login`
- **方法**: `POST`
- **Content-Type**: `application/json`
- **说明**: 设备端和控制端使用同一个登录接口

### 请求参数

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| username | String | 是 | 用户名（6位） |
| password | String | 是 | 登录密码 |

### 请求示例

```json
{
  "username": "dev001",
  "password": "password123"
}
```

### 响应示例

**成功响应 (200 OK)**:
```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "username": "dev001",
  "userType": "采集端",
  "deviceId": "ABC123-DEF456-GHI789",
  "permanentToken": "perm-token-abc123",
  "membershipType": "试用版",
  "status": "活跃",
  "message": "登录成功"
}
```

**失败响应 (400 Bad Request)**:
```json
{
  "error": "用户名或密码错误"
}
```

### 注意事项
- 登录成功后会返回JWT令牌（`token`），请将其保存在本地
- 后续所有需要认证的API请求都需要在请求头中携带此令牌：`Authorization: Bearer <token>`
- `userType` 可能的值：
  - `"采集端"` - 设备端用户
  - `"控制端"` - 控制端用户
- `permanentToken` 是永久令牌，可用于长期保持登录状态

---

## 4. 重置二级密码

### 接口信息
- **URL**: `/api/auth/reset-secondary-password`
- **方法**: `POST`
- **Content-Type**: `application/json`
- **说明**: 设备端用户通过回答密保问题来重置二级密码（只有设备端用户才有二级密码）

### 请求参数

| 参数名 | 类型 | 必填 | 长度限制 | 说明 |
|--------|------|------|----------|------|
| username | String | 是 | 6位 | 设备端用户名 |
| newSecondaryPassword | String | 是 | 6-20位 | 新的二级密码 |
| securityAnswer1 | String | 是 | - | 密保问题1的答案 |
| securityAnswer2 | String | 是 | - | 密保问题2的答案 |
| securityAnswer3 | String | 是 | - | 密保问题3的答案 |

### 请求示例

```json
{
  "username": "dev001",
  "newSecondaryPassword": "newsecpass789",
  "securityAnswer1": "19900101",
  "securityAnswer2": "北京",
  "securityAnswer3": "编程"
}
```

### 响应示例

**成功响应 (200 OK)**:
```json
{
  "message": "二级密码重置成功"
}
```

**失败响应 (400 Bad Request)**:
```json
{
  "error": "用户不存在"
}
```

或

```json
{
  "error": "只有设备端用户才能重置二级密码"
}
```

或

```json
{
  "error": "密保问题答案不正确"
}
```

### iOS Swift 示例代码

```swift
struct ResetSecondaryPasswordRequest: Codable {
    let username: String
    let newSecondaryPassword: String
    let securityAnswer1: String
    let securityAnswer2: String
    let securityAnswer3: String
}

func resetSecondaryPassword(username: String, newPassword: String, 
                           answer1: String, answer2: String, answer3: String) {
    let url = URL(string: "http://your-server.com/api/auth/reset-secondary-password")!
    var request = URLRequest(url: url)
    request.httpMethod = "POST"
    request.setValue("application/json", forHTTPHeaderField: "Content-Type")
    
    let requestBody = ResetSecondaryPasswordRequest(
        username: username,
        newSecondaryPassword: newPassword,
        securityAnswer1: answer1,
        securityAnswer2: answer2,
        securityAnswer3: answer3
    )
    
    request.httpBody = try? JSONEncoder().encode(requestBody)
    
    URLSession.shared.dataTask(with: request) { data, response, error in
        if let data = data {
            print(String(data: data, encoding: .utf8) ?? "")
        }
    }.resume()
}
```

---

## 5. 获取默认密保问题

### 接口信息
- **URL**: `/api/config/security_question_1` (问题1)
- **URL**: `/api/config/security_question_2` (问题2)
- **URL**: `/api/config/security_question_3` (问题3)
- **方法**: `GET`
- **说明**: 获取系统配置的默认密保问题，可在注册界面展示供用户选择

### 请求示例

```bash
GET /api/config/security_question_1
```

### 响应示例

```json
{
  "id": 2,
  "configKey": "security_question_1",
  "configValue": "您的出生年月日是？",
  "description": "密保问题1"
}
```

### JavaScript 示例代码

```javascript
async function getDefaultSecurityQuestions() {
  const questions = [];
  
  for (let i = 1; i <= 3; i++) {
    const response = await fetch(`http://your-server.com/api/config/security_question_${i}`);
    const data = await response.json();
    questions.push(data.configValue);
  }
  
  return questions;
}

// 使用示例
getDefaultSecurityQuestions()
  .then(questions => {
    console.log('默认密保问题:', questions);
    // ["您的出生年月日是？", "您的老家是哪里？", "您最喜欢干的事是？"]
  });
```

---

## 常见错误码

| HTTP状态码 | 错误信息 | 说明 |
|-----------|---------|------|
| 400 | 用户名已存在 | 用户名重复，需要更换用户名 |
| 400 | 设备ID已注册，一个设备只能注册一个设备端账号 | 该设备已注册过 |
| 400 | 用户名必须是6位字母或数字 | 用户名格式不正确 |
| 400 | 密码长度必须在6到20位之间 | 密码长度不符合要求 |
| 400 | 设备ID不能为空 | 设备端注册时必须提供deviceId |
| 400 | 用户不存在 | 用户名不存在 |
| 400 | 密保问题答案不正确 | 重置二级密码时答案错误 |
| 400 | 只有设备端用户才能重置二级密码 | 控制端用户没有二级密码 |

---

## 注册流程说明

### 设备端注册流程

1. **获取设备ID**
   - iOS: 使用 `UIDevice.current.identifierForVendor?.uuidString`
   - Android: 使用 `Settings.Secure.ANDROID_ID`

2. **用户输入信息**
   - 用户手动输入6位用户名（字母或数字组合）
   - 输入登录密码（6-20位）
   - 输入二级密码（6-20位）
   - 选择或输入3个密保问题及答案

3. **提交注册请求**
   - 调用 `/api/auth/register/device` 接口
   - 等待服务器响应

4. **注册成功**
   - 保存用户名和deviceId
   - 跳转到登录界面或自动登录

### 控制端注册流程

1. **用户输入信息**
   - 用户手动输入6位用户名（字母或数字组合）
   - 输入登录密码（6-20位）

2. **提交注册请求**
   - 调用 `/api/auth/register/control` 接口
   - 等待服务器响应

3. **注册成功**
   - 保存用户名
   - 跳转到登录界面或自动登录

---

## 用户名规则

- **长度**: 必须是6位
- **字符**: 只能包含字母（a-z, A-Z）和数字（0-9）
- **唯一性**: 全局唯一，设备端和控制端共享用户名空间
- **示例**:
  - ✅ `abc123`
  - ✅ `USER01`
  - ✅ `123456`
  - ❌ `abc12` (少于6位)
  - ❌ `abc1234` (多于6位)
  - ❌ `abc_12` (包含特殊字符)
  - ❌ `中文12` (包含中文)

---

## 密码规则

### 登录密码
- **长度**: 6-20位
- **字符**: 无特殊限制，建议包含字母和数字
- **用途**: 用于用户登录系统

### 二级密码（仅设备端）
- **长度**: 6-20位
- **字符**: 无特殊限制
- **用途**: 用于设备绑定时的验证，提供额外的安全保护
- **找回**: 可通过3个密保问题找回

---

## 安全建议

1. **密码加密**: 所有密码在传输前应使用HTTPS加密
2. **用户名唯一性**: 注册前可先调用一个检查接口验证用户名是否已存在（如果需要）
3. **设备ID安全**: deviceId是敏感信息，不应暴露给其他用户
4. **二级密码**: 建议与登录密码设置不同，增强安全性
5. **密保答案**: 建议在前端对密保答案进行规范化处理（如去除空格、统一大小写）

---

## 测试示例

### Bash 测试脚本

```bash
#!/bin/bash

API_BASE="http://localhost:8080/api"

echo "=== 1. 设备端注册 ==="
curl -X POST "$API_BASE/auth/register/device" \
  -H "Content-Type: application/json" \
  -d '{
    "username": "dev001",
    "deviceId": "TEST-DEVICE-001",
    "password": "pass123",
    "secondaryPassword": "secpass456",
    "securityQuestion1": "您的出生年月日是？",
    "securityAnswer1": "19900101",
    "securityQuestion2": "您的老家是哪里？",
    "securityAnswer2": "北京",
    "securityQuestion3": "您最喜欢干的事是？",
    "securityAnswer3": "编程"
  }'

echo -e "\n\n=== 2. 控制端注册 ==="
curl -X POST "$API_BASE/auth/register/control" \
  -H "Content-Type: application/json" \
  -d '{
    "username": "ctrl01",
    "password": "pass123"
  }'

echo -e "\n\n=== 3. 设备端登录 ==="
DEVICE_TOKEN=$(curl -s -X POST "$API_BASE/auth/login" \
  -H "Content-Type: application/json" \
  -d '{
    "username": "dev001",
    "password": "pass123"
  }' | jq -r '.token')

echo "Device Token: $DEVICE_TOKEN"

echo -e "\n\n=== 4. 控制端登录 ==="
CONTROL_TOKEN=$(curl -s -X POST "$API_BASE/auth/login" \
  -H "Content-Type: application/json" \
  -d '{
    "username": "ctrl01",
    "password": "pass123"
  }' | jq -r '.token')

echo "Control Token: $CONTROL_TOKEN"

echo -e "\n\n=== 5. 重置二级密码 ==="
curl -X POST "$API_BASE/auth/reset-secondary-password" \
  -H "Content-Type: application/json" \
  -d '{
    "username": "dev001",
    "newSecondaryPassword": "newsecpass789",
    "securityAnswer1": "19900101",
    "securityAnswer2": "北京",
    "securityAnswer3": "编程"
  }'

echo -e "\n\n测试完成！"
```

---

## 相关文档

- [设备绑定API文档](./API_BINDING_GUIDE.md)
- [绑定流程详解](./BINDING_FLOW_FINAL.md)
- [设备ID获取指南](./DEVICE_ID_GUIDE.md)
- [项目总览](./README.md)

---

## 版本历史

- **v1.0** (2025-11-25): 初始版本
  - 设备端和控制端分离注册
  - 手动输入6位用户名
  - 设备端需要二级密码和密保问题
  - 控制端简化注册流程

---

## 附录：控制端绑定相关接口

### 1. 控制端查询待验证的绑定

**URL**: `/api/binding/pending`  
**方法**: `GET`  
**说明**: 控制端在显示二维码后，调用此接口查询是否有设备端已扫码并验证的绑定，从而获取 `bindingId`

**请求头**:
```
Authorization: Bearer {control_token}
```

**成功响应**（有待验证的绑定）：
```json
{
  "bindings": [
    {
      "bindingId": 1,
      "deviceUsername": "abc123",
      "deviceId": "9F0B44DC-76A5-433B-8DB8-D3187E05A459",
      "deviceVerified": true,
      "controlVerified": false,
      "createdAt": "2025-11-25T10:30:00",
      "deviceVerifyTime": "2025-11-25T10:30:15"
    }
  ],
  "count": 1
}
```

**成功响应**（无待验证的绑定）：
```json
{
  "bindings": [],
  "count": 0
}
```

**使用建议**：
- 控制端可以定期轮询此接口（如每3秒一次）
- 也可以提供一个"刷新"按钮让用户手动查询
- 当 `count > 0` 时，获取第一个绑定的 `bindingId`，提示用户验证

---

### 2. 控制端验证接口

**URL**: `/api/binding/verify-control`

**成功响应**（绑定完成）：
```json
{
  "success": true,
  "bindingId": 1,
  "deviceId": "9F0B44DC-76A5-433B-8DB8-D3187E05A459",
  "deviceVerified": true,
  "controlVerified": true,
  "status": "ACTIVE",
  "message": "绑定成功！",
  "bindCompleteTime": "2025-11-25T13:45:30"
}
```

**失败响应**（设备端未验证）：
```json
{
  "error": "请先让设备端确认绑定"
}
```

**失败响应**（二级密码错误）：
```json
{
  "error": "设备端二级密码错误"
}
```

### 字段说明

| 字段 | 类型 | 说明 |
|------|------|------|
| `success` | Boolean | 验证是否成功 |
| `bindingId` | Long | 绑定记录ID |
| `deviceId` | String | iOS设备ID（绑定成功后返回） |
| `deviceVerified` | Boolean | 设备端是否已验证 |
| `controlVerified` | Boolean | 控制端是否已验证 |
| `status` | String | 绑定状态（ACTIVE=已激活） |
| `message` | String | 提示信息 |
| `bindCompleteTime` | String | 绑定完成时间 |

### ⚠️ 重要提示

1. **验证顺序**：必须设备端先验证，控制端才能验证
2. **设备ID**：控制端验证成功后，响应中包含设备ID
3. **自动更新**：绑定成功后，控制端用户的 `device_id` 字段会被自动更新为设备端的 `device_id`

---

如有问题或建议，请联系开发团队。

