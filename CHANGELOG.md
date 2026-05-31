# PichiX — Historial de cambios

Formato de versión: `x.y.z` — `z` (PATCH) sube en cada compilación de **0 a 100**; al llegar a 100, `y` (+1) y `z` = 0 (igual si `y` llega a 100 con `x` +1).

La versión se actualiza automáticamente en `app/version.properties` al ejecutar `assemble*` o `compile*Kotlin`.

**Convención de entradas:** en cada versión se listan cambios como **Añadido**, **Actualizado**, **Corregido** o **Eliminado**.

---

## v0.1.14 (Mayo 2026)

### Actualizado
- Log UI: volcado **sin throttle** en cada clic Refresh (antes + 450 ms después), con `TEXTO_PANTALLA` y todos los nodos (clase, bounds, text, desc).
- Lectura de pantalla incluye `contentDescription` y se repite tras Refresh aunque la UI no cambie visualmente.

---

## v0.1.13 (Mayo 2026)

### Corregido
- Log UI Flex en Logcat: líneas en nivel **Info** (tag `PichiXDebug`) y volcado también en cada tick del bot con debug activo.

### Actualizado
- Textos Config: qué filtros afectan al motor vs alertas; «solo primer plano» marcado como reservado.

---

## v0.1.12 (Mayo 2026)

### Corregido
- **Regresión v0.1.8+:** el motor vuelve al comportamiento de **v0.1.7** (`rootInActiveWindow`, sin bloqueo por «solo primer plano»).
- Eliminado `FlexWindowRoots` (rompía lectura/clic en muchos móviles).
- Filtro de texto en pantalla para Refresh: el motor usa siempre Contiene + ignora mayúsculas (los toggles de Config siguen para alertas; el motor no se queda mudo).
- Clic en botón Refresh: exacto o parcial, siempre sin distinguir mayúsculas.
- Migración: desactiva «Solo con Flex en primer plano» guardado en `true` (ese filtro bloqueaba el bucle).

---

## v0.1.11 (Mayo 2026)

### Corregido
- Motor bloqueado: `isActive` en ventanas Flex falla en varios móviles; ahora se busca ventana activa → con foco → cualquier ventana Flex.
- `flexRoot()` ya no depende del switch «solo primer plano» (solo filtra *si* actuar, no *cómo* leer el árbol).
- Overlay sobre Flex: no se confunde con «usuario en PichiX» si Flex sigue en `windows`.
- Clic Refresh: si modo Exacto no encuentra el botón, reintenta como Contiene.
- Aviso «Esperando Flex en primer plano…» si el tick se omite por foreground.

---

## v0.1.10 (Mayo 2026)

### Corregido
- Bot inactivo con **Flex en primer plano** y overlay flotante: ahora lee la ventana activa de Flex (`flagRetrieveInteractiveWindows`), no la de PichiX.
- Modos de coincidencia antiguos (`screen_match_*`) se normalizan a `contains` / `exact`.
- Modo solo Refresh: aviso en observador si el texto de pantalla no coincide (p. ej. mayúsculas).

---

## v0.1.9 (Mayo 2026)

### Actualizado
- Texto de **pantalla de ofertas**: distingue mayúsculas por defecto; opción «Ignorar mayúsculas» y Contiene/Exacto.
- Botón Refresh, Pause by over clicks y **Alertas**: «Ignorar mayúsculas» activado por defecto; Contiene/Exacto configurable.

---

## v0.1.8 (Mayo 2026)

### Añadido
- Log UI/archivo estilo MakiX (`PichiXDebug` en Logcat, `pichix_ui_*.log`, compartir diagnóstico).
- Config: secciones expandibles; coincidencia de pantalla **exacta** o **parte del texto** (sensible a mayúsculas).
- Config: bot solo actúa con **Flex en primer plano**.

### Actualizado
- Return 2 offers: switch Home y Config sincronizados en tiempo real.
- Basic click: intervalo en **segundos** (Smart click muestra solo rango aleatorio).

---

## v0.1.7 (Mayo 2026)

### Añadido
- **Config → Clics del bot:** Basic click (intervalo fijo en ms) y Smart click (espera aleatoria entre segundos).
- Texto exacto del botón a pulsar y texto que debe aparecer en pantalla antes de hacer clic.

### Actualizado
- **Home:** solo interruptor Return 2 offers (sin botón «ejecutar ahora»).
- Alertas: una sola regla suena por notificación (la primera que coincida).

---

## v0.1.6 (Mayo 2026)

### Añadido
- **Alertas:** varios textos por regla (líneas o comas); modo **Cualquiera** (OR) o **Todas** (AND).
- **Alertas:** sonido desde archivo de audio del dispositivo (carpetas / almacenamiento), además del selector de tonos.
- **Config → Pause by over clicks:** pausa el bot al detectar notificación con texto configurado; sonidos al pausar/reanudar; minutos hasta reanudar automáticamente.
- **Config → Return 2 offers:** interruptor para macro `return2offers` (2× Atrás fuera de la lista de ofertas).
- **Home → accesos rápidos:** interruptor Return 2 offers (auto) y botón para ejecutarlo manualmente.

### Actualizado
- Una regla puede vigilar varios textos (OR/AND); por notificación solo suena **una** regla (la primera que coincida).
- Reglas antiguas con un solo `matchText` se migran al nuevo formato `matchTexts`.

### Corregido
- Crash `ArrayIndexOutOfBoundsException` en indicadores de pestaña sucia tras quitar **Filtros** (sidebar de 7 pestañas).

---

## v0.1.5 (Mayo 2026)

### Añadido
- Pestaña **Tarifas → Reglas** (modo detallado), editor de reglas y duración en formato h.min.
- Colores modo noche para editor de reglas Flex.

### Corregido
- Bucle de índices del sidebar (`refreshDirtyIndicators`) al usar **Reglas** en Tarifas.

---

## v0.1.1 (Mayo 2026)

### Añadido
- Versión automática estilo MakiX (`version.properties` + Gradle).
- Pestaña **Alertas:** reglas por texto de notificación Flex (sonido, repeticiones).
- Repositorio GitHub `zilongsys/PichiX`.

### Eliminado
- Pestaña **Filtros** (criterios integrados en **Tarifas**).
