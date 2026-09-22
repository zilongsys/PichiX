# Genera ZIPs de entrega: proyecto completo (sin build) + solo ficheros cambiados.
# Uso: powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\pack-delivery.ps1
# Opcional: -Version 0.2.14  -ChangedSince HEAD~1

param(
    [string]$Version = "",
    [string]$ChangedSince = "HEAD~1"
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path (Join-Path $Root "app"))) {
    $Root = $PSScriptRoot
    if (-not (Test-Path (Join-Path $Root "app"))) {
        throw "No se encontro la raiz del proyecto PichiX"
    }
}

Set-Location $Root

if ([string]::IsNullOrWhiteSpace($Version)) {
    $vp = Join-Path $Root "app\version.properties"
    if (Test-Path $vp) {
        $props = Get-Content $vp -Raw
        if ($props -match 'VERSION_NAME\s*=\s*([^\r\n]+)') {
            $Version = $Matches[1].Trim()
        } elseif ($props -match 'versionName\s*=\s*([^\r\n]+)') {
            $Version = $Matches[1].Trim()
        }
    }
}
if ([string]::IsNullOrWhiteSpace($Version)) {
    $Version = "dev"
}

$Dist = Join-Path $Root "dist"
New-Item -ItemType Directory -Force -Path $Dist | Out-Null

$ExcludeDirNames = @(
    "build", ".gradle", ".idea", ".git", "captures", ".cxx",
    "node_modules", "dist", ".kotlin"
)
$ExcludeFilePatterns = @(
    "*.apk", "*.aab", "*.jks", "*.keystore", "*.iml",
    "local.properties", ".env", "*.zip"
)

function Test-ExcludedPath([string]$FullPath, [string]$Base) {
    $rel = $FullPath.Substring($Base.Length).TrimStart("\", "/")
    $parts = $rel -split "[\\/]"
    foreach ($p in $parts) {
        if ($ExcludeDirNames -contains $p) { return $true }
    }
    $name = Split-Path $FullPath -Leaf
    if ($name -eq "local.properties" -or $name -eq ".env") { return $true }
    foreach ($pat in $ExcludeFilePatterns) {
        if ($name -like $pat) { return $true }
    }
    return $false
}

function New-ZipFromFiles([string]$ZipPath, [string[]]$Files, [string]$Base) {
    if (Test-Path $ZipPath) { Remove-Item $ZipPath -Force }
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::Open($ZipPath, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($f in $Files) {
            if (-not (Test-Path -LiteralPath $f -PathType Leaf)) { continue }
            $rel = $f.Substring($Base.Length).TrimStart("\", "/").Replace("\", "/")
            [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
                $zip, $f, $rel,
                [System.IO.Compression.CompressionLevel]::Optimal
            ) | Out-Null
        }
    } finally {
        $zip.Dispose()
    }
}

# --- Full project ---
$allFiles = Get-ChildItem -Path $Root -Recurse -File -Force | Where-Object {
    -not (Test-ExcludedPath $_.FullName $Root)
} | ForEach-Object { $_.FullName }

$fullZip = Join-Path $Dist "PichiX-v$Version-full.zip"
New-ZipFromFiles $fullZip $allFiles $Root
Write-Host "FULL: $fullZip ($($allFiles.Count) files)"

# --- Changed only ---
$changedRel = @()
try {
    $changedRel = @(git -C $Root diff --name-only $ChangedSince HEAD 2>$null)
    if (-not $changedRel -or $changedRel.Count -eq 0) {
        $changedRel = @(git -C $Root show --name-only --pretty=format: HEAD 2>$null | Where-Object { $_ })
    }
} catch {
    $changedRel = @()
}

$changedAbs = @()
foreach ($rel in $changedRel) {
    if ([string]::IsNullOrWhiteSpace($rel)) { continue }
    $abs = Join-Path $Root ($rel -replace "/", "\")
    if ((Test-Path -LiteralPath $abs -PathType Leaf) -and -not (Test-ExcludedPath $abs $Root)) {
        $changedAbs += $abs
    }
}

$changedZip = Join-Path $Dist "PichiX-v$Version-changed.zip"
if ($changedAbs.Count -eq 0) {
    # Fallback: empty marker so el usuario siempre recibe el zip
    $tmpMarker = Join-Path $env:TEMP "pichix-changed-empty.txt"
    "No tracked file changes for $ChangedSince" | Set-Content $tmpMarker -Encoding UTF8
    if (Test-Path $changedZip) { Remove-Item $changedZip -Force }
    Compress-Archive -Path $tmpMarker -DestinationPath $changedZip -Force
    Write-Host "CHANGED: $changedZip (0 project files; marker only)"
} else {
    New-ZipFromFiles $changedZip $changedAbs $Root
    Write-Host "CHANGED: $changedZip ($($changedAbs.Count) files)"
}

Write-Output $fullZip
Write-Output $changedZip
