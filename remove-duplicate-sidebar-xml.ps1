# Elimina los .xml duplicados del sidebar cuando ya existen los .png de MakiX.
# Ejecutar en la raíz del proyecto:  powershell -File remove-duplicate-sidebar-xml.ps1

$dir = Join-Path $PSScriptRoot 'app\src\main\res\drawable'
if (-not (Test-Path $dir)) {
    Write-Error "No se encontró: $dir"
    exit 1
}

$names = @(
    'ic_sidebar_home', 'ic_sidebar_config', 'ic_sidebar_tarifas',
    'ic_sidebar_filtros', 'ic_sidebar_historial', 'ic_sidebar_revision'
)

$removed = 0
foreach ($name in $names) {
    foreach ($suffix in @('', '_filled')) {
        $png = Join-Path $dir "$name$suffix.png"
        $xml = Join-Path $dir "$name$suffix.xml"
        if ((Test-Path $png) -and (Test-Path $xml)) {
            Remove-Item $xml -Force
            Write-Host "Eliminado: $name$suffix.xml (se usa el .png)"
            $removed++
        }
    }
}

if ($removed -eq 0) {
    Write-Host 'No había duplicados png+xml, o faltan los .png.'
} else {
    Write-Host "Listo. $removed archivo(s) eliminado(s). Vuelve a compilar."
}
