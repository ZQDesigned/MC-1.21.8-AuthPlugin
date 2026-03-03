# AuthPlugin 多加载器授权模组开发任务说明

## 项目背景

正在开发一个 Minecraft 多加载器服务端授权模组：

-   名称：AuthPlugin
-   基于 Architectury 模板
-   支持：Fabric / NeoForge（如包含 Forge 也需支持）
-   Minecraft 版本：1.21.x
-   JDK：21
-   使用 Mixins
-   使用 Architectury API
-   仅作为服务端功能模组

该模组用于实现基于"令牌 Token"的玩家授权系统，并提供内嵌 Web 管理面板。

------------------------------------------------------------------------

# 一、总体目标

实现一个服务端授权系统，具备：

1.  玩家必须通过 `/login <token>` 登录
2.  未登录玩家：
    -   禁止移动
    -   禁止交互
    -   禁止破坏/放置
    -   禁止攻击
    -   禁止使用除 `/login` 之外的命令
3.  成功登录后：
    -   自动绑定 Token 与 UUID
    -   后续进服自动登录
4.  内嵌 Web 管理面板
    -   查看在线玩家及授权状态
    -   查看所有 Token 状态
    -   添加/删除/禁用 Token
    -   账号密码登录
5.  首次启动：
    -   自动生成随机管理账号、密码、端口
    -   写入配置文件
    -   后续修改必须改配置文件，不提供接口

------------------------------------------------------------------------

# 二、架构要求

## 必须采用多加载器标准结构

### common 模块

放所有核心逻辑：

-   AuthService
-   TokenRepository
-   SessionManager
-   ConfigManager
-   WebAdminServer
-   DTO / Model
-   业务逻辑

不得在 common 中直接引用 Fabric/Forge API。

------------------------------------------------------------------------

### platform 层（fabric / neoforge）

仅负责：

-   注册命令
-   监听事件
-   调用 common 的接口
-   实现 PlatformHooks

不得在 platform 中写业务逻辑。

------------------------------------------------------------------------

## 平台抽象接口

``` java
public interface PlatformHooks {
    void registerLoginCommand();
    void registerEventListeners();
    void sendMessage(UUID player, String message);
    void kick(UUID player, String reason);
    boolean isPlayerOnline(UUID uuid);
}
```

------------------------------------------------------------------------

# 三、登录系统设计

## Token 数据模型

``` java
class TokenInfo {
    String token;
    UUID boundPlayer;
    boolean disabled;
    long createdAt;
    long lastUsedAt;
}
```

Token 状态： - 未绑定 - 已绑定 - 已禁用

------------------------------------------------------------------------

## 登录逻辑

### /login `<token>`{=html}

校验规则：

-   token 不存在 → 拒绝
-   token 已禁用 → 拒绝
-   token 已绑定他人 UUID → 拒绝
-   token 未绑定 → 绑定当前 UUID
-   token 已绑定当前 UUID → 允许登录

成功后： - 记录 session - 更新 lastUsedAt

------------------------------------------------------------------------

## 自动登录

玩家 join 时：

-   若 UUID 已绑定有效 token
-   自动标记已登录

------------------------------------------------------------------------

## 未登录限制

必须实现：

-   禁止移动（强制回滚或 cancel 事件）
-   禁止交互
-   禁止攻击
-   禁止破坏放置
-   禁止除 /login 外命令

必须避免死循环卡死。

------------------------------------------------------------------------

# 四、Web 管理面板设计

## 使用轻量内嵌 Web 服务

推荐：

-   Javalin + Jetty
-   必须可 shade
-   必须 relocate 包名避免冲突

------------------------------------------------------------------------

## Web 配置文件结构

``` yaml
web:
  port: 18333
  username: generated_user
  password: generated_password_plaintext
```

首次启动自动生成随机：

-   username
-   password（明文存储）
-   port

写入配置文件。

------------------------------------------------------------------------

## Web 功能 API

GET /api/players\
返回在线玩家及登录状态

GET /api/tokens\
返回所有 token 状态

POST /api/tokens\
添加 token

DELETE /api/tokens/{token}\
删除 token

PATCH /api/tokens/{token}/disable\
禁用 token

------------------------------------------------------------------------

## Web 鉴权

-   使用 Session 或 Basic Auth
-   密码以明文形式存储在配置文件中
-   不提供在线修改接口

------------------------------------------------------------------------

# 五、数据存储

见另一个文档：AuthPlugin_Data_Storage_H2_Spec.md

------------------------------------------------------------------------

# 六、安全与运行要求

1.  Token 操作必须同步
2.  Web 服务异常不得影响 MC 主线程
3.  所有 IO 操作使用独立线程池
4.  禁止在 Tick 中执行阻塞操作

------------------------------------------------------------------------

# 七、Gradle 要求

1.  所有 Web 依赖必须 shade
2.  必须 relocate 包名
3.  构建产物：
    -   fabric jar
    -   neoforge jar
    -   forge jar（如存在）

------------------------------------------------------------------------

# 八、日志要求

必须打印：

-   首次生成账号密码
-   Web 启动端口
-   Token 绑定记录
-   登录失败原因

------------------------------------------------------------------------

# 九、开发顺序

1.  实现 ConfigManager
2.  实现 TokenRepository
3.  实现 AuthService
4.  实现 SessionManager
5.  实现 WebAdminServer
6.  实现 PlatformHooks
7.  实现 Fabric 端
8.  实现 NeoForge 端
9.  测试登录限制闭环
10. 添加构建 shade 配置

------------------------------------------------------------------------

# 十、代码风格要求

-   分层清晰
-   不允许 God Object
-   业务逻辑与平台层完全分离
-   不允许硬编码路径
-   使用 Optional 避免 NPE

------------------------------------------------------------------------

# 十一、未来扩展预留接口

-   IP 绑定功能
-   Token 过期时间
-   外部数据库支持
-   Web API Key 鉴权模式

------------------------------------------------------------------------

# 十二、最终验收标准

1.  三端均可启动
2.  未登录玩家无法移动
3.  正确 token 可登录
4.  绑定后自动登录
5.  Web 面板可管理 token
6.  删除 token 后玩家无法再自动登录
7.  禁用 token 立即失效
8.  所有数据重启后保留
