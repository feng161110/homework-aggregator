# 作业信息聚合系统（homework-aggregator）

把**学习通 / 雨课堂 / 智慧树**三个平台的课程与作业汇总到一个界面：绑定一次平台账号，之后可以统一查看课程、作业列表，并配置**定时提醒**（邮件 / 站内）。

前端 Vue 3 SPA，业务后端 Spring Boot，统一入口是 Spring Cloud Gateway，三个平台的差异被封装成三个独立的 Python FastAPI 服务。

---

## 一、系统架构

```
浏览器
  │  http://localhost:5173        Vite dev / preview（或 frontend/legacy/server.js 旧版）
  ▼
Vite 代理 /api、/uploads          VITE_BACKEND 默认 http://localhost:8081
  │
  ├──────────────► 8080  gateway（Spring Cloud Gateway，唯一对外入口）
  │                  ├─ /api/zhs/**   ──StripPrefix=2──► 2001  api/zhs（智慧树 FastAPI）
  │                  ├─ /api/ykt/**   ──StripPrefix=2──► 2002  api/ykt（雨课堂 FastAPI）
  │                  ├─ /api/xxt/**   ──StripPrefix=2──► 2003  api/xxt（学习通 FastAPI）
  │                  ├─ /api/**       ───────────────► 8081  Student（Spring Boot）
  │                  └─ /uploads/**   ───────────────► 8081  Student 静态头像
  │
  ▼
8081  Student ──服务端直连（不经网关）──► 2001 / 2002 / 2003
  │
  ▼
3306  MySQL 8.0（docker-compose）
```

要点：

- **前端只认 `/api` 和 `/uploads` 两个前缀**，不知道任何端口，也不会跨域。
- **网关不是必须的**：本地只跑「MySQL + Student + 前端」即可开发；代价是首次「用账号密码绑定平台」（走 `/api/zhs|ykt|xxt`）会 404，已绑定平台的课程 / 作业查看不受影响。要完整链路请启动网关并 `VITE_BACKEND=http://localhost:8080`。
- **Student 谈平台服务时直连 2001-2003**（`app.platform.*-base`），只有浏览器侧才经过网关，所以网关路由顺序上 `/api/zhs|ykt|xxt` 必须排在 `/api/**` 之前。

---

## 二、技术栈

| 层 | 目录 | 技术 |
|---|---|---|
| 前端 | `frontend/` | Vue 3.4 + vue-router 4.4 + Vite 5（无 Pinia、无 UI 框架，组件与样式自写） |
| 网关 | `gateway/` | Spring Cloud Gateway 2023.0.3（WebFlux 响应式，仅有配置转发，无自定义过滤器） |
| 业务后端 | `Student/` | Spring Boot 3.2.12 / Java 17、Spring Security + JWT（jjwt 0.12.6）、Spring Data JPA、Spring Mail |
| 平台适配 | `api/zhs`、`api/ykt`、`api/xxt` | Python + FastAPI + uvicorn（校验码识别用 onnxruntime / opencv / captcha-recognizer） |
| 数据库 | `docker-compose.yml` | MySQL 8.0（`tt_project` 库，JPA `ddl-auto: update` 自动建表） |

---

## 三、目录结构

```
.
├── pom.xml                  Maven 聚合父 POM（modules: gateway、Student；Java 17、Spring Boot 3.2.12）
├── docker-compose.yml       只起一个 MySQL 8.0（student-mysql，库 tt_project，端口 3306）
├── gateway/                 Spring Cloud Gateway（8080）
│   └── src/main/resources/application.yml    路由表全部在这里
├── Student/                 Spring Boot 业务后端（8081）
│   └── src/main/java/com/xiaofu/student/
│       ├── controller/      AuthController、PlatformController、TaskController、AdminController
│       ├── service/         AuthService、PlatformAccountService、PlatformClient、TaskService、
│       │                    ReminderScheduler、MailService、LoginAttemptService、RepeatRuleService…
│       ├── security/        JWT 过滤器、SecurityConfig、登录参数解密（RSA + RC4）
│       ├── scheduler/       ReminderScheduler 定时扫描
│       ├── entity/          users、platform_accounts、scheduled_tasks、task_logs、
│       │                    refresh_tokens、email_verification_codes、
│       │                    password_reset_codes、password_reset_tokens
│       ├── repository/ dto/ common/ config/ util/
│       └── StudentApplication.java
├── api/
│   ├── zhs/                 智慧树 FastAPI（2001）：login_api.py、Zhihuishu.py、captcha/
│   ├── ykt/                 雨课堂 FastAPI（2002）：ykt_api.py、crack_tcaptcha/（本地包）
│   └── xxt/                 学习通 FastAPI（2003）：login_api.py、script.user.js（油猴助手脚本）
└── frontend/                Vite + Vue 3 SPA（5173）
    ├── index.html           /chunk.a.js（RSA）、/chunk.b.js（RC4）先加载，再加载 src/main.js
    ├── vite.config.js       5173 固定端口；代理 /api、/uploads；还负责对外提供 script.user.js
    ├── public/              chunk.a.js、chunk.b.js（登录参数加密脚本）
    ├── src/
    │   ├── views/           Login/Register/Verify/Forgot/Reset*/ChangePassword/Home/Platform/
    │   │                    Courses/Homework/Reminder/Profile/Devices/Admin/Apps
    │   ├── router/          history 模式 + 登录/游客/管理员守卫
    │   ├── api/             http.js、auth.js、platform.js、reminder.js、admin.js
    │   ├── components/      CourseCard、HomeworkCard、FacetFilter、PlatformCard、
    │   │                    platform/{CredentialPanel,HelperInstallSteps,XxtPanel}…
    │   ├── stores/ ui/ utils/ styles/
    │   └── main.js App.vue
    └── legacy/              旧版无框架前端（index.html + js/app.js + 零依赖 server.js，同样 5173）
```

---

## 四、端口约定

| 端口 | 服务 | 说明 |
|---|---|---|
| 5173 | 前端（Vite / legacy server） | 也出现在 Student 的 CORS 白名单与重置邮件链接里 |
| 8080 | gateway | 对外唯一入口 |
| 8081 | Student | 只被网关访问，不直接暴露 |
| 2001 | api/zhs | 智慧树登录 / 课程 / 作业提醒 |
| 2002 | api/ykt | 雨课堂登录 / 课程 |
| 2003 | api/xxt | 学习通登录 / 课程 / 油猴脚本回调 |
| 3306 | MySQL | docker-compose 提供 |

---

## 五、快速开始

### 0. 前置环境

- JDK 17、Maven 3.9+
- Node.js 18+
- Python 3.10+
- Docker（或本机自装 MySQL 8.0）

### 1. 启动 MySQL

```bash
docker compose up -d
```

库 `tt_project`、账号 `feng / 123456`、root 密码 `root123456`，与 `Student/src/main/resources/application.yml` 默认值一致，无需改配置。

### 2. 启动三个平台服务（按需启动，也可以只起自己用得到的平台）

```bash
# 智慧树 2001
cd api/zhs && pip install -r requirements.txt && python login_api.py

# 雨课堂 2002
cd api/ykt && pip install -r requirements.txt && python ykt_api.py

# 学习通 2003
cd api/xxt && pip install -r requirements.txt && python login_api.py
```

`api/ykt` 的验证码轨迹求解需要 Node + jsdom，详见 `api/ykt/crack_tcaptcha/` 内的说明。

### 3. 启动后端（8081）

```bash
mvn -pl Student -am spring-boot:run
```

表结构由 JPA 自动创建，首次启动会自动建表。

### 4. 启动网关（可选，8080）

只要用到「用账号密码首次绑定平台」，就需要它：

```bash
mvn -pl gateway -am spring-boot:run
```

### 5. 启动前端

```bash
cd frontend
npm install
npm run dev
```

打开 http://localhost:5173 。

- 只跑了后端（8081）：直接 `npm run dev`，网关相关接口不可用。
- 全套链路（8080）：`$env:VITE_BACKEND='http://localhost:8080'; npm run dev`（PowerShell）。

生产构建：`npm run build` → `frontend/dist/`（该目录不进版本库）。

---

## 六、配置与环境变量

开发用默认值即可跑起来，默认值都写在 `Student/src/main/resources/application.yml`；生产环境用 `SPRING_PROFILES_ACTIVE=prod` 加载 `application-prod.yml`，此时下列变量**没有默认值、必须显式提供**：

| 变量 | 作用 |
|---|---|
| `DB_HOST` `DB_PORT` `DB_NAME` `DB_USERNAME` `DB_PASSWORD` | MySQL 连接 |
| `JWT_SECRET` | JWT 签名密钥（HS256，至少 32 字节） |
| `LOGIN_ACCOUNT_KEY` | 登录账号 RC4 密钥，必须与前端 `public/chunk.b.js` 中的 KEY 一致 |
| `LOGIN_RSA_PRIVATE_KEY` | 登录密码 RSA 私钥（PKCS#8 Base64），与 `public/chunk.a.js` 内嵌公钥配对 |
| `PLATFORM_SECRET_KEY` | 平台密码 AES-256-GCM 密钥（换掉后已入库的平台密码无法解密，用户需重新绑定） |
| `MAIL_HOST` `MAIL_PORT` `MAIL_USERNAME` `MAIL_PASSWORD` | SMTP；`MAIL_PASSWORD` 是邮箱**授权码**而非登录密码 |
| `PLATFORM_ZHIHUISHU_BASE` / `PLATFORM_YUKETANG_BASE` / `PLATFORM_XUEXITONG_BASE` | 三个平台服务地址（默认 2001/2002/2003） |
| `REMIND_ZONE` | 定时任务时区基准，默认 `Asia/Shanghai`，需与 JDBC `serverTimezone` 一致，否则提醒时间差 8 小时 |
| `SERVER_PORT` | 覆盖 8080 / 8081 |
| `ZHIHUISHU_BASE` `YUKETANG_BASE` `XUEXITONG_BASE` `BACKEND_BASE` | 网关侧路由目标 |
| `VITE_BACKEND` `VITE_API_BASE` | 前端代理目标 / 接口前缀（后者也支持运行时 `window.__API_BASE__`） |

其他可调参数：邮箱验证码 5 分钟 / 5 次、重置链接 30 分钟、连续登录失败 5 次锁定 15 分钟、同邮箱 60 秒限发一封且 24 小时内最多 5 封、登录参数时间窗 10 秒、头像上限 2MB（存 `uploads/`，映射到 `/uploads/**`）。

---

## 七、主要接口

所有业务接口在网关下统一前缀 `/api`。

**认证 `AuthController`（`/api/auth`）**
`POST register`、`verify-email`、`resend-verification`、`login`、`refresh`、`logout`、`forgot-password`、`forgot-password/code`、`reset-password`、`reset-password/code`、`change-password`、`avatar`、`devices/revoke-others`；`GET me`、`devices`、`reset-password/check`；`DELETE devices/{id}`

**平台账号 `PlatformController`（`/api/platform`）**
`GET/POST /{platform}`、`PUT /{platform}/cookies`、`GET /{platform}/secret`、`POST /{platform}/check`、`POST /{platform}/refresh`、`GET /{platform}/desktop-links`、`GET /{platform}/courses`、`DELETE /{platform}`（`platform` 取 `zhihuishu` / `yuketang` / `xuexitong`）

**提醒任务 `TaskController`（`/api/task`）**
`GET ?action=list|detail|stats`；`POST`（`body.action` = `create` / `execute` / `cancel` / `enable` / `update` / `delete` / `restore`）

**管理 `AdminController`（`/api/admin`）**
`GET users`、`GET users/{id}`、`POST users/{id}/disable|enable`、`POST users/{id}/sessions/revoke`、`DELETE sessions/{id}`

**平台服务（经网关转发后剥掉 `/api/xx`）**

| 服务 | 端点 |
|---|---|
| 智慧树 2001 | `GET /`；`POST /api/login/password`、`/api/login/cookie`、`/api/course/list`、`/api/homework/reminder` |
| 雨课堂 2002 | `GET /`；`POST /api/login`、`/api/check_login`、`/api/courses` |
| 学习通 2003 | `GET /`；`POST /login`、`/check_cookie`、`/desktop_links`、`/courses` |

---

## 八、认证与安全设计

- 无状态 JWT：access token 30 分钟、refresh token 7 天；`SecurityConfig` 白名单放行注册/登录/找回密码等接口，其余 `authenticated()`；`/api/admin/**` 额外校验 `isAdmin`。
- 登录参数加密：前端 `chunk.a.js`（RSA 加密密码）+ `chunk.b.js`（RC4 加密账号），后端 `security/LoginPayloadCipher` 解密，密文格式 `密文.时间戳(毫秒)`，双向偏差超过 10 秒返回「登录超时」。
- 平台凭据：用户平台密码以 AES-256-GCM 加密入库；Cookie 只入数据库，`.gitignore` 已排除 `cookie.json` / `cookies.json`。
- 登录失败 5 次锁定 15 分钟；验证码与重置链接均限次、限时、限频。

> ⚠️ **安全提醒**：仓库当前把开发用的 `jwt.secret`、`app.security.login-account-key`、`login-rsa-private-key`、`app.platform.secret-key` 以及一个邮箱 SMTP 授权码作为默认值写在 `application.yml` 中，且已提交到版本库。**部署到公网前务必**：用环境变量覆盖上述所有值、更换邮箱授权码、把 `JWT_SECRET` / `LOGIN_RSA_PRIVATE_KEY` / `PLATFORM_SECRET_KEY` 重新生成一套仅生产使用。RSA 密钥对更换时需同步更新前端 `public/chunk.a.js` 中的公钥。

---

## 九、说明

- 数据库表由 JPA `ddl-auto: update` 自动维护，仓库内**没有 SQL 脚本**，也没有 Dockerfile / CI 工作流。
- `frontend/legacy/` 是重构前的旧版前端，保留用于对照；日常开发用 `src/`。
- 学习通助手脚本由 `frontend/vite.config.js` 直接读取 `api/xxt/script.user.js` 对外提供（构建时作为资产 emit），只有一份文件，避免版本漂移。
