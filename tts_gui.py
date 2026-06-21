#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
维吾尔语音合成系统 — 图形界面
ئۇيغۇرچە ئاۋاز بىرىكتۈرۈش سىستېمىسى

基于 UighurTTS 音节拼接引擎 (Lab.dat + dada.db)，提供经典桌面 UI：
  - 蓝色渐变横幅 + 头像 + 双语标题 + 单位信息
  - 工具栏：文件打开 / 朗读 / 停止 / 保存 / 退出
  - 进度条
  - 右到左 (RTL) 维吾尔文输入区
"""

import os
import sys
import ctypes
import tempfile
import threading

import tkinter as tk
from tkinter import ttk, filedialog, messagebox
import tkinter.font as tkfont

try:
    import winsound
except ImportError:
    winsound = None

from PIL import Image, ImageTk

from UighurTTS import UighurTTS, save_wav, PROFILES

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DB_PATH = os.path.join(BASE_DIR, "dada.db")
DAT_PATH = os.path.join(BASE_DIR, "Lab.dat")
AVATAR_PATH = os.path.join(BASE_DIR, "assets", "avatar.png")
BUNDLED_FONT_PATH = os.path.join(BASE_DIR, "assets", "UKIJTuT.ttf")
BUNDLED_FONT_FAMILY = "UKIJ Tuz Tom"

# ---- 文案（品牌：robot AI 语音合成系统） ----
TITLE_UY = "robot AI"
TITLE_CN = "AI 语 音 合 成 系 统"
ORG_UY = "robot AI \u0626\u0627\u06CB\u0627\u0632 \u0628\u0649\u0631\u0649\u0643\u062A\u06C8\u0631\u06C8\u0634 \u0633\u0649\u0633\u062A\u06D0\u0645\u0649\u0633\u0649"
ORG_CN = "robot AI 语音合成系统"
ADDR_UY = "UighurTTS 音节拼接引擎"
# PHONE_UY = ""
# WEB_UY = ""

SAMPLE_TEXT = "ئۇيغۇرچە ئاۋاز بىرىكتۈرۈش سېستىمىسى"

# 工具栏按钮：(中文, 维文)
BTN_OPEN = ("文件打开", "\u06BE\u06C6\u062C\u062C\u06D5\u062A\u062A\u0649\u0646 \u0626\u06D0\u0686\u0649\u0634")
BTN_READ = ("朗读", "\u0626\u0648\u0642\u06C7\u0634")
BTN_STOP = ("停止", "\u062A\u0648\u062E\u062A\u0649\u062A\u0649\u0634")
BTN_SAVE = ("保存", "\u0633\u0627\u0642\u0644\u0649\u06CB\u06D0\u0644\u0649\u0634")
BTN_EXIT = ("退出", "\u0686\u06D0\u0643\u0649\u0646\u0649\u0634")

# 合成方案：(key, 中文标签, 说明)
PROFILE_CHOICES = [
    ("raw", "原始", "30ms 拼接 / 首个单元"),
    ("smooth", "平滑增强", "50ms 拼接 + 响度归一"),
    ("smart", "智能选音", "多候选 join-cost 选音"),
    ("prosody", "韵律自然", "选音 + 句读停顿 + 句末降调"),
    ("hifi", "高保真", "PSOLA 基频平滑 + 句末降调（最自然）"),
]

# ---- 配色 ----
GRAD_TOP = (0x33, 0x77, 0xCC)
GRAD_BOTTOM = (0x6F, 0xA8, 0xE8)
COL_TITLE_UY = "#C81E1E"
COL_TITLE_CN = "#11317A"
COL_INFO = "#0B2A5B"
COL_PANEL = "#EAEFF6"
COL_BTN = "#F2F2F2"


def load_private_font(path):
    """将随程序附带的字体私有加载到当前进程，使 Tk 可按字体家族名引用。
    返回 True 表示加载成功（仅 Windows）。"""
    if os.name != "nt" or not os.path.exists(path):
        return False
    FR_PRIVATE = 0x10
    try:
        n = ctypes.windll.gdi32.AddFontResourceExW(
            ctypes.c_wchar_p(path), FR_PRIVATE, 0)
        return n > 0
    except Exception:
        return False


def pick_font(preferred, root):
    """从首选字体列表中挑一个系统已安装的，确保维吾尔文能正确显示。"""
    available = set(f.lower() for f in tkfont.families(root))
    for name in preferred:
        if name.lower() in available:
            return name
    return preferred[-1]


def make_gradient(width, height):
    """生成蓝色对角渐变 + 柔光纹理的横幅底图。"""
    import numpy as np
    yy = np.linspace(0, 1, height)[:, None]
    xx = np.linspace(0, 1, width)[None, :]
    t = np.clip(0.65 * yy + 0.35 * xx, 0, 1)
    r = (GRAD_TOP[0] + (GRAD_BOTTOM[0] - GRAD_TOP[0]) * t)
    g = (GRAD_TOP[1] + (GRAD_BOTTOM[1] - GRAD_TOP[1]) * t)
    b = (GRAD_TOP[2] + (GRAD_BOTTOM[2] - GRAD_TOP[2]) * t)
    # 柔和光斑纹理
    cx, cy = width * 0.62, height * 0.30
    dist = np.sqrt(((xx * width - cx) ** 2) + ((yy * height - cy) ** 2))
    glow = np.clip(1 - dist / (width * 0.55), 0, 1) ** 2 * 38
    r = np.clip(r + glow, 0, 255)
    g = np.clip(g + glow, 0, 255)
    b = np.clip(b + glow, 0, 255)
    arr = np.dstack([r, g, b]).astype("uint8")
    return Image.fromarray(arr, "RGB")


class TTSApp:
    def __init__(self, root):
        self.root = root
        self.root.title("Uighur TTS")
        self.root.geometry("1024x720")
        self.root.minsize(880, 560)
        self.root.configure(bg=COL_PANEL)

        self.engine = None
        self.engine_error = None
        self.last_pcm = b""
        self.temp_wav = os.path.join(tempfile.gettempdir(), "uighur_tts_play.wav")
        self._busy = False

        # 优先使用随程序附带的 UKIJ Tuz Tom 维文字体
        self._font_loaded = load_private_font(BUNDLED_FONT_PATH)
        if self._font_loaded:
            self.font_uy = BUNDLED_FONT_FAMILY
        else:
            self.font_uy = pick_font(
                ["UKIJ Tuz Tom", "Microsoft Uighur", "UKIJ Tuz",
                 "Alkatip Basma Tom", "Tahoma", "Segoe UI", "Arial"], root)
        self.font_cn = pick_font(
            ["Microsoft YaHei UI", "Microsoft YaHei", "SimHei", "SimSun", "Arial"], root)

        self.profile_var = tk.StringVar(value="raw")

        self._build_header()
        self._build_toolbar()
        self._build_profile_selector()
        self._build_progress()
        self._build_textarea()
        self._build_statusbar()

        self.text.insert("1.0", SAMPLE_TEXT)
        self._apply_rtl()

        self.root.after(120, self._init_engine)

    # ---------- 顶部横幅 ----------
    def _build_header(self):
        self.header_h = 200
        self.header = tk.Canvas(self.root, height=self.header_h,
                                highlightthickness=0, bd=0)
        self.header.pack(side="top", fill="x")

        self._avatar_img = None
        if os.path.exists(AVATAR_PATH):
            try:
                im = Image.open(AVATAR_PATH).convert("RGB")
                im = im.resize((150, 150), Image.LANCZOS)
                self._avatar_img = ImageTk.PhotoImage(im)
            except Exception:
                self._avatar_img = None

        self._grad_cache = None
        self.header.bind("<Configure>", self._redraw_header)

    def _redraw_header(self, event=None):
        c = self.header
        w = c.winfo_width()
        h = self.header_h
        if w <= 1:
            return
        c.delete("all")

        grad = make_gradient(w, h)
        self._grad_cache = ImageTk.PhotoImage(grad)
        c.create_image(0, 0, anchor="nw", image=self._grad_cache)

        # 左侧头像 + 白框
        if self._avatar_img is not None:
            c.create_rectangle(24, 22, 24 + 158, 22 + 158,
                               fill="#FFFFFF", outline="#D8E4F2", width=2)
            c.create_image(24 + 4, 22 + 4, anchor="nw", image=self._avatar_img)

        # 中部标题（居中）
        cx = w // 2 + 20
        c.create_text(cx, 44, text=TITLE_UY, fill=COL_TITLE_UY,
                      font=("Arial Black", 34, "bold"))
        c.create_text(cx, 92, text=TITLE_CN, fill=COL_TITLE_CN,
                      font=(self.font_cn, 24, "bold"))

        # 左下角联系信息（维文，右对齐到中线左侧）
        info_x = 210
        c.create_text(info_x, 128, text=ADDR_UY, fill=COL_INFO, anchor="w",
                      font=(self.font_uy, 10))
        c.create_text(info_x, 150, text=PHONE_UY, fill=COL_INFO, anchor="w",
                      font=(self.font_uy, 10))
        c.create_text(info_x, 172, text=WEB_UY, fill=COL_INFO, anchor="w",
                      font=(self.font_uy, 10))

        # 右侧单位名称
        right_x = w - 28
        c.create_text(right_x, 132, text=ORG_UY, fill=COL_INFO, anchor="e",
                      font=(self.font_uy, 11, "bold"))
        c.create_text(right_x, 162, text=ORG_CN, fill=COL_INFO, anchor="e",
                      font=(self.font_cn, 13, "bold"))

        # 底部分隔亮线
        c.create_line(0, h - 2, w, h - 2, fill="#1B4F9B", width=2)

    # ---------- 工具栏 ----------
    def _build_toolbar(self):
        bar = tk.Frame(self.root, bg=COL_PANEL, pady=10)
        bar.pack(side="top", fill="x")

        inner = tk.Frame(bar, bg=COL_PANEL)
        inner.pack()

        # 视觉顺序（左→右）：退出 保存 停止 朗读 文件打开
        specs = [
            (BTN_EXIT, self.on_exit, False),
            (BTN_SAVE, self.on_save, False),
            (BTN_STOP, self.on_stop, False),
            (BTN_READ, self.on_read, True),
            (BTN_OPEN, self.on_open, False),
        ]
        for col, (label, cmd, primary) in enumerate(specs):
            cn, uy = label
            b = tk.Button(
                inner, command=cmd, cursor="hand2",
                text="%s\n%s" % (cn, uy),
                width=12, height=2, justify="center",
                relief="raised", bd=2,
                bg=("#DCEBFF" if primary else COL_BTN),
                activebackground="#CFE0FF",
                fg="#10316B",
                font=(self.font_uy, 11, "bold"),
            )
            if primary:
                b.configure(highlightbackground="#1B5FB8",
                            highlightcolor="#1B5FB8", highlightthickness=2)
            b.grid(row=0, column=col, padx=8)

    # ---------- 合成方案选择器 ----------
    def _build_profile_selector(self):
        wrap = tk.Frame(self.root, bg=COL_PANEL, pady=2)
        wrap.pack(side="top", fill="x", padx=14)

        tk.Label(wrap, text="合成方案：", bg=COL_PANEL, fg="#10316B",
                 font=(self.font_cn, 11, "bold")).pack(side="left", padx=(0, 6))

        for key, label, desc in PROFILE_CHOICES:
            rb = tk.Radiobutton(
                wrap, text="%s" % label, value=key, variable=self.profile_var,
                bg=COL_PANEL, fg="#10316B", activebackground=COL_PANEL,
                selectcolor="#DCEBFF", font=(self.font_cn, 10, "bold"),
                command=lambda d=desc, l=label: self.status.set(
                    "已选方案：%s — %s" % (l, d)),
            )
            rb.pack(side="left", padx=4)

        tk.Label(wrap, text="（切换方案后点「朗读」对比效果）", bg=COL_PANEL,
                 fg="#5A6B85", font=(self.font_cn, 9)).pack(side="left", padx=8)

    # ---------- 进度条 ----------
    def _build_progress(self):
        wrap = tk.Frame(self.root, bg=COL_PANEL)
        wrap.pack(side="top", fill="x", padx=14)
        self.progress = ttk.Progressbar(wrap, mode="determinate", maximum=100)
        self.progress.pack(fill="x")
        self.progress["value"] = 0

    # ---------- 文本区 ----------
    def _build_textarea(self):
        frame = tk.Frame(self.root, bg=COL_PANEL, padx=14, pady=10)
        frame.pack(side="top", fill="both", expand=True)

        self.text = tk.Text(
            frame, wrap="word", undo=True,
            font=(self.font_uy, 22), bd=1, relief="sunken",
            bg="#FFFFFF", fg="#111111",
            padx=14, pady=12, spacing3=8,
        )
        sb = ttk.Scrollbar(frame, orient="vertical", command=self.text.yview)
        self.text.configure(yscrollcommand=sb.set)
        sb.pack(side="right", fill="y")
        self.text.pack(side="left", fill="both", expand=True)

        # RTL：右对齐标签 + 实时维持
        self.text.tag_configure("rtl", justify="right")
        self.text.bind("<KeyRelease>", self._apply_rtl)
        self.text.bind("<<Paste>>", lambda e: self.root.after(1, self._apply_rtl))
        self.text.bind("<<Modified>>", self._on_modified)

    def _on_modified(self, event=None):
        if self.text.edit_modified():
            self._apply_rtl()
            self.text.edit_modified(False)

    def _apply_rtl(self, event=None):
        self.text.tag_add("rtl", "1.0", "end")

    # ---------- 状态栏 ----------
    def _build_statusbar(self):
        self.status = tk.StringVar(value="正在初始化引擎…")
        bar = tk.Label(self.root, textvariable=self.status, anchor="w",
                       bg="#DDE6F2", fg="#10316B", bd=1, relief="sunken",
                       font=(self.font_cn, 10), padx=10)
        bar.pack(side="bottom", fill="x")

    # ---------- 引擎 ----------
    def _init_engine(self):
        if not os.path.exists(DB_PATH) or not os.path.exists(DAT_PATH):
            self.engine_error = "未找到 dada.db / Lab.dat，请先完成数据库构建。"
            self.status.set(self.engine_error)
            return
        try:
            self.engine = UighurTTS(DB_PATH, DAT_PATH)
            self.status.set("就绪 — 输入维吾尔文后点击「朗读」")
        except Exception as e:
            self.engine_error = "引擎初始化失败：%s" % e
            self.status.set(self.engine_error)

    def _get_text(self):
        return self.text.get("1.0", "end").strip()

    def _ensure_engine(self):
        if self.engine is None:
            messagebox.showerror("错误", self.engine_error or "引擎未就绪")
            return False
        return True

    # ---------- 合成 ----------
    def _synthesize_async(self, on_done):
        if self._busy:
            return
        text = self._get_text()
        if not text:
            messagebox.showinfo("提示", "请输入要合成的维吾尔文文本。")
            return
        if not self._ensure_engine():
            return

        self._busy = True
        self.progress.configure(mode="indeterminate")
        self.progress.start(12)
        self.status.set("合成中…")

        result = {}

        opts = PROFILES.get(self.profile_var.get(), PROFILES["raw"])

        def work():
            try:
                pcm, total = self.engine.synthesize_pcm(
                    text, verbose=False, opts=opts)
                result["pcm"] = pcm
                result["total"] = total
            except Exception as e:
                result["error"] = str(e)
            self.root.after(0, finish)

        def finish():
            self.progress.stop()
            self.progress.configure(mode="determinate")
            self.progress["value"] = 100
            self._busy = False
            if "error" in result:
                self.status.set("合成失败")
                messagebox.showerror("合成失败", result["error"])
                return
            pcm = result.get("pcm", b"")
            if not pcm:
                self.status.set("没有匹配到任何音节片段")
                messagebox.showwarning("提示", "没有匹配到音节，请检查文本。")
                return
            self.last_pcm = pcm
            dur = (len(pcm) / 2) / 16000.0
            self.status.set("合成完成：%d 个片段，%.2f 秒" % (result.get("total", 0), dur))
            on_done(pcm)

        threading.Thread(target=work, daemon=True).start()

    # ---------- 按钮回调 ----------
    def on_read(self):
        def after(pcm):
            try:
                save_wav(pcm, self.temp_wav)
                if winsound is not None:
                    winsound.PlaySound(self.temp_wav,
                                       winsound.SND_FILENAME | winsound.SND_ASYNC)
                else:
                    messagebox.showinfo("提示", "当前平台不支持内置播放，请使用「保存」。")
            except Exception as e:
                messagebox.showerror("播放失败", str(e))
        self._synthesize_async(after)

    def on_stop(self):
        if winsound is not None:
            winsound.PlaySound(None, winsound.SND_PURGE)
        self.status.set("已停止")

    def on_save(self):
        def after(pcm):
            path = filedialog.asksaveasfilename(
                title="保存为 WAV",
                defaultextension=".wav",
                filetypes=[("WAV 音频", "*.wav")],
                initialfile="output.wav",
            )
            if not path:
                return
            try:
                save_wav(pcm, path)
                self.status.set("已保存：%s" % path)
                messagebox.showinfo("保存成功", path)
            except Exception as e:
                messagebox.showerror("保存失败", str(e))
        self._synthesize_async(after)

    def on_open(self):
        path = filedialog.askopenfilename(
            title="打开文本文件",
            filetypes=[("文本文件", "*.txt"), ("所有文件", "*.*")],
        )
        if not path:
            return
        try:
            with open(path, "r", encoding="utf-8") as f:
                content = f.read()
        except UnicodeDecodeError:
            with open(path, "r", encoding="gbk", errors="replace") as f:
                content = f.read()
        except Exception as e:
            messagebox.showerror("打开失败", str(e))
            return
        self.text.delete("1.0", "end")
        self.text.insert("1.0", content)
        self._apply_rtl()
        self.status.set("已载入：%s" % os.path.basename(path))

    def on_exit(self):
        try:
            if winsound is not None:
                winsound.PlaySound(None, winsound.SND_PURGE)
            if self.engine is not None:
                self.engine.close()
        finally:
            self.root.destroy()


def main():
    root = tk.Tk()
    try:
        root.iconbitmap(default="")
    except Exception:
        pass
    app = TTSApp(root)
    root.protocol("WM_DELETE_WINDOW", app.on_exit)
    root.mainloop()


if __name__ == "__main__":
    main()
