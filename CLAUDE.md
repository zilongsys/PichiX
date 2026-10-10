# PichiX — guía para Claude Code

Asistente Android (Kotlin, AccessibilityService) que automatiza la toma de bloques en **Amazon Flex**.
Repo: https://github.com/zilongsys/PichiX (`origin`, rama `main`). Idioma de trabajo: **español** (UI, changelog, commits).

Las reglas originales viven en `.cursor/rules/*.mdc` (Cursor). Este archivo las resume y las adapta a Claude Code
(escritorio y móvil/nube). Si hay conflicto, manda este archivo; si cambias una regla, actualiza ambos.

## Entorno y compilación

- Paquete `com.oceanlab.pichix`, minSdk 26, compileSdk/targetSdk 34, Kotlin 1.9.25, AGP 8.7.3, Gradle 8.11.1, JDK 17.
- Un solo módulo `:app`. Sin tests automatizados: la verificación es que **compile** (`./gradlew :app:assembleDebug`).
- **El APK oficial lo compila GitHub Actions** (`.github/workflows/android.yml`), no el móvil ni la nube:
  - push a `main` → build + **GitHub Release `vX.Y.Z`** con `PichiX-vX.Y.Z.apk` (notas = sección del CHANGELOG).
  - push a otra rama / PR → build + artefacto descargable (sin Release).
  - Firma estable opcional con secretos `PICHIX_KEYSTORE_BASE64`, `PICHIX_KEYSTORE_PASSWORD`, `PICHIX_KEY_ALIAS`,
    `PICHIX_KEY_PASSWORD`. Sin ellos la clave debug es efímera y el APK no actualiza sobre otro firmado distinto.
- Si el entorno no tiene JDK/SDK (típico en móvil/nube), **no intentes compilar**: revisa el código con cuidado
  (el historial tiene fallos de compilación como `$` sin escapar en strings Kotlin) y confía en el CI; revisa su
  resultado con `gh run list` / `gh run watch` si hay `gh`.

## Versionado (regla `version-bump.mdc`)

- Fuente única: `app/version.properties` (`VERSION_MAJOR/MIDDLE/PATCH/CODE`). **No** editar `versionName` en `build.gradle`.
- Compilar **no** sube versión. Se sube **una vez por entrega con cambios de código de la app**:
  `./gradlew :app:bumpVersion` o editar a mano (PATCH +1 y `VERSION_CODE` +1).
- PATCH 0–100; al pasar de 100 → MIDDLE+1 y PATCH=0; si MIDDLE=100 → MAJOR+1, MIDDLE=0.
- Cambios solo de infraestructura/docs/CI (sin tocar la app) **no** suben versión.
- Cada versión nueva lleva sección `## vX.Y.Z (Mes AAAA)` al principio de `CHANGELOG.md`, con subsecciones
  **Añadido / Actualizado / Corregido / Eliminado** (solo las que apliquen). El workflow usa esa sección como notas del Release.

## Entrega (regla `auto-git-push.mdc`)

Al terminar un cambio de código compilable y **sin pedir confirmación** (salvo que el usuario diga «sin commit»/«no subas»
o sea solo una pregunta/revisión):

1. Bump de versión + entrada de CHANGELOG (ver arriba).
2. `git add` solo de lo tocado; **nunca** `.env`, `local.properties`, `*.jks`, `*.keystore`, credenciales ni `dist/`.
3. Commit con una línea clara que empiece por `vX.Y.Z:` (p. ej. `v0.2.34: fix pausa al reservar`).
4. `git push origin HEAD`. En Claude Code móvil/nube: **empuja a `main`** (es lo que dispara Release + APK); si el
   entorno te obliga a una rama, avisa al usuario y dile que el APK sale como artefacto del workflow, no como Release.
5. Tag: el Release de CI ya crea el tag `vX.Y.Z`; no hace falta `git tag` manual.
6. Si el push falla por auth en local: `gh auth login` y reintentar una vez.
7. Autoría local histórica: `onlyeyes <onlyeyes@users.noreply.github.com>`.

**ZIPs en `dist/`** (`PichiX-vX.Y.Z-full.zip` y `-changed.zip`): solo cuando se trabaja **en el PC Windows** del usuario con
PowerShell: `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\pack-delivery.ps1`, y dar las rutas absolutas.
En móvil/nube **no** se generan (la entrega es el APK del Release). `dist/` está en `.gitignore`.

## Mapa del código (`app/src/main/java/com/oceanlab/pichix/`)

| Paquete | Rol |
|---|---|
| `service/` | `PichixAccessibilityService` (lee/clica Flex), `PichixForegroundService`, `FlexNotificationListenerService`, `OverlayService`, `BotServiceCoordinator`, `PauseByOverClicksController`, `FlexBlockingPhrases`; `accessibility/` = lectura de pantalla, scroll, gate de primer plano, volcado UI |
| `analyzer/` | Interpretar ofertas y decidir: `FlexOfferSelector`, `FlexTariffEvaluator`, `FlexStationMatcher`, `FlexTakeOutcomeReader`, `GrabEval`, `OfferListDetailMatcher` |
| `data/` | Estado y persistencia: `AppSettings`, `FlexState`, `FlexTariffRule`/`FlexAlertRule`, `FlexMessageHub`, `OfferLogger`/`OfferLogCsvStore` (CSV de ofertas), `NotTakenAnalysis`, `OfferStatsAnalyzer`, `BotEventLog`, `PichixConfigBackup` |
| `ui/` | Pestañas Home, Config, Tarifas, Alertas, Historial, Calendario, Estadísticas, Análisis, Log bot; `MainActivity` |
| `util/` | `AlertManager`, `FlexAlertDispatcher`, `CallOnBlockHelper`, `PeakHoursScheduler`, matchers de texto, `DiagnosticPack` |
| `dashboardcontrol/` | Enlace WebSocket propio con el «centro de control» de la PC (docs en `docs/dashboardcontrol/`) |

## Reglas de trabajo

- **Velocidad primero**: el clic de Schedule y el grabber no deben ralentizarse (ver CHANGELOG v0.2.30). No añadir
  esperas ni trabajo pesado en el camino de toma de bloques.
- **No tocar la lógica del bot** para tareas del dashboard: `docs/dashboardcontrol/CURSOR_INTEGRACION.md` solo pide
  enchufar archivos; sin dependencias nuevas (WebSocket propio, sin OkHttp/coroutines).
- El bot debe funcionar igual con la PC apagada o la conexión desactivada.
- Estados de oferta (`OfferStatus`): distinguir ACEPTADA / PERDIDA / RECHAZADA / VISTA como en el CHANGELOG.
- Vigilar Kotlin: escapar `\$` en strings; no añadir dependencias sin necesidad (`FAIL_ON_PROJECT_REPOS`, solo google+mavenCentral).
- `proguard-rules.pro` mantiene todo `com.oceanlab.pichix.**`; el CI compila **debug**, release no se usa.
- `README.md` lista las pestañas: actualizarlo si se añade/quita una.
- Leer `CHANGELOG.md` (últimas 3–4 versiones) antes de tocar un área: documenta decisiones y regresiones previas.
