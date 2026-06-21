import java.awt.*;
import java.awt.event.*;
import java.awt.image.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.List;
import javax.imageio.*;
import javax.sound.sampled.*;
import javax.swing.*;
import javax.swing.filechooser.*;

public class UighurTTS extends JFrame {

    // ==================== Audio Constants ====================
    static final int SAMPLE_RATE = 16000;
    static final int BITS_PER_SAMPLE = 16;
    static final int CHANNELS = 1;

    // ==================== Character Sets ====================
    static final Set<Character> SEG_VOWELS = new HashSet<>(Arrays.asList(
            '\u0627', '\u0648', '\u0649', '\u06C6', '\u06C7', '\u06C8', '\u06D0', '\u06D5'));

    static final Set<Character> NFI_VOWELS = new HashSet<>(Arrays.asList(
            '\u0626', '\u0627', '\u0648', '\u0649', '\u06C6', '\u06C7', '\u06C8', '\u06D0', '\u06D5'));

    static final Set<Character> VOICED_CONSONANTS = new HashSet<>(Arrays.asList(
            '\u0628', '\u062C', '\u062F', '\u0631', '\u0632', '\u0698', '\u063A',
            '\u06AF', '\u06AD', '\u0644', '\u0645', '\u0646', '\u064E', '\u06CB', '\u064A'));

    static final Set<Character> UYGHUR_LETTERS = new HashSet<>(Arrays.asList(
            '\u0626', '\u0627', '\u06D5', '\u06D0', '\u0649', '\u0648', '\u06C7', '\u06C6', '\u06C8',
            '\u0628', '\u067E', '\u062A', '\u062C', '\u0686', '\u062E', '\u062F', '\u0631', '\u0632',
            '\u0698', '\u0633', '\u0634', '\u063A', '\u0641', '\u0642', '\u0643', '\u06AF', '\u06AD',
            '\u0644', '\u0645', '\u0646', '\u06BE', '\u06CB', '\u064A'));

    // ==================== Punctuation Pauses (ms) ====================
    static final Map<Character, Integer> PUNCT_PAUSE_MS = new HashMap<>();
    static {
        PUNCT_PAUSE_MS.put('\u060C', 170); PUNCT_PAUSE_MS.put(',', 170);
        PUNCT_PAUSE_MS.put('\u061B', 200); PUNCT_PAUSE_MS.put(';', 200);
        PUNCT_PAUSE_MS.put('\u3001', 150);
        PUNCT_PAUSE_MS.put('.', 300); PUNCT_PAUSE_MS.put('\u06D4', 300);
        PUNCT_PAUSE_MS.put('\u3002', 320);
        PUNCT_PAUSE_MS.put('!', 300); PUNCT_PAUSE_MS.put('\uFF01', 300);
        PUNCT_PAUSE_MS.put('?', 320); PUNCT_PAUSE_MS.put('\u061F', 320);
        PUNCT_PAUSE_MS.put('\uFF1F', 320);
        PUNCT_PAUSE_MS.put(':', 200); PUNCT_PAUSE_MS.put('\uFF1A', 200);
    }

    // ==================== Profile Options ====================
    static class ProfileOptions {
        int crossfadeMs, wordPauseMs, candidates;
        String unitSelection;
        boolean energyNorm, prosody, psola;

        ProfileOptions(int cfMs, int wpMs, String us, boolean en, boolean pr, int cand, boolean ps) {
            crossfadeMs = cfMs; wordPauseMs = wpMs; unitSelection = us;
            energyNorm = en; prosody = pr; candidates = cand; psola = ps;
        }

        ProfileOptions copy() {
            return new ProfileOptions(crossfadeMs, wordPauseMs, unitSelection,
                    energyNorm, prosody, candidates, psola);
        }
    }

    static final Map<String, ProfileOptions> PROFILES = new LinkedHashMap<>();
    static {
        PROFILES.put("raw",     new ProfileOptions(30, 80,  "first",     false, false, 1, false));
        PROFILES.put("smooth",  new ProfileOptions(50, 80,  "first",     true,  false, 1, false));
        PROFILES.put("smart",   new ProfileOptions(50, 80,  "join_cost", true,  false, 8, false));
        PROFILES.put("prosody", new ProfileOptions(55, 100, "join_cost", true,  true,  8, false));
        PROFILES.put("hifi",    new ProfileOptions(55, 100, "join_cost", true,  true,  8, true));
    }

    // ==================== UI Constants ====================
    static final String TITLE_UY = "robot AI";
    static final String TITLE_CN = "AI \u8BED \u97F3 \u5408 \u6210 \u7CFB \u7EDF";
    static final String ORG_UY = "robot AI \u0626\u0627\u06CB\u0627\u0632 \u0628\u0649\u0631\u0649\u0643\u062A\u06C8\u0631\u06C8\u0634 \u0633\u0649\u0633\u062A\u06D0\u0645\u0649\u0633\u0649";
    static final String ORG_CN = "robot AI \u8BED\u97F3\u5408\u6210\u7CFB\u7EDF";
    static final String ADDR_UY = "UighurTTS \u97F3\u8282\u62FC\u63A5\u5F15\u64CE";
    static final String SAMPLE_TEXT = "\u0626\u06C7\u064A\u063A\u06C7\u0631\u0686\u06D5 \u0626\u0627\u06CB\u0627\u0632 \u0628\u0649\u0631\u0649\u0643\u062A\u06C8\u0631\u06C8\u0634 \u0633\u06D0\u0633\u062A\u0649\u0645\u0649\u0633\u0649";

    static final String[][] BTN_SPECS = {
        {"\u9000\u51FA",     "\u0686\u06D0\u0643\u0649\u0646\u0649\u0634"},
        {"\u4FDD\u5B58",     "\u0633\u0627\u0642\u0644\u0649\u06CB\u06D0\u0644\u0649\u0634"},
        {"\u505C\u6B62",     "\u062A\u0648\u062E\u062A\u0649\u062A\u0649\u0634"},
        {"\u6717\u8BFB",     "\u0626\u0648\u0642\u06C7\u0634"},
        {"\u6587\u4EF6\u6253\u5F00", "\u06BE\u06C6\u062C\u062C\u06D5\u062A\u062A\u0649\u0646 \u0626\u06D0\u0686\u0649\u0634"},
    };

    static final String[][] PROFILE_CHOICES = {
        {"raw",     "\u539F\u59CB",       "30ms \u62FC\u63A5 / \u9996\u4E2A\u5355\u5143"},
        {"smooth",  "\u5E73\u6ED1\u589E\u5F3A", "50ms \u62FC\u63A5 + \u54CD\u5EA6\u5F52\u4E00"},
        {"smart",   "\u667A\u80FD\u9009\u97F3", "\u591A\u5019\u9009 join-cost \u9009\u97F3"},
        {"prosody", "\u97F5\u5F8B\u81EA\u7136", "\u9009\u97F3 + \u53E5\u8BFB\u505C\u987F + \u53E5\u672B\u964D\u8C03"},
        {"hifi",    "\u9AD8\u4FDD\u771F",   "PSOLA \u57FA\u9891\u5E73\u6ED1 + \u53E5\u672B\u964D\u8C03"},
    };

    static final Color GRAD_TOP    = new Color(0x33, 0x77, 0xCC);
    static final Color GRAD_BOTTOM = new Color(0x6F, 0xA8, 0xE8);
    static final Color COL_TITLE_UY = new Color(0xC8, 0x1E, 0x1E);
    static final Color COL_TITLE_CN = new Color(0x11, 0x31, 0x7A);
    static final Color COL_INFO   = new Color(0x0B, 0x2A, 0x5B);
    static final Color COL_PANEL  = new Color(0xEA, 0xEF, 0xF6);

    // ==================== Data classes ====================
    static class SylRow {
        long beginPos, endPos;
        SylRow(long b, long e) { beginPos = b; endPos = e; }
    }

    static class LookupResult {
        List<SylRow> rows;
        String matchType;
        LookupResult(List<SylRow> r, String mt) { rows = r; matchType = mt; }
    }

    static class SynthResult {
        byte[] pcm;
        int totalSegments;
        SynthResult(byte[] p, int t) { pcm = p; totalSegments = t; }
    }

    // ==================== SyllableDB ====================
    static class SyllableDB {
        private Connection conn;

        SyllableDB(String dbPath) throws Exception {
            Class.forName("org.sqlite.JDBC");
            conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        }

        LookupResult lookupCandidates(String fi1Key, String nfi1Key, int limit) {
            String[][] plans = {
                {"FISyllable1",  fi1Key,  "FI1"},
                {"NFISyllable1", nfi1Key, "NFI1"},
                {"FISyllable2",  fi1Key,  "FI2"},
                {"NFISyllable2", nfi1Key, "NFI2"},
            };
            for (String[] plan : plans) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT begin_pos, end_pos FROM Syllable WHERE " + plan[0] + "=? LIMIT ?")) {
                    ps.setString(1, plan[1]);
                    ps.setInt(2, limit);
                    ResultSet rs = ps.executeQuery();
                    List<SylRow> rows = new ArrayList<>();
                    while (rs.next())
                        rows.add(new SylRow(rs.getLong("begin_pos"), rs.getLong("end_pos")));
                    if (!rows.isEmpty())
                        return new LookupResult(rows, plan[2]);
                } catch (SQLException ignored) {}
            }
            return new LookupResult(new ArrayList<>(), null);
        }

        void close() { try { conn.close(); } catch (Exception ignored) {} }
    }

    // ==================== Syllable Segmentation ====================
    static boolean isSegVowel(char c) { return SEG_VOWELS.contains(c); }

    static String classifyNfi(char c) {
        if (NFI_VOWELS.contains(c)) return "V";
        if (VOICED_CONSONANTS.contains(c)) return "C";
        return "U";
    }

    static String cleanWord(String word) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (UYGHUR_LETTERS.contains(c)) sb.append(c);
        }
        return sb.toString();
    }

    static List<String> word2syllable(String word) {
        int wordLen = word.length();
        if (wordLen == 0) return null;
        if (wordLen == 1) {
            if (isSegVowel(word.charAt(0))) return null;
            return new ArrayList<>(Collections.singletonList(word));
        }
        if (isSegVowel(word.charAt(0))) return null;

        List<String> syllables = new ArrayList<>();
        int[] st = {1, 0}; // st[0]=pos, st[1]=start
        boolean ok = stateCv(word, wordLen, st, syllables);
        return ok ? syllables : null;
    }

    private static boolean stateCv(String w, int wl, int[] st, List<String> syls) {
        st[0]++;
        if (st[0] > wl) return false;
        char ch = w.charAt(st[0] - 1);
        if (isSegVowel(ch)) return foundCv(w, wl, st, syls);
        return ccStart(w, wl, st, syls);
    }

    private static boolean foundCv(String w, int wl, int[] st, List<String> syls) {
        if (wl == st[0]) { syls.add(w.substring(st[1], st[1] + 2)); return true; }
        st[0]++;
        char ch = w.charAt(st[0] - 1);
        if (isSegVowel(ch)) return foundCvv(w, wl, st, syls);
        if (wl == st[0]) { syls.add(w.substring(st[1], st[1] + 3)); return true; }
        st[0]++;
        ch = w.charAt(st[0] - 1);
        if (isSegVowel(ch)) {
            syls.add(w.substring(st[1], st[1] + 2)); st[1] += 2;
            return foundCv(w, wl, st, syls);
        }
        if (wl == st[0]) { syls.add(w.substring(st[1], st[1] + 4)); return true; }
        st[0]++;
        ch = w.charAt(st[0] - 1);
        if (isSegVowel(ch)) {
            syls.add(w.substring(st[1], st[1] + 3)); st[1] += 3;
            return foundCv(w, wl, st, syls);
        }
        syls.add(w.substring(st[1], st[1] + 4)); st[1] += 4;
        return stateCv(w, wl, st, syls);
    }

    private static boolean foundCvv(String w, int wl, int[] st, List<String> syls) {
        if (wl == st[0]) { syls.add(w.substring(st[1], st[1] + 3)); return true; }
        st[0]++;
        char ch = w.charAt(st[0] - 1);
        if (isSegVowel(ch)) return false;
        if (wl == st[0]) { syls.add(w.substring(st[1], st[1] + 4)); return true; }
        st[0]++;
        ch = w.charAt(st[0] - 1);
        if (isSegVowel(ch)) {
            syls.add(w.substring(st[1], st[1] + 3)); st[1] += 3;
            return foundCv(w, wl, st, syls);
        }
        syls.add(w.substring(st[1], st[1] + 4)); st[1] += 4;
        return stateCv(w, wl, st, syls);
    }

    private static boolean ccStart(String w, int wl, int[] st, List<String> syls) {
        if (wl == st[0]) return false;
        st[0]++;
        char ch = w.charAt(st[0] - 1);
        if (!isSegVowel(ch)) return false;
        if (wl == st[0]) { syls.add(w.substring(st[1], st[1] + 3)); return true; }
        st[0]++;
        ch = w.charAt(st[0] - 1);
        if (isSegVowel(ch)) return false;
        if (wl == st[0]) { syls.add(w.substring(st[1], st[1] + 4)); return true; }
        st[0]++;
        ch = w.charAt(st[0] - 1);
        if (isSegVowel(ch)) {
            syls.add(w.substring(st[1], st[1] + 3)); st[1] += 3;
            return foundCv(w, wl, st, syls);
        }
        if (wl == st[0]) { syls.add(w.substring(st[1], st[1] + 5)); return true; }
        st[0]++;
        ch = w.charAt(st[0] - 1);
        if (isSegVowel(ch)) {
            syls.add(w.substring(st[1], st[1] + 4)); st[1] += 4;
            return foundCv(w, wl, st, syls);
        }
        syls.add(w.substring(st[1], st[1] + 5)); st[1] += 5;
        return stateCv(w, wl, st, syls);
    }

    // ==================== Context Keys ====================
    static List<String[]> buildContextKeys(List<String> syllables) {
        int count = syllables.size();
        if (count == 0) return new ArrayList<>();
        List<String[]> keys = new ArrayList<>();
        if (count == 1) {
            String s = syllables.get(0);
            keys.add(new String[]{s, s});
            return keys;
        }
        // first
        String syl = syllables.get(0);
        char rc = syllables.get(1).charAt(0);
        keys.add(new String[]{syl + "-" + rc, syl + "-" + classifyNfi(rc)});
        // middle
        for (int i = 1; i < count - 1; i++) {
            syl = syllables.get(i);
            char lc = syllables.get(i - 1).charAt(syllables.get(i - 1).length() - 1);
            rc = syllables.get(i + 1).charAt(0);
            keys.add(new String[]{lc + "-" + syl + "-" + rc,
                    classifyNfi(lc) + "-" + syl + "-" + classifyNfi(rc)});
        }
        // last
        syl = syllables.get(count - 1);
        char lc = syllables.get(count - 2).charAt(syllables.get(count - 2).length() - 1);
        keys.add(new String[]{lc + "-" + syl, classifyNfi(lc) + "-" + syl});
        return keys;
    }

    // ==================== PCM Utilities ====================
    static short[] pcmToSamples(byte[] pcm) {
        ShortBuffer sb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        short[] samples = new short[pcm.length / 2];
        sb.get(samples);
        return samples;
    }

    static byte[] samplesToPcm(double[] arr) {
        ByteBuffer bb = ByteBuffer.allocate(arr.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (double v : arr)
            bb.putShort((short) Math.max(-32768, Math.min(32767, Math.round(v))));
        return bb.array();
    }

    static byte[] shortsToPcm(short[] arr) {
        ByteBuffer bb = ByteBuffer.allocate(arr.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short s : arr) bb.putShort(s);
        return bb.array();
    }

    static double rms(short[] arr, int off, int len) {
        double sum = 0;
        for (int i = off; i < off + len; i++) sum += (double) arr[i] * arr[i];
        return Math.sqrt(sum / len);
    }

    static double[] hanningWindow(int n) {
        double[] w = new double[n];
        if (n == 1) { w[0] = 1; return w; }
        for (int i = 0; i < n; i++)
            w[i] = 0.5 * (1 - Math.cos(2 * Math.PI * i / (n - 1)));
        return w;
    }

    static double[] linspace(double start, double end, int n) {
        double[] r = new double[n];
        if (n == 1) { r[0] = start; return r; }
        for (int i = 0; i < n; i++) r[i] = start + (end - start) * i / (n - 1);
        return r;
    }

    static double[] interp(double[] x, double[] xp, double[] fp, double left, double right) {
        double[] result = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            if (x[i] <= xp[0]) { result[i] = left; continue; }
            if (x[i] >= xp[xp.length - 1]) { result[i] = right; continue; }
            int lo = 0, hi = xp.length - 1;
            while (lo < hi - 1) {
                int mid = (lo + hi) / 2;
                if (xp[mid] <= x[i]) lo = mid; else hi = mid;
            }
            double t = (x[i] - xp[lo]) / (xp[hi] - xp[lo]);
            result[i] = fp[lo] + t * (fp[hi] - fp[lo]);
        }
        return result;
    }

    // ==================== Hanning Crossfade ====================
    static byte[] hanningCrossfade(List<byte[]> segments, int overlap) {
        if (segments.isEmpty()) return new byte[0];
        if (segments.size() == 1) return segments.get(0);

        List<short[]> arrays = new ArrayList<>();
        for (byte[] s : segments) arrays.add(pcmToSamples(s));

        int totalLen = 0;
        for (short[] a : arrays) totalLen += a.length;
        totalLen -= overlap * (arrays.size() - 1);
        if (totalLen < 1) totalLen = 1;
        double[] result = new double[totalLen];

        int pos = 0;
        for (int i = 0; i < arrays.size(); i++) {
            short[] arr = arrays.get(i);
            if (i == 0) {
                for (int j = 0; j < arr.length && pos + j < result.length; j++)
                    result[pos + j] += arr[j];
                pos += arr.length - overlap;
            } else {
                int actualOverlap = Math.min(overlap, arr.length);
                actualOverlap = Math.min(actualOverlap, result.length - pos);
                if (actualOverlap <= 0) {
                    for (int j = 0; j < arr.length && pos + j < result.length; j++)
                        result[pos + j] += arr[j];
                    pos += arr.length - overlap;
                    continue;
                }
                double[] fadeOut = new double[actualOverlap];
                double[] fadeIn  = new double[actualOverlap];
                for (int j = 0; j < actualOverlap; j++) {
                    fadeOut[j] = 0.5 * (1 + Math.cos(Math.PI * j / actualOverlap));
                    fadeIn[j]  = 0.5 * (1 - Math.cos(Math.PI * j / actualOverlap));
                }
                int ovStart = pos;
                for (int j = 0; j < actualOverlap; j++)
                    result[ovStart + j] *= fadeOut[j];
                double[] arrF = new double[arr.length];
                for (int j = 0; j < arr.length; j++) arrF[j] = arr[j];
                for (int j = 0; j < actualOverlap; j++)
                    arrF[j] *= fadeIn[j];
                for (int j = 0; j < actualOverlap; j++)
                    result[ovStart + j] += arrF[j];
                int remaining = arr.length - actualOverlap;
                if (remaining > 0) {
                    int dstStart = ovStart + actualOverlap;
                    for (int j = 0; j < remaining && dstStart + j < result.length; j++)
                        result[dstStart + j] += arrF[actualOverlap + j];
                }
                pos = ovStart + arr.length - overlap;
            }
        }
        int outLen = Math.max(1, pos + overlap);
        if (outLen > result.length) outLen = result.length;
        return samplesToPcm(Arrays.copyOf(result, outLen));
    }

    // ==================== Energy Normalization ====================
    static byte[] normalizeWordRms(byte[] pcm, double targetRms) {
        short[] arr = pcmToSamples(pcm);
        if (arr.length == 0) return pcm;
        double r = rms(arr, 0, arr.length);
        if (r < 10) return pcm;
        double scale = Math.min(targetRms / r, 4.0);
        double[] out = new double[arr.length];
        for (int i = 0; i < arr.length; i++) out[i] = arr[i] * scale;
        return samplesToPcm(out);
    }

    // ==================== Resampling ====================
    static byte[] resampleFactor(byte[] pcm, double factor) {
        short[] arr = pcmToSamples(pcm);
        int n = arr.length;
        if (n < 2 || Math.abs(factor - 1.0) < 1e-3) return pcm;
        int newN = Math.max(2, (int)(n * factor));
        double[] src = new double[n];
        for (int i = 0; i < n; i++) src[i] = arr[i];
        double[] xOld = linspace(0, n - 1, n);
        double[] xNew = linspace(0, n - 1, newN);
        double[] y = interp(xNew, xOld, src, src[0], src[n - 1]);
        return samplesToPcm(y);
    }

    // ==================== F0 Estimation ====================
    static double[] estimateF0(double[] x, int sr, int[] outHop) {
        int frameMs = 40, hopMs = 10;
        double fmin = 70, fmax = 350, energyFloor = 140.0, voicingRatio = 0.30;
        int n = x.length;
        int frame = frameMs * sr / 1000;
        int hop = hopMs * sr / 1000;
        outHop[0] = hop;
        if (n < frame) return new double[0];
        int minLag = (int)(sr / fmax);
        int maxLag = (int)(sr / fmin);
        int nFrames = (n - frame) / hop + 1;
        double[] f0 = new double[nFrames];

        for (int fi = 0; fi < nFrames; fi++) {
            int s = fi * hop;
            double mean = 0;
            for (int j = s; j < s + frame; j++) mean += x[j];
            mean /= frame;
            double[] seg = new double[frame];
            for (int j = 0; j < frame; j++) seg[j] = x[s + j] - mean;
            double segRms = 0;
            for (double v : seg) segRms += v * v;
            segRms = Math.sqrt(segRms / frame);
            if (segRms < energyFloor) continue;

            int acLen = Math.min(maxLag + 1, frame);
            double[] ac = new double[acLen];
            for (int k = 0; k < acLen; k++) {
                double sum = 0;
                for (int j = 0; j < frame - k; j++) sum += seg[j] * seg[j + k];
                ac[k] = sum;
            }
            double r0 = ac[0];
            if (r0 <= 0 || acLen <= maxLag) continue;
            if (maxLag > acLen) continue;

            int regionLen = maxLag - minLag;
            if (regionLen <= 0) continue;
            int peakIdx = minLag;
            double peakVal = ac[minLag];
            for (int k = minLag + 1; k < maxLag && k < acLen; k++) {
                if (ac[k] > peakVal) { peakVal = ac[k]; peakIdx = k; }
            }
            if (peakVal / r0 < voicingRatio) continue;

            double peak = peakIdx;
            if (peakIdx >= 1 && peakIdx < acLen - 1) {
                double a = ac[peakIdx - 1], b = ac[peakIdx], c = ac[peakIdx + 1];
                double denom = a - 2 * b + c;
                if (denom != 0) peak = peakIdx + 0.5 * (a - c) / denom;
            }
            if (peak > 0) f0[fi] = (double) sr / peak;
        }
        return f0;
    }

    // ==================== Median Smooth F0 ====================
    static double[] medianSmoothF0(double[] v, int k) {
        double[] out = v.clone();
        int half = k / 2;
        for (int i = 0; i < v.length; i++) {
            int lo = Math.max(0, i - half), hi = Math.min(v.length, i + half + 1);
            List<Double> nz = new ArrayList<>();
            for (int j = lo; j < hi; j++) if (v[j] > 0) nz.add(v[j]);
            if (!nz.isEmpty()) {
                Collections.sort(nz);
                out[i] = nz.get(nz.size() / 2);
            }
        }
        return out;
    }

    // ==================== PSOLA Pitch Smoothing ====================
    static byte[] psolaPitchSmooth(byte[] pcm) {
        int sr = SAMPLE_RATE;
        int smoothK = 9;
        double declination = 0.90, strength = 0.85;

        short[] sArr = pcmToSamples(pcm);
        int n = sArr.length;
        if (n < sr / 8) return pcm;
        double[] x = new double[n];
        for (int i = 0; i < n; i++) x[i] = sArr[i];

        int[] outHop = new int[1];
        double[] f0 = estimateF0(x, sr, outHop);
        int hop = outHop[0];
        if (f0.length == 0) return pcm;
        int voicedCount = 0;
        for (double v : f0) if (v > 0) voicedCount++;
        if (voicedCount < 3) return pcm;

        double[] centers = new double[f0.length];
        for (int i = 0; i < f0.length; i++) centers[i] = i * hop + hop / 2.0;

        List<Integer> vtList = new ArrayList<>();
        for (int i = 0; i < f0.length; i++) if (f0[i] > 0) vtList.add(i);
        if (vtList.size() < 2) return pcm;

        double[] vtCenters = new double[vtList.size()];
        double[] vtF0 = new double[vtList.size()];
        for (int i = 0; i < vtList.size(); i++) {
            vtCenters[i] = centers[vtList.get(i)];
            vtF0[i] = f0[vtList.get(i)];
        }

        double[] xIdx = new double[n];
        for (int i = 0; i < n; i++) xIdx[i] = i;
        double[] src = interp(xIdx, vtCenters, vtF0, vtF0[0], vtF0[vtF0.length - 1]);

        double[] sf0 = medianSmoothF0(f0, smoothK);
        List<Integer> svtList = new ArrayList<>();
        for (int i = 0; i < sf0.length; i++) if (sf0[i] > 0) svtList.add(i);
        if (svtList.isEmpty()) return pcm;
        double[] svtCenters = new double[svtList.size()];
        double[] svtF0 = new double[svtList.size()];
        for (int i = 0; i < svtList.size(); i++) {
            svtCenters[i] = centers[svtList.get(i)];
            svtF0[i] = sf0[svtList.get(i)];
        }
        double[] smooth = interp(xIdx, svtCenters, svtF0, svtF0[0], svtF0[svtF0.length - 1]);

        double[] decl = linspace(1.0, declination, n);
        double[] tgt = new double[n];
        for (int i = 0; i < n; i++) {
            tgt[i] = smooth[i] * decl[i];
            tgt[i] = src[i] * (1 - strength) + tgt[i] * strength;
            tgt[i] = Math.max(60.0, Math.min(400.0, tgt[i]));
            src[i] = Math.max(60.0, Math.min(400.0, src[i]));
        }

        List<Integer> marks = new ArrayList<>();
        double pos = 0;
        while (pos < n) {
            marks.add((int) pos);
            int idx = Math.min((int) pos, n - 1);
            pos += (double) sr / src[idx];
        }
        if (marks.size() < 3) return pcm;
        int[] marksArr = marks.stream().mapToInt(Integer::intValue).toArray();

        double[] out = new double[n + sr / 2];
        pos = 0;
        while (pos < n) {
            int t = (int) Math.round(pos);
            int ai = findClosest(marksArr, t);
            int T = (int)((double) sr / src[Math.min(ai, n - 1)]);
            if (T < 4) {
                pos += (double) sr / tgt[Math.min(t, n - 1)];
                continue;
            }
            int l = Math.max(0, ai - T);
            int r = Math.min(n, ai + T);
            int fLen = r - l;
            if (fLen >= 4) {
                double[] hw = hanningWindow(fLen);
                double[] frame = new double[fLen];
                for (int j = 0; j < fLen; j++) frame[j] = x[l + j] * hw[j];
                int start = t - (ai - l);
                if (start < 0) {
                    int skip = -start;
                    double[] trimmed = new double[fLen - skip];
                    System.arraycopy(frame, skip, trimmed, 0, trimmed.length);
                    frame = trimmed;
                    fLen = frame.length;
                    start = 0;
                }
                int end = start + fLen;
                if (end > out.length) { end = out.length; fLen = end - start; }
                for (int j = 0; j < fLen; j++) out[start + j] += frame[j];
            }
            pos += (double) sr / tgt[Math.min(t, n - 1)];
        }

        double[] outTrim = Arrays.copyOf(out, n);
        double srcRms = 0, outRms = 0;
        for (int i = 0; i < n; i++) { srcRms += x[i] * x[i]; outRms += outTrim[i] * outTrim[i]; }
        srcRms = Math.sqrt(srcRms / n);
        outRms = Math.sqrt(outRms / n);
        if (outRms > 1) {
            double scale = srcRms / outRms;
            for (int i = 0; i < n; i++) outTrim[i] *= scale;
        }
        return samplesToPcm(outTrim);
    }

    static int findClosest(int[] arr, int target) {
        int lo = 0, hi = arr.length - 1;
        while (lo < hi - 1) {
            int mid = (lo + hi) / 2;
            if (arr[mid] <= target) lo = mid; else hi = mid;
        }
        return Math.abs(arr[lo] - target) <= Math.abs(arr[hi] - target) ? arr[lo] : arr[hi];
    }

    // ==================== WAV File I/O ====================
    static void saveWav(byte[] pcm, String path) throws IOException {
        int dataLen = pcm.length;
        int byteRate = SAMPLE_RATE * CHANNELS * (BITS_PER_SAMPLE / 8);
        int blockAlign = CHANNELS * (BITS_PER_SAMPLE / 8);
        ByteBuffer hdr = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        hdr.put("RIFF".getBytes()); hdr.putInt(dataLen + 36);
        hdr.put("WAVE".getBytes()); hdr.put("fmt ".getBytes());
        hdr.putInt(16); hdr.putShort((short) 1); hdr.putShort((short) CHANNELS);
        hdr.putInt(SAMPLE_RATE); hdr.putInt(byteRate);
        hdr.putShort((short) blockAlign); hdr.putShort((short) BITS_PER_SAMPLE);
        hdr.put("data".getBytes()); hdr.putInt(dataLen);
        try (FileOutputStream fos = new FileOutputStream(path)) {
            fos.write(hdr.array());
            fos.write(pcm);
        }
    }

    static byte[] buildWavBytes(byte[] pcm) {
        int dataLen = pcm.length;
        int byteRate = SAMPLE_RATE * CHANNELS * (BITS_PER_SAMPLE / 8);
        int blockAlign = CHANNELS * (BITS_PER_SAMPLE / 8);
        ByteBuffer buf = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN);
        buf.put("RIFF".getBytes()); buf.putInt(dataLen + 36);
        buf.put("WAVE".getBytes()); buf.put("fmt ".getBytes());
        buf.putInt(16); buf.putShort((short) 1); buf.putShort((short) CHANNELS);
        buf.putInt(SAMPLE_RATE); buf.putInt(byteRate);
        buf.putShort((short) blockAlign); buf.putShort((short) BITS_PER_SAMPLE);
        buf.put("data".getBytes()); buf.putInt(dataLen);
        buf.put(pcm);
        return buf.array();
    }

    // ==================== TTSEngine ====================
    static class TTSEngine {
        private SyllableDB db;
        private RandomAccessFile datFile;
        private final Map<Long, byte[]> segCache = new LinkedHashMap<Long, byte[]>(256, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<Long, byte[]> e) {
                return size() > 8192;
            }
        };

        TTSEngine(String dbPath, String datPath) throws Exception {
            db = new SyllableDB(dbPath);
            datFile = new RandomAccessFile(datPath, "r");
        }

        byte[] readSegment(long begin, long end) throws IOException {
            long key = begin * 1_000_000_000L + end;
            byte[] cached = segCache.get(key);
            if (cached != null) return cached;
            int len = (int)(end - begin);
            byte[] data = new byte[len];
            datFile.seek(begin);
            datFile.readFully(data);
            segCache.put(key, data);
            return data;
        }

        SylRow pickUnit(List<SylRow> rows, short[] prevTail, ProfileOptions opts) throws IOException {
            if (!"join_cost".equals(opts.unitSelection) || rows.size() == 1
                    || prevTail == null || prevTail.length == 0)
                return rows.get(0);
            double pe = prevTail[prevTail.length - 1];
            double prms = rms(prevTail, 0, prevTail.length);
            SylRow best = rows.get(0);
            double bestCost = 1e18;
            for (SylRow r : rows) {
                byte[] data = readSegment(r.beginPos, r.endPos);
                short[] head = pcmToSamples(data);
                int headLen = Math.min(80, head.length);
                if (headLen == 0) continue;
                double he = head[0];
                double hrms = rms(head, 0, headLen);
                double cost = Math.abs(he - pe) + 0.5 * Math.abs(hrms - prms);
                if (cost < bestCost) { bestCost = cost; best = r; }
            }
            return best;
        }

        SynthResult synthesizePcm(String text, ProfileOptions opts) throws Exception {
            int overlap = opts.crossfadeMs * SAMPLE_RATE / 1000;

            String[] tokens = text.split("\\s+");
            List<String[]> plan = new ArrayList<>(); // {cleanWord, pauseMs}
            for (String tok : tokens) {
                String w = cleanWord(tok);
                if (w.isEmpty()) continue;
                int pause = opts.wordPauseMs;
                if (opts.prosody) {
                    for (int ci = tok.length() - 1; ci >= 0; ci--) {
                        char ch = tok.charAt(ci);
                        if (PUNCT_PAUSE_MS.containsKey(ch)) { pause = PUNCT_PAUSE_MS.get(ch); break; }
                        if (UYGHUR_LETTERS.contains(ch)) break;
                    }
                }
                plan.add(new String[]{w, String.valueOf(pause)});
            }

            int nWords = plan.size();
            List<byte[]> wordPcms = new ArrayList<>();
            List<Integer> wordPauses = new ArrayList<>();
            int total = 0;
            short[] prevTail = null;

            for (int wi = 0; wi < nWords; wi++) {
                String word = plan.get(wi)[0];
                int pause = Integer.parseInt(plan.get(wi)[1]);

                List<String> syllables = word2syllable(word);
                if (syllables == null) continue;

                List<byte[]> wordSegments = new ArrayList<>();
                List<String[]> keys = buildContextKeys(syllables);
                for (String[] kp : keys) {
                    LookupResult lr = db.lookupCandidates(kp[0], kp[1], opts.candidates);
                    if (lr.rows.isEmpty()) continue;
                    SylRow row = pickUnit(lr.rows, prevTail, opts);
                    byte[] seg = readSegment(row.beginPos, row.endPos);
                    wordSegments.add(seg);
                    short[] arr = pcmToSamples(seg);
                    prevTail = arr.length >= 80
                            ? Arrays.copyOfRange(arr, arr.length - 80, arr.length)
                            : arr.clone();
                    total++;
                }
                if (wordSegments.isEmpty()) continue;

                byte[] wordPcm = hanningCrossfade(wordSegments, overlap);
                if (opts.energyNorm)
                    wordPcm = normalizeWordRms(wordPcm, 3500.0);
                if (opts.prosody && wi == nWords - 1 && pause >= 300)
                    wordPcm = resampleFactor(wordPcm, 1.06);
                wordPcms.add(wordPcm);
                wordPauses.add(pause);
            }

            if (wordPcms.isEmpty()) return new SynthResult(new byte[0], 0);

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            int defaultPause = opts.wordPauseMs * SAMPLE_RATE / 1000;
            for (int i = 0; i < wordPcms.size(); i++) {
                bos.write(wordPcms.get(i));
                if (i < wordPcms.size() - 1) {
                    int ps = opts.prosody
                            ? wordPauses.get(i) * SAMPLE_RATE / 1000
                            : defaultPause;
                    bos.write(new byte[ps * 2]);
                }
            }
            byte[] pcmData = bos.toByteArray();

            if (opts.psola) {
                try { pcmData = psolaPitchSmooth(pcmData); } catch (Exception ignored) {}
            }
            return new SynthResult(pcmData, total);
        }

        void close() {
            try { datFile.close(); } catch (Exception ignored) {}
            db.close();
        }
    }

    // ==================== Base Directory Detection ====================
    static String findBaseDir() {
        File cwd = new File(System.getProperty("user.dir"));
        if (new File(cwd, "dada.db").exists()) return cwd.getAbsolutePath();
        File parent = cwd.getParentFile();
        if (parent != null && new File(parent, "dada.db").exists()) return parent.getAbsolutePath();
        File up = new File(cwd, "..");
        try { up = up.getCanonicalFile(); } catch (IOException ignored) {}
        if (new File(up, "dada.db").exists()) return up.getAbsolutePath();
        return parent != null ? parent.getAbsolutePath() : cwd.getAbsolutePath();
    }

    // ==================== GUI ====================
    private TTSEngine engine;
    private String engineError;
    private JTextArea textArea;
    private JProgressBar progressBar;
    private JLabel statusLabel;
    private String profileKey = "raw";
    private byte[] lastPcm;
    private volatile boolean busy;
    private Clip currentClip;
    private Font uyFont, cnFont;

    public UighurTTS() {
        super("Uighur TTS");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setSize(1024, 720);
        setMinimumSize(new Dimension(880, 560));
        getContentPane().setBackground(COL_PANEL);

        loadFonts();

        JPanel topPanel = new JPanel();
        topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
        topPanel.setBackground(COL_PANEL);

        topPanel.add(buildHeader());
        topPanel.add(buildToolbar());
        topPanel.add(buildProfileSelector());
        topPanel.add(buildProgress());

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(topPanel, BorderLayout.NORTH);
        getContentPane().add(buildTextArea(), BorderLayout.CENTER);
        getContentPane().add(buildStatusBar(), BorderLayout.SOUTH);

        textArea.setText(SAMPLE_TEXT);

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { onExit(); }
        });

        SwingUtilities.invokeLater(this::initEngine);
    }

    private void loadFonts() {
        String baseDir = findBaseDir();
        File fontFile = new File(baseDir, "assets" + File.separator + "UKIJTuT.ttf");
        if (fontFile.exists()) {
            try {
                Font loaded = Font.createFont(Font.TRUETYPE_FONT, fontFile);
                GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(loaded);
                uyFont = loaded.deriveFont(22f);
            } catch (Exception ignored) {}
        }
        if (uyFont == null) {
            String[] fallbacks = {"Microsoft Uighur", "UKIJ Tuz Tom", "Tahoma", "Arial"};
            for (String name : fallbacks) {
                Font f = new Font(name, Font.PLAIN, 22);
                if (f.canDisplay('\u0626')) { uyFont = f; break; }
            }
            if (uyFont == null) uyFont = new Font("Arial", Font.PLAIN, 22);
        }
        String[] cnFallbacks = {"Microsoft YaHei UI", "Microsoft YaHei", "SimHei", "SimSun", "Arial"};
        for (String name : cnFallbacks) {
            Font f = new Font(name, Font.PLAIN, 14);
            if (f.canDisplay('\u4E2D')) { cnFont = f; break; }
        }
        if (cnFont == null) cnFont = new Font("Arial", Font.PLAIN, 14);
    }

    // ---------- Header with gradient ----------
    private JPanel buildHeader() {
        String baseDir = findBaseDir();
        File avatarFile = new File(baseDir, "assets" + File.separator + "avatar.png");
        BufferedImage[] avatarHolder = new BufferedImage[1];
        if (avatarFile.exists()) {
            try {
                BufferedImage raw = ImageIO.read(avatarFile);
                avatarHolder[0] = new BufferedImage(150, 150, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = avatarHolder[0].createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(raw, 0, 0, 150, 150, null);
                g.dispose();
            } catch (Exception ignored) {}
        }

        JPanel header = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
                int w = getWidth(), h = getHeight();

                // diagonal gradient with glow
                BufferedImage grad = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                for (int y = 0; y < h; y++) {
                    double yy = (double) y / h;
                    for (int x = 0; x < w; x++) {
                        double xx = (double) x / w;
                        double t = Math.min(1.0, Math.max(0.0, 0.65 * yy + 0.35 * xx));
                        int ri = (int)(0x33 + (0x6F - 0x33) * t);
                        int gi = (int)(0x77 + (0xA8 - 0x77) * t);
                        int bi = (int)(0xCC + (0xE8 - 0xCC) * t);
                        double cx = w * 0.62, cy = h * 0.30;
                        double dist = Math.sqrt(Math.pow(x - cx, 2) + Math.pow(y - cy, 2));
                        double glow = Math.pow(Math.max(0, 1 - dist / (w * 0.55)), 2) * 38;
                        ri = Math.min(255, ri + (int) glow);
                        gi = Math.min(255, gi + (int) glow);
                        bi = Math.min(255, bi + (int) glow);
                        grad.setRGB(x, y, (ri << 16) | (gi << 8) | bi);
                    }
                }
                g2.drawImage(grad, 0, 0, null);

                // avatar
                if (avatarHolder[0] != null) {
                    g2.setColor(Color.WHITE);
                    g2.fillRect(24, 22, 158, 158);
                    g2.setColor(new Color(0xD8, 0xE4, 0xF2));
                    g2.drawRect(24, 22, 158, 158);
                    g2.drawImage(avatarHolder[0], 28, 26, null);
                }

                int cx = w / 2 + 20;
                // red title
                g2.setColor(COL_TITLE_UY);
                g2.setFont(new Font("Arial", Font.BOLD, 34));
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(TITLE_UY, cx - fm.stringWidth(TITLE_UY) / 2, 52);
                // blue subtitle
                g2.setColor(COL_TITLE_CN);
                g2.setFont(cnFont.deriveFont(Font.BOLD, 24f));
                fm = g2.getFontMetrics();
                g2.drawString(TITLE_CN, cx - fm.stringWidth(TITLE_CN) / 2, 92);

                // info text
                g2.setColor(COL_INFO);
                g2.setFont(uyFont.deriveFont(10f));
                g2.drawString(ADDR_UY, 210, 134);

                // org names
                g2.setFont(uyFont.deriveFont(Font.BOLD, 11f));
                fm = g2.getFontMetrics();
                g2.drawString(ORG_UY, w - 28 - fm.stringWidth(ORG_UY), 138);
                g2.setFont(cnFont.deriveFont(Font.BOLD, 13f));
                fm = g2.getFontMetrics();
                g2.drawString(ORG_CN, w - 28 - fm.stringWidth(ORG_CN), 166);

                // bottom line
                g2.setColor(new Color(0x1B, 0x4F, 0x9B));
                g2.fillRect(0, h - 2, w, 2);
            }
        };
        header.setPreferredSize(new Dimension(0, 200));
        header.setMaximumSize(new Dimension(Integer.MAX_VALUE, 200));
        return header;
    }

    // ---------- Toolbar ----------
    private JPanel buildToolbar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 10));
        bar.setBackground(COL_PANEL);
        bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 70));

        Runnable[] actions = { this::onExit, this::onSave, this::onStop, this::onRead, this::onOpen };
        for (int i = 0; i < BTN_SPECS.length; i++) {
            String label = BTN_SPECS[i][0] + "\n" + BTN_SPECS[i][1];
            JButton btn = new JButton("<html><center>" + BTN_SPECS[i][0] + "<br>" + BTN_SPECS[i][1] + "</center></html>");
            btn.setFont(uyFont.deriveFont(Font.BOLD, 11f));
            btn.setPreferredSize(new Dimension(130, 48));
            btn.setBackground(i == 3 ? new Color(0xDC, 0xEB, 0xFF) : new Color(0xF2, 0xF2, 0xF2));
            btn.setForeground(new Color(0x10, 0x31, 0x6B));
            btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            btn.setFocusPainted(false);
            final int idx = i;
            btn.addActionListener(e -> actions[idx].run());
            bar.add(btn);
        }
        return bar;
    }

    // ---------- Profile Selector ----------
    private JPanel buildProfileSelector() {
        JPanel wrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        wrap.setBackground(COL_PANEL);
        wrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        wrap.setBorder(BorderFactory.createEmptyBorder(0, 14, 0, 14));

        JLabel lbl = new JLabel("\u5408\u6210\u65B9\u6848\uFF1A");
        lbl.setFont(cnFont.deriveFont(Font.BOLD, 11f));
        lbl.setForeground(new Color(0x10, 0x31, 0x6B));
        wrap.add(lbl);

        ButtonGroup bg = new ButtonGroup();
        for (String[] pc : PROFILE_CHOICES) {
            JRadioButton rb = new JRadioButton(pc[1]);
            rb.setActionCommand(pc[0]);
            rb.setFont(cnFont.deriveFont(Font.BOLD, 10f));
            rb.setBackground(COL_PANEL);
            rb.setForeground(new Color(0x10, 0x31, 0x6B));
            if ("raw".equals(pc[0])) rb.setSelected(true);
            rb.addActionListener(e -> {
                profileKey = e.getActionCommand();
                for (String[] p : PROFILE_CHOICES) {
                    if (p[0].equals(profileKey))
                        statusLabel.setText("\u5DF2\u9009\u65B9\u6848\uFF1A" + p[1] + " \u2014 " + p[2]);
                }
            });
            bg.add(rb);
            wrap.add(rb);
        }

        JLabel hint = new JLabel("(\u5207\u6362\u65B9\u6848\u540E\u70B9\u300C\u6717\u8BFB\u300D\u5BF9\u6BD4\u6548\u679C)");
        hint.setFont(cnFont.deriveFont(9f));
        hint.setForeground(new Color(0x5A, 0x6B, 0x85));
        wrap.add(hint);
        return wrap;
    }

    // ---------- Progress Bar ----------
    private JPanel buildProgress() {
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBackground(COL_PANEL);
        wrap.setBorder(BorderFactory.createEmptyBorder(2, 14, 2, 14));
        wrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        progressBar = new JProgressBar(0, 100);
        progressBar.setValue(0);
        wrap.add(progressBar, BorderLayout.CENTER);
        return wrap;
    }

    // ---------- Text Area ----------
    private JScrollPane buildTextArea() {
        textArea = new JTextArea();
        textArea.setFont(uyFont.deriveFont(22f));
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.setMargin(new Insets(12, 14, 12, 14));
        textArea.setComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT);
        JScrollPane sp = new JScrollPane(textArea);
        sp.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));
        return sp;
    }

    // ---------- Status Bar ----------
    private JPanel buildStatusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(new Color(0xDD, 0xE6, 0xF2));
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Color.GRAY),
                BorderFactory.createEmptyBorder(4, 10, 4, 10)));
        statusLabel = new JLabel("\u6B63\u5728\u521D\u59CB\u5316\u5F15\u64CE\u2026");
        statusLabel.setFont(cnFont.deriveFont(10f));
        statusLabel.setForeground(new Color(0x10, 0x31, 0x6B));
        bar.add(statusLabel, BorderLayout.WEST);
        return bar;
    }

    // ---------- Engine Init ----------
    private void initEngine() {
        String baseDir = findBaseDir();
        String dbPath = new File(baseDir, "dada.db").getAbsolutePath();
        String datPath = new File(baseDir, "Lab.dat").getAbsolutePath();

        if (!new File(dbPath).exists() || !new File(datPath).exists()) {
            engineError = "\u672A\u627E\u5230 dada.db / Lab.dat\uFF0C\u8BF7\u68C0\u67E5\u8DEF\u5F84: " + baseDir;
            statusLabel.setText(engineError);
            return;
        }
        try {
            engine = new TTSEngine(dbPath, datPath);
            statusLabel.setText("\u5C31\u7EEA \u2014 \u8F93\u5165\u7EF4\u543E\u5C14\u6587\u540E\u70B9\u51FB\u300C\u6717\u8BFB\u300D");
        } catch (Exception e) {
            engineError = "\u5F15\u64CE\u521D\u59CB\u5316\u5931\u8D25\uFF1A" + e.getMessage();
            statusLabel.setText(engineError);
        }
    }

    private boolean ensureEngine() {
        if (engine == null) {
            JOptionPane.showMessageDialog(this,
                    engineError != null ? engineError : "\u5F15\u64CE\u672A\u5C31\u7EEA",
                    "\u9519\u8BEF", JOptionPane.ERROR_MESSAGE);
            return false;
        }
        return true;
    }

    // ---------- Async Synthesis ----------
    private void synthesizeAsync(java.util.function.Consumer<byte[]> onDone) {
        if (busy) return;
        String text = textArea.getText().trim();
        if (text.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "\u8BF7\u8F93\u5165\u8981\u5408\u6210\u7684\u7EF4\u543E\u5C14\u6587\u6587\u672C\u3002",
                    "\u63D0\u793A", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (!ensureEngine()) return;

        busy = true;
        progressBar.setIndeterminate(true);
        statusLabel.setText("\u5408\u6210\u4E2D\u2026");

        ProfileOptions opts = PROFILES.getOrDefault(profileKey, PROFILES.get("raw")).copy();

        new Thread(() -> {
            try {
                SynthResult result = engine.synthesizePcm(text, opts);
                SwingUtilities.invokeLater(() -> {
                    progressBar.setIndeterminate(false);
                    progressBar.setValue(100);
                    busy = false;
                    if (result.pcm == null || result.pcm.length == 0) {
                        statusLabel.setText("\u6CA1\u6709\u5339\u914D\u5230\u4EFB\u4F55\u97F3\u8282\u7247\u6BB5");
                        JOptionPane.showMessageDialog(UighurTTS.this,
                                "\u6CA1\u6709\u5339\u914D\u5230\u97F3\u8282\uFF0C\u8BF7\u68C0\u67E5\u6587\u672C\u3002",
                                "\u63D0\u793A", JOptionPane.WARNING_MESSAGE);
                        return;
                    }
                    lastPcm = result.pcm;
                    double dur = (result.pcm.length / 2.0) / SAMPLE_RATE;
                    statusLabel.setText(String.format(
                            "\u5408\u6210\u5B8C\u6210\uFF1A%d \u4E2A\u7247\u6BB5\uFF0C%.2f \u79D2", result.totalSegments, dur));
                    onDone.accept(result.pcm);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    progressBar.setIndeterminate(false);
                    busy = false;
                    statusLabel.setText("\u5408\u6210\u5931\u8D25");
                    JOptionPane.showMessageDialog(UighurTTS.this,
                            ex.getMessage(), "\u5408\u6210\u5931\u8D25", JOptionPane.ERROR_MESSAGE);
                });
            }
        }).start();
    }

    // ---------- Audio Playback ----------
    private void playPcm(byte[] pcm) {
        try {
            stopPlayback();
            AudioFormat fmt = new AudioFormat(SAMPLE_RATE, BITS_PER_SAMPLE, CHANNELS, true, false);
            byte[] wavBytes = buildWavBytes(pcm);
            AudioInputStream ais = AudioSystem.getAudioInputStream(
                    new ByteArrayInputStream(wavBytes));
            currentClip = AudioSystem.getClip();
            currentClip.open(ais);
            currentClip.start();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, e.getMessage(),
                    "\u64AD\u653E\u5931\u8D25", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void stopPlayback() {
        if (currentClip != null) {
            try { currentClip.stop(); currentClip.close(); } catch (Exception ignored) {}
            currentClip = null;
        }
    }

    // ---------- Button Handlers ----------
    private void onRead() {
        synthesizeAsync(this::playPcm);
    }

    private void onStop() {
        stopPlayback();
        statusLabel.setText("\u5DF2\u505C\u6B62");
    }

    private void onSave() {
        synthesizeAsync(pcm -> {
            JFileChooser fc = new JFileChooser();
            fc.setDialogTitle("\u4FDD\u5B58\u4E3A WAV");
            fc.setSelectedFile(new File("output.wav"));
            fc.setFileFilter(new FileNameExtensionFilter("WAV \u97F3\u9891", "wav"));
            if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                String path = fc.getSelectedFile().getAbsolutePath();
                if (!path.toLowerCase().endsWith(".wav")) path += ".wav";
                try {
                    saveWav(pcm, path);
                    statusLabel.setText("\u5DF2\u4FDD\u5B58\uFF1A" + path);
                    JOptionPane.showMessageDialog(this, path,
                            "\u4FDD\u5B58\u6210\u529F", JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(this, e.getMessage(),
                            "\u4FDD\u5B58\u5931\u8D25", JOptionPane.ERROR_MESSAGE);
                }
            }
        });
    }

    private void onOpen() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("\u6253\u5F00\u6587\u672C\u6587\u4EF6");
        fc.setFileFilter(new FileNameExtensionFilter("\u6587\u672C\u6587\u4EF6", "txt"));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                String content = new String(Files.readAllBytes(fc.getSelectedFile().toPath()), "UTF-8");
                textArea.setText(content);
                statusLabel.setText("\u5DF2\u8F7D\u5165\uFF1A" + fc.getSelectedFile().getName());
            } catch (Exception e) {
                JOptionPane.showMessageDialog(this, e.getMessage(),
                        "\u6253\u5F00\u5931\u8D25", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void onExit() {
        stopPlayback();
        if (engine != null) engine.close();
        dispose();
        System.exit(0);
    }

    // ==================== Main ====================
    public static void main(String[] args) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
        catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> {
            UighurTTS app = new UighurTTS();
            app.setLocationRelativeTo(null);
            app.setVisible(true);
        });
    }
}
