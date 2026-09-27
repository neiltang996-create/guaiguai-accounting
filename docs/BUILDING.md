# 构建、演示与自托管

## Android

要求 JDK 17、Android SDK Platform 35 / Build Tools 35.0.0。配置 `JAVA_HOME` 和 `ANDROID_HOME`，或使用 Android Studio 创建本机 `local.properties`。这些值不提交到 Git。

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDemo
./gradlew assembleDebugAndroidTest
```

Gradle Wrapper 首次会下载 8.11.1 和依赖；依赖完整缓存后可加 `--offline`。仓库不复制本机 SDK、JDK 或 Gradle 缓存。

| 构建 | application ID | 用途 |
|---|---|---|
| debug | `dev.guaiguai.accounting.dev` | 独立开发与测试 |
| demo | `dev.guaiguai.accounting.demo` | 虚构示例账本，禁用同步及自动采集 |
| release | `dev.guaiguai.accounting` | 自行配置与签名；仓库不提供现用私人签名 |

产物默认在 `app/build/outputs/apk/`。可通过 `FL_BUILD_ROOT` 把构建输出重定向到仓库之外。`build.sh` 仅包装 Wrapper，支持 `FL_JAVA_HOME`、`FL_ANDROID_HOME`、`FL_GRADLE_HOME` 和可选 `FL_GRADLE_EXECUTABLE`，没有个人电脑路径。

### Demo

安装 `assembleDemo` 的产物，首次打开自动建立两位虚构人物、几类示例资产、收入/支出及跨月退款/报销。页面顶部显示“演示模式”。再次启动不重复播种；要重置，清除 **Demo 包** 的应用数据即可，不对其他包操作。

Demo 的地址、令牌、人物名与商户规则覆盖为内置示例值，即使存在 `config.local.properties` 也不带入私人配置。普通公开构建则从空账本开始，只有虚构默认账户与分类。

### 本地配置和签名

复制 `config.local.properties.example` 为 `config.local.properties`。可以填两位显示名、自己的家庭 ID、NAS HTTPS 地址和商户规则。`sync.token` 可选；**写在这里会编译进 APK，可以被提取**。不要分享带私人配置的 APK。

发布版默认不带签名。需要时，将 keystore 和含 `storeFile`、`storePassword`、`keyAlias`、`keyPassword` 的 properties 放在仓库之外，使用 `FL_SIGNING_PROPERTIES` 显式提供。`storeFile` 为绝对路径或相对于工程根目录的路径。不要把公开版安装到现用私人包上，也不要改动已有签名材料。

## 自动化测试

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
python3 -m unittest discover -s server -p 'test_*.py'
python3 -m compileall -q server
python3 scripts/privacy_scan.py
```

设备测试只运行在 `.dev` 包，核心用例使用内存 Room 和随机测试偏好。请只选择隔离模拟器：

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest
```

不要在保留个人账本的设备或包上执行清库测试。仓库中的 CSV 样例由 `app/src/sharedTest/.../SampleLedger.kt` 手工构造；没有需要从个人电脑补齐的财务文件。JVM Python 协议测试需 `python3` 可用；其他环境无法启动时会报告跳过，不能把跳过算作通过。

截图测试为 `PublicScreenshotsTest`，使用内存演示账本、生产 Compose 页面、关闭同步。生成的图片必须人工检查后才加入 `docs/images/`。

## 自托管 NAS 服务

服务仅面向同一可信家庭。先在独立目录验证，再部署到自己的服务器，不要直接把旧的个人数据目录当演示输入。

1. 复制 `.env.example` 为 `.env.local`，生成新的随机 token（例如 `python3 -c 'import secrets; print(secrets.token_urlsafe(32))'`）。不要把生成结果写进 Issue 或截图。
2. 选择数据和备份目录，确保容器用户（默认 UID 1000）有写权限。相对目录相对于 `server/docker-compose.yml` 所在目录；环境变量也可使用你自己的绝对路径。
3. 检查 Docker Compose 配置后启动：

```bash
docker compose --env-file .env.local -f server/docker-compose.yml build
docker compose --env-file .env.local -f server/docker-compose.yml up -d
```

示例默认绑定 `127.0.0.1:47822`。如需手机连接，在 NAS 上配置 HTTPS 反向代理或可信私网入口。Android 只对开发回环地址例外允许 HTTP。配置直接最终地址，公开客户端不跟随重定向，以免令牌或账目发送到另一站点。群晖中继的特殊私人配置不包含在公开版本中。

服务启动拒绝空 token 和示例占位符；备份默认保留最近 30 份。备份包含私人数据，不能提交到仓库。首次使用两台手机前，请先在空的测试家庭验证连接和数据恢复。

`deploy_to_synology.sh` 需要显式 NAS 主机、SSH 用户、私钥路径，只上传指定服务文件到新 staging 目录；不会自动重启线上服务。`upgrade_on_nas.py` 面向已有标准目录部署，保留原令牌、端口映射和数据，验证挂载、备份并准备回滚。它仍需管理员理解部署布局后手工运行，本次公开整理没有执行线上升级。

## 发布前扫描

安装 [Gitleaks 官方工具](https://github.com/gitleaks/gitleaks)，在公开目录执行：

```bash
gitleaks dir . --redact
gitleaks git . --redact --log-opts=--all
python3 scripts/privacy_scan.py --history
```

不要用忽略规则压住真实秘密。`.gitignore` 不会清理旧提交；如果已有泄漏，先撤销凭据，再讨论历史清理。查看 [最终检查清单](../GITHUB_RELEASE_CHECKLIST.md) 中实际执行的版本与结果。
