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
$b64File = Join-Path $env:TEMP "pichix-keystore.b64"
[System.IO.File]::WriteAllText($b64File, $b64, [System.Text.ASCIIEncoding]::new())
cmd /c "gh secret set PICHIX_KEYSTORE_BASE64 < `"$b64File`""
if ($LASTEXITCODE -ne 0) { throw "No se pudo subir PICHIX_KEYSTORE_BASE64" }
gh secret set PICHIX_KEYSTORE_PASSWORD --body $props['storePassword']
gh secret set PICHIX_KEY_ALIAS --body $props['keyAlias']
gh secret set PICHIX_KEY_PASSWORD --body $props['keyPassword']

Write-Host "Secretos subidos a GitHub. El próximo APK de Actions usará la misma clave que Android Studio."
Write-Host "Guarda una copia de $storePath y de keystore.properties fuera del PC (USB / gestor de contraseñas)."
