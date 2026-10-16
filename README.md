# 快休 · AI 早睡执行系统

> 让 AI 管住我晚上别干活。不打分不打卡不激励，只做一件事：**到点把手机放下**。

🌐 官网：<https://ligdesign.win/kuaixiu> ｜ 📱 下载：[夸克网盘](https://pan.quark.cn/s/435c480c6ed9) · [百度网盘](https://pan.baidu.com/s/1DRyVy7IKW73fDga1NOVzZA?pwd=35c8)

Android App（Kotlin + Compose）。一次生成 28 天作息计划，直接写进手机系统日历（带原生提醒），
AI 教练在对话里帮你改计划、加提醒、建习惯、做海报——**没有账号、没有服务器、数据全在本机**。

> **Kuaixiu** — an AI *wind-down* coach for going to bed earlier. Kotlin/Compose Android app.
> It generates a staged sleep plan, writes it into your phone's own calendar with native reminders,
> and keeps every record on the device: no account, no backend. Bilingual UI (Chinese / English)
> follows the system language. Build: `./gradlew :app:assembleDebug`. License: Apache-2.0.

## 为什么不是又一个 sleep tracker

早睡失败通常不是因为不知道几点该睡，而是**晚上十点又打开了工程**。所以快休不催你「自律」，
它把三件事接管掉：计划替你排好并落到日历、到点由系统日历提醒你、事后如实告诉你这一晚值多少分。

## 功能

**作息计划** — 起床锚点固定，入睡时刻每 3 天前移 15 分钟，28 天分「适应期 / 巩固期」阶梯。
一次生成、幂等更新：重算只删旧插新，不留重复事件。写入自建的本地日历账户
`快休早睡`，提醒走系统日历，换手机也不会被 App 绑死。

**AI 教练对话** — 接任意 OpenAI 兼容服务（`/chat/completions`，默认 `glm-5.3-flash`），
动作卡用 Function Calling 执行：改入睡/起床时间、加待办提醒、建连响 N 天的习惯、记打卡、做海报。
模型「嘴上答应没执行」时会自动追呼补执行。**不配 Key 也能用**：内置离线教练认得日常说法，
把同一句话翻译成同样的动作卡。

**评分** — 风屏 30 + 入睡 30 + 起床 40，量纲是「偏差分钟」，卡面直接列出扣分项。
早睡不是越多越好：早 30 分钟以内照满分。打卡时自动记下「放下手机」的时刻
（`PACKAGE_USAGE_STATS`，只读本地熄屏/UsageStats，不上传）。

**分享海报** — 四版式（A 打卡纪念 / B 奖章 / C 杂志 / D 周报）× 四种模式
（纯本地图 / 模型出底图本地叠字 / 只要无字底图 / 连字一起交给模型）。
**海报上的数字全部来自本地记录，AI 只画图不写数**。可以在对话里直接做，做完弹保存/分享。

**习惯与提醒** — 独立日历 `快休习惯` / `快休提醒`：一次写入连响 N 天，重算作息计划碰不到它们。

**中英双语** — 全部文案进 Android 语言资源，跟随系统语言自动切换，海报与更新说明同样跟着换。

**应用内更新检测** — 启动后静默查一次官网清单 `https://ligdesign.win/kuaixiu-update.json`，
设置页「关于」可手动检查。

**与 PC 端联动（可选）** — 同一局域网内 UDP 自动发现 + TCP 推送计划、回传打卡，
对接 PC 托盘 `KuaixiuTray`（到点弹通知、暖色全屏遮罩督眠）。App 单独用完全没问题。

## 权限与隐私

| 权限 | 用途 |
|------|------|
| `INTERNET` | 调用你自己配置的 LLM / 生图接口；检查版本清单 |
| `READ/WRITE_CALENDAR` | 读写 App 自建的 `快休早睡` / `快休习惯` / `快休提醒` 三个本地日历 |
| `PACKAGE_USAGE_STATS` | 读本机熄屏与使用记录，用来确认「几点真的放下了手机」 |

- 记录存在 `SharedPreferences`（文件名 `kuaixiu`）里，**没有任何遥测、没有账号、没有云端库**。
- LLM API Key 用 `AndroidKeyStore` 的 AES-GCM 密钥加密后落盘，明文只存在于内存。
- 明文 HTTP（`usesCleartextTraffic`）只为局域网里的 PC 信息页与打卡回传，地址是动态发现的；
  对公网的 LLM、生图、版本检查请求仍是 https。

> 快休是自我管理工具，不是医疗建议。有睡眠障碍请先看医生。

## 构建

需要 JDK 17+（Android Studio 自带的 JBR 21 即可）、Android SDK 34、Gradle wrapper 自带。

```bash
export JAVA_HOME="<你的 JDK 17+ 目录>"   # Windows PowerShell: $env:JAVA_HOME="..."
./gradlew :app:testDebugUnitTest        # 123 个单测
./gradlew :app:assembleDebug            # 产物 app/build/outputs/apk/debug/app-debug.apk
```

- 仓库没有配 release 签名，官方分发包是 debug 签名的 APK。
- 单测用 Robolectric 解析真 `strings.xml`（逻辑层取文案走 `KxText`）。
  `app/build.gradle.kts` 把 `android-all` jar 的下载源指向阿里云镜像，直连 Maven Central 会很慢；
  墙外可自行删除那行 `robolectric.dependency.repo.url`。
- 想在单测里看海报真像素：`./gradlew testDebugUnitTest -Dkx.poster.out=<输出目录>`，不给就不落盘。

## 目录

```
app/src/main/java/local/kuaixiu/
  MainActivity.kt KxApp.kt        入口与全局状态注入
  engine/    PlanEngine PlanModels   28 天阶梯计划生成（纯逻辑，单测覆盖）
  data/      Prefs KxStore Checkins Habits Score UsageProbe Time   记录、评分与取数
             PosterData PosterBuild PosterComposer PosterFlow      海报
             ImageGen ModelClassifier ProviderProfiles SecretStore LlmLogger  模型接入与密钥
             UpdateChecker LanSync ChatLog KxText                  清单更新、局域网同步、文案取用
  llm/       LlmClient ActionRunner CoachPrompt LocalCoach   Function Calling 与离线教练
  calendar/  CalendarWriter      系统日历读写（幂等）
  ui/        HomeScreen ChatScreen SettingsScreen PcScreen EditDialogs PickerFields
             PosterDialog UpdateDialog Theme ChatStore       Compose 界面
app/src/main/res/values/      中文（默认）      app/src/main/res/values-en/  英文
app/src/main/assets/poster_bg/  内置海报底图
app/src/test/java/local/kuaixiu/  单测
```

改文案的规则：**中英两边同时加**，占位符一律位置式（`%1$s`）；不要写语言判断代码，
Android 按系统语言自己选。发给模型的提示词、协议取值（`on_time/late/skip`、`op`/`kind`、
供应商档案名）故意保持中文，不是漏翻。

## 版本

当前 `0.8.0` / versionCode 22。更新历史见官网「最新版本」区块（数据源就是那份 `kuaixiu-update.json`）。

## License

Apache License 2.0 — 见 [LICENSE](LICENSE)。
