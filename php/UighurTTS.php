<?php
/**
 * Uyghur TTS Engine — PHP Port
 * Based on syllable concatenation, using SQLite database dada.db + Lab.dat
 * Ported from UighurTTS.py
 */

mb_internal_encoding('UTF-8');

const TTS_SAMPLE_RATE   = 16000;
const TTS_BITS          = 16;
const TTS_CHANNELS      = 1;
const TTS_CROSSFADE_MS  = 30;
const TTS_CROSSFADE_SAMPLES = 480;   // 30 * 16000 / 1000
const TTS_WORD_PAUSE_MS = 80;
const TTS_WORD_PAUSE_SAMPLES = 1280; // 80 * 16000 / 1000

// ── Synthesis profiles ─────────────────────────────────────────────
function ttsProfiles() {
    static $p = [
        'raw'     => ['crossfade_ms'=>30,  'word_pause_ms'=>80,  'unit_selection'=>'first',
                       'energy_norm'=>false,'prosody'=>false,'candidates'=>1,'psola'=>false],
        'smooth'  => ['crossfade_ms'=>50,  'word_pause_ms'=>80,  'unit_selection'=>'first',
                       'energy_norm'=>true, 'prosody'=>false,'candidates'=>1,'psola'=>false],
        'smart'   => ['crossfade_ms'=>50,  'word_pause_ms'=>80,  'unit_selection'=>'join_cost',
                       'energy_norm'=>true, 'prosody'=>false,'candidates'=>8,'psola'=>false],
        'prosody' => ['crossfade_ms'=>55,  'word_pause_ms'=>100, 'unit_selection'=>'join_cost',
                       'energy_norm'=>true, 'prosody'=>true, 'candidates'=>8,'psola'=>false],
        'hifi'    => ['crossfade_ms'=>55,  'word_pause_ms'=>100, 'unit_selection'=>'join_cost',
                       'energy_norm'=>true, 'prosody'=>true, 'candidates'=>8,'psola'=>true],
    ];
    return $p;
}

function ttsDefaultOptions() {
    $p = ttsProfiles();
    return array_merge($p['raw'], ['psola' => false]);
}

function ttsPunctPauses() {
    static $p = null;
    if ($p === null) {
        $p = [
            "\u{060C}" => 170, "," => 170, "\u{061B}" => 200, ";" => 200,
            "\u{3001}" => 150, "." => 300, "\u{06D4}" => 300, "\u{3002}" => 320,
            "!" => 300, "\u{FF01}" => 300, "?" => 320, "\u{061F}" => 320,
            "\u{FF1F}" => 320, ":" => 200, "\u{FF1A}" => 200,
        ];
    }
    return $p;
}

// ── Character classification ───────────────────────────────────────
function isSegVowel($c) {
    static $m = null;
    if ($m === null) {
        $m = [
            "\u{0627}"=>1, "\u{0648}"=>1, "\u{0649}"=>1, "\u{06C6}"=>1,
            "\u{06C7}"=>1, "\u{06C8}"=>1, "\u{06D0}"=>1, "\u{06D5}"=>1,
        ];
    }
    return isset($m[$c]);
}

function classifyNfi($c) {
    static $vowels = null;
    static $voiced = null;
    if ($vowels === null) {
        $vowels = [
            "\u{0626}"=>1, "\u{0627}"=>1, "\u{0648}"=>1, "\u{0649}"=>1,
            "\u{06C6}"=>1, "\u{06C7}"=>1, "\u{06C8}"=>1, "\u{06D0}"=>1, "\u{06D5}"=>1,
        ];
        $voiced = [
            "\u{0628}"=>1, "\u{062C}"=>1, "\u{062F}"=>1, "\u{0631}"=>1,
            "\u{0632}"=>1, "\u{0698}"=>1, "\u{063A}"=>1, "\u{06AF}"=>1,
            "\u{06AD}"=>1, "\u{0644}"=>1, "\u{0645}"=>1, "\u{0646}"=>1,
            "\u{064E}"=>1, "\u{06CB}"=>1, "\u{064A}"=>1,
        ];
    }
    if (isset($vowels[$c])) return 'V';
    if (isset($voiced[$c])) return 'C';
    return 'U';
}

function isUyghurLetter($c) {
    static $m = null;
    if ($m === null) {
        $m = [
            "\u{0626}"=>1,"\u{0627}"=>1,"\u{06D5}"=>1,"\u{06D0}"=>1,
            "\u{0649}"=>1,"\u{0648}"=>1,"\u{06C7}"=>1,"\u{06C6}"=>1,
            "\u{06C8}"=>1,"\u{0628}"=>1,"\u{067E}"=>1,"\u{062A}"=>1,
            "\u{062C}"=>1,"\u{0686}"=>1,"\u{062E}"=>1,"\u{062F}"=>1,
            "\u{0631}"=>1,"\u{0632}"=>1,"\u{0698}"=>1,"\u{0633}"=>1,
            "\u{0634}"=>1,"\u{063A}"=>1,"\u{0641}"=>1,"\u{0642}"=>1,
            "\u{0643}"=>1,"\u{06AF}"=>1,"\u{06AD}"=>1,"\u{0644}"=>1,
            "\u{0645}"=>1,"\u{0646}"=>1,"\u{06BE}"=>1,"\u{06CB}"=>1,
            "\u{064A}"=>1,
        ];
    }
    return isset($m[$c]);
}

function cleanWord($word) {
    $result = '';
    $len = mb_strlen($word);
    for ($i = 0; $i < $len; $i++) {
        $ch = mb_substr($word, $i, 1);
        if (isUyghurLetter($ch)) $result .= $ch;
    }
    return $result;
}

// ── Syllable segmentation (exact port of word2syllable) ────────────
function word2syllable($word) {
    $chars = preg_split('//u', $word, -1, PREG_SPLIT_NO_EMPTY);
    $wordLen = count($chars);
    if ($wordLen === 0) return null;
    if ($wordLen === 1) {
        return isSegVowel($chars[0]) ? null : [$word];
    }
    if (isSegVowel($chars[0])) return null;

    $syllables = [];
    $pos   = 0;
    $start = 0;

    $stateCv = $foundCv = $foundCvv = $ccStart = null;

    $grab = function($from, $len) use (&$chars) {
        return implode('', array_slice($chars, $from, $len));
    };

    $stateCv = function() use (&$pos, &$start, &$chars, $wordLen,
                                &$syllables, &$foundCv, &$ccStart) {
        $pos++;
        if ($pos > $wordLen) return false;
        $ch = $chars[$pos - 1];
        if (isSegVowel($ch)) return $foundCv();
        return $ccStart();
    };

    $foundCv = function() use (&$pos, &$start, &$chars, $wordLen,
                                &$syllables, &$foundCvv, &$stateCv, &$foundCv, &$grab) {
        if ($wordLen === $pos) {
            $syllables[] = $grab($start, 2);
            return true;
        }
        $pos++;
        $ch = $chars[$pos - 1];
        if (isSegVowel($ch)) return $foundCvv();
        if ($wordLen === $pos) {
            $syllables[] = $grab($start, 3);
            return true;
        }
        $pos++;
        $ch = $chars[$pos - 1];
        if (isSegVowel($ch)) {
            $syllables[] = $grab($start, 2);
            $start += 2;
            return $foundCv();
        }
        if ($wordLen === $pos) {
            $syllables[] = $grab($start, 4);
            return true;
        }
        $pos++;
        $ch = $chars[$pos - 1];
        if (isSegVowel($ch)) {
            $syllables[] = $grab($start, 3);
            $start += 3;
            return $foundCv();
        }
        $syllables[] = $grab($start, 4);
        $start += 4;
        return $stateCv();
    };

    $foundCvv = function() use (&$pos, &$start, &$chars, $wordLen,
                                 &$syllables, &$foundCv, &$stateCv, &$grab) {
        if ($wordLen === $pos) {
            $syllables[] = $grab($start, 3);
            return true;
        }
        $pos++;
        $ch = $chars[$pos - 1];
        if (isSegVowel($ch)) return false;
        if ($wordLen === $pos) {
            $syllables[] = $grab($start, 4);
            return true;
        }
        $pos++;
        $ch = $chars[$pos - 1];
        if (isSegVowel($ch)) {
            $syllables[] = $grab($start, 3);
            $start += 3;
            return $foundCv();
        }
        $syllables[] = $grab($start, 4);
        $start += 4;
        return $stateCv();
    };

    $ccStart = function() use (&$pos, &$start, &$chars, $wordLen,
                                &$syllables, &$foundCv, &$stateCv, &$grab) {
        if ($wordLen === $pos) return false;
        $pos++;
        $ch = $chars[$pos - 1];
        if (!isSegVowel($ch)) return false;
        if ($wordLen === $pos) {
            $syllables[] = $grab($start, 3);
            return true;
        }
        $pos++;
        $ch = $chars[$pos - 1];
        if (isSegVowel($ch)) return false;
        if ($wordLen === $pos) {
            $syllables[] = $grab($start, 4);
            return true;
        }
        $pos++;
        $ch = $chars[$pos - 1];
        if (isSegVowel($ch)) {
            $syllables[] = $grab($start, 3);
            $start += 3;
            return $foundCv();
        }
        if ($wordLen === $pos) {
            $syllables[] = $grab($start, 5);
            return true;
        }
        $pos++;
        $ch = $chars[$pos - 1];
        if (isSegVowel($ch)) {
            $syllables[] = $grab($start, 4);
            $start += 4;
            return $foundCv();
        }
        $syllables[] = $grab($start, 5);
        $start += 5;
        return $stateCv();
    };

    $pos = 1;
    $ok = $stateCv();
    return $ok ? $syllables : null;
}

// ── Context key building ───────────────────────────────────────────
function buildContextKeys($syllables) {
    $count = count($syllables);
    if ($count === 0) return [];
    if ($count === 1) return [[$syllables[0], $syllables[0]]];

    $keys = [];

    $syl = $syllables[0];
    $rc  = mb_substr($syllables[1], 0, 1);
    $keys[] = [$syl . '-' . $rc, $syl . '-' . classifyNfi($rc)];

    for ($i = 1; $i < $count - 1; $i++) {
        $syl = $syllables[$i];
        $lc  = mb_substr($syllables[$i - 1], -1, 1);
        $rc  = mb_substr($syllables[$i + 1], 0, 1);
        $keys[] = [
            $lc . '-' . $syl . '-' . $rc,
            classifyNfi($lc) . '-' . $syl . '-' . classifyNfi($rc),
        ];
    }

    $syl = $syllables[$count - 1];
    $lc  = mb_substr($syllables[$count - 2], -1, 1);
    $keys[] = [$lc . '-' . $syl, classifyNfi($lc) . '-' . $syl];

    return $keys;
}

// ── PCM conversion helpers ─────────────────────────────────────────
function pcmToSamples($pcmBytes) {
    $len = strlen($pcmBytes);
    if ($len < 2) return [];
    $count = intdiv($len, 2);
    $samples = array_values(unpack("v{$count}", $pcmBytes));
    for ($i = 0; $i < $count; $i++) {
        if ($samples[$i] >= 32768) $samples[$i] -= 65536;
    }
    return $samples;
}

function samplesToPcm($samples) {
    $pcm = '';
    foreach ($samples as $s) {
        $v = (int)round(max(-32768, min(32767, (float)$s)));
        if ($v < 0) $v += 65536;
        $pcm .= pack('v', $v);
    }
    return $pcm;
}

// ── Audio processing ───────────────────────────────────────────────
function resampleFactor($pcm, $factor) {
    $arr = pcmToSamples($pcm);
    $n = count($arr);
    if ($n < 2 || abs($factor - 1.0) < 1e-3) return $pcm;
    $newN = max(2, (int)round($n * $factor));
    $out = [];
    for ($i = 0; $i < $newN; $i++) {
        $x = $i * ($n - 1) / ($newN - 1);
        $lo = (int)floor($x);
        $hi = min($lo + 1, $n - 1);
        $frac = $x - $lo;
        $out[] = $arr[$lo] * (1 - $frac) + $arr[$hi] * $frac;
    }
    return samplesToPcm($out);
}

function normalizeWordRms($pcm, $targetRms = 3500.0) {
    $arr = pcmToSamples($pcm);
    $n = count($arr);
    if ($n === 0) return $pcm;
    $sumSq = 0;
    foreach ($arr as $v) $sumSq += (float)$v * $v;
    $rms = sqrt($sumSq / $n);
    if ($rms < 10) return $pcm;
    $scale = min($targetRms / $rms, 4.0);
    $out = [];
    foreach ($arr as $v) $out[] = $v * $scale;
    return samplesToPcm($out);
}

function hanningCrossfade($segmentsPcm, $overlap = null) {
    if (empty($segmentsPcm)) return '';
    if (count($segmentsPcm) === 1) return $segmentsPcm[0];
    if ($overlap === null) $overlap = TTS_CROSSFADE_SAMPLES;

    $arrays = [];
    $totalLen = 0;
    foreach ($segmentsPcm as $seg) {
        $arr = pcmToSamples($seg);
        $arrays[] = $arr;
        $totalLen += count($arr);
    }
    $totalLen -= $overlap * (count($arrays) - 1);
    $totalLen = max(1, $totalLen);

    $result = array_fill(0, $totalLen, 0.0);

    $pos = 0;
    foreach ($arrays as $i => $arr) {
        $arrLen = count($arr);
        $arrF = array_map('floatval', $arr);

        if ($i === 0) {
            for ($j = 0; $j < $arrLen; $j++) {
                if ($pos + $j < $totalLen) $result[$pos + $j] += $arrF[$j];
            }
            $pos += $arrLen - $overlap;
            continue;
        }

        $actualOverlap = min($overlap, $arrLen);
        if ($pos < 0) $actualOverlap = 0;
        if ($actualOverlap <= 0) {
            for ($j = 0; $j < $arrLen; $j++) {
                $idx = $pos + $j;
                if ($idx >= 0 && $idx < $totalLen) $result[$idx] += $arrF[$j];
            }
            $pos += $arrLen - $overlap;
            continue;
        }

        $overlapStart = $pos;
        for ($j = 0; $j < $actualOverlap; $j++) {
            $idx = $overlapStart + $j;
            if ($idx >= 0 && $idx < $totalLen) {
                $fadeOut = 0.5 * (1 + cos(M_PI * $j / $actualOverlap));
                $result[$idx] *= $fadeOut;
            }
        }
        for ($j = 0; $j < $actualOverlap; $j++) {
            $fadeIn = 0.5 * (1 - cos(M_PI * $j / $actualOverlap));
            $arrF[$j] *= $fadeIn;
        }
        for ($j = 0; $j < $actualOverlap; $j++) {
            $idx = $overlapStart + $j;
            if ($idx >= 0 && $idx < $totalLen) {
                $result[$idx] += $arrF[$j];
            }
        }
        $remaining = $arrLen - $actualOverlap;
        if ($remaining > 0) {
            $destStart = $overlapStart + $actualOverlap;
            for ($j = 0; $j < $remaining; $j++) {
                $idx = $destStart + $j;
                if ($idx >= 0 && $idx < $totalLen) {
                    $result[$idx] += $arrF[$actualOverlap + $j];
                }
            }
        }
        $pos = $overlapStart + $arrLen - $overlap;
    }

    $endPos = min(max(1, $pos + $overlap), $totalLen);
    return samplesToPcm(array_slice($result, 0, $endPos));
}

// ── Simplified PSOLA (F0 smooth + OLA) ─────────────────────────────
function estimateF0($samples, $sr = TTS_SAMPLE_RATE,
                    $fmin = 70, $fmax = 350, $frameMs = 60, $hopMs = 15,
                    $energyFloor = 140.0, $voicingRatio = 0.30) {
    $n = count($samples);
    $frame = (int)($frameMs * $sr / 1000);
    $hop   = (int)($hopMs   * $sr / 1000);
    if ($n < $frame) return [[], $hop];

    $minLag = (int)($sr / $fmax);
    $maxLag = (int)($sr / $fmin);
    $nFrames = intdiv($n - $frame, $hop) + 1;
    $f0 = array_fill(0, $nFrames, 0.0);

    for ($fi = 0; $fi < $nFrames; $fi++) {
        $s = $fi * $hop;
        $mean = 0;
        for ($j = 0; $j < $frame; $j++) $mean += $samples[$s + $j];
        $mean /= $frame;

        $rms = 0;
        for ($j = 0; $j < $frame; $j++) {
            $v = $samples[$s + $j] - $mean;
            $rms += $v * $v;
        }
        $rms = sqrt($rms / $frame);
        if ($rms < $energyFloor) continue;

        $r0 = 0;
        for ($j = 0; $j < $frame; $j++) {
            $v = $samples[$s + $j] - $mean;
            $r0 += $v * $v;
        }
        if ($r0 <= 0) continue;

        $bestLag = 0;
        $bestAc  = -1e18;
        $ml = min($maxLag, $frame - 1);
        for ($lag = $minLag; $lag < $ml; $lag++) {
            $ac = 0;
            $limit = $frame - $lag;
            for ($j = 0; $j < $limit; $j++) {
                $ac += ($samples[$s + $j] - $mean) * ($samples[$s + $j + $lag] - $mean);
            }
            if ($ac > $bestAc) {
                $bestAc  = $ac;
                $bestLag = $lag;
            }
        }
        if ($bestLag > 0 && ($bestAc / $r0) >= $voicingRatio) {
            $f0[$fi] = $sr / $bestLag;
        }
    }
    return [$f0, $hop];
}

function medianSmoothF0($v, $k = 9) {
    $n = count($v);
    $out = $v;
    $half = intdiv($k, 2);
    for ($i = 0; $i < $n; $i++) {
        $lo = max(0, $i - $half);
        $hi = min($n, $i + $half + 1);
        $nz = [];
        for ($j = $lo; $j < $hi; $j++) {
            if ($v[$j] > 0) $nz[] = $v[$j];
        }
        if (!empty($nz)) {
            sort($nz);
            $out[$i] = $nz[intdiv(count($nz), 2)];
        }
    }
    return $out;
}

function psolaPitchSmooth($pcm, $sr = TTS_SAMPLE_RATE,
                          $smoothK = 9, $declination = 0.90, $strength = 0.85) {
    $x = pcmToSamples($pcm);
    $n = count($x);
    if ($n < intdiv($sr, 8)) return $pcm;
    $maxPsola = $sr * 5;
    if ($n > $maxPsola) {
        $head = array_slice($x, 0, $maxPsola);
        $tail = samplesToPcm(array_slice($x, $maxPsola));
        return psolaPitchSmooth(samplesToPcm($head), $sr, $smoothK, $declination, $strength) . $tail;
    }

    list($f0, $hop) = estimateF0($x, $sr);
    $nf = count($f0);
    if ($nf === 0) return $pcm;
    $voiced = [];
    foreach ($f0 as $i => $v) { if ($v > 0) $voiced[] = $i; }
    if (count($voiced) < 3) return $pcm;

    $centers = [];
    for ($i = 0; $i < $nf; $i++) $centers[] = $i * $hop + intdiv($hop, 2);

    $src = array_fill(0, $n, 0.0);
    for ($i = 0; $i < $n; $i++) {
        $best = 0; $bestDist = PHP_INT_MAX;
        foreach ($voiced as $vi) {
            $d = abs($i - $centers[$vi]);
            if ($d < $bestDist) { $bestDist = $d; $best = $vi; }
        }
        $src[$i] = $f0[$best];
    }

    $sf0 = medianSmoothF0($f0, $smoothK);
    $sVoiced = [];
    foreach ($sf0 as $i => $v) { if ($v > 0) $sVoiced[] = $i; }
    if (count($sVoiced) < 2) return $pcm;

    $smooth = array_fill(0, $n, 0.0);
    for ($i = 0; $i < $n; $i++) {
        $best = 0; $bestDist = PHP_INT_MAX;
        foreach ($sVoiced as $vi) {
            $d = abs($i - $centers[$vi]);
            if ($d < $bestDist) { $bestDist = $d; $best = $vi; }
        }
        $smooth[$i] = $sf0[$best];
    }

    $tgt = [];
    for ($i = 0; $i < $n; $i++) {
        $decl = 1.0 + ($declination - 1.0) * $i / max(1, $n - 1);
        $t = $smooth[$i] * $decl;
        $t = $src[$i] * (1 - $strength) + $t * $strength;
        $tgt[] = max(60.0, min(400.0, $t));
        $src[$i] = max(60.0, min(400.0, $src[$i]));
    }

    $marks = [];
    $p = 0.0;
    while ($p < $n) {
        $marks[] = (int)$p;
        $idx = min((int)$p, $n - 1);
        $p += $sr / $src[$idx];
    }
    if (count($marks) < 3) return $pcm;

    $xf = array_map('floatval', $x);
    $outLen = $n + intdiv($sr, 2);
    $out = array_fill(0, $outLen, 0.0);
    $written = 0;
    $p = 0.0;

    while ($p < $n) {
        $t = (int)round($p);
        $nearestDist = PHP_INT_MAX;
        $ai = 0;
        foreach ($marks as $mk) {
            $d = abs($mk - $t);
            if ($d < $nearestDist) { $nearestDist = $d; $ai = $mk; }
        }
        $idx = min($ai, $n - 1);
        $T = (int)($sr / $src[$idx]);
        if ($T < 4) {
            $tidx = min($t, $n - 1);
            $p += $sr / $tgt[$tidx];
            continue;
        }
        $l = max(0, $ai - $T);
        $r = min($n, $ai + $T);
        $frameLen = $r - $l;
        if ($frameLen >= 4) {
            $frame = [];
            for ($j = $l; $j < $r; $j++) $frame[] = $xf[$j];
            $wLen = count($frame);
            for ($j = 0; $j < $wLen; $j++) {
                $frame[$j] *= 0.5 * (1 - cos(2 * M_PI * $j / ($wLen - 1)));
            }
            $oStart = $t - ($ai - $l);
            if ($oStart < 0) {
                $frame = array_slice($frame, -$oStart);
                $oStart = 0;
            }
            $oEnd = min($oStart + count($frame), $outLen);
            $copyLen = $oEnd - $oStart;
            for ($j = 0; $j < $copyLen; $j++) {
                $out[$oStart + $j] += $frame[$j];
            }
            $written = max($written, $oEnd);
        }
        $tidx = min($t, $n - 1);
        $p += $sr / $tgt[$tidx];
    }

    $out = array_slice($out, 0, $n);
    $srcRms = 0;
    $outRms = 0;
    foreach ($xf as $v) $srcRms += $v * $v;
    foreach ($out as $v) $outRms += $v * $v;
    $srcRms = sqrt($srcRms / max(1, $n));
    $outRms = sqrt($outRms / max(1, $n));
    if ($outRms > 1) {
        $scale = $srcRms / $outRms;
        for ($i = 0; $i < $n; $i++) $out[$i] *= $scale;
    }
    return samplesToPcm($out);
}

// ── WAV output ─────────────────────────────────────────────────────
function buildWavData($pcmData) {
    $dataLen    = strlen($pcmData);
    $bytesPerSec = TTS_SAMPLE_RATE * TTS_CHANNELS * (TTS_BITS >> 3);
    $blockAlign  = TTS_CHANNELS * (TTS_BITS >> 3);

    $header  = 'RIFF';
    $header .= pack('V', $dataLen + 36);
    $header .= 'WAVE';
    $header .= 'fmt ';
    $header .= pack('V', 16);
    $header .= pack('vv', 1, TTS_CHANNELS);
    $header .= pack('V', TTS_SAMPLE_RATE);
    $header .= pack('V', $bytesPerSec);
    $header .= pack('vv', $blockAlign, TTS_BITS);
    $header .= 'data';
    $header .= pack('V', $dataLen);

    return $header . $pcmData;
}

function saveWav($pcmData, $outputPath) {
    file_put_contents($outputPath, buildWavData($pcmData));
}


// ═══════════════════════════════════════════════════════════════════
//  SyllableDB — SQLite lookup wrapper
// ═══════════════════════════════════════════════════════════════════
class SyllableDB {
    private $db;

    public function __construct($dbPath) {
        $this->db = new SQLite3($dbPath, SQLITE3_OPEN_READONLY);
        $this->db->busyTimeout(3000);
    }

    public function lookup($fi1Key, $nfi1Key) {
        $rows = $this->lookupCandidates($fi1Key, $nfi1Key, 1);
        if (!empty($rows[0])) return [$rows[0][0], $rows[1]];
        return [null, null];
    }

    public function lookupCandidates($fi1Key, $nfi1Key, $limit = 8) {
        $plans = [
            ['FISyllable1',  $fi1Key,  'FI1'],
            ['NFISyllable1', $nfi1Key, 'NFI1'],
            ['FISyllable2',  $fi1Key,  'FI2'],
            ['NFISyllable2', $nfi1Key, 'NFI2'],
        ];
        foreach ($plans as list($col, $key, $mt)) {
            $stmt = $this->db->prepare(
                "SELECT * FROM Syllable WHERE {$col}=:k LIMIT :lim"
            );
            $stmt->bindValue(':k',   $key,   SQLITE3_TEXT);
            $stmt->bindValue(':lim', $limit, SQLITE3_INTEGER);
            $res = $stmt->execute();
            $rows = [];
            while ($row = $res->fetchArray(SQLITE3_ASSOC)) {
                $rows[] = $row;
            }
            $stmt->close();
            if (!empty($rows)) return [$rows, $mt];
        }
        return [[], null];
    }

    public function close() {
        $this->db->close();
    }
}


// ═══════════════════════════════════════════════════════════════════
//  UighurTTS — main TTS engine
// ═══════════════════════════════════════════════════════════════════
class UighurTTS {
    private $db;
    private $datPath;
    private $datHandle;
    private $segCache   = [];
    private $cacheOrder = [];
    private $cacheCap   = 4096;

    public function __construct($dbPath, $datPath) {
        $this->db      = new SyllableDB($dbPath);
        $this->datPath = $datPath;
        $this->datHandle = fopen($datPath, 'rb');
        if ($this->datHandle === false) {
            throw new RuntimeException("Cannot open Lab.dat: $datPath");
        }
    }

    private function readSeg($begin, $end) {
        $key = "$begin:$end";
        if (isset($this->segCache[$key])) return $this->segCache[$key];
        fseek($this->datHandle, $begin);
        $data = fread($this->datHandle, $end - $begin);
        $this->segCache[$key] = $data;
        $this->cacheOrder[] = $key;
        if (count($this->cacheOrder) > $this->cacheCap) {
            $old = array_shift($this->cacheOrder);
            unset($this->segCache[$old]);
        }
        return $data;
    }

    private function pickUnit($rows, $prevTail, $opts) {
        if (($opts['unit_selection'] ?? '') !== 'join_cost'
            || count($rows) === 1
            || $prevTail === null || empty($prevTail)) {
            return $rows[0];
        }
        $pe   = (float)end($prevTail);
        $pSum = 0;
        foreach ($prevTail as $v) $pSum += (float)$v * $v;
        $prms = sqrt($pSum / count($prevTail));

        $best     = $rows[0];
        $bestCost = 1e18;
        foreach ($rows as $r) {
            $data = $this->readSeg($r['begin_pos'], $r['end_pos']);
            $head = array_slice(pcmToSamples($data), 0, 80);
            if (empty($head)) continue;
            $he = (float)$head[0];
            $hSum = 0;
            foreach ($head as $v) $hSum += (float)$v * $v;
            $hrms = sqrt($hSum / count($head));
            $cost = abs($he - $pe) + 0.5 * abs($hrms - $prms);
            if ($cost < $bestCost) {
                $bestCost = $cost;
                $best     = $r;
            }
        }
        return $best;
    }

    public function synthesizePcm($text, $opts = null) {
        $o = ttsDefaultOptions();
        if ($opts) $o = array_merge($o, $opts);
        $overlap = (int)($o['crossfade_ms'] * TTS_SAMPLE_RATE / 1000);

        $tokens = preg_split('/\s+/u', $text, -1, PREG_SPLIT_NO_EMPTY);
        $plan   = [];
        $punctPauses = ttsPunctPauses();

        foreach ($tokens as $tok) {
            $w = cleanWord($tok);
            if ($w === '') continue;
            $pause = $o['word_pause_ms'];
            if ($o['prosody']) {
                $tokChars = preg_split('//u', $tok, -1, PREG_SPLIT_NO_EMPTY);
                for ($ci = count($tokChars) - 1; $ci >= 0; $ci--) {
                    $ch = $tokChars[$ci];
                    if (isset($punctPauses[$ch])) {
                        $pause = $punctPauses[$ch];
                        break;
                    }
                    if (isUyghurLetter($ch)) break;
                }
            }
            $plan[] = [$w, $pause];
        }

        $nWords   = count($plan);
        $wordItems = [];
        $total    = 0;
        $prevTail = null;

        foreach ($plan as $wi => list($word, $pause)) {
            $syllables = word2syllable($word);
            if ($syllables === null) continue;

            $wordSegments = [];
            $keys = buildContextKeys($syllables);
            foreach ($keys as list($fi1, $nfi1)) {
                list($rows, $matchType) = $this->db->lookupCandidates(
                    $fi1, $nfi1, $o['candidates']
                );
                if (empty($rows)) continue;
                $row = $this->pickUnit($rows, $prevTail, $o);
                $seg = $this->readSeg($row['begin_pos'], $row['end_pos']);
                $wordSegments[] = $seg;
                $arr = pcmToSamples($seg);
                $prevTail = count($arr) >= 80
                    ? array_slice($arr, -80) : $arr;
                $total++;
            }
            if (empty($wordSegments)) continue;

            $wordPcm = hanningCrossfade($wordSegments, $overlap);
            if ($o['energy_norm']) {
                $wordPcm = normalizeWordRms($wordPcm);
            }
            if ($o['prosody'] && $wi === $nWords - 1 && $pause >= 300) {
                $wordPcm = resampleFactor($wordPcm, 1.06);
            }
            $wordItems[] = [$wordPcm, $pause];
        }

        if (empty($wordItems)) return ['', 0];

        $finalParts = [];
        $cnt = count($wordItems);
        foreach ($wordItems as $i => list($wp, $pause)) {
            $finalParts[] = $wp;
            if ($i < $cnt - 1) {
                $ps = $o['prosody']
                    ? (int)($pause * TTS_SAMPLE_RATE / 1000)
                    : TTS_WORD_PAUSE_SAMPLES;
                $finalParts[] = str_repeat("\x00\x00", $ps);
            }
        }

        $pcmData = implode('', $finalParts);
        if (!empty($o['psola'])) {
            try {
                $pcmData = psolaPitchSmooth($pcmData);
            } catch (\Throwable $e) {
                // PSOLA failed, use unprocessed PCM
            }
        }
        return [$pcmData, $total];
    }

    public function synthesize($text, $outputPath, $opts = null) {
        list($pcmData, $total) = $this->synthesizePcm($text, $opts);
        if (strlen($pcmData) === 0) return false;
        saveWav($pcmData, $outputPath);
        return true;
    }

    public function close() {
        if ($this->datHandle) {
            fclose($this->datHandle);
            $this->datHandle = null;
        }
        $this->db->close();
    }
}
