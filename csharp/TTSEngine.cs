using System;
using System.Collections.Generic;
using System.Data.SQLite;
using System.IO;
using System.IO.MemoryMappedFiles;
using System.Linq;

namespace UighurTTS
{
    public enum UnitSelectionMode { First, JoinCost }

    public class SynthesisOptions
    {
        public int CrossfadeMs { get; set; } = 30;
        public int WordPauseMs { get; set; } = 80;
        public UnitSelectionMode UnitSelection { get; set; } = UnitSelectionMode.First;
        public bool EnergyNorm { get; set; }
        public bool Prosody { get; set; }
        public int Candidates { get; set; } = 1;
        public bool Psola { get; set; }

        public SynthesisOptions Clone() => (SynthesisOptions)MemberwiseClone();

        public static readonly Dictionary<string, SynthesisOptions> Profiles = new Dictionary<string, SynthesisOptions>
        {
            ["raw"] = new SynthesisOptions { CrossfadeMs = 30, WordPauseMs = 80, UnitSelection = UnitSelectionMode.First, EnergyNorm = false, Prosody = false, Candidates = 1 },
            ["smooth"] = new SynthesisOptions { CrossfadeMs = 50, WordPauseMs = 80, UnitSelection = UnitSelectionMode.First, EnergyNorm = true, Prosody = false, Candidates = 1 },
            ["smart"] = new SynthesisOptions { CrossfadeMs = 50, WordPauseMs = 80, UnitSelection = UnitSelectionMode.JoinCost, EnergyNorm = true, Prosody = false, Candidates = 8 },
            ["prosody"] = new SynthesisOptions { CrossfadeMs = 55, WordPauseMs = 100, UnitSelection = UnitSelectionMode.JoinCost, EnergyNorm = true, Prosody = true, Candidates = 8 },
            ["hifi"] = new SynthesisOptions { CrossfadeMs = 55, WordPauseMs = 100, UnitSelection = UnitSelectionMode.JoinCost, EnergyNorm = true, Prosody = true, Candidates = 8, Psola = true },
        };
    }

    public class TTSEngine : IDisposable
    {
        public const int SampleRate = 16000;
        public const int BitsPerSample = 16;
        public const int Channels = 1;

        private static readonly HashSet<char> SegVowels = new HashSet<char>(
            "\u0627\u0648\u0649\u06C6\u06C7\u06C8\u06D0\u06D5");

        private static readonly HashSet<char> NfiVowels = new HashSet<char>(
            "\u0626\u0627\u0648\u0649\u06C6\u06C7\u06C8\u06D0\u06D5");

        private static readonly HashSet<char> VoicedConsonants = new HashSet<char>(
            "\u0628\u062C\u062F\u0631\u0632\u0698\u063A\u06AF\u06AD\u0644\u0645\u0646\u064E\u06CB\u064A");

        private static readonly HashSet<char> UyghurLetters = new HashSet<char>(
            "\u0626\u0627\u06D5\u06D0\u0649\u0648\u06C7\u06C6\u06C8" +
            "\u0628\u067E\u062A\u062C\u0686\u062E\u062F\u0631\u0632\u0698" +
            "\u0633\u0634\u063A\u0641\u0642\u0643\u06AF\u06AD\u0644\u0645" +
            "\u0646\u06BE\u06CB\u064A");

        private static readonly Dictionary<char, int> PunctPauseMs = new Dictionary<char, int>
        {
            ['\u060C'] = 170, [','] = 170, ['\u061B'] = 200, [';'] = 200, ['\u3001'] = 150,
            ['.'] = 300, ['\u06D4'] = 300, ['\u3002'] = 320, ['!'] = 300, ['\uFF01'] = 300,
            ['?'] = 320, ['\u061F'] = 320, ['\uFF1F'] = 320, [':'] = 200, ['\uFF1A'] = 200,
        };

        private readonly SQLiteConnection _conn;
        private readonly FileStream _datFile;
        private readonly MemoryMappedFile _mmf;
        private readonly MemoryMappedViewAccessor _accessor;
        private readonly Dictionary<long, byte[]> _segCache = new Dictionary<long, byte[]>();
        private readonly List<long> _cacheOrder = new List<long>();
        private const int CacheCap = 8192;
        private bool _disposed;

        public TTSEngine(string dbPath, string datPath)
        {
            _conn = new SQLiteConnection($"Data Source={dbPath};Read Only=True;");
            _conn.Open();

            _datFile = new FileStream(datPath, FileMode.Open, FileAccess.Read, FileShare.Read);
            try
            {
                _mmf = MemoryMappedFile.CreateFromFile(_datFile, null, 0, MemoryMappedFileAccess.Read, HandleInheritability.None, false);
                _accessor = _mmf.CreateViewAccessor(0, 0, MemoryMappedFileAccess.Read);
            }
            catch
            {
                _mmf = null;
                _accessor = null;
            }
        }

        #region Character Classification

        public static bool IsSegVowel(char c) => SegVowels.Contains(c);

        public static string ClassifyNfi(char c)
        {
            if (NfiVowels.Contains(c)) return "V";
            if (VoicedConsonants.Contains(c)) return "C";
            return "U";
        }

        public static string CleanWord(string word)
        {
            var sb = new System.Text.StringBuilder(word.Length);
            foreach (char ch in word)
                if (UyghurLetters.Contains(ch)) sb.Append(ch);
            return sb.ToString();
        }

        #endregion

        #region Syllable Segmentation

        public static List<string> Word2Syllable(string word)
        {
            var syllables = new List<string>();
            int wordLen = word.Length;
            if (wordLen == 0) return null;

            if (wordLen == 1)
            {
                if (IsSegVowel(word[0])) return null;
                return new List<string> { word };
            }
            if (IsSegVowel(word[0])) return null;

            int pos = 0, start = 0;

            bool StateCV()
            {
                pos++;
                if (pos > wordLen) return false;
                char ch = word[pos - 1];
                if (IsSegVowel(ch)) return FoundCV();
                return CCStart();
            }

            bool FoundCV()
            {
                if (wordLen == pos) { syllables.Add(word.Substring(start, 2)); return true; }
                pos++;
                char ch = word[pos - 1];
                if (IsSegVowel(ch)) return FoundCVV();
                if (wordLen == pos) { syllables.Add(word.Substring(start, 3)); return true; }
                pos++;
                ch = word[pos - 1];
                if (IsSegVowel(ch)) { syllables.Add(word.Substring(start, 2)); start += 2; return FoundCV(); }
                if (wordLen == pos) { syllables.Add(word.Substring(start, 4)); return true; }
                pos++;
                ch = word[pos - 1];
                if (IsSegVowel(ch)) { syllables.Add(word.Substring(start, 3)); start += 3; return FoundCV(); }
                syllables.Add(word.Substring(start, 4));
                start += 4;
                return StateCV();
            }

            bool FoundCVV()
            {
                if (wordLen == pos) { syllables.Add(word.Substring(start, 3)); return true; }
                pos++;
                char ch = word[pos - 1];
                if (IsSegVowel(ch)) return false;
                if (wordLen == pos) { syllables.Add(word.Substring(start, 4)); return true; }
                pos++;
                ch = word[pos - 1];
                if (IsSegVowel(ch)) { syllables.Add(word.Substring(start, 3)); start += 3; return FoundCV(); }
                syllables.Add(word.Substring(start, 4));
                start += 4;
                return StateCV();
            }

            bool CCStart()
            {
                if (wordLen == pos) return false;
                pos++;
                char ch = word[pos - 1];
                if (!IsSegVowel(ch)) return false;
                if (wordLen == pos) { syllables.Add(word.Substring(start, 3)); return true; }
                pos++;
                ch = word[pos - 1];
                if (IsSegVowel(ch)) return false;
                if (wordLen == pos) { syllables.Add(word.Substring(start, 4)); return true; }
                pos++;
                ch = word[pos - 1];
                if (IsSegVowel(ch)) { syllables.Add(word.Substring(start, 3)); start += 3; return FoundCV(); }
                if (wordLen == pos) { syllables.Add(word.Substring(start, 5)); return true; }
                pos++;
                ch = word[pos - 1];
                if (IsSegVowel(ch)) { syllables.Add(word.Substring(start, 4)); start += 4; return FoundCV(); }
                syllables.Add(word.Substring(start, 5));
                start += 5;
                return StateCV();
            }

            pos = 1;
            bool ok = StateCV();
            return ok ? syllables : null;
        }

        public static List<(string fi1, string nfi1)> BuildContextKeys(List<string> syllables)
        {
            int count = syllables.Count;
            if (count == 0) return new List<(string, string)>();
            if (count == 1) return new List<(string, string)> { (syllables[0], syllables[0]) };

            var keys = new List<(string, string)>(count);

            string syl = syllables[0];
            char rc = syllables[1][0];
            keys.Add((syl + "-" + rc, syl + "-" + ClassifyNfi(rc)));

            for (int i = 1; i < count - 1; i++)
            {
                syl = syllables[i];
                char lc = syllables[i - 1][syllables[i - 1].Length - 1];
                rc = syllables[i + 1][0];
                keys.Add((lc + "-" + syl + "-" + rc, ClassifyNfi(lc) + "-" + syl + "-" + ClassifyNfi(rc)));
            }

            syl = syllables[count - 1];
            char lastLc = syllables[count - 2][syllables[count - 2].Length - 1];
            keys.Add((lastLc + "-" + syl, ClassifyNfi(lastLc) + "-" + syl));

            return keys;
        }

        #endregion

        #region PCM Helpers

        private static short[] PcmToSamples(byte[] pcm)
        {
            var samples = new short[pcm.Length / 2];
            Buffer.BlockCopy(pcm, 0, samples, 0, pcm.Length);
            return samples;
        }

        private static byte[] SamplesToPcm(double[] samples)
        {
            var shorts = new short[samples.Length];
            for (int i = 0; i < samples.Length; i++)
                shorts[i] = (short)Math.Max(-32768, Math.Min(32767, Math.Round(samples[i])));
            var result = new byte[shorts.Length * 2];
            Buffer.BlockCopy(shorts, 0, result, 0, result.Length);
            return result;
        }

        private static double[] Linspace(double start, double stop, int num)
        {
            if (num <= 0) return Array.Empty<double>();
            if (num == 1) return new[] { start };
            var result = new double[num];
            double step = (stop - start) / (num - 1);
            for (int i = 0; i < num; i++)
                result[i] = start + i * step;
            return result;
        }

        private static double[] Interp(double[] x, double[] xp, double[] fp,
            double? left = null, double? right = null)
        {
            double lv = left ?? fp[0];
            double rv = right ?? fp[fp.Length - 1];
            var result = new double[x.Length];
            int j = 0;
            for (int i = 0; i < x.Length; i++)
            {
                double xi = x[i];
                if (xi <= xp[0]) { result[i] = lv; continue; }
                if (xi >= xp[xp.Length - 1]) { result[i] = rv; continue; }
                while (j < xp.Length - 2 && xp[j + 1] < xi) j++;
                double t = (xi - xp[j]) / (xp[j + 1] - xp[j]);
                result[i] = fp[j] + t * (fp[j + 1] - fp[j]);
            }
            return result;
        }

        private static double[] MakeSilence(int sampleCount)
        {
            return new double[sampleCount];
        }

        #endregion

        #region Crossfade & Normalization

        public static byte[] HanningCrossfade(List<byte[]> segments, int overlap)
        {
            if (segments.Count == 0) return Array.Empty<byte>();
            if (segments.Count == 1) return segments[0];

            var arrays = segments.Select(PcmToSamples).ToArray();
            int totalLen = arrays.Sum(a => a.Length) - overlap * (arrays.Length - 1);
            var result = new double[totalLen];

            int pos = 0;
            for (int i = 0; i < arrays.Length; i++)
            {
                int len = arrays[i].Length;
                if (i == 0)
                {
                    for (int k = 0; k < len; k++)
                        result[pos + k] += arrays[i][k];
                    pos += len - overlap;
                }
                else
                {
                    int actualOverlap = Math.Min(overlap, Math.Min(pos + overlap - Math.Max(0, pos), len));
                    if (actualOverlap <= 0)
                    {
                        for (int k = 0; k < len && pos + k < result.Length; k++)
                            result[pos + k] += arrays[i][k];
                        pos += len - overlap;
                        continue;
                    }

                    var arrF = new double[len];
                    for (int k = 0; k < len; k++) arrF[k] = arrays[i][k];

                    int overlapStart = pos;
                    for (int k = 0; k < actualOverlap; k++)
                    {
                        double fadeOut = 0.5 * (1.0 + Math.Cos(Math.PI * k / actualOverlap));
                        double fadeIn = 0.5 * (1.0 - Math.Cos(Math.PI * k / actualOverlap));
                        result[overlapStart + k] *= fadeOut;
                        arrF[k] *= fadeIn;
                        result[overlapStart + k] += arrF[k];
                    }

                    int remaining = len - actualOverlap;
                    if (remaining > 0)
                    {
                        int destStart = overlapStart + actualOverlap;
                        int destEnd = destStart + remaining;
                        if (destEnd <= result.Length)
                        {
                            for (int k = 0; k < remaining; k++)
                                result[destStart + k] += arrF[actualOverlap + k];
                        }
                    }
                    pos = overlapStart + len - overlap;
                }
            }

            int outLen = Math.Max(1, pos + overlap);
            if (outLen > result.Length) outLen = result.Length;
            var final = new double[outLen];
            Array.Copy(result, final, outLen);
            return SamplesToPcm(final);
        }

        public static byte[] NormalizeWordRms(byte[] pcm, double targetRms = 3500.0)
        {
            var arr = PcmToSamples(pcm);
            if (arr.Length == 0) return pcm;
            double sumSq = 0;
            for (int i = 0; i < arr.Length; i++) sumSq += (double)arr[i] * arr[i];
            double rms = Math.Sqrt(sumSq / arr.Length);
            if (rms < 10) return pcm;
            double scale = Math.Min(targetRms / rms, 4.0);
            var result = new double[arr.Length];
            for (int i = 0; i < arr.Length; i++) result[i] = arr[i] * scale;
            return SamplesToPcm(result);
        }

        public static byte[] ResampleFactor(byte[] pcm, double factor)
        {
            var arr = PcmToSamples(pcm);
            int n = arr.Length;
            if (n < 2 || Math.Abs(factor - 1.0) < 1e-3) return pcm;
            int newN = Math.Max(2, (int)(n * factor));
            var x = Linspace(0, n - 1, newN);
            var xp = new double[n];
            var fp = new double[n];
            for (int i = 0; i < n; i++) { xp[i] = i; fp[i] = arr[i]; }
            var y = Interp(x, xp, fp);
            return SamplesToPcm(y);
        }

        #endregion

        #region PSOLA

        private static (double[] f0, int hop) EstimateF0(double[] x, int sr = SampleRate,
            int fmin = 70, int fmax = 350, int frameMs = 40, int hopMs = 10,
            double energyFloor = 140.0, double voicingRatio = 0.30)
        {
            int n = x.Length;
            int frame = frameMs * sr / 1000;
            int hop = hopMs * sr / 1000;
            if (n < frame) return (Array.Empty<double>(), hop);

            int minLag = sr / fmax;
            int maxLag = sr / fmin;
            int nFrames = (n - frame) / hop + 1;
            var f0 = new double[nFrames];

            for (int fi = 0; fi < nFrames; fi++)
            {
                int s = fi * hop;
                double mean = 0;
                for (int j = 0; j < frame; j++) mean += x[s + j];
                mean /= frame;

                var seg = new double[frame];
                for (int j = 0; j < frame; j++) seg[j] = x[s + j] - mean;

                double sumSq = 0;
                for (int j = 0; j < frame; j++) sumSq += seg[j] * seg[j];
                if (Math.Sqrt(sumSq / frame) < energyFloor) continue;

                int acLen = frame;
                var ac = new double[acLen];
                for (int lag = 0; lag < acLen; lag++)
                {
                    double sum = 0;
                    for (int j = 0; j < frame - lag; j++) sum += seg[j] * seg[j + lag];
                    ac[lag] = sum;
                }

                double r0 = ac[0];
                if (r0 <= 0 || acLen <= maxLag) continue;

                int regionLen = Math.Min(maxLag, acLen) - minLag;
                if (regionLen <= 0) continue;

                int peakIdx = minLag;
                double peakVal = ac[minLag];
                for (int k = minLag + 1; k < Math.Min(maxLag, acLen); k++)
                {
                    if (ac[k] > peakVal) { peakVal = ac[k]; peakIdx = k; }
                }

                if (ac[peakIdx] / r0 < voicingRatio) continue;

                double peak = peakIdx;
                if (peakIdx >= 1 && peakIdx < acLen - 1)
                {
                    double a = ac[peakIdx - 1], b = ac[peakIdx], c = ac[peakIdx + 1];
                    double denom = a - 2 * b + c;
                    if (Math.Abs(denom) > 1e-12)
                        peak = peakIdx + 0.5 * (a - c) / denom;
                }
                if (peak > 0) f0[fi] = (double)sr / peak;
            }
            return (f0, hop);
        }

        private static double[] MedianSmoothF0(double[] v, int k = 9)
        {
            var outArr = new double[v.Length];
            Array.Copy(v, outArr, v.Length);
            int half = k / 2;
            for (int i = 0; i < v.Length; i++)
            {
                int lo = Math.Max(0, i - half);
                int hi = Math.Min(v.Length, i + half + 1);
                var nz = new List<double>();
                for (int j = lo; j < hi; j++)
                    if (v[j] > 0) nz.Add(v[j]);
                if (nz.Count > 0)
                {
                    nz.Sort();
                    outArr[i] = nz[nz.Count / 2];
                }
            }
            return outArr;
        }

        public static byte[] PsolaPitchSmooth(byte[] pcm, int sr = SampleRate,
            int smoothK = 9, double declination = 0.90, double strength = 0.85)
        {
            var shortArr = PcmToSamples(pcm);
            int n = shortArr.Length;
            if (n < sr / 8) return pcm;

            var x = new double[n];
            for (int i = 0; i < n; i++) x[i] = shortArr[i];

            var (f0, hop) = EstimateF0(x, sr);
            if (f0.Length == 0) return pcm;
            int voiced = 0;
            for (int i = 0; i < f0.Length; i++) if (f0[i] > 0) voiced++;
            if (voiced < 3) return pcm;

            var centers = new double[f0.Length];
            for (int i = 0; i < f0.Length; i++) centers[i] = i * hop + hop / 2.0;

            var vtIdx = new List<int>();
            for (int i = 0; i < f0.Length; i++) if (f0[i] > 0) vtIdx.Add(i);
            if (vtIdx.Count < 2) return pcm;

            var vtCenters = vtIdx.Select(i => centers[i]).ToArray();
            var vtF0 = vtIdx.Select(i => f0[i]).ToArray();
            var xRange = new double[n];
            for (int i = 0; i < n; i++) xRange[i] = i;
            var src = Interp(xRange, vtCenters, vtF0, vtF0[0], vtF0[vtF0.Length - 1]);

            var sf0 = MedianSmoothF0(f0, smoothK);
            var svtIdx = new List<int>();
            for (int i = 0; i < sf0.Length; i++) if (sf0[i] > 0) svtIdx.Add(i);
            if (svtIdx.Count < 2) return pcm;

            var svtCenters = svtIdx.Select(i => centers[i]).ToArray();
            var svtF0 = svtIdx.Select(i => sf0[i]).ToArray();
            var smooth = Interp(xRange, svtCenters, svtF0, svtF0[0], svtF0[svtF0.Length - 1]);

            var decl = Linspace(1.0, declination, n);
            var tgt = new double[n];
            for (int i = 0; i < n; i++)
            {
                tgt[i] = smooth[i] * decl[i];
                tgt[i] = src[i] * (1 - strength) + tgt[i] * strength;
                tgt[i] = Math.Max(60.0, Math.Min(400.0, tgt[i]));
                src[i] = Math.Max(60.0, Math.Min(400.0, src[i]));
            }

            var marks = new List<int>();
            double mpos = 0;
            while (mpos < n) { marks.Add((int)mpos); mpos += sr / src[Math.Min((int)mpos, n - 1)]; }
            if (marks.Count < 3) return pcm;
            var aArr = marks.ToArray();

            var outBuf = new double[n + sr / 2];
            double opos = 0;
            while (opos < n)
            {
                int t = (int)Math.Round(opos);
                int nearestIdx = 0;
                double nearestDist = double.MaxValue;
                for (int k = 0; k < aArr.Length; k++)
                {
                    double d = Math.Abs(aArr[k] - t);
                    if (d < nearestDist) { nearestDist = d; nearestIdx = k; }
                }
                int ai = aArr[nearestIdx];
                int T = (int)(sr / src[Math.Min(ai, n - 1)]);
                if (T < 4) { opos += sr / tgt[Math.Min(t, n - 1)]; continue; }

                int left = Math.Max(0, ai - T);
                int right = Math.Min(n, ai + T);
                int frameLen = right - left;
                if (frameLen >= 4)
                {
                    var hann = new double[frameLen];
                    for (int j = 0; j < frameLen; j++)
                        hann[j] = 0.5 * (1.0 - Math.Cos(2.0 * Math.PI * j / (frameLen - 1)));

                    var frameBuf = new double[frameLen];
                    for (int j = 0; j < frameLen; j++) frameBuf[j] = x[left + j] * hann[j];

                    int fStart = t - (ai - left);
                    int fOffset = 0;
                    if (fStart < 0) { fOffset = -fStart; fStart = 0; }
                    int fEnd = fStart + (frameLen - fOffset);
                    if (fEnd > outBuf.Length) fEnd = outBuf.Length;

                    for (int j = 0; j < fEnd - fStart && fOffset + j < frameLen; j++)
                        outBuf[fStart + j] += frameBuf[fOffset + j];
                }
                opos += sr / tgt[Math.Min(t, n - 1)];
            }

            var outFinal = new double[n];
            Array.Copy(outBuf, outFinal, n);
            double srcRms = 0, outRms = 0;
            for (int i = 0; i < n; i++) { srcRms += x[i] * x[i]; outRms += outFinal[i] * outFinal[i]; }
            srcRms = Math.Sqrt(srcRms / n);
            outRms = Math.Sqrt(outRms / n);
            if (outRms > 1)
                for (int i = 0; i < n; i++) outFinal[i] *= srcRms / outRms;

            return SamplesToPcm(outFinal);
        }

        #endregion

        #region WAV Output

        public static void SaveWav(byte[] pcmData, string outputPath)
        {
            int dataLen = pcmData.Length;
            int bytesPerSec = SampleRate * Channels * (BitsPerSample / 8);
            int blockAlign = Channels * (BitsPerSample / 8);

            using (var fs = new FileStream(outputPath, FileMode.Create, FileAccess.Write))
            using (var bw = new BinaryWriter(fs))
            {
                bw.Write(System.Text.Encoding.ASCII.GetBytes("RIFF"));
                bw.Write(dataLen + 36);
                bw.Write(System.Text.Encoding.ASCII.GetBytes("WAVE"));
                bw.Write(System.Text.Encoding.ASCII.GetBytes("fmt "));
                bw.Write(16);
                bw.Write((short)1);
                bw.Write((short)Channels);
                bw.Write(SampleRate);
                bw.Write(bytesPerSec);
                bw.Write((short)blockAlign);
                bw.Write((short)BitsPerSample);
                bw.Write(System.Text.Encoding.ASCII.GetBytes("data"));
                bw.Write(dataLen);
                bw.Write(pcmData);
            }
        }

        #endregion

        #region Database & Segment I/O

        private byte[] ReadSegment(long begin, long end)
        {
            long key = begin * 100000000L + end;
            if (_segCache.TryGetValue(key, out var cached)) return cached;

            int len = (int)(end - begin);
            byte[] data = new byte[len];
            if (_accessor != null)
            {
                _accessor.ReadArray(begin, data, 0, len);
            }
            else
            {
                _datFile.Seek(begin, SeekOrigin.Begin);
                _datFile.Read(data, 0, len);
            }

            _segCache[key] = data;
            _cacheOrder.Add(key);
            if (_cacheOrder.Count > CacheCap)
            {
                long old = _cacheOrder[0];
                _cacheOrder.RemoveAt(0);
                _segCache.Remove(old);
            }
            return data;
        }

        private struct SyllableRow
        {
            public long BeginPos;
            public long EndPos;
        }

        private (List<SyllableRow> rows, string matchType) LookupCandidates(
            string fi1Key, string nfi1Key, int limit = 8)
        {
            var plans = new[]
            {
                ("FISyllable1", fi1Key, "FI1"),
                ("NFISyllable1", nfi1Key, "NFI1"),
                ("FISyllable2", fi1Key, "FI2"),
                ("NFISyllable2", nfi1Key, "NFI2"),
            };

            foreach (var (col, queryKey, mt) in plans)
            {
                using var cmd = _conn.CreateCommand();
                cmd.CommandText = $"SELECT begin_pos, end_pos FROM Syllable WHERE {col}=@key LIMIT @limit";
                cmd.Parameters.AddWithValue("@key", queryKey);
                cmd.Parameters.AddWithValue("@limit", limit);

                var rows = new List<SyllableRow>();
                using var reader = cmd.ExecuteReader();
                while (reader.Read())
                {
                    rows.Add(new SyllableRow
                    {
                        BeginPos = reader.GetInt64(0),
                        EndPos = reader.GetInt64(1),
                    });
                }
                if (rows.Count > 0) return (rows, mt);
            }
            return (new List<SyllableRow>(), null);
        }

        private SyllableRow PickUnit(List<SyllableRow> rows, short[] prevTail, SynthesisOptions opts)
        {
            if (opts.UnitSelection != UnitSelectionMode.JoinCost || rows.Count == 1
                || prevTail == null || prevTail.Length == 0)
                return rows[0];

            double pe = prevTail[prevTail.Length - 1];
            double pSumSq = 0;
            for (int i = 0; i < prevTail.Length; i++) pSumSq += (double)prevTail[i] * prevTail[i];
            double prms = Math.Sqrt(pSumSq / prevTail.Length);

            var best = rows[0];
            double bestCost = double.MaxValue;

            foreach (var r in rows)
            {
                var data = ReadSegment(r.BeginPos, r.EndPos);
                var head = PcmToSamples(data);
                int headLen = Math.Min(80, head.Length);
                if (headLen == 0) continue;

                double he = head[0];
                double hSumSq = 0;
                for (int i = 0; i < headLen; i++) hSumSq += (double)head[i] * head[i];
                double hrms = Math.Sqrt(hSumSq / headLen);

                double cost = Math.Abs(he - pe) + 0.5 * Math.Abs(hrms - prms);
                if (cost < bestCost) { bestCost = cost; best = r; }
            }
            return best;
        }

        #endregion

        #region Synthesis

        public (byte[] pcm, int total) SynthesizePcm(string text, SynthesisOptions opts = null)
        {
            if (opts == null) opts = SynthesisOptions.Profiles["raw"];
            int overlap = opts.CrossfadeMs * SampleRate / 1000;

            var tokens = text.Split(new[] { ' ', '\t', '\r', '\n' }, StringSplitOptions.RemoveEmptyEntries);
            var plan = new List<(string word, int pauseMs)>();

            foreach (var tok in tokens)
            {
                string w = CleanWord(tok);
                if (string.IsNullOrEmpty(w)) continue;

                int pause = opts.WordPauseMs;
                if (opts.Prosody)
                {
                    for (int ci = tok.Length - 1; ci >= 0; ci--)
                    {
                        if (PunctPauseMs.TryGetValue(tok[ci], out int pp)) { pause = pp; break; }
                        if (UyghurLetters.Contains(tok[ci])) break;
                    }
                }
                plan.Add((w, pause));
            }

            int nWords = plan.Count;
            var wordItems = new List<(byte[] pcm, int pauseMs)>();
            int total = 0;
            short[] prevTail = null;

            for (int wi = 0; wi < plan.Count; wi++)
            {
                var (word, pause) = plan[wi];
                var syllables = Word2Syllable(word);
                if (syllables == null) continue;

                var wordSegments = new List<byte[]>();
                var keys = BuildContextKeys(syllables);

                for (int si = 0; si < keys.Count; si++)
                {
                    var (fi1, nfi1) = keys[si];
                    var (rows, matchType) = LookupCandidates(fi1, nfi1, opts.Candidates);
                    if (rows.Count == 0) continue;

                    var row = PickUnit(rows, prevTail, opts);
                    byte[] seg = ReadSegment(row.BeginPos, row.EndPos);
                    wordSegments.Add(seg);

                    var arr = PcmToSamples(seg);
                    prevTail = arr.Length >= 80
                        ? arr.Skip(arr.Length - 80).ToArray()
                        : arr;
                    total++;
                }

                if (wordSegments.Count == 0) continue;

                byte[] wordPcm = HanningCrossfade(wordSegments, overlap);
                if (opts.EnergyNorm) wordPcm = NormalizeWordRms(wordPcm);
                if (opts.Prosody && wi == nWords - 1 && pause >= 300)
                    wordPcm = ResampleFactor(wordPcm, 1.06);
                wordItems.Add((wordPcm, pause));
            }

            if (wordItems.Count == 0) return (Array.Empty<byte>(), 0);

            var finalParts = new List<byte[]>();
            int wordPauseSamples = opts.WordPauseMs * SampleRate / 1000;

            for (int i = 0; i < wordItems.Count; i++)
            {
                finalParts.Add(wordItems[i].pcm);
                if (i < wordItems.Count - 1)
                {
                    int ps = opts.Prosody
                        ? wordItems[i].pauseMs * SampleRate / 1000
                        : wordPauseSamples;
                    finalParts.Add(new byte[ps * 2]);
                }
            }

            byte[] pcmData;
            using (var ms = new MemoryStream())
            {
                foreach (var part in finalParts) ms.Write(part, 0, part.Length);
                pcmData = ms.ToArray();
            }

            if (opts.Psola)
            {
                try { pcmData = PsolaPitchSmooth(pcmData); }
                catch { /* PSOLA skipped */ }
            }

            return (pcmData, total);
        }

        public void Synthesize(string text, string outputPath, SynthesisOptions opts = null)
        {
            var (pcmData, total) = SynthesizePcm(text, opts);
            if (pcmData == null || pcmData.Length == 0) return;
            SaveWav(pcmData, outputPath);
        }

        #endregion

        public void Dispose()
        {
            if (_disposed) return;
            _disposed = true;
            _accessor?.Dispose();
            _mmf?.Dispose();
            _datFile?.Dispose();
            _conn?.Dispose();
        }
    }
}
