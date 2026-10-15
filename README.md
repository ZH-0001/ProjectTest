# 静习 · 高校自习室智能预约系统

基于需求规格 V1.1 的单机 Web 项目：Spring Boot + MyBatis-Plus + MySQL，Vue 3 + Element Plus。包含学生预约、管理后台和本地历史偏好推荐。

## 本地启动

需要 Java 21、Maven 3.9、MySQL 8.0.16+、Node 20.18+。项目固定使用 Vite 6，依赖版本由 `frontend/package-lock.json` 锁定。

1. 启动 MySQL；在 `backend/.env.local` 中填写数据库连接和初始管理员密码。首次使用可复制 `backend/.env.example`。
2. 在项目根目录执行后端命令，在另一个终端执行前端命令：

```powershell
mvn -B -f backend/pom.xml spring-boot:run
```

```powershell
cd frontend
npm ci
npm run dev
```

访问 <http://127.0.0.1:5173>。后端监听 `127.0.0.1:8080`（仅本地开发），Vite 将 `/api` 代理到后端并去掉该前缀。首次启动自动建表、创建管理员 `admin`、3间示例自习室和72个座位。学生通过页面自行注册。

管理员初始密码使用本机 `ADMIN_PASSWORD`，至少8字符、至多72个UTF-8字节。启动时仅在不存在管理员时创建；修改配置不会覆盖已存在账号的密码。不要提交 `.env.local`、数据库密码或真实账号凭据。

配置文件采用 Java properties 语法，**不加引号**。值中的反斜杠须写作 `\\`，特殊字符可使用 properties 转义。可用环境变量替代本地配置。

|配置|说明|
|---|---|
|DB_URL|独立开发库，默认 `study_room_app`；URL使用 `serverTimezone=Asia/Shanghai`|
|DB_USERNAME / DB_PASSWORD|开发及测试所用的本机数据库账号，不建议用于生产的root账号|
|DB_TEST_URL|独立测试库，数据库名称必须以 `_test` 结尾，不能与开发库相同|
|ADMIN_PASSWORD|首次初始化管理员所需密码，不会写入源码或日志|
|SEED_ENABLED|默认true；已有数据时不会重复创建示例空间|
|COOKIE_SECURE|本地HTTP默认false，HTTPS部署设为true|
|PORT|后端端口，默认8080；改变后同步修改Vite代理|
|SERVER_ADDRESS|默认127.0.0.1；仅在需要对外提供后端服务时调整|

账号需有建库权限才能使用 `createDatabaseIfNotExist=true`；否则先手动创建UTF-8数据库并授予该库所需权限。`schema.sql`使用 `CREATE TABLE IF NOT EXISTS`，适用于首次初始化，不承担未来数据库版本迁移。

## 功能与实现

- 学生：注册、Session登录、目标时段查询、座位选择、预约、取消、个人记录和推荐。
- 管理员：自习室新增／编辑／启停／逻辑删除，座位维护，按账号／状态／预约日期查询，以及每日统计。
- 时间：北京时间、半小时档位、同日30分钟至4小时、结束不超过当前时刻未来168小时；相邻预约允许存在。
- 预约：InnoDB事务，按用户→自习室→座位→预约记录的顺序加锁；READ COMMITTED下检查最新冲突。预约与属性快照同事务写入。
- 幂等：`(user_id, request_id)`唯一；同一UUID和参数返回原记录，即使已取消或已结束。浏览器网络失败后保留该请求标识以便重试。
- 历史：仅已结束未取消的预约参与推荐，使用预约时的座位属性快照。权重为自习室40%、座位类型30%、插座20%、安静区10%。新用户固定顺序推荐，不预占座位。
- 安全：BCrypt、服务端角色和归属校验、CSRF、HttpOnly会话Cookie、拒绝额外JSON字段。管理员不能强制取消或代预约。

这是预约系统，未实现签到，不能验证到场或实际使用率；无校园身份核验、多实例会话或Redis。自习室级锁会让同一室的写入串行执行，适合当前规模。

## 验证与构建

配置 `DB_TEST_URL` 后在根目录运行：

```powershell
mvn -B -f backend/pom.xml test
```

测试会初始化独立测试库，使用带随机标识的测试账号／自习室，不清空已有业务表；结束后仅清理本次创建的数据。包括并发抢座、同一用户跨室冲突、幂等、行为写入失败回滚、取消边界、管理修改竞态、权限、CSRF、推荐公式及1万条预约的查询性能。测试应在专用测试库执行，不与其他测试进程同时运行。

报告位置：`backend/target/surefire-reports/`、`backend/target/performance-report.json`。只跑无数据库单元测试：

```powershell
mvn -B -f backend/pom.xml "-Dtest=TimeRulesTest,RecommendationEngineTest" test
```

浏览器端到端验收需要本机安装 Google Chrome，并配置测试数据库及管理员密码。从 `frontend` 目录运行：

```powershell
npm run test:e2e
```

脚本使用8081和5174端口，自动启动独立测试服务；运行前保证这两个端口空闲。覆盖学生注册、登录、预约、取消、推荐、手机布局，以及管理员自习室与座位维护、预约记录和统计。测试只连接 `DB_TEST_URL`，会保留随机测试学生、已取消预约和逻辑删除的测试空间；不与后端测试同时执行。截图写入被Git忽略的 `.cache/`，不记录登录密码。

构建交付物：

```powershell
mvn -B -f backend/pom.xml package -DskipTests
cd frontend
npm run build
```

后端JAR位于 `backend/target/study-room-1.0.0.jar`，前端静态文件位于 `frontend/dist/`。使用 `java -jar` 时从 `backend` 目录启动以加载本地配置，或通过环境变量配置。

部署时由同源反向代理提供前端静态文件，代理 `/api/` 到 `http://127.0.0.1:8080/`，保持路径改写与开发环境一致；启用HTTPS和 `COOKIE_SECURE=true`。不要把 `.env.local` 放入静态目录。

## 项目结构与文档

```text
backend/       Spring Boot API、五张表、业务服务、MySQL测试
frontend/      Vue页面、API客户端、响应式样式
docs/API.md    接口、权限和请求样例
docs/验收记录.md  测试结果与性能口径
需求文档.txt    V1.1需求规格
```

统计接口单次最多查询367个自然日，避免无限日期范围生成过大结果；可分段查询。这是对规格的实现层保护限制。基础版保留原 `/ai/recommend` 接口路径，但采用规则加权推荐，不调用大模型。
