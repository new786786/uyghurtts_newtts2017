#!/usr/bin/env python3
"""
Uyghur TTS Engine — 优化版
基于音节拼接，使用 SQLite 数据库 dada.db

优化改进:
  1. Hanning 窗 crossfade (30ms overlap)
  2. 可选 PSOLA F0 平滑
  3. 更好的静音插入
"""

import sqlite3
import struct
import sys
import os
import mmap
import numpy as np

SEG_VOWELS = set("\u0627\u0648\u0649\u06C6\u06C7\u06C8\u06D0\u06D5")
NFI_VOWELS = set("\u0626\u0627\u0648\u0649\u06C6\u06C7\u06C8\u06D0\u06D5")
VOICED_CONSONANTS = set(
    "\u0628\u062C\u062F\u0631\u0632\u0698\u063A\u06AF\u06AD"
    "\u0644\u0645\u0646\u064E\u06CB\u064A"
)

# 完整维吾尔字母表（用于剥离标点/数字/空白等非字母字符）
UYGHUR_LETTERS = set(
    "\u0626\u0627\u06D5\u06D0\u0649\u0648\u06C7\u06C6\u06C8"
    "\u0628\u067E\u062A\u062C\u0686\u062E\u062F\u0631\u0632\u0698"
    "\u0633\u0634\u063A\u0641\u0642\u0643\u06AF\u06AD\u0644\u0645"
    "\u0646\u06BE\u06CB\u064A"
)


def clean_word(word):
    """去掉词中的标点、数字、拉丁字母、空白等，仅保留维吾尔字母。
    解决句尾标点被并入最后音节、导致末尾音节查不到而静音的问题。"""
    return "".join(ch for ch in word if ch in UYGHUR_LETTERS)

SAMPLE_RATE = 16000
BITS_PER_SAMPLE = 16
CHANNELS = 1
CROSSFADE_MS = 30
CROSSFADE_SAMPLES = int(CROSSFADE_MS * SAMPLE_RATE / 1000)
WORD_PAUSE_MS = 80
WORD_PAUSE_SAMPLES = int(WORD_PAUSE_MS * SAMPLE_RATE / 1000)

# 句读停顿（毫秒），用于韵律方案
PUNCT_PAUSE_MS = {
    "\u060C": 170, ",": 170, "\u061B": 200, ";": 200, "\u3001": 150,
    ".": 300, "\u06D4": 300, "\u3002": 320, "!": 300, "\uFF01": 300,
    "?": 320, "\u061F": 320, "\uFF1F": 320, ":": 200, "\uFF1A": 200,
}

# 合成方案预设：供 GUI 切换对比
PROFILES = {
    "raw": dict(crossfade_ms=30, word_pause_ms=80, unit_selection="first",
                energy_norm=False, prosody=False, candidates=1),
    "smooth": dict(crossfade_ms=50, word_pause_ms=80, unit_selection="first",
                   energy_norm=True, prosody=False, candidates=1),
    "smart": dict(crossfade_ms=50, word_pause_ms=80, unit_selection="join_cost",
                  energy_norm=True, prosody=False, candidates=8),
    "prosody": dict(crossfade_ms=55, word_pause_ms=100, unit_selection="join_cost",
                    energy_norm=True, prosody=True, candidates=8),
    "hifi": dict(crossfade_ms=55, word_pause_ms=100, unit_selection="join_cost",
                 energy_norm=True, prosody=True, candidates=8, psola=True),
}
DEFAULT_OPTIONS = dict(PROFILES["raw"], psola=False)


def is_seg_vowel(c):
    return c in SEG_VOWELS


def classify_nfi(c):
    if c in NFI_VOWELS:
        return "V"
    if c in VOICED_CONSONANTS:
        return "C"
    return "U"


def word2syllable(word):
    syllables = []
    word_len = len(word)
    if word_len == 0:
        return None

    pos = 0
    start = 0

    if word_len == 1:
        if is_seg_vowel(word[0]):
            return None
        return [word]

    if is_seg_vowel(word[0]):
        return None

    def state_cv():
        nonlocal pos, start
        pos += 1
        if pos > word_len:
            return False
        ch = word[pos - 1]
        if is_seg_vowel(ch):
            return found_cv()
        return cc_start()

    def found_cv():
        nonlocal pos, start
        if word_len == pos:
            syllables.append(word[start:start + 2])
            return True
        pos += 1
        ch = word[pos - 1]
        if is_seg_vowel(ch):
            return found_cvv()
        if word_len == pos:
            syllables.append(word[start:start + 3])
            return True
        pos += 1
        ch = word[pos - 1]
        if is_seg_vowel(ch):
            syllables.append(word[start:start + 2])
            start += 2
            return found_cv()
        if word_len == pos:
            syllables.append(word[start:start + 4])
            return True
        pos += 1
        ch = word[pos - 1]
        if is_seg_vowel(ch):
            syllables.append(word[start:start + 3])
            start += 3
            return found_cv()
        syllables.append(word[start:start + 4])
        start += 4
        return state_cv()

    def found_cvv():
        nonlocal pos, start
        if word_len == pos:
            syllables.append(word[start:start + 3])
            return True
        pos += 1
        ch = word[pos - 1]
        if is_seg_vowel(ch):
            return False
        if word_len == pos:
            syllables.append(word[start:start + 4])
            return True
        pos += 1
        ch = word[pos - 1]
        if is_seg_vowel(ch):
            syllables.append(word[start:start + 3])
            start += 3
            return found_cv()
        syllables.append(word[start:start + 4])
        start += 4
        return state_cv()

    def cc_start():
        nonlocal pos, start
        if word_len == pos:
            return False
        pos += 1
        ch = word[pos - 1]
        if not is_seg_vowel(ch):
            return False
        if word_len == pos:
            syllables.append(word[start:start + 3])
            return True
        pos += 1
        ch = word[pos - 1]
        if is_seg_vowel(ch):
            return False
        if word_len == pos:
            syllables.append(word[start:start + 4])
            return True
        pos += 1
        ch = word[pos - 1]
        if is_seg_vowel(ch):
            syllables.append(word[start:start + 3])
            start += 3
            return found_cv()
        if word_len == pos:
            syllables.append(word[start:start + 5])
            return True
        pos += 1
        ch = word[pos - 1]
        if is_seg_vowel(ch):
            syllables.append(word[start:start + 4])
            start += 4
            return found_cv()
        syllables.append(word[start:start + 5])
        start += 5
        return state_cv()

    pos = 1
    ok = state_cv()
    return syllables if ok else None


def build_context_keys(syllables):
    count = len(syllables)
    if count == 0:
        return []
    if count == 1:
        return [(syllables[0], syllables[0])]

    keys = []

    syl = syllables[0]
    rc = syllables[1][0]
    fi1 = syl + "-" + rc
    nfi1 = syl + "-" + classify_nfi(rc)
    keys.append((fi1, nfi1))

    for i in range(1, count - 1):
        syl = syllables[i]
        lc = syllables[i - 1][-1]
        rc = syllables[i + 1][0]
        fi1 = lc + "-" + syl + "-" + rc
        nfi1 = classify_nfi(lc) + "-" + syl + "-" + classify_nfi(rc)
        keys.append((fi1, nfi1))

    syl = syllables[-1]
    lc = syllables[-2][-1]
    fi1 = lc + "-" + syl
    nfi1 = classify_nfi(lc) + "-" + syl
    keys.append((fi1, nfi1))

    return keys


class SyllableDB:
    def __init__(self, db_path):
        # check_same_thread=False: 允许 GUI 后台线程合成（调用已由 _busy 串行化）
        self.conn = sqlite3.connect(db_path, check_same_thread=False)
        self.conn.row_factory = sqlite3.Row
        self.cur = self.conn.cursor()

    def lookup(self, fi1_key, nfi1_key):
        rows, mt = self.lookup_candidates(fi1_key, nfi1_key, limit=1)
        if rows:
            return rows[0], mt
        return None, None

    def lookup_candidates(self, fi1_key, nfi1_key, limit=8):
        """按优先级 FI1 > NFI1 > FI2 > NFI2 返回首个命中层级的全部候选行。"""
        plans = [
            ("FISyllable1", fi1_key, "FI1"),
            ("NFISyllable1", nfi1_key, "NFI1"),
            ("FISyllable2", fi1_key, "FI2"),
            ("NFISyllable2", nfi1_key, "NFI2"),
        ]
        for col, key, mt in plans:
            self.cur.execute(
                "SELECT * FROM Syllable WHERE %s=? LIMIT ?" % col, (key, limit)
            )
            rows = [dict(r) for r in self.cur.fetchall()]
            if rows:
                return rows, mt
        return [], None

    def close(self):
        self.conn.close()


def extract_segment(dat_path, begin, end):
    with open(dat_path, "rb") as f:
        f.seek(begin)
        return f.read(end - begin)


def pcm_to_samples(pcm_bytes):
    return np.frombuffer(pcm_bytes, dtype=np.int16).copy()


def samples_to_pcm(samples):
    return np.clip(samples, -32768, 32767).astype(np.int16).tobytes()


def resample_factor(pcm, factor):
    """按 factor 线性重采样（factor>1：变慢+降调，用于句末韵律）。"""
    arr = pcm_to_samples(pcm).astype(np.float64)
    n = len(arr)
    if n < 2 or abs(factor - 1.0) < 1e-3:
        return pcm
    newn = max(2, int(n * factor))
    x = np.linspace(0, n - 1, newn)
    y = np.interp(x, np.arange(n), arr)
    return samples_to_pcm(y)


def normalize_word_rms(pcm, target_rms=3500.0):
    """整词 RMS 归一化，消除词间响度差异。"""
    arr = pcm_to_samples(pcm).astype(np.float64)
    if len(arr) == 0:
        return pcm
    rms = np.sqrt(np.mean(arr ** 2))
    if rms < 10:
        return pcm
    scale = min(target_rms / rms, 4.0)
    return samples_to_pcm(arr * scale)


def estimate_f0(x, sr=SAMPLE_RATE, fmin=70, fmax=350, frame_ms=40, hop_ms=10,
                energy_floor=140.0, voicing_ratio=0.30):
    """逐帧自相关基频检测。返回 (f0_per_frame, hop_samples)，0 表示清音/静音。"""
    x = x.astype(np.float64)
    n = len(x)
    frame = int(frame_ms * sr / 1000)
    hop = int(hop_ms * sr / 1000)
    if n < frame:
        return np.zeros(0), hop
    min_lag = int(sr / fmax)
    max_lag = int(sr / fmin)
    n_frames = (n - frame) // hop + 1
    f0 = np.zeros(n_frames)
    for i in range(n_frames):
        s = i * hop
        seg = x[s:s + frame]
        seg = seg - seg.mean()
        if np.sqrt(np.mean(seg ** 2)) < energy_floor:
            continue
        ac = np.correlate(seg, seg, "full")[frame - 1:]
        r0 = ac[0]
        if r0 <= 0 or len(ac) <= max_lag:
            continue
        region = ac[min_lag:max_lag]
        if len(region) == 0:
            continue
        peak = int(np.argmax(region)) + min_lag
        if ac[peak] / r0 < voicing_ratio:
            continue
        if 1 <= peak < len(ac) - 1:
            a, b, c = ac[peak - 1], ac[peak], ac[peak + 1]
            denom = (a - 2 * b + c)
            if denom != 0:
                peak = peak + 0.5 * (a - c) / denom
        if peak > 0:
            f0[i] = sr / peak
    return f0, hop


def _median_smooth_f0(v, k=9):
    out = v.copy()
    half = k // 2
    for i in range(len(v)):
        win = v[max(0, i - half):min(len(v), i + half + 1)]
        nz = win[win > 0]
        if len(nz) > 0:
            out[i] = np.median(nz)
    return out


def psola_pitch_smooth(pcm, sr=SAMPLE_RATE, smooth_k=9, declination=0.90,
                       strength=0.85):
    """TD-PSOLA 基频平滑：把逐单元跳变的基频拉成平滑曲线 + 句末轻微降调，
    保持总时长不变。用于「高保真」方案，消除拼接的“跳音/机器人”感。"""
    x = pcm_to_samples(pcm).astype(np.float64)
    n = len(x)
    if n < sr // 8:
        return pcm
    f0, hop = estimate_f0(x, sr)
    if f0.size == 0 or np.count_nonzero(f0) < 3:
        return pcm

    centers = np.arange(len(f0)) * hop + hop // 2
    vt = np.where(f0 > 0)[0]
    if len(vt) < 2:
        return pcm

    # 源基频（逐样本）：用检测到的有声帧插值
    src = np.interp(np.arange(n), centers[vt], f0[vt],
                    left=f0[vt[0]], right=f0[vt[-1]])
    # 目标基频：中值平滑去跳变 + 句末下降，再与源做强度混合
    sf0 = _median_smooth_f0(f0, smooth_k)
    svt = np.where(sf0 > 0)[0]
    smooth = np.interp(np.arange(n), centers[svt], sf0[svt],
                       left=sf0[svt[0]], right=sf0[svt[-1]])
    decl = np.linspace(1.0, declination, n)
    tgt = smooth * decl
    tgt = src * (1 - strength) + tgt * strength
    tgt = np.clip(tgt, 60.0, 400.0)
    src = np.clip(src, 60.0, 400.0)

    # 分析基音标记（按源周期）
    marks = []
    pos = 0.0
    while pos < n:
        marks.append(int(pos))
        pos += sr / src[min(int(pos), n - 1)]
    if len(marks) < 3:
        return pcm
    a_arr = np.array(marks)

    out = np.zeros(n + sr // 2, dtype=np.float64)
    written = 0
    pos = 0.0
    while pos < n:
        t = int(round(pos))
        ai = int(a_arr[np.argmin(np.abs(a_arr - t))])
        T = int(sr / src[min(ai, n - 1)])
        if T < 4:
            pos += sr / tgt[min(t, n - 1)]
            continue
        l = max(0, ai - T)
        r = min(n, ai + T)
        frame = x[l:r]
        if len(frame) >= 4:
            frame = frame * np.hanning(len(frame))
            start = t - (ai - l)
            if start < 0:
                frame = frame[-start:]
                start = 0
            end = start + len(frame)
            if end > len(out):
                end = len(out)
                frame = frame[:end - start]
            out[start:end] += frame
            written = max(written, end)
        pos += sr / tgt[min(t, n - 1)]

    out = out[:n]
    src_rms = np.sqrt(np.mean(x ** 2))
    out_rms = np.sqrt(np.mean(out ** 2))
    if out_rms > 1:
        out = out * (src_rms / out_rms)
    return samples_to_pcm(out)


def hanning_crossfade(segments_pcm, overlap=None):
    """Hanning 窗 overlap-add 拼接，比线性 crossfade 更自然"""
    if not segments_pcm:
        return b""
    if len(segments_pcm) == 1:
        return segments_pcm[0]

    arrays = [pcm_to_samples(s) for s in segments_pcm]
    overlap = CROSSFADE_SAMPLES if overlap is None else overlap

    total_len = sum(len(a) for a in arrays) - overlap * (len(arrays) - 1)
    result = np.zeros(total_len, dtype=np.float64)

    pos = 0
    for i, arr in enumerate(arrays):
        arr_f = arr.astype(np.float64)
        if i == 0:
            result[pos:pos + len(arr_f)] += arr_f
            pos += len(arr_f) - overlap
        else:
            actual_overlap = min(overlap, pos + overlap - max(0, pos), len(arr_f))
            if actual_overlap <= 0:
                result[pos:pos + len(arr_f)] += arr_f
                pos += len(arr_f) - overlap
                continue

            fade_out = 0.5 * (1 + np.cos(np.pi * np.arange(actual_overlap) / actual_overlap))
            fade_in = 0.5 * (1 - np.cos(np.pi * np.arange(actual_overlap) / actual_overlap))

            overlap_start = pos
            result[overlap_start:overlap_start + actual_overlap] *= fade_out
            arr_f[:actual_overlap] *= fade_in
            result[overlap_start:overlap_start + actual_overlap] += arr_f[:actual_overlap]

            remaining = len(arr_f) - actual_overlap
            if remaining > 0:
                dest_start = overlap_start + actual_overlap
                dest_end = dest_start + remaining
                if dest_end <= len(result):
                    result[dest_start:dest_end] += arr_f[actual_overlap:]

            pos = overlap_start + len(arr_f) - overlap

    return samples_to_pcm(result[:max(1, pos + overlap)])


def save_wav(pcm_data, output_path):
    data_len = len(pcm_data)
    bytes_per_sec = SAMPLE_RATE * CHANNELS * (BITS_PER_SAMPLE // 8)
    block_align = CHANNELS * (BITS_PER_SAMPLE // 8)

    with open(output_path, "wb") as f:
        f.write(b"RIFF")
        f.write(struct.pack("<I", data_len + 36))
        f.write(b"WAVE")
        f.write(b"fmt ")
        f.write(struct.pack("<I", 16))
        f.write(struct.pack("<HH", 1, CHANNELS))
        f.write(struct.pack("<I", SAMPLE_RATE))
        f.write(struct.pack("<I", bytes_per_sec))
        f.write(struct.pack("<HH", block_align, BITS_PER_SAMPLE))
        f.write(b"data")
        f.write(struct.pack("<I", data_len))
        f.write(pcm_data)


class UighurTTS:
    def __init__(self, db_path, dat_path):
        self.db = SyllableDB(db_path)
        self.dat_path = dat_path
        # 提速：mmap 只打开一次 Lab.dat + 片段 LRU 缓存
        self._datf = open(dat_path, "rb")
        try:
            self._mm = mmap.mmap(self._datf.fileno(), 0, access=mmap.ACCESS_READ)
        except Exception:
            self._mm = None
        self._seg_cache = {}
        self._cache_order = []
        self._cache_cap = 8192

    def _read_seg(self, begin, end):
        key = (begin, end)
        cached = self._seg_cache.get(key)
        if cached is not None:
            return cached
        if self._mm is not None:
            data = self._mm[begin:end]
        else:
            data = extract_segment(self.dat_path, begin, end)
        self._seg_cache[key] = data
        self._cache_order.append(key)
        if len(self._cache_order) > self._cache_cap:
            old = self._cache_order.pop(0)
            self._seg_cache.pop(old, None)
        return data

    def _pick_unit(self, rows, prev_tail, opts):
        """多候选 join-cost 选音：在候选中选边界与上一单元最连贯者。"""
        if opts.get("unit_selection") != "join_cost" or len(rows) == 1 \
                or prev_tail is None or len(prev_tail) == 0:
            return rows[0]
        pe = float(prev_tail[-1])
        prms = float(np.sqrt(np.mean(prev_tail.astype(np.float64) ** 2)))
        best, best_cost = rows[0], 1e18
        for r in rows:
            data = self._read_seg(r["begin_pos"], r["end_pos"])
            head = np.frombuffer(data, dtype=np.int16)[:80]
            if len(head) == 0:
                continue
            he = float(head[0])
            hrms = float(np.sqrt(np.mean(head.astype(np.float64) ** 2)))
            cost = abs(he - pe) + 0.5 * abs(hrms - prms)
            if cost < best_cost:
                best_cost, best = cost, r
        return best

    def synthesize_pcm(self, text, verbose=True, opts=None):
        """合成文本为 PCM 字节流（16kHz/16bit/mono），返回 (pcm_data, total_segments)。
        opts: 合成方案参数（见 PROFILES）。不写文件，供 GUI 直接播放或保存。"""
        o = dict(DEFAULT_OPTIONS)
        if opts:
            o.update(opts)
        overlap = int(o["crossfade_ms"] * SAMPLE_RATE / 1000)

        if verbose:
            print(f"[TTS] Input: {text}")

        # 预处理：清洗每个词，记录句读停顿
        tokens = text.split()
        plan = []  # (clean_word, pause_ms)
        for tok in tokens:
            w = clean_word(tok)
            if not w:
                continue
            pause = o["word_pause_ms"]
            if o["prosody"]:
                for ch in reversed(tok):
                    if ch in PUNCT_PAUSE_MS:
                        pause = PUNCT_PAUSE_MS[ch]
                        break
                    if ch in UYGHUR_LETTERS:
                        break
            plan.append((w, pause))

        n_words = len(plan)
        word_items = []  # (word_pcm, pause_ms)
        total = 0
        prev_tail = None

        for wi, (word, pause) in enumerate(plan):
            syllables = word2syllable(word)
            if syllables is None:
                if verbose:
                    print(f"[WARN] Word2Syllable failed for '{word}'")
                continue

            word_segments = []
            keys = build_context_keys(syllables)
            for i, (fi1, nfi1) in enumerate(keys):
                rows, match_type = self.db.lookup_candidates(
                    fi1, nfi1, o["candidates"])
                if not rows:
                    if verbose:
                        print(f"[WARN] No match: FI1='{fi1}' NFI1='{nfi1}'")
                    continue
                row = self._pick_unit(rows, prev_tail, o)
                seg = self._read_seg(row["begin_pos"], row["end_pos"])
                word_segments.append(seg)
                arr = pcm_to_samples(seg)
                prev_tail = arr[-80:] if len(arr) >= 80 else arr
                total += 1

            if not word_segments:
                continue

            word_pcm = hanning_crossfade(word_segments, overlap)
            if o["energy_norm"]:
                word_pcm = normalize_word_rms(word_pcm)
            # 韵律：句末词轻微变慢+降调
            if o["prosody"] and wi == n_words - 1 and pause >= 300:
                word_pcm = resample_factor(word_pcm, 1.06)
            word_items.append((word_pcm, pause))

        if not word_items:
            if verbose:
                print("[ERROR] No segments matched!")
            return b"", 0

        final_parts = []
        for i, (wp, pause) in enumerate(word_items):
            final_parts.append(wp)
            if i < len(word_items) - 1:
                ps = int(pause * SAMPLE_RATE / 1000) if o["prosody"] \
                    else WORD_PAUSE_SAMPLES
                final_parts.append(np.zeros(ps, dtype=np.int16).tobytes())

        pcm_data = b"".join(final_parts)
        if o.get("psola"):
            try:
                pcm_data = psola_pitch_smooth(pcm_data)
            except Exception as ex:
                if verbose:
                    print(f"[WARN] PSOLA skipped: {ex}")
        return pcm_data, total

    def synthesize(self, text, output_path, opts=None):
        pcm_data, total = self.synthesize_pcm(text, verbose=True, opts=opts)
        if not pcm_data:
            return
        save_wav(pcm_data, output_path)
        duration = (len(pcm_data) / 2) / SAMPLE_RATE
        print(f"[TTS] Output: {output_path} ({duration:.2f}s, {total} segments, "
              f"{len(pcm_data)} bytes)")

    def close(self):
        try:
            if getattr(self, "_mm", None) is not None:
                self._mm.close()
            if getattr(self, "_datf", None) is not None:
                self._datf.close()
        except Exception:
            pass
        self.db.close()


def main():
    import io
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding='utf-8', errors='replace')

    base_dir = os.path.dirname(os.path.abspath(__file__))
    db_path = os.path.join(base_dir, "dada.db")
    dat_path = os.path.join(base_dir, "Lab.dat")

    if not os.path.exists(db_path):
        print(f"ERROR: dada.db not found at {db_path}")
        sys.exit(1)
    if not os.path.exists(dat_path):
        print(f"ERROR: Lab.dat not found at {dat_path}")
        sys.exit(1)

    engine = UighurTTS(db_path, dat_path)

    if len(sys.argv) >= 2:
        text = sys.argv[1]
        output = sys.argv[2] if len(sys.argv) >= 3 else os.path.join(base_dir, "output.wav")
        engine.synthesize(text, output)
    else:
        print("=== Uyghur TTS Engine (Optimized) ===")
        print(f"Database: {db_path}")
        print(f"Crossfade: {CROSSFADE_MS}ms Hanning window")
        print(f"Word pause: {WORD_PAUSE_MS}ms")
        print("Usage: python UighurTTS.py \"text\" [output.wav]")
        print("Enter text (type 'exit' to quit):\n")
        while True:
            try:
                text = input("Input> ")
            except (EOFError, KeyboardInterrupt):
                break
            if not text or text.lower() == "exit":
                break
            engine.synthesize(text, os.path.join(base_dir, "output.wav"))
            print()

    engine.close()


if __name__ == "__main__":
    main()
