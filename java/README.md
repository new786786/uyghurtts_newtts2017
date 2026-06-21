# Uyghur TTS — Java Edition

维吾尔语音合成系统 (Java Swing 版本)，基于音节拼接引擎，从 Python 版本移植。

## 环境要求

- **JDK 8** 或更高版本（需要 `javac` 和 `java` 在 PATH 中）
- **sqlite-jdbc** 驱动（`build.bat` 会自动下载）
- 网络连接（首次运行时下载 JDBC 驱动）

## 数据文件

程序需要以下数据文件，位于上级目录 (`../`)：

```
G:\dbuy_asr\tts\newtts2017\
├── dada.db          # SQLite 音节数据库
├── Lab.dat          # 原始 PCM 音频数据
├── assets\
│   ├── avatar.png   # 头像图片
│   └── UKIJTuT.ttf  # UKIJ Tuz Tom 维文字体
└── java\            # 本目录
    ├── UighurTTS.java
    ├── build.bat
    └── README.md
```

## 编译运行

```bat
cd java
build.bat
```

`build.bat` 会自动完成：
1. 下载 `sqlite-jdbc.jar`（如果不存在）
2. 编译 `UighurTTS.java`
3. 启动 GUI 界面

## 合成方案

| 方案 | 说明 |
|------|------|
| 原始 | 30ms Hanning 窗拼接，使用首个候选单元 |
| 平滑增强 | 50ms 拼接 + 整词 RMS 响度归一化 |
| 智能选音 | 多候选 join-cost 选音，边界最连贯 |
| 韵律自然 | 选音 + 标点停顿 + 句末减速降调 |
| 高保真 | PSOLA 基频平滑 + 句末降调（最自然） |

## 功能

- Swing 桌面 GUI，蓝色渐变横幅 + 头像
- 5 个工具栏按钮：文件打开、朗读、停止、保存、退出
- 右到左 (RTL) 维吾尔文输入区
- 后台异步合成，带进度条
- 实时音频播放和 WAV 文件保存
