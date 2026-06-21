<?php
/**
 * Uyghur TTS — Synthesis API endpoint
 * POST: { text: string, profile: string }  →  audio/wav
 * On error: JSON { error: string }
 */

header('Access-Control-Allow-Origin: *');
header('Access-Control-Allow-Methods: POST, OPTIONS');
header('Access-Control-Allow-Headers: Content-Type');

if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
    http_response_code(204);
    exit;
}

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    header('Content-Type: application/json; charset=utf-8');
    http_response_code(405);
    echo json_encode(['error' => 'POST only']);
    exit;
}

require_once __DIR__ . '/UighurTTS.php';

$baseDir = dirname(__DIR__);
$dbPath  = $baseDir . DIRECTORY_SEPARATOR . 'dada.db';
$datPath = $baseDir . DIRECTORY_SEPARATOR . 'Lab.dat';

$contentType = $_SERVER['CONTENT_TYPE'] ?? '';
if (stripos($contentType, 'application/json') !== false) {
    $input = json_decode(file_get_contents('php://input'), true) ?? [];
} else {
    $input = $_POST;
}

$text    = trim($input['text'] ?? '');
$profile = $input['profile'] ?? 'raw';

if ($text === '') {
    header('Content-Type: application/json; charset=utf-8');
    http_response_code(400);
    echo json_encode(['error' => '请输入要合成的维吾尔文文本']);
    exit;
}

if (!file_exists($dbPath)) {
    header('Content-Type: application/json; charset=utf-8');
    http_response_code(500);
    echo json_encode(['error' => "dada.db not found: {$dbPath}"]);
    exit;
}
if (!file_exists($datPath)) {
    header('Content-Type: application/json; charset=utf-8');
    http_response_code(500);
    echo json_encode(['error' => "Lab.dat not found: {$datPath}"]);
    exit;
}

$profiles = ttsProfiles();
$opts = $profiles[$profile] ?? $profiles['raw'];

try {
    $engine = new UighurTTS($dbPath, $datPath);
    list($pcmData, $total) = $engine->synthesizePcm($text, $opts);
    $engine->close();
} catch (Throwable $e) {
    header('Content-Type: application/json; charset=utf-8');
    http_response_code(500);
    echo json_encode(['error' => '合成引擎异常: ' . $e->getMessage()]);
    exit;
}

if (strlen($pcmData) === 0) {
    header('Content-Type: application/json; charset=utf-8');
    http_response_code(422);
    echo json_encode(['error' => '没有匹配到任何音节片段，请检查文本']);
    exit;
}

$wavData  = buildWavData($pcmData);
$duration = (strlen($pcmData) / 2) / TTS_SAMPLE_RATE;

header('Content-Type: audio/wav');
header('Content-Length: ' . strlen($wavData));
header('X-TTS-Segments: ' . $total);
header('X-TTS-Duration: ' . round($duration, 2));
echo $wavData;
