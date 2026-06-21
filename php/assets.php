<?php
/**
 * Serve whitelisted static files from the parent assets directory.
 * Usage: assets.php?f=UKIJTuT.ttf
 */

$allowed = ['UKIJTuT.ttf', 'avatar.png'];
$file = basename($_GET['f'] ?? '');

if (!in_array($file, $allowed, true)) {
    http_response_code(404);
    exit;
}

$path = dirname(__DIR__) . DIRECTORY_SEPARATOR . 'assets' . DIRECTORY_SEPARATOR . $file;
if (!file_exists($path)) {
    http_response_code(404);
    exit;
}

$types = [
    'ttf' => 'font/ttf',
    'png' => 'image/png',
    'jpg' => 'image/jpeg',
    'woff'=> 'font/woff',
    'woff2'=>'font/woff2',
];
$ext = strtolower(pathinfo($file, PATHINFO_EXTENSION));
header('Content-Type: ' . ($types[$ext] ?? 'application/octet-stream'));
header('Content-Length: ' . filesize($path));
header('Cache-Control: public, max-age=604800');
readfile($path);
