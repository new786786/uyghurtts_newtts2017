<!DOCTYPE html>
<html lang="ug" dir="ltr">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Uyghur TTS — ئۇيغۇرچە ئاۋاز بىرىكتۈرۈش</title>
<style>
@font-face {
  font-family: 'UKIJ Tuz Tom';
  src: url('assets.php?f=UKIJTuT.ttf') format('truetype');
  font-weight: normal;
  font-style: normal;
  font-display: swap;
}
:root {
  --grad-top:    #3377CC;
  --grad-bottom: #6FA8E8;
  --title-red:   #C81E1E;
  --title-blue:  #11317A;
  --info-dark:   #0B2A5B;
  --panel-bg:    #EAEFF6;
  --btn-bg:      #F2F2F2;
  --btn-primary: #DCEBFF;
  --accent:      #1B5FB8;
  --font-uy: 'UKIJ Tuz Tom', 'Microsoft Uighur', 'Alkatip Basma Tom', Tahoma, sans-serif;
  --font-cn: 'Microsoft YaHei', 'SimHei', 'PingFang SC', sans-serif;
}
*, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
body {
  font-family: var(--font-cn);
  background: var(--panel-bg);
  color: #111;
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

/* ── Header banner ───────────────────────────────────── */
.header {
  position: relative;
  background: linear-gradient(135deg, var(--grad-top) 0%, var(--grad-bottom) 70%, #8ABCF0 100%);
  padding: 28px 32px 22px;
  display: flex;
  align-items: center;
  gap: 28px;
  overflow: hidden;
}
.header::after {
  content: '';
  position: absolute;
  left: 0; right: 0; bottom: 0;
  height: 3px;
  background: #1B4F9B;
}
.header .glow {
  position: absolute;
  width: 420px; height: 420px;
  background: radial-gradient(circle, rgba(255,255,255,.12) 0%, transparent 70%);
  top: -120px; right: 10%;
  pointer-events: none;
}
.avatar-frame {
  flex-shrink: 0;
  width: 130px; height: 130px;
  background: #fff;
  border: 2px solid #D8E4F2;
  border-radius: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 2px 12px rgba(0,0,0,.12);
}
.avatar-frame img {
  width: 120px; height: 120px;
  object-fit: cover;
  border-radius: 3px;
}
.header-text {
  flex: 1;
  text-align: center;
}
.header-text .title-en {
  font-size: 34px;
  font-weight: 900;
  color: var(--title-red);
  letter-spacing: 2px;
  text-shadow: 0 1px 4px rgba(0,0,0,.15);
}
.header-text .title-cn {
  font-size: 22px;
  font-weight: 700;
  color: var(--title-blue);
  margin-top: 6px;
  letter-spacing: 6px;
}
.header-info {
  text-align: right;
  flex-shrink: 0;
  color: var(--info-dark);
  font-size: 12px;
  line-height: 1.8;
}
.header-info .uy { font-family: var(--font-uy); direction: rtl; font-size: 13px; font-weight: 600; }
.header-info .cn { font-weight: 700; font-size: 14px; }

/* ── Toolbar ─────────────────────────────────────────── */
.toolbar {
  display: flex;
  justify-content: center;
  gap: 12px;
  padding: 16px 20px 8px;
  flex-wrap: wrap;
}
.toolbar button {
  min-width: 130px;
  padding: 10px 8px;
  border: 2px solid #C8D4E4;
  border-radius: 6px;
  background: var(--btn-bg);
  cursor: pointer;
  text-align: center;
  font-size: 13px;
  font-weight: 700;
  color: var(--info-dark);
  transition: all .15s;
  line-height: 1.5;
}
.toolbar button:hover {
  background: #E4EDFB;
  border-color: var(--accent);
}
.toolbar button:active { transform: scale(.97); }
.toolbar button.primary {
  background: var(--btn-primary);
  border-color: var(--accent);
  box-shadow: 0 0 0 2px rgba(27,95,184,.18);
}
.toolbar button .uy {
  font-family: var(--font-uy);
  direction: rtl;
  display: block;
  font-size: 14px;
  font-weight: normal;
}
.toolbar button:disabled {
  opacity: .5;
  cursor: not-allowed;
}

/* ── Profile selector ────────────────────────────────── */
.profiles {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 24px;
  flex-wrap: wrap;
}
.profiles .label {
  font-weight: 700;
  color: var(--info-dark);
  font-size: 13px;
  margin-right: 6px;
}
.profiles .hint {
  color: #5A6B85;
  font-size: 12px;
  margin-left: 10px;
}
.profiles label {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  border-radius: 4px;
  cursor: pointer;
  font-size: 13px;
  font-weight: 600;
  color: var(--info-dark);
  transition: background .12s;
}
.profiles label:hover { background: #D8E6F8; }
.profiles input[type="radio"] { accent-color: var(--accent); }

/* ── Progress ────────────────────────────────────────── */
.progress-wrap {
  padding: 0 24px;
  height: 6px;
}
.progress-bar {
  height: 100%;
  background: #D0D8E4;
  border-radius: 3px;
  overflow: hidden;
  position: relative;
}
.progress-bar .fill {
  height: 100%;
  width: 0%;
  background: linear-gradient(90deg, var(--accent), #5CA4F7);
  border-radius: 3px;
  transition: width .3s;
}
.progress-bar.indeterminate .fill {
  width: 30%;
  animation: indeterminate 1.2s ease-in-out infinite;
}
@keyframes indeterminate {
  0%   { transform: translateX(-100%); }
  100% { transform: translateX(430%); }
}

/* ── Text area ───────────────────────────────────────── */
.textarea-wrap {
  flex: 1;
  padding: 12px 24px;
  display: flex;
  flex-direction: column;
  min-height: 200px;
}
#inputText {
  flex: 1;
  width: 100%;
  min-height: 180px;
  resize: vertical;
  padding: 16px 18px;
  font-family: var(--font-uy);
  font-size: 22px;
  direction: rtl;
  text-align: right;
  line-height: 1.8;
  border: 1px solid #B8C6D8;
  border-radius: 6px;
  background: #fff;
  color: #111;
  outline: none;
  transition: border-color .2s;
}
#inputText:focus { border-color: var(--accent); box-shadow: 0 0 0 3px rgba(27,95,184,.1); }
#inputText::placeholder { color: #AAB4C0; }

/* ── Audio player ────────────────────────────────────── */
.player-wrap {
  padding: 0 24px 8px;
  display: none;
}
.player-wrap.visible { display: block; }
#audioPlayer { width: 100%; }

/* ── Status bar ──────────────────────────────────────── */
.statusbar {
  background: #DDE6F2;
  border-top: 1px solid #B8C6D8;
  padding: 6px 24px;
  font-size: 12px;
  color: var(--info-dark);
  display: flex;
  justify-content: space-between;
}

/* ── Responsive ──────────────────────────────────────── */
@media (max-width: 700px) {
  .header { flex-direction: column; text-align: center; padding: 18px 14px; }
  .header-info { text-align: center; }
  .toolbar { gap: 8px; }
  .toolbar button { min-width: 100px; font-size: 12px; }
  .profiles { justify-content: center; }
  .textarea-wrap, .progress-wrap, .player-wrap { padding-left: 12px; padding-right: 12px; }
}
</style>
</head>
<body>

<!-- ═══ Header ═══ -->
<div class="header">
  <div class="glow"></div>
  <div class="avatar-frame">
    <img src="assets.php?f=avatar.png" alt="Avatar"
         onerror="this.parentElement.style.display='none'">
  </div>
  <div class="header-text">
    <div class="title-en">robot AI</div>
    <div class="title-cn">AI 语 音 合 成 系 统</div>
  </div>
  <div class="header-info">
    <div class="uy">robot AI ئاۋاز بىرىكتۈرۈش سىستېمىسى</div>
    <div class="cn">robot AI 语音合成系统</div>
    <div style="margin-top:4px; font-size:11px;">UighurTTS PHP Edition</div>
  </div>
</div>

<!-- ═══ Toolbar ═══ -->
<div class="toolbar">
  <button onclick="openFile()" id="btnOpen">
    文件打开<span class="uy">ھۆججەتتىن ئېچىش</span>
  </button>
  <button onclick="doRead()" id="btnRead" class="primary">
    朗读<span class="uy">ئوقۇش</span>
  </button>
  <button onclick="doStop()" id="btnStop">
    停止<span class="uy">توختىتىش</span>
  </button>
  <button onclick="doSave()" id="btnSave">
    保存<span class="uy">ساقلىۋېلىش</span>
  </button>
  <button onclick="doExit()" id="btnExit">
    退出<span class="uy">چېكىنىش</span>
  </button>
</div>

<!-- ═══ Profile selector ═══ -->
<div class="profiles">
  <span class="label">合成方案：</span>
  <label><input type="radio" name="profile" value="raw" checked> 原始</label>
  <label><input type="radio" name="profile" value="smooth"> 平滑增强</label>
  <label><input type="radio" name="profile" value="smart"> 智能选音</label>
  <label><input type="radio" name="profile" value="prosody"> 韵律自然</label>
  <label><input type="radio" name="profile" value="hifi"> 高保真</label>
  <span class="hint">（切换方案后点「朗读」对比效果）</span>
</div>

<!-- ═══ Progress ═══ -->
<div class="progress-wrap">
  <div class="progress-bar" id="progressBar">
    <div class="fill" id="progressFill"></div>
  </div>
</div>

<!-- ═══ Text area ═══ -->
<div class="textarea-wrap">
  <textarea id="inputText"
            placeholder="ئۇيغۇرچە تېكىست كىرگۈزۈڭ…  /  请输入维吾尔文文本…"
  >ئۇيغۇرچە ئاۋاز بىرىكتۈرۈش سېستىمىسى</textarea>
</div>

<!-- ═══ Audio player ═══ -->
<div class="player-wrap" id="playerWrap">
  <audio id="audioPlayer" controls></audio>
</div>

<!-- ═══ Status bar ═══ -->
<div class="statusbar">
  <span id="statusText">就绪 — 输入维吾尔文后点击「朗读」</span>
  <span id="statusRight"></span>
</div>

<script>
const $ = id => document.getElementById(id);
const player   = $('audioPlayer');
const progress = $('progressBar');
const fill     = $('progressFill');
let   busy     = false;
let   lastBlob = null;

function getProfile() {
  const r = document.querySelector('input[name="profile"]:checked');
  return r ? r.value : 'raw';
}

function setStatus(msg) { $('statusText').textContent = msg; }
function setProgress(pct) {
  progress.classList.remove('indeterminate');
  fill.style.width = pct + '%';
}
function setIndeterminate(on) {
  if (on) { progress.classList.add('indeterminate'); fill.style.width = '30%'; }
  else    { progress.classList.remove('indeterminate'); fill.style.width = '0%'; }
}
function setBusy(on) {
  busy = on;
  $('btnRead').disabled = on;
  $('btnSave').disabled = on;
}

async function synthesize(autoPlay) {
  const text = $('inputText').value.trim();
  if (!text) { setStatus('请输入要合成的维吾尔文文本'); return; }
  if (busy) return;

  setBusy(true);
  setIndeterminate(true);
  setStatus('合成中…');

  try {
    const resp = await fetch('api.php', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ text, profile: getProfile() }),
    });

    if (!resp.ok) {
      let errMsg = `HTTP ${resp.status}`;
      try { const j = await resp.json(); errMsg = j.error || errMsg; } catch {}
      throw new Error(errMsg);
    }

    const blob = await resp.blob();
    if (lastBlob) URL.revokeObjectURL(lastBlob);
    const url = URL.createObjectURL(blob);
    lastBlob = url;

    player.src = url;
    $('playerWrap').classList.add('visible');

    const segs = resp.headers.get('X-TTS-Segments') || '?';
    const dur  = resp.headers.get('X-TTS-Duration')  || '?';
    setStatus(`合成完成：${segs} 个片段，${dur} 秒`);
    $('statusRight').textContent = `${(blob.size / 1024).toFixed(1)} KB`;
    setProgress(100);

    if (autoPlay) {
      try { await player.play(); } catch {}
    }
  } catch (err) {
    setStatus('合成失败：' + err.message);
    setProgress(0);
  } finally {
    setBusy(false);
    setTimeout(() => setIndeterminate(false), 300);
  }
}

function doRead() { synthesize(true); }

function doStop() {
  player.pause();
  player.currentTime = 0;
  setStatus('已停止');
}

function doSave() {
  synthesize(false).then(() => {
    if (!lastBlob) return;
    const a = document.createElement('a');
    a.href = lastBlob;
    a.download = 'output.wav';
    a.click();
    setStatus('已保存 output.wav');
  });
}

function openFile() {
  const input = document.createElement('input');
  input.type = 'file';
  input.accept = '.txt,.text';
  input.onchange = () => {
    const file = input.files[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => {
      $('inputText').value = reader.result;
      setStatus('已载入：' + file.name);
    };
    reader.readAsText(file, 'utf-8');
  };
  input.click();
}

function doExit() {
  doStop();
  if (confirm('确定退出？')) window.close();
}

document.querySelectorAll('input[name="profile"]').forEach(r => {
  r.addEventListener('change', () => {
    const labels = { raw:'原始 — 30ms 拼接 / 首个单元', smooth:'平滑增强 — 50ms 拼接 + 响度归一',
      smart:'智能选音 — 多候选 join-cost 选音', prosody:'韵律自然 — 选音 + 句读停顿 + 句末降调',
      hifi:'高保真 — PSOLA 基频平滑 + 句末降调' };
    setStatus('已选方案：' + (labels[r.value] || r.value));
  });
});
</script>
</body>
</html>
