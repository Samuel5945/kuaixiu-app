# Kuaixiu · an AI wind-down coach for going to bed earlier

[中文](README.md) ｜ [English](README.en.md)

> The point is not another streak counter. It is one thing: **put the phone down on time.**

🌐 Site: <https://ligdesign.win/kuaixiu> ｜ 📱 Download: [Quark](https://pan.quark.cn/s/435c480c6ed9) · [Baidu](https://pan.baidu.com/s/1DRyVy7IKW73fDga1NOVzZA?pwd=35c8)

An Android app (Kotlin + Compose). It builds a 28-day sleep plan in one shot, writes it straight into
your phone's own calendar with native reminders, and lets an AI coach edit the plan, add reminders,
create habits and render share posters from a chat — **no account, no server, every record stays on the device**.

## Why not just another sleep tracker

Going to bed early rarely fails because you did not know the time. It fails because at 22:00 you opened
a project again. So Kuaixiu does not ask you to be disciplined. It takes over three jobs instead:
it schedules the plan and puts it on your calendar, your own calendar app rings when the time comes,
and afterwards it tells you honestly what the night was worth.

## Features

**Sleep plan** — the wake anchor is fixed, bedtime moves 15 minutes earlier every 3 days, across a
28-day ladder in two phases (acclimation, consolidation). Written once, updated idempotently:
recomputing deletes the old events and inserts the new, never duplicating. It lives in a local
calendar account the app creates itself, so reminders are system reminders and your records are not
locked inside this app.

**AI coach chat** — works with any OpenAI-compatible endpoint (`/chat/completions`). Action cards are
executed through Function Calling: change bedtime or wake time, add a to-do reminder, create a habit
that rings for N days, log a check-in, render a poster. When a model agrees but does not act, the app
calls it again to finish the job. **It also works with no key at all** — a built-in offline coach
understands everyday phrasing and produces the same action cards.

**Scoring** — wind-down 30 + sleep 30 + wake 40, measured in minutes of deviation, with the deductions
printed on the card. Sleeping early is not automatically better: up to 30 minutes early still scores full
marks. A check-in records the "phone down" moment automatically (`PACKAGE_USAGE_STATS`, read locally
from screen-off and usage stats, never uploaded).

**Share posters** — four layouts (A Keepsake / B Medal / C Magazine / D Weekly) × four modes
(local render / local type on model artwork / text-free artwork only / AI draws everything).
**Every number on a poster comes from local records — the AI draws the picture, never the figures.**
Posters are made inside the chat, then offered straight to save or share.

**Habits and reminders** — their own calendar accounts, so one write rings for N days and recomputing
the sleep plan cannot touch them.

**Bilingual UI** — every string lives in Android language resources and follows the system language,
posters and release notes included.

**In-app update check** — a silent lookup of the site manifest at
`https://ligdesign.win/kuaixiu-update.json` after startup, plus a manual check under Settings → About.

**Optional PC companion** — on the same LAN the app discovers a PC over UDP, receives the plan over TCP
and pushes check-ins back, driving the `KuaixiuTray` desktop app (a notification at wind-down, a warm
full-screen overlay at bedtime). The app is perfectly usable on its own.

## Permissions and privacy

| Permission | Why |
|------|------|
| `INTERNET` | Call the LLM / image endpoint *you* configure; fetch the version manifest |
| `READ/WRITE_CALENDAR` | Read and write the three local calendars the app creates for the plan, habits and reminders |
| `PACKAGE_USAGE_STATS` | Read local screen-off and usage records to confirm when the phone actually went down |

- Records live in `SharedPreferences` (file `kuaixiu`). **No telemetry, no account, no cloud database.**
- Your LLM API key is encrypted with an `AndroidKeyStore` AES-GCM key before it is written to disk;
  the plaintext exists only in memory.
- Cleartext HTTP (`usesCleartextTraffic`) is only for the LAN: the PC info page and the check-in
  hand-back use dynamically discovered addresses. LLM, image and update requests over the public
  internet stay https.
- The calendar account names are Chinese literals in code (`快休早睡`, `快休习惯`, `快休提醒`) because they
  are data keys, not UI text.

> Kuaixiu is a self-management tool, not medical advice. See a doctor for a real sleep disorder.

## Build

Needs JDK 17+ (the JBR 21 bundled with Android Studio works), Android SDK 34, and the committed Gradle wrapper.

```bash
export JAVA_HOME="<your JDK 17+>"   # Windows PowerShell: $env:JAVA_HOME="..."
./gradlew :app:testDebugUnitTest    # 123 unit tests
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
```

- There is no release signing configured in the repo; the distributed APKs are debug-signed.
- Unit tests run on Robolectric so they can read the real `strings.xml` (the logic layer resolves text
  through `KxText`). `app/build.gradle.kts` points the `android-all` jar download at an Aliyun mirror
  because Maven Central is slow behind the GFW — remove that `robolectric.dependency.repo.url` line if
  you are elsewhere.
- To dump the actual poster pixels from tests: `./gradlew testDebugUnitTest -Dkx.poster.out=<dir>`;
  without the property nothing is written.

## Layout

```
app/src/main/java/local/kuaixiu/
  MainActivity.kt KxApp.kt        entry points, global state injection
  engine/    PlanEngine PlanModels   the 28-day ladder (pure logic, covered by tests)
  data/      Prefs KxStore Checkins Habits Score UsageProbe Time   records, scoring, reads
             PosterData PosterBuild PosterComposer PosterFlow      posters
             ImageGen ModelClassifier ProviderProfiles SecretStore LlmLogger  models and keys
             UpdateChecker LanSync ChatLog KxText                  manifest update, LAN sync, strings
  llm/       LlmClient ActionRunner CoachPrompt LocalCoach   function calling and the offline coach
  calendar/  CalendarWriter      system calendar reads/writes (idempotent)
  ui/        HomeScreen ChatScreen SettingsScreen PcScreen EditDialogs PickerFields
             PosterDialog UpdateDialog Theme ChatStore       Compose screens
app/src/main/res/values/      Chinese (default)     app/src/main/res/values-en/  English
app/src/main/assets/poster_bg  built-in poster artwork
app/src/test/java/local/kuaixiu/  unit tests
```

Adding a string: **add it to both languages at once**, always with positional placeholders (`%1$s`),
and never branch on language in code — Android picks the resource. Prompts sent to the model and
protocol values (`on_time/late/skip`, `op`/`kind`, provider profile names) are deliberately left in
Chinese; that is not a missing translation.

## Version

Currently `0.8.0` / versionCode 22. The changelog lives on the site, fed by that same
`kuaixiu-update.json` manifest.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
