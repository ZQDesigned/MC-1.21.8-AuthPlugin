# AuthPlugin Bot API 接口文档

本文整理了当前 AuthPlugin 中与 Bot 对接相关的 API，包括鉴权方式、接口定义和可直接执行的示例。

## 1. 基础信息

- Web 管理端地址：`http://<服务器IP或域名>:<Web端口>`
- Bot 业务接口统一在：`/api/bot/*`
- 时间字段均为 Unix 毫秒时间戳

## 2. 鉴权模型

系统有两套独立鉴权：

1. 管理员访问令牌（Bearer Access Token）
- 用于管理接口（例如 API Key 管理）
- 通过 `POST /api/auth/login` 获取
- 默认有效期 90 分钟

2. Bot API Key
- 仅用于 `/api/bot/*` 接口
- 推荐放在请求头：`X-API-Key: <api_key>`
- 也支持查询参数：`?api_key=<api_key>`

## 3. Bot 可调用接口

## 3.1 查询在线玩家

`GET /api/bot/players`

用途：
- 返回当前在线玩家列表
- 每项为“玩家名 + 登录状态”
- 不返回任何 token 信息

请求头：
- `X-API-Key: <api_key>`

成功响应（`200`）：

```json
{
  "count": 2,
  "players": [
    "Steve（已登录）",
    "Alex（未登录）"
  ]
}
```

失败响应（`401`）：

```json
{
  "error": "Invalid API key"
}
```

## 3.2 创建单个 Token

`POST /api/bot/tokens`

用途：
- 创建 1 个新 token，并返回明文 token

请求头：
- `X-API-Key: <api_key>`

请求体：
- 无

成功响应（`201`）：

```json
{
  "token": "aB3kLm9Q2xY7tN5p"
}
```

失败响应（`401`）：

```json
{
  "error": "Invalid API key"
}
```

说明：
- 返回 token 为 16 位，字符集 `[A-Za-z0-9]`，且包含大小写字母和数字。

## 4. Bot API Key 管理接口（管理端）

这些接口不是 Bot 业务接口本身，但用于给 Bot 签发和管理 API Key。

## 4.1 管理员登录

`POST /api/auth/login`

请求体：

```json
{
  "username": "<web_username>",
  "password": "<web_password>"
}
```

成功响应（`200`）：

```json
{
  "accessToken": "<bearer_token>",
  "tokenType": "Bearer",
  "expiresIn": 5400,
  "expiresAt": 1760000000000
}
```

失败响应（`401`）：

```json
{
  "error": "Invalid credentials"
}
```

## 4.2 查询 API Key 列表

`GET /api/apikeys`

请求头：
- `Authorization: Bearer <accessToken>`

成功响应（`200`）：

```json
{
  "apiKeys": [
    {
      "name": "qqbot-main",
      "maskedApiKey": "apk_ab...XyZ9",
      "disabled": false,
      "createdAt": 1760000000000,
      "lastUsedAt": 1760000100000
    }
  ]
}
```

## 4.3 创建 API Key

`POST /api/apikeys`

请求头：
- `Authorization: Bearer <accessToken>`

请求体：

```json
{
  "name": "qqbot-main"
}
```

`name` 约束：
- 会被标准化为小写
- 正则：`[A-Za-z0-9_-]{3,48}`

成功响应（`201`）：

```json
{
  "name": "qqbot-main",
  "apiKey": "apk_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
  "createdAt": 1760000000000
}
```

失败响应（`400` 示例）：

```json
{
  "error": "name must match [A-Za-z0-9_-]{3,48}"
}
```

```json
{
  "error": "API key name already exists"
}
```

注意：
- 明文 `apiKey` 只会在创建成功时返回一次，请立即保存。

## 4.4 禁用 / 启用 API Key

禁用：
- `PATCH /api/apikeys/{name}/disable`

启用：
- `PATCH /api/apikeys/{name}/enable`

请求头：
- `Authorization: Bearer <accessToken>`

成功响应（`200`）：

```json
{
  "name": "qqbot-main",
  "disabled": true
}
```

失败响应（`404`）：

```json
{
  "error": "API key name not found"
}
```

## 5. 一套可直接执行的 cURL 示例

```bash
BASE_URL="http://127.0.0.1:8080"
WEB_USER="admin"
WEB_PASS="change_me"

# 1) 管理员登录，拿 Access Token
ACCESS_TOKEN=$(curl -sS -X POST "$BASE_URL/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"$WEB_USER\",\"password\":\"$WEB_PASS\"}" \
  | jq -r '.accessToken')

# 2) 创建 Bot API Key
BOT_API_KEY=$(curl -sS -X POST "$BASE_URL/api/apikeys" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"qqbot-main"}' \
  | jq -r '.apiKey')

# 3) 查询在线玩家（Bot 接口）
curl -sS "$BASE_URL/api/bot/players" \
  -H "X-API-Key: $BOT_API_KEY"

# 4) 创建一个 token（Bot 接口）
curl -sS -X POST "$BASE_URL/api/bot/tokens" \
  -H "X-API-Key: $BOT_API_KEY"
```

## 6. 常见状态码

- `200`：请求成功
- `201`：创建成功
- `400`：请求参数或请求体不合法
- `401`：鉴权失败（Access Token 无效/过期，或 API Key 无效）
- `404`：目标资源不存在（例如 API Key 名称不存在）
- `500`：服务端内部错误
