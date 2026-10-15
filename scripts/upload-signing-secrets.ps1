# Sube la clave de firma a secretos de GitHub (mismo APK que Android Studio).
# Requiere: gh auth login  y  keystore.properties + keystore/pichix.jks en la raíz del repo.
# Uso (desde la raíz):
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\upload-signing-secrets.ps1

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$propsPath = Join-Path $root "keystore.properties"
if (-not (Test-Path $propsPath)) {
    throw "Falta keystore.properties en la raíz del proyecto."
}

$props = @{}
Get-Content $propsPath | ForEach-Object {
    if ($_ -match '^\s*#' -or $_ -match '^\s*$') { return }
    $n, $v = $_ -split '=', 2
    $props[$n.Trim()] = $v.Trim()
}

$storeRel = $props['storeFile']
$storePath = Join-Path $root $storeRel
if (-not (Test-Path $storePath)) {
    throw "No está el almacén: $storePath"
}

$b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($storePath))
$b64 | gh secret set PICHIX_KEYSTORE_BASE64
$props['storePassword'] | gh secret set PICHIX_KEYSTORE_PASSWORD
$props['keyAlias'] | gh secret set PICHIX_KEY_ALIAS
$props['keyPassword'] | gh secret set PICHIX_KEY_PASSWORD

Write-Host "Secretos subidos a GitHub. El próximo APK de Actions usará la misma clave que Android Studio."
Write-Host "Guarda una copia de $storePath y de keystore.properties fuera del PC (USB / gestor de contraseñas)."
