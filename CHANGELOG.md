# PichiX — Historial de cambios

Formato de versión: `x.y.z` — `z` (PATCH) sube en cada compilación de **0 a 100**; al llegar a 100, `y` (+1) y `z` = 0 (igual si `y` llega a 100 con `x` +1).

La versión se actualiza automáticamente en `app/version.properties` al ejecutar `assemble*` o `compile*Kotlin`.

**Convención de entradas:** en cada versión se listan cambios como **Añadido**, **Actualizado**, **Corregido** o **Eliminado**.

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
