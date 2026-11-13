# PichiX

Asistente Android para **Amazon Flex** (diseño y flujo inspirados en [MakiX](https://github.com/zilongsys/MakiX)).

## Repositorio

- GitHub: [zilongsys/PichiX](https://github.com/zilongsys/PichiX)
- Cuenta/organización: misma que MakiX (`zilongsys`)

## Versión

Formato `x.y.z` (como MakiX): el segmento **z** sube **solo al entregar cambios**, no en cada compilación. Usar `./gradlew :app:bumpVersion` o editar `app/version.properties`. Al llegar a **100**, sube **y** y **z** vuelve a **0**.

Valores en `app/version.properties`; no editar `versionName` en `build.gradle`.

## APK desde GitHub

Cada push a `main` dispara `.github/workflows/android.yml`, que compila y publica el APK en
[Releases](https://github.com/zilongsys/PichiX/releases) (`PichiX-vX.Y.Z.apk`). Ramas y PR dejan el APK como artefacto del workflow.
PC y GitHub deben firmar con la **misma clave** (`keystore.properties` + secretos). Guía: `docs/FIRMA.md`.

Guía para Claude Code: `CLAUDE.md`. Reglas de Cursor: `.cursor/rules/`.

## Compilar

```bash
./gradlew :app:assembleDebug
```

## Navegación

Barra inferior con 5 destinos; los que agrupan varias páginas muestran sub-pestañas arriba.

| Destino | Páginas |
|---------|---------|
| Inicio | Estado, lista previa, resumen de hoy, accesos rápidos |
| Reglas | Tarifas · Alertas |
| Actividad | Historial · Calendario · Log bot |
| Análisis | Estadísticas · No tomadas |
| Ajustes | Índice de 12 secciones con resumen; cada una se abre a pantalla completa. «Todos los ajustes» = vista completa con buscador |

## Páginas

| Pestaña | Función |
|---------|---------|
| Home | Estado del bot, respaldo completo (exportar/importar) |
| Config | Paquete Flex, accesibilidad, notificaciones, respaldo completo |
| Tarifas | Criterios rápidos o reglas detalladas |
| Alertas | Acciones por texto de notificación Flex |
| Historial | Registro de bloques |
| Calendario | Rutas tomadas por **fecha del bloque** (precio y horario) |
| Estadísticas | Resumen del CSV de ofertas |
| Análisis | Ofertas no tomadas y sugerencias de reglas |
| Log bot | Eventos en tiempo real |

La pestaña **Filtros** no aplica: filtros y exclusiones van en **Tarifas**.
