# PichiX ↔ Centro de control — integración para Cursor

Los archivos de la conexión con la PC **ya están escritos** en
`app/src/main/java/com/oceanlab/pichix/dashboardcontrol/` y
`app/src/main/res/layout/dashboardcontrol_activity.xml`. Tu trabajo es **solo enchufarlos**:
7 cambios pequeños en archivos existentes, listados abajo con su archivo y su línea exacta.

## Reglas

1. **No cambies la lógica del bot.** Solo agrega las líneas indicadas.
2. **No reescribas los archivos de `dashboardcontrol/`.** Si algo no compila, corrige lo mínimo
   y explícalo en el CHANGELOG.
3. **No agregues dependencias.** No hace falta OkHttp ni coroutines: el WebSocket es propio
   (`DashboardSocket.kt`) y usa hilos normales. Tampoco hace falta `network_security_config`,
   porque un Socket directo no pasa por la política de cleartext.
4. El bot debe funcionar **igual** con la PC apagada o con la conexión desactivada.
5. Al terminar: compila (`./gradlew :app:assembleDebug`), sube la versión y sigue tus reglas de
   entrega (`.cursor/rules`).

## Qué hace cada archivo nuevo

| Archivo | Para qué |
|---|---|
| `DashboardLink.kt` | Núcleo: cola en memoria → bandeja en disco → envío numerado a la PC; comandos; estado. |
| `DashboardOutbox.kt` | Bandeja de salida (SQLite propio `dashboardcontrol_outbox.db`). Guarda hasta 7 días sin PC. |
| `DashboardSocket.kt` | Cliente WebSocket mínimo (sin librerías). |
| `DashboardSettings.kt` | Ajustes que la PC puede cambiar: versionados, validados, aplicados de una vez. |
| `PichixDashboard.kt` | Lo propio de PichiX: pausar/reanudar, estado, métricas, ofertas → eventos, ajustes expuestos. |
| `DashboardControlActivity.kt` + `dashboardcontrol_activity.xml` | Pantalla "Conexión con PC". |

## Los 7 cambios

### 1. `app/src/main/AndroidManifest.xml` — permiso de red (PichiX aún no lo tiene)
Antes de `<application` (línea 11), junto a los otros permisos:
```xml
<uses-permission android:name="android.permission.INTERNET" />
```

### 2. `app/src/main/AndroidManifest.xml` — declarar la pantalla
Dentro de `<application>`, junto a `HelpActivity`:
```xml
<activity
    android:name=".dashboardcontrol.DashboardControlActivity"
    android:exported="false"
    android:label="Conexión con PC" />
```

### 3. `PichixApplication.kt` — arrancar el enlace
En `onCreate()`, después de `BotEventLog.init(this)`:
```kotlin
com.oceanlab.pichix.dashboardcontrol.PichixDashboard.init(this)
```
No toca disco ni red en el hilo principal: solo lee preferencias y arranca dos hilos de fondo.

### 4. `data/BotEventLog.kt` — log del bot hacia la PC
En `fun log(context, category, message)`, justo después de `val item = Pending(...)`:
```kotlin
com.oceanlab.pichix.dashboardcontrol.PichixDashboard.onBotEvent(category, message)
```
Y en `private fun enqueueControl(category, message)` (ráfagas), como primera línea:
```kotlin
com.oceanlab.pichix.dashboardcontrol.PichixDashboard.onBotEvent(category, message)
```

### 5. `data/PichiFileLog.kt` — log técnico hacia la PC
En `private fun enqueue(channel, tag, level, msg, force)`, como **primera línea**, antes del
`if (!force && !fileLogEnabled) return`. Así llega aunque el log a archivo esté apagado:
```kotlin
if (channel == Channel.BOT) com.oceanlab.pichix.dashboardcontrol.PichixDashboard.onFileLog(level, tag, msg)
```
El canal UI (volcados de pantalla de `FlexUiDumper`) no se envía: es muy pesado.

### 6. `data/OfferLogger.kt` — cada oferta es un punto en los carriles de la PC
En `fun log(entry: OfferLogEntry)`, justo antes de `try { store.appendEntry(tracedEntry)`,
es decir, **después** del dedup:
```kotlin
com.oceanlab.pichix.dashboardcontrol.PichixDashboard.onOffer(tracedEntry)
```

### 7. Acceso a la pantalla "Conexión con PC"
En la pestaña Config (`ui/FlexConfigFragment.kt` + `res/layout/fragment_pichix_config.xml`),
agrega al final una sección con el mismo estilo que las demás (`SectionLabel` + `Card`) con un
botón `SparkButtonSecondary` "Conexión con PC" que abra:
```kotlin
startActivity(Intent(requireContext(), com.oceanlab.pichix.dashboardcontrol.DashboardControlActivity::class.java))
```
Si quieres mostrar el estado en el botón, usa `DashboardLink.info().status.label` en `onResume`.

## Rendimiento (compruébalo)

- `PichixDashboard.onBotEvent/onFileLog/onOffer` con la conexión **desactivada** = una lectura
  de un `@Volatile` y return.
- Activada: una comparación de nivel y un `ArrayBlockingQueue.offer` (sin JSON, disco ni red).
  Mide con `System.nanoTime()` en debug (10 000 llamadas) que la media sea **< 20 µs** y anótalo
  en el CHANGELOG.
- Disco: un hilo de prioridad mínima escribe cada 250 ms en una transacción.
- Red: hilos propios `dash-link`, `dash-read` y `dash-cmd`. Nunca el hilo principal ni el del
  servicio de accesibilidad.

## Qué verá la PC

- **Tarjeta** PichiX con estado (activo, pausado o apagado), batería, aceptadas y ganado hoy,
  y cuántos registros faltan por sincronizar.
- **Pausar/Reanudar**: igual que la pausa por notificación (`pausedAfterAccept`), sin apagar el bot.
  Si el bot está apagado en el teléfono, la PC recibe un error claro.
- **Comandos**: "Volver a ofertas" y "Pausar/seguir motor (navegación)".
- **Ajustes** (29): aceptar automático, simulación, pausas, mínimos $/h y $/bloque, hora mínima,
  modo de tarifas, qué oferta tomar, clics y refresco, ráfagas, volver a ofertas y alertas.
  Se aplican todos juntos con una sola `syncEngine()`. Si cambias algo en el teléfono, la PC se entera.
- **Carriles**: cada oferta (vista, aceptada, rechazada, perdida o simulada) y cada pausa.

## Prueba final (checklist)

1. En la PC abre el Centro de control (v1.1.0 o superior) con `iniciar.bat`.
2. En el teléfono: Config → Conexión con PC → pon la IP de la PC y el token → activa → Guardar.
   El estado debe decir **Conectado** y en la PC aparece la tarjeta PichiX.
3. Pausar y reanudar desde la PC: el bot debe pausarse y reanudarse de verdad.
4. Cambia "Mínimo por hora" desde la PC: el valor debe cambiar en Config del teléfono.
5. **Sin conexión**: cierra el Centro de control, deja trabajar al bot 10 minutos (la pantalla
   "Conexión con PC" muestra los registros pendientes subiendo), vuelve a abrir el Centro de control.
   Los pendientes deben bajar a 0 y en la PC, en PichiX → Historial, deben estar esos 10 minutos.
6. Con la conexión desactivada, el bot funciona exactamente igual que antes.
