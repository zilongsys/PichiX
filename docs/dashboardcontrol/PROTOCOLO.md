# Protocolo teléfono ↔ Centro de control (v2)

Transporte: WebSocket de texto, mensajes JSON, campo `t` = tipo.
El teléfono es el **cliente**; la PC es el **servidor**.

```
ws://<ip-de-la-pc>:8765/agent?token=<TOKEN>
```

- Token incorrecto → la PC cierra con código **4001** (no reintentar rápido).
- Primer mensaje obligatorio: `hello` (antes de 10 s) → si no, cierre **4002**.
- Si el mismo teléfono se conecta dos veces, la PC cierra la conexión vieja con **4003**.
- La identidad de un sistema es `system@deviceId`. Dos apps en el mismo teléfono = dos sistemas.

## Novedad de v2: nada se pierde sin conexión

El teléfono guarda **todo** log y evento en una **bandeja de salida en disco**
(SQLite propio de la app), numerado con `q` (1, 2, 3… nunca se repite).
Si la PC no está (minutos o días), se acumula (hasta 7 días). Al reconectar:

1. El teléfono manda `hello` con su `outboxId` (identifica la bandeja).
2. La PC responde `welcome` con `ack` = último `q` que ya tiene guardado de esa bandeja.
3. El teléfono borra hasta `ack` y manda el resto en lotes `up`, en orden.
4. La PC guarda cada lote y, **después del commit en disco**, responde `up_ack` con el último `q`.
5. Solo entonces el teléfono borra esos registros.

Si la conexión se corta a mitad, se reanuda desde el último `up_ack`. La PC descarta lo
repetido (q ≤ último recibido), así que tampoco hay duplicados. Si se borran los datos
de la app nace una bandeja nueva (`outboxId` distinto) y la PC empieza de cero para ella.

Lo que llega con más de 60 s de atraso se trata como **atrasado**: va al historial
(pestaña Historial) y los eventos se colocan en su hora real en los carriles, sin
notificación de escritorio. No se mezcla con el log en vivo.

## Reglas de rendimiento (obligatorias en la app)

1. Nada de red, disco ni JSON en el hilo principal ni en el del AccessibilityService.
2. `log()` y `event()` solo encolan en memoria (cola fija; si se llena, se descarta lo más viejo y se avisa).
3. Un hilo de prioridad mínima pasa la cola a la bandeja cada 250 ms (una transacción por lote).
4. Envío en lotes `up` de hasta 500 ítems / 512 KB, máximo 4 lotes sin confirmar. Estado cada **3 s**.
5. Filtrado de nivel **en el teléfono** (`set_log_level`): lo filtrado ni se guarda.
6. Si la PC no está, el bot sigue igual. Reintento con backoff 1 s → 30 s (±20 %).
7. Los ajustes se aplican de forma **atómica** y con **versión** (ver `set_settings`).

## Teléfono → PC

### hello
```json
{"t":"hello","proto":2,"system":"PichiX","device":"samsung SM-A546U","deviceId":"a1b2c3",
 "appVersion":"0.2.15","time":1758580000000,
 "outboxId":"9a5773e9d57f480d850b23146aa3d3ac",
 "sync":{"pending":18234,"oldestTs":1758400000000},
 "schema":{"commands":[...],"settings":[...]},
 "settings":{"flex_min_hourly":23.0},"settingsVersion":12,
 "state":{...}}
```
`time` = reloj del teléfono en ms (la PC calcula el desfase).
`sync.pending` = registros en la bandeja sin confirmar; `oldestTs` = el más viejo.

### up (lote numerado desde la bandeja)
```json
{"t":"up","pending":17734,"items":[
  {"q":501,"k":"l","ts":1758400000123,"lv":"I","tag":"scan","m":"refresh #212"},
  {"q":502,"k":"e","ts":1758400000456,"type":"bloque","status":"ACEPTADA","tone":"ok",
   "title":"16:00–19:00 · $66 · Fort Myers","data":{"pago":"$66"},"notify":true}]}
```
`k`: `l` = log, `e` = evento. `pending` = lo que queda después de este lote.
Log: `lv` = `D` `I` `W` `E`. Evento: `tone` = `ok` (verde) · `bad` (rojo) · `warn` (ámbar) ·
`info` (azul) · `muted` (gris). `notify: true` → notificación de escritorio (solo si llega a tiempo).

### schema
```json
{"commands":[
   {"name":"pause","label":"Pausar"},
   {"name":"resume","label":"Reanudar"},
   {"name":"volver_ofertas","label":"Volver a ofertas"},
   {"name":"reiniciar_contadores","label":"Reiniciar contadores","confirm":true}],
 "settings":[
   {"key":"flex_min_hourly","label":"Mínimo por hora","type":"float","group":"Criterios",
    "min":0,"max":500,"step":0.5,"unit":"$/h","help":"texto corto"},
   {"key":"flex_click_mode","label":"Modo de clic","type":"enum","options":["basic","smart"],"group":"Clics"},
   {"key":"flex_auto_accept","label":"Aceptar automáticamente","type":"bool","group":"Comportamiento"}],
 "settingsLayout":"tabs",
 "settingsGroupsAsTabs":true}
```
Tipos: `bool`, `int`, `float`, `string`, `enum`. El dashboard dibuja el formulario a partir de esto:
no hay que tocar la PC para agregar un ajuste o una app nueva.
Si `settingsLayout` = `"tabs"` (o `settingsGroupsAsTabs` = true), la PC debería mostrar **cada `group` como pestaña** (menos scroll).
Las ofertas vistas/aceptadas/rechazadas llegan como eventos `bloque` en los **carriles** y en la pestaña **Historial** de la PC.

### state (cada 3 s, y dentro de los acks de pause/resume)
```json
{"t":"state","state":{"status":"running","statusText":"buscando bloques",
  "battery":61,"charging":false,"temp":34.2,
  "sync":{"pending":0,"oldestTs":0},
  "metrics":[{"k":"Aceptadas hoy","v":"2"},{"k":"Ganado hoy","v":"$132"},{"k":"Vistas hoy","v":"41"}]}}
```
`status`: `running` | `paused` | `error` | `idle`. Las 3 primeras métricas salen en la tarjeta de Inicio.

### settings (cuando el usuario cambia ajustes desde el teléfono)
```json
{"t":"settings","settings":{...},"settingsVersion":13}
```

### ack (respuesta a cada `cmd`, mismo `id`)
```json
{"t":"ack","id":"u1k2","ok":true,"data":{"settings":{...},"settingsVersion":13}}
{"t":"ack","id":"u1k3","ok":false,"error":"conflicto: el teléfono está en v13","data":{"settings":{...},"settingsVersion":13}}
```
La PC espera el ack **8 s**; después marca el comando como "sin confirmación".

### log / event (v1, siguen aceptándose)
`{"t":"log","lines":[...]}` y `{"t":"event","events":[...]}` sin número: se muestran en vivo
pero no tienen garantía de entrega. Las apps nuevas usan `up`.

## PC → teléfono

```json
{"t":"welcome","serverTime":1758580000000,"proto":2,"ack":500}
{"t":"up_ack","q":1000}
{"t":"cmd","id":"u1k2","name":"pause","args":{}}
```

| name | args | qué debe hacer la app | data del ack |
|---|---|---|---|
| `pause` | — | pausar el bot (igual que el botón de la app) | `{"state":{...}}` |
| `resume` | — | reanudar | `{"state":{...}}` |
| `get_settings` | — | nada, solo leer | `{"settings","settingsVersion"}` |
| `set_settings` | `{"baseVersion":12,"values":{"k":v}}` | si `baseVersion` ≠ versión actual → rechazar; si no, validar todo, aplicar todo junto, subir versión **una vez**, recargar el motor **una vez** | `{"settings","settingsVersion"}` |
| `set_log_level` | `{"level":"W"}` | cambiar el filtro de logs en origen | — |
| `ping` | — | responder ok | — |
| otro | `{...}` | comando propio de la app declarado en `schema.commands` | libre |

## Historial en la PC (solo desde la propia PC)

```
GET /api/logs?agent=<system@deviceId>&from=<ms>&to=<ms>&lv=I&q=<texto>&limit=5000
GET /api/events?agent=<system@deviceId>&from=<ms>&to=<ms>&limit=5000
```
Lo usa el dashboard (pestaña Historial y el detalle de los carriles). Retención: `retencion_dias` de `config.json`.
