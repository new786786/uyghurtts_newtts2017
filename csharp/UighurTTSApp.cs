using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.Drawing.Text;
using System.IO;
using System.Media;
using System.Runtime.InteropServices;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace UighurTTS
{
    public class UighurTTSApp : Form
    {
        private const string TitleUy = "robot AI";
        private const string TitleCn = "AI \u8BED \u97F3 \u5408 \u6210 \u7CFB \u7EDF";
        private const string OrgUy = "robot AI \u0626\u0627\u06CB\u0627\u0632 \u0628\u0649\u0631\u0649\u0643\u062A\u06C8\u0631\u06C8\u0634 \u0633\u0649\u0633\u062A\u06D0\u0645\u0649\u0633\u0649";
        private const string OrgCn = "robot AI \u8BED\u97F3\u5408\u6210\u7CFB\u7EDF";
        private const string AddrUy = "UighurTTS \u97F3\u8282\u62FC\u63A5\u5F15\u64CE";
        private const string SampleText = "\u0626\u06C7\u064A\u063A\u06C7\u0631\u0686\u06D5 \u0626\u0627\u06CB\u0627\u0632 \u0628\u0649\u0631\u0649\u0643\u062A\u06C8\u0631\u06C8\u0634 \u0633\u06D0\u0633\u062A\u0649\u0645\u0649\u0633\u0649";

        private static readonly Color GradTop = Color.FromArgb(0x33, 0x77, 0xCC);
        private static readonly Color GradBottom = Color.FromArgb(0x6F, 0xA8, 0xE8);
        private static readonly Color ColTitleUy = Color.FromArgb(0xC8, 0x1E, 0x1E);
        private static readonly Color ColTitleCn = Color.FromArgb(0x11, 0x31, 0x7A);
        private static readonly Color ColInfo = Color.FromArgb(0x0B, 0x2A, 0x5B);
        private static readonly Color ColPanel = Color.FromArgb(0xEA, 0xEF, 0xF6);
        private static readonly Color ColBtn = Color.FromArgb(0xF2, 0xF2, 0xF2);

        private static readonly string BaseDir = FindBaseDir();
        private static readonly string DbPath = Path.Combine(BaseDir, "dada.db");
        private static readonly string DatPath = Path.Combine(BaseDir, "Lab.dat");
        private static readonly string AvatarPath = Path.Combine(BaseDir, "assets", "avatar.png");
        private static readonly string FontPath = Path.Combine(BaseDir, "assets", "UKIJTuT.ttf");

        private static string FindBaseDir()
        {
            var candidates = new List<string>();
            string asmDir = Path.GetDirectoryName(typeof(UighurTTSApp).Assembly.Location) ?? ".";
            string dir = asmDir;
            for (int i = 0; i < 7; i++)
            {
                candidates.Add(dir);
                string parent = Path.GetDirectoryName(dir);
                if (parent == null || parent == dir) break;
                dir = parent;
            }
            candidates.Add(Environment.CurrentDirectory);
            string cwd = Environment.CurrentDirectory;
            for (int i = 0; i < 3; i++)
            {
                string p = Path.GetDirectoryName(cwd);
                if (p != null && p != cwd) { candidates.Add(p); cwd = p; }
            }
            foreach (var c in candidates)
            {
                string full = Path.GetFullPath(c);
                if (File.Exists(Path.Combine(full, "dada.db"))) return full;
            }
            return Path.GetFullPath(Path.Combine(asmDir, ".."));
        }

        private Panel _headerPanel;
        private RichTextBox _textBox;
        private ProgressBar _progressBar;
        private ToolStripStatusLabel _statusLabel;
        private string _profileKey = "raw";

        private TTSEngine _engine;
        private string _engineError;
        private byte[] _lastPcm;
        private string _tempWav;
        private bool _busy;

        private Image _avatarImage;
        private Bitmap _gradientCache;
        private int _gradientW, _gradientH;

        private PrivateFontCollection _privateFonts;
        private string _fontUy = "Segoe UI";
        private string _fontCn = "Microsoft YaHei UI";

        [DllImport("gdi32.dll")]
        private static extern int AddFontResourceEx(string lpFilename, uint fl, IntPtr pdv);

        public UighurTTSApp()
        {
            Text = "Uighur TTS";
            Size = new Size(1024, 720);
            MinimumSize = new Size(880, 560);
            BackColor = ColPanel;
            StartPosition = FormStartPosition.CenterScreen;

            _tempWav = Path.Combine(Path.GetTempPath(), "uighur_tts_play.wav");

            LoadFonts();
            LoadAvatar();
            BuildStatusBar();
            BuildTextArea();
            BuildProgress();
            BuildProfileSelector();
            BuildToolbar();
            BuildHeader();

            _textBox.Text = SampleText;
            ApplyRtl();

            Load += (s, e) => InitEngine();
        }

        private void LoadFonts()
        {
            if (File.Exists(FontPath))
            {
                try
                {
                    AddFontResourceEx(FontPath, 0x10, IntPtr.Zero);
                    _privateFonts = new PrivateFontCollection();
                    _privateFonts.AddFontFile(FontPath);
                    if (_privateFonts.Families.Length > 0)
                        _fontUy = _privateFonts.Families[0].Name;
                }
                catch { }
            }

            foreach (var name in new[] { "Microsoft YaHei UI", "Microsoft YaHei", "SimHei", "SimSun" })
            {
                using (var test = new Font(name, 10))
                {
                    if (test.Name.Equals(name, StringComparison.OrdinalIgnoreCase))
                    { _fontCn = name; break; }
                }
            }
        }

        private void LoadAvatar()
        {
            if (File.Exists(AvatarPath))
            {
                try
                {
                    using (var img = Image.FromFile(AvatarPath))
                        _avatarImage = new Bitmap(img, 150, 150);
                }
                catch { }
            }
        }

        #region Header

        private void BuildHeader()
        {
            _headerPanel = new Panel
            {
                Dock = DockStyle.Top,
                Height = 200,
            };
            _headerPanel.Paint += HeaderPanel_Paint;
            _headerPanel.Resize += (s, e) => _headerPanel.Invalidate();
            Controls.Add(_headerPanel);
        }

        private Bitmap CreateGradient(int width, int height)
        {
            var bmp = new Bitmap(width, height, PixelFormat.Format24bppRgb);
            var data = bmp.LockBits(new Rectangle(0, 0, width, height),
                ImageLockMode.WriteOnly, PixelFormat.Format24bppRgb);
            var pixels = new byte[height * data.Stride];

            for (int y = 0; y < height; y++)
            {
                double ty = (double)y / Math.Max(1, height - 1);
                for (int x = 0; x < width; x++)
                {
                    double tx = (double)x / Math.Max(1, width - 1);
                    double t = Math.Max(0, Math.Min(1, 0.65 * ty + 0.35 * tx));
                    double r = GradTop.R + (GradBottom.R - GradTop.R) * t;
                    double g = GradTop.G + (GradBottom.G - GradTop.G) * t;
                    double b = GradTop.B + (GradBottom.B - GradTop.B) * t;

                    double cx = width * 0.62, cy = height * 0.30;
                    double dist = Math.Sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy));
                    double glow = Math.Pow(Math.Max(0, Math.Min(1, 1 - dist / (width * 0.55))), 2) * 38;
                    r = Math.Max(0, Math.Min(255, r + glow));
                    g = Math.Max(0, Math.Min(255, g + glow));
                    b = Math.Max(0, Math.Min(255, b + glow));

                    int offset = y * data.Stride + x * 3;
                    pixels[offset + 0] = (byte)b;
                    pixels[offset + 1] = (byte)g;
                    pixels[offset + 2] = (byte)r;
                }
            }
            Marshal.Copy(pixels, 0, data.Scan0, pixels.Length);
            bmp.UnlockBits(data);
            return bmp;
        }

        private void HeaderPanel_Paint(object sender, PaintEventArgs e)
        {
            var g = e.Graphics;
            int w = _headerPanel.Width, h = _headerPanel.Height;
            if (w <= 1 || h <= 1) return;

            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.TextRenderingHint = TextRenderingHint.AntiAlias;

            if (_gradientCache == null || _gradientW != w || _gradientH != h)
            {
                _gradientCache?.Dispose();
                _gradientCache = CreateGradient(w, h);
                _gradientW = w;
                _gradientH = h;
            }
            g.DrawImage(_gradientCache, 0, 0);

            if (_avatarImage != null)
            {
                using (var pen = new Pen(Color.FromArgb(0xD8, 0xE4, 0xF2), 2))
                    g.DrawRectangle(pen, 24, 22, 158, 158);
                g.FillRectangle(Brushes.White, 25, 23, 156, 156);
                g.DrawImage(_avatarImage, 28, 26, 150, 150);
            }

            int cx = w / 2 + 20;
            using (var titleFont = new Font("Arial Black", 34, FontStyle.Bold))
            using (var brush = new SolidBrush(ColTitleUy))
            {
                var sz = g.MeasureString(TitleUy, titleFont);
                g.DrawString(TitleUy, titleFont, brush, cx - sz.Width / 2, 20);
            }
            using (var subtitleFont = new Font(_fontCn, 24, FontStyle.Bold))
            using (var brush = new SolidBrush(ColTitleCn))
            {
                var sz = g.MeasureString(TitleCn, subtitleFont);
                g.DrawString(TitleCn, subtitleFont, brush, cx - sz.Width / 2, 68);
            }

            using (var infoFont = new Font(_fontUy, 10))
            using (var brush = new SolidBrush(ColInfo))
                g.DrawString(AddrUy, infoFont, brush, 210, 120);

            int rx = w - 28;
            using (var orgFontUy = new Font(_fontUy, 11, FontStyle.Bold))
            using (var brush = new SolidBrush(ColInfo))
            {
                var sz = g.MeasureString(OrgUy, orgFontUy);
                g.DrawString(OrgUy, orgFontUy, brush, rx - sz.Width, 124);
            }
            using (var orgFontCn = new Font(_fontCn, 13, FontStyle.Bold))
            using (var brush = new SolidBrush(ColInfo))
            {
                var sz = g.MeasureString(OrgCn, orgFontCn);
                g.DrawString(OrgCn, orgFontCn, brush, rx - sz.Width, 154);
            }

            using (var pen = new Pen(Color.FromArgb(0x1B, 0x4F, 0x9B), 2))
                g.DrawLine(pen, 0, h - 2, w, h - 2);
        }

        #endregion

        #region Toolbar

        private void BuildToolbar()
        {
            var bar = new FlowLayoutPanel
            {
                Dock = DockStyle.Top,
                Height = 64,
                BackColor = ColPanel,
                Padding = new Padding(0, 8, 0, 4),
                FlowDirection = FlowDirection.LeftToRight,
                WrapContents = false,
            };
            bar.Resize += (s, e) =>
            {
                int total = 0;
                foreach (Control c in bar.Controls) total += c.Width + c.Margin.Horizontal;
                bar.Padding = new Padding(Math.Max(0, (bar.Width - total) / 2), 8, 0, 4);
            };

            var specs = new (string cn, string uy, Action handler, bool primary)[]
            {
                ("\u9000\u51FA", "\u0686\u06D0\u0643\u0649\u0646\u0649\u0634", OnExit, false),
                ("\u4FDD\u5B58", "\u0633\u0627\u0642\u0644\u0649\u06CB\u06D0\u0644\u0649\u0634", OnSave, false),
                ("\u505C\u6B62", "\u062A\u0648\u062E\u062A\u0649\u062A\u0649\u0634", OnStop, false),
                ("\u6717\u8BFB", "\u0626\u0648\u0642\u06C7\u0634", OnRead, true),
                ("\u6587\u4EF6\u6253\u5F00", "\u06BE\u06C6\u062C\u062C\u06D5\u062A\u062A\u0649\u0646 \u0626\u06D0\u0686\u0649\u0634", OnOpen, false),
            };

            foreach (var (cn, uy, handler, primary) in specs)
            {
                var btn = new Button
                {
                    Text = cn + "\n" + uy,
                    Size = new Size(130, 48),
                    Cursor = Cursors.Hand,
                    FlatStyle = FlatStyle.Standard,
                    BackColor = primary ? Color.FromArgb(0xDC, 0xEB, 0xFF) : ColBtn,
                    ForeColor = Color.FromArgb(0x10, 0x31, 0x6B),
                    Font = new Font(_fontUy, 11, FontStyle.Bold),
                    TextAlign = ContentAlignment.MiddleCenter,
                    Margin = new Padding(8, 0, 8, 0),
                };
                btn.Click += (s, e) => handler();
                bar.Controls.Add(btn);
            }

            Controls.Add(bar);
        }

        #endregion

        #region Profile Selector

        private void BuildProfileSelector()
        {
            var wrap = new FlowLayoutPanel
            {
                Dock = DockStyle.Top,
                Height = 32,
                BackColor = ColPanel,
                Padding = new Padding(14, 4, 14, 0),
                FlowDirection = FlowDirection.LeftToRight,
                WrapContents = false,
            };

            var label = new Label
            {
                Text = "\u5408\u6210\u65B9\u6848\uFF1A",
                AutoSize = true,
                ForeColor = Color.FromArgb(0x10, 0x31, 0x6B),
                Font = new Font(_fontCn, 11, FontStyle.Bold),
                Margin = new Padding(0, 3, 6, 0),
            };
            wrap.Controls.Add(label);

            var choices = new (string key, string label, string desc)[]
            {
                ("raw", "\u539F\u59CB", "30ms \u62FC\u63A5 / \u9996\u4E2A\u5355\u5143"),
                ("smooth", "\u5E73\u6ED1\u589E\u5F3A", "50ms \u62FC\u63A5 + \u54CD\u5EA6\u5F52\u4E00"),
                ("smart", "\u667A\u80FD\u9009\u97F3", "\u591A\u5019\u9009 join-cost \u9009\u97F3"),
                ("prosody", "\u97F5\u5F8B\u81EA\u7136", "\u9009\u97F3 + \u53E5\u8BFB\u505C\u987F + \u53E5\u672B\u964D\u8C03"),
                ("hifi", "\u9AD8\u4FDD\u771F", "PSOLA \u57FA\u9891\u5E73\u6ED1 + \u53E5\u672B\u964D\u8C03"),
            };

            bool first = true;
            foreach (var (key, lbl, desc) in choices)
            {
                var rb = new RadioButton
                {
                    Text = lbl,
                    AutoSize = true,
                    Checked = first,
                    ForeColor = Color.FromArgb(0x10, 0x31, 0x6B),
                    BackColor = ColPanel,
                    Font = new Font(_fontCn, 10, FontStyle.Bold),
                    Margin = new Padding(4, 3, 4, 0),
                    Tag = key,
                };
                string capturedKey = key;
                string capturedLbl = lbl;
                string capturedDesc = desc;
                rb.CheckedChanged += (s, e) =>
                {
                    if (((RadioButton)s).Checked)
                    {
                        _profileKey = capturedKey;
                        SetStatus($"\u5DF2\u9009\u65B9\u6848\uFF1A{capturedLbl} \u2014 {capturedDesc}");
                    }
                };
                wrap.Controls.Add(rb);
                first = false;
            }

            var hint = new Label
            {
                Text = "\uFF08\u5207\u6362\u65B9\u6848\u540E\u70B9\u300C\u6717\u8BFB\u300D\u5BF9\u6BD4\u6548\u679C\uFF09",
                AutoSize = true,
                ForeColor = Color.FromArgb(0x5A, 0x6B, 0x85),
                BackColor = ColPanel,
                Font = new Font(_fontCn, 9),
                Margin = new Padding(8, 5, 0, 0),
            };
            wrap.Controls.Add(hint);

            Controls.Add(wrap);
        }

        #endregion

        #region Progress & Text

        private void BuildProgress()
        {
            var wrap = new Panel { Dock = DockStyle.Top, Height = 24, BackColor = ColPanel, Padding = new Padding(14, 2, 14, 2) };
            _progressBar = new ProgressBar { Dock = DockStyle.Fill, Minimum = 0, Maximum = 100 };
            wrap.Controls.Add(_progressBar);
            Controls.Add(wrap);
        }

        private void BuildTextArea()
        {
            var frame = new Panel { Dock = DockStyle.Fill, BackColor = ColPanel, Padding = new Padding(14, 10, 14, 10) };
            _textBox = new RichTextBox
            {
                Dock = DockStyle.Fill,
                Font = new Font(_fontUy, 22),
                BorderStyle = BorderStyle.FixedSingle,
                BackColor = Color.White,
                ForeColor = Color.FromArgb(0x11, 0x11, 0x11),
                RightToLeft = RightToLeft.Yes,
                WordWrap = true,
                Multiline = true,
                ScrollBars = RichTextBoxScrollBars.Vertical,
            };
            frame.Controls.Add(_textBox);
            Controls.Add(frame);
        }

        private void ApplyRtl()
        {
            _textBox.SelectAll();
            _textBox.SelectionAlignment = HorizontalAlignment.Right;
            _textBox.DeselectAll();
        }

        #endregion

        #region Status Bar

        private void BuildStatusBar()
        {
            var strip = new StatusStrip { BackColor = Color.FromArgb(0xDD, 0xE6, 0xF2) };
            _statusLabel = new ToolStripStatusLabel
            {
                Text = "\u6B63\u5728\u521D\u59CB\u5316\u5F15\u64CE\u2026",
                ForeColor = Color.FromArgb(0x10, 0x31, 0x6B),
                Font = new Font(_fontCn, 10),
                Spring = true,
                TextAlign = ContentAlignment.MiddleLeft,
            };
            strip.Items.Add(_statusLabel);
            Controls.Add(strip);
        }

        private void SetStatus(string msg)
        {
            if (InvokeRequired) { Invoke(new Action<string>(SetStatus), msg); return; }
            _statusLabel.Text = msg;
        }

        #endregion

        #region Engine

        private void InitEngine()
        {
            if (!File.Exists(DbPath) || !File.Exists(DatPath))
            {
                _engineError = "\u672A\u627E\u5230 dada.db / Lab.dat\uFF0C\u8BF7\u786E\u8BA4\u6570\u636E\u6587\u4EF6\u4F4D\u7F6E\u3002";
                SetStatus(_engineError);
                return;
            }
            try
            {
                _engine = new TTSEngine(DbPath, DatPath);
                SetStatus("\u5C31\u7EEA \u2014 \u8F93\u5165\u7EF4\u543E\u5C14\u6587\u540E\u70B9\u51FB\u300C\u6717\u8BFB\u300D");
            }
            catch (Exception ex)
            {
                _engineError = $"\u5F15\u64CE\u521D\u59CB\u5316\u5931\u8D25\uFF1A{ex.Message}";
                SetStatus(_engineError);
            }
        }

        private bool EnsureEngine()
        {
            if (_engine == null)
            {
                MessageBox.Show(_engineError ?? "\u5F15\u64CE\u672A\u5C31\u7EEA", "\u9519\u8BEF",
                    MessageBoxButtons.OK, MessageBoxIcon.Error);
                return false;
            }
            return true;
        }

        #endregion

        #region Synthesis

        private async void SynthesizeAsync(Action<byte[]> onDone)
        {
            if (_busy) return;
            string text = _textBox.Text.Trim();
            if (string.IsNullOrEmpty(text))
            {
                MessageBox.Show("\u8BF7\u8F93\u5165\u8981\u5408\u6210\u7684\u7EF4\u543E\u5C14\u6587\u6587\u672C\u3002",
                    "\u63D0\u793A", MessageBoxButtons.OK, MessageBoxIcon.Information);
                return;
            }
            if (!EnsureEngine()) return;

            _busy = true;
            _progressBar.Style = ProgressBarStyle.Marquee;
            SetStatus("\u5408\u6210\u4E2D\u2026");

            var opts = SynthesisOptions.Profiles.ContainsKey(_profileKey)
                ? SynthesisOptions.Profiles[_profileKey]
                : SynthesisOptions.Profiles["raw"];

            try
            {
                var result = await Task.Run(() => _engine.SynthesizePcm(text, opts));

                _progressBar.Style = ProgressBarStyle.Blocks;
                _progressBar.Value = 100;
                _busy = false;

                if (result.pcm == null || result.pcm.Length == 0)
                {
                    SetStatus("\u6CA1\u6709\u5339\u914D\u5230\u4EFB\u4F55\u97F3\u8282\u7247\u6BB5");
                    MessageBox.Show("\u6CA1\u6709\u5339\u914D\u5230\u97F3\u8282\uFF0C\u8BF7\u68C0\u67E5\u6587\u672C\u3002",
                        "\u63D0\u793A", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                    return;
                }

                _lastPcm = result.pcm;
                double dur = (result.pcm.Length / 2.0) / TTSEngine.SampleRate;
                SetStatus($"\u5408\u6210\u5B8C\u6210\uFF1A{result.total} \u4E2A\u7247\u6BB5\uFF0C{dur:F2} \u79D2");
                onDone(result.pcm);
            }
            catch (Exception ex)
            {
                _progressBar.Style = ProgressBarStyle.Blocks;
                _busy = false;
                SetStatus("\u5408\u6210\u5931\u8D25");
                MessageBox.Show(ex.Message, "\u5408\u6210\u5931\u8D25", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        }

        #endregion

        #region Button Handlers

        private void OnRead()
        {
            SynthesizeAsync(pcm =>
            {
                try
                {
                    TTSEngine.SaveWav(pcm, _tempWav);
                    using (var player = new SoundPlayer(_tempWav))
                        player.Play();
                }
                catch (Exception ex)
                {
                    MessageBox.Show(ex.Message, "\u64AD\u653E\u5931\u8D25", MessageBoxButtons.OK, MessageBoxIcon.Error);
                }
            });
        }

        private void OnStop()
        {
            SetStatus("\u5DF2\u505C\u6B62");
        }

        private void OnSave()
        {
            SynthesizeAsync(pcm =>
            {
                using (var dlg = new SaveFileDialog
                {
                    Title = "\u4FDD\u5B58\u4E3A WAV",
                    DefaultExt = ".wav",
                    Filter = "WAV \u97F3\u9891|*.wav",
                    FileName = "output.wav",
                })
                {
                    if (dlg.ShowDialog() != DialogResult.OK) return;
                    try
                    {
                        TTSEngine.SaveWav(pcm, dlg.FileName);
                        SetStatus($"\u5DF2\u4FDD\u5B58\uFF1A{dlg.FileName}");
                        MessageBox.Show(dlg.FileName, "\u4FDD\u5B58\u6210\u529F",
                            MessageBoxButtons.OK, MessageBoxIcon.Information);
                    }
                    catch (Exception ex)
                    {
                        MessageBox.Show(ex.Message, "\u4FDD\u5B58\u5931\u8D25",
                            MessageBoxButtons.OK, MessageBoxIcon.Error);
                    }
                }
            });
        }

        private void OnOpen()
        {
            using (var dlg = new OpenFileDialog
            {
                Title = "\u6253\u5F00\u6587\u672C\u6587\u4EF6",
                Filter = "\u6587\u672C\u6587\u4EF6|*.txt|\u6240\u6709\u6587\u4EF6|*.*",
            })
            {
                if (dlg.ShowDialog() != DialogResult.OK) return;
                try
                {
                    string content;
                    try { content = File.ReadAllText(dlg.FileName, System.Text.Encoding.UTF8); }
                    catch (Exception) { content = File.ReadAllText(dlg.FileName, System.Text.Encoding.Default); }
                    _textBox.Text = content;
                    ApplyRtl();
                    SetStatus($"\u5DF2\u8F7D\u5165\uFF1A{Path.GetFileName(dlg.FileName)}");
                }
                catch (Exception ex)
                {
                    MessageBox.Show(ex.Message, "\u6253\u5F00\u5931\u8D25",
                        MessageBoxButtons.OK, MessageBoxIcon.Error);
                }
            }
        }

        private void OnExit()
        {
            _engine?.Dispose();
            Close();
        }

        #endregion

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            _engine?.Dispose();
            _gradientCache?.Dispose();
            _avatarImage?.Dispose();
            _privateFonts?.Dispose();
            base.OnFormClosing(e);
        }
    }
}
