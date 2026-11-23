<p align="center">
  <img src="docs/logo.png" alt="LiquidLyre" width="120"/>
</p>

<h1 align="center">璃音 · LiquidLyre</h1>

<p align="center">
  <b>一个带液态玻璃（Liquid Glass）质感的 Android 原生原琴自动演奏器。免 Root，支持三种主流谱式自动识别。</b>
</p>

<p align="center">
  <a href="https://github.com/varesafatcutecow/LiquidLyre/releases/latest"><img src="https://img.shields.io/badge/%E2%86%93%20%E4%B8%8B%E8%BD%BD%20APK-v1.6.5-2ea44f?style=for-the-badge" alt="Download APK"/></a>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="License"/></a>
  <img src="https://img.shields.io/badge/platform-Android%2012%2B-green.svg" alt="Platform"/>
  <img src="https://img.shields.io/badge/Kotlin-2.4.10-A97BFF.svg" alt="Kotlin"/>
  <img src="https://img.shields.io/badge/version-1.6.5-orange.svg" alt="Version"/>
</p>

---

> 📥 **下载 / Download**：点上方绿色按钮，或前往 [Releases](https://github.com/varesafatcutecow/LiquidLyre/releases/latest) 下载 `LiquidLyre-v1.6.5.apk`（Android 12+）。
> 安装后按 [快速开始](#快速开始--quick-start) 开启无障碍服务并校准琴键即可使用。

---

> **Disclaimer / 免责声明**
>
> 本项目仅用于学习、研究与个人娱乐。它通过 Android 无障碍服务向屏幕发送模拟点击来演奏游戏内虚拟乐器，
> **不读取、不修改任何游戏内存或数据，不破解、不绕过任何游戏保护机制**。
> 请将演奏音量调至合适大小，遵守所在游戏的社区规范。因使用本项目产生的任何后果由使用者自行承担。
>
> This project is for learning, research and personal entertainment only. It simulates taps via the Android
> Accessibility Service to play in-game virtual instruments. It **does not read or modify game memory/data,
> nor bypass any anti-cheat mechanism**. Use at your own risk and follow your game's community rules.

---

## 目录 / Contents

- [特色 · Highlights](#特色--highlights)
- [支持的谱式 · Supported Score Formats](#支持的谱式--supported-score-formats)
- [界面预览 · Screenshots](#界面预览--screenshots)
- [快速开始 · Quick Start](#快速开始--quick-start)
- [琴键校准 · Calibration](#琴键校准--calibration)
- [构建 · Build](#构建--build)
- [项目结构 · Structure](#项目结构--structure)
- [测试 · Tests](#测试--tests)
- [致谢 · Credits](#致谢--credits)
- [协议 · License](#协议--license)

---

## 特色 · Highlights

**中文**

- 🎛️ **液态玻璃 UI**：基于 AGSL 的 `backdrop` 玻璃质感，配合自研 Spring 弹簧内核——卡片、滑杆、悬浮窗都有真实的折射与回弹手感，在本类工具中独树一帜。
- 📱 **Android 原生 · 免 Root**：通过无障碍 `dispatchGesture` 注入手势，无需 Root、无需 Xposed、无需连接电脑跑脚本，手机装好即用。
- 🎼 **三种谱式自动嗅探**：BGI 文本键盘谱、谐律键琴 MIDI 事件流、原琴 `[n]` 时值谱，丢进来自动识别，不用手动选格式。
- 🔊 **本地复音试听**：内置 AudioTrack 复音合成器，不开游戏、不给无障碍也能在演奏台听旋律，方便调试和练手。
- 🪟 **悬浮窗演奏台**：可折叠成小圆点、可拖动，提供播放 / 暂停 / 上一首 / 下一首控制，游戏内不用切出来。
- 🗂️ **曲库管理**：SAF 目录导入、收藏星标、搜索、三种排序（新旧 / 字母 / 时长）。
- 🎯 **全屏琴键校准**：21 个琴键圆点拖到真实位置，适配任意分辨率与 UI 缩放。

**English**

- 🎛️ **Liquid Glass UI**: AGSL-based glass backdrop with a custom spring kernel — cards, sliders and the floating window all have real refraction and bounce.
- 📱 **Native Android, no Root**: injects taps via the Accessibility `dispatchGesture` API — no root, no Xposed, no PC-side script needed.
- 🎼 **Three score formats, auto-detected**: BGI text, Better-GI harp MIDI event streams, and Yuanqin `[n]`-duration notation — drop a file in and it figures out the format.
- 🔊 **Local polyphonic preview**: a built-in AudioTrack synth lets you hear the melody without launching the game or granting accessibility.
- 🪟 **Floating player**: collapsible into a draggable dot with play / pause / prev / next controls — control it without leaving the game.
- 🗂️ **Library**: SAF folder import, starred, search, and three sort modes (recent / name / duration).
- 🎯 **Full-screen key calibration**: drag 21 key markers onto the real keys to fit any resolution and UI scale.

---

## 支持的谱式 · Supported Score Formats

解析器会**自动嗅探**格式，无需手动指定。三个分支互不干扰，并各有真实谱单测覆盖。

The parser **auto-detects** the format; no manual selection needed. Each branch is covered by real-score unit tests.

| 格式 Format | `type` | `notes` 样例 Example | 识别特征 Signature |
|---|---|---|---|
| 谐律键琴 MIDI | `midi` / `harp` | `DVQET0\|UVQET168\|DA72\|UA168` | `D/U` + 键名 + tick 增量 |
| 原琴谱 Yuanqin | `yuanqin` / `原琴` | `(NQ)[16]H[16]D[16]@[8]` | `[n]` 时值 + `(AB)` 和弦 + `@` 休止 |
| BGI 文本键盘谱 | — | `Q W E / R T Y U` | `/` 分拍、空格分单位 |

解析结果统一输出为标准时间轴事件（绝对时间对齐，不累积误差），播放引擎、合成器和悬浮窗对格式无感知。

All formats normalize into a standard timeline (absolute time alignment, no drift), so the playback engine, synth and floating window are format-agnostic.

---

## 界面预览 · Screenshots

> 待补充。将截图放到 `docs/` 目录（`stage.png` / `library.png` / `overlay.png`）即可在此展示。
> Coming soon. Drop screenshots into `docs/` (`stage.png` / `library.png` / `overlay.png`) to display here.

| 演奏台 Stage | 曲库 Library | 悬浮窗 Floating window |
|---|---|---|
| 待补充 TBD | 待补充 TBD | 待补充 TBD |

---

## 快速开始 · Quick Start

**中文**

1. 安装 APK，打开 App。
2. 进入 **设置 → ① 无障碍服务**，开启「璃音自动弹琴服务」。
3. 进入 **曲库 → ＋ 目录**，选择存放 `.json` 谱子的文件夹并刷新。
4. 点选一首曲子，先在 **演奏台** 点「🔊 测试音」确认手机能发声，再点 ▶ 试听。
5. 要在游戏里真实弹奏：进入游戏调出风物之诗琴 → 用悬浮窗点 ▶。
   - 首次使用请先做 **琴键校准**（见下）。
   - 悬浮窗可在 **设置 → ② 悬浮窗总开关** 里开启。

**English**

1. Install the APK and open the app.
2. Go to **Settings → ① Accessibility** and enable the "LiquidLyre Auto Play" service.
3. Go to **Library → + Folder**, pick the folder containing your `.json` scores and refresh.
4. Pick a score, tap the **🔊 Test Tone** on the Stage to confirm audio works, then hit ▶ to preview.
5. To actually play in-game: open the game, bring up the Lyre, then tap ▶ on the floating window.
   - Do the **key calibration** first (see below).
   - Enable the floating window in **Settings → ② Floating window**.

---

## 琴键校准 · Calibration

默认坐标按 `3168×1440` 分辨率推算，换设备必偏。进入 **设置 → ⑧ 琴键校准 → 打开全屏校准**，
21 个圆点会盖在游戏画面上，逐个拖到对应琴键后点「保存」。也可先用「👆 试弹 Q 键」验证注入链路是否通畅。

Default coordinates assume a `3168×1440` screen and will be off on other devices. Go to
**Settings → ⑧ Calibration**; drag the 21 dots onto the real keys and save. Use "Test Q key" first to
verify that gesture injection works.

---

## 构建 · Build

```bash
# 依赖：JDK 17+、Android SDK (compileSdk 36)
git clone https://github.com/varesafatcutecow/LiquidLyre-璃音.git
cd LiquidLyre-璃音

# 生成 local.properties，指向你的 Android SDK
echo "sdk.dir=/path/to/android-sdk" > local.properties

# 跑测试 + 构建 Release（未签名）
./gradlew :app:testReleaseUnitTest :app:assembleRelease

# 签名（需自备 keystore，密码通过环境变量传入，不入库）
export LYRE_KS_PASS=你的密码
bash sign.sh
```

> 两个液态玻璃 AAR（`backdrop-android-2.0.0.aar`、`shapes-android-1.2.0.aar`，Apache-2.0）已随仓库提供在 `app/libs/`，
> 无需额外下载。签名 keystore 与密码**不在仓库中**，请自行生成。
>
> The two Liquid Glass AARs (Apache-2.0) are bundled in `app/libs/`. The signing keystore and password are
> **not** included — generate your own.

要求 / Requirements: `minSdk 31` (Android 12), `targetSdk 36`, Kotlin 2.4.10, Compose 1.11.0.

---

## 项目结构 · Structure

```
app/src/main/kotlin/com/liquidlyre/app/
├── data/       # 谱式解析（MIDI / Yuanqin / BGI）、索引、偏好、键位映射
├── engine/     # 播放时间轴、AudioTrack 复音合成、状态总线
├── glass/      # 液态玻璃基础组件、自研 Spring、滑杆数学
├── service/    # 无障碍手势注入、悬浮窗、前台保活、全屏校准
├── ui/         # 演奏台 / 曲库 / 设置 三个页面
├── vm/         # MainViewModel
└── util/       # 壁纸存储
```

---

## 测试 · Tests

`./gradlew :app:testReleaseUnitTest` — **61 tests passing**，覆盖三种谱式（含三份真实曲谱）、滑杆越界 clamp、
悬浮窗几何、曲库排序等纯函数逻辑。

---

## 致谢 · Credits

- 液态玻璃 AAR：[AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)（Apache-2.0）
- 谱式规则参考：BGI / CuSimpAutoGenshinLyre 与谐律键琴社区
- 图标角色：用户提供的原创形象

---

## 协议 · License

[MIT](LICENSE) © 2026 varesafatcutecow
