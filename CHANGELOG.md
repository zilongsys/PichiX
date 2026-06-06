# PichiX — Historial de cambios

Formato de versión: `x.y.z` — `z` (PATCH) sube en cada compilación de **0 a 100**; al llegar a 100, `y` (+1) y `z` = 0 (igual si `y` llega a 100 con `x` +1).

La versión se actualiza automáticamente en `app/version.properties` al ejecutar `assemble*` o `compile*Kotlin`.

**Convención de entradas:** en cada versión se listan cambios como **Añadido**, **Actualizado**, **Corregido** o **Eliminado**.

---

## v0.1.58 (Mayo 2026)

### Actualizado
- **Config — Clics del bot (resto)**: botón Refresh, coincidencia de texto, pantalla de ofertas y nota final con hints colapsables; criterio «Mejor de pantalla» agrupado; separadores entre bloques.

---

## v0.1.57 (Mayo 2026)

### Actualizado
- **Config — Clics del bot**: Basic/Smart compactos con iconos y leyendas; Refresh, ráfaga, scroll y elección de oferta en una línea con switch; hints colapsables antes de cada separador; tiempos de ráfaga en filas compactas.

---

## v0.1.56 (Mayo 2026)

### Actualizado
- **Config — Automatización Flex**: campos de tiempo más compactos (50×34dp); leyendas más descriptivas; textos colapsables antes de cada separador.

---

## v0.1.54 (Mayo 2026)

### Actualizado
- **Config — Automatización Flex**: Return 2, aceptar auto y solo primer plano en una línea con switch; textos explicativos colapsables; tiempos Return 2 en una fila con iconos min/máx/cooldown y leyenda; disparadores con descripción colapsable; botones añadir/restaurar compactos con iconos y leyenda.

---

## v0.1.53 (Mayo 2026)

### Actualizado
- **Campos de texto**: fondo crema claro (`#FFF8F0`) en todos los inputs de la app (Config, Tarifas, filtros, bottom sheets); borde cálido acorde. En tema oscuro, tono ligeramente más claro que la tarjeta para distinguirlos.

---

## v0.1.51 (Mayo 2026)

### Actualizado
- **Config — layout**: nota del motor Flex al inicio del scroll (con Mostrar/Ocultar); permisos en una línea con botón icono; encabezados de sección más grandes; campos de tiempo con etiqueta a la izquierda y ancho para 4 dígitos; más aire en campos de entrada.

---

## v0.1.48 (Mayo 2026)

### Actualizado
- **Config — pie de pantalla**: nota informativa azul arriba del botón Guardar, con opción Mostrar/Ocultar (estado guardado); botón con icono de guardar y márgenes superior/inferior simétricos (14dp).

---

## v0.1.47 (Mayo 2026)

### Corregido
- **Foco en Config**: encabezados de sección ya no roban foco (un solo toque para colapsar); al usar switches/campos en INTERFAZ u otras secciones el scroll ya no salta a la primera sección.

---

## v0.1.45 (Mayo 2026)

### Corregido
- **Ráfaga**: al terminar la duración configurada vuelve al intervalo normal (Basic/Smart, scroll y evaluación completa); el reloj de ráfaga avanza aunque Flex no esté al frente.
- **Detección de pantalla**: observers, Return 2 y grabber solo corren con Flex en primer plano; eliminado periodo de gracia de 15 s por eventos antiguos.
- **Foco en Config**: scroll y foco se anclan al control activo al colapsar/expandir secciones o cambiar valores.

### Añadido
- **Secciones Config**: estado colapsado/expandido persistido por sección al salir y volver a la pantalla.
- **Permisos en Config**: estado visible (activado / no activado) para accesibilidad, notificaciones y superponer sobre otras apps.

---

## v0.1.44 (Mayo 2026)

### Corregido
- **Return 2 cíclico**: bloqueo por ids de lista (offer_pay, filter, recycler) y detalle (offer_details_station, pay_range, time_window); periodo de asentamiento tras retorno; limpia scroll/grabber y reanuda con scheduleWork; sin reintentos de menú al finalizar.

---

## v0.1.43 (Mayo 2026)

### Corregido
- **Return 2 en ofertas**: ya no dispara en lista de ofertas (offer_pay o filtrar = bloqueo); solo si coincide un disparador activo fuera de ofertas. Lógica simplificada sin fallback estructural.

### Actualizado
- Disparadores por defecto más específicos (sin read more / learn more genéricos).

---

## v0.1.42 (Mayo 2026)

### Corregido
- **Return 2 detección**: ya no bloquea por `offer_pay` fantasma en otras pantallas; contexto de lista = «filter offers by»/filtrar + Refresh; triggers no disparan si hay filtro de ofertas; quitado disparador «updates+schedule» (falso positivo en barra inferior); comprobación también en cada ciclo del grabber; más eventos de accesibilidad.

### Actualizado
- Disparadores por defecto con frases en español; log «Return probe» cada ~12 s con depuración activa.

---

## v0.1.41 (Mayo 2026)

### Añadido
- **Return 2 → Disparadores de pantalla**: lista editable con frases del macro por defecto; Contiene/Exacto e ignorar mayúsculas por disparador; varias líneas = AND.

### Actualizado
- **Ráfaga**: duración mín/máx hasta **3600 s (1 h)** — 240 s es válido.

---

## v0.1.40 (Mayo 2026)

### Actualizado
- **Ráfaga de clics**: duración aleatoria entre seg mín y máx (antes era un valor fijo).

---

## v0.1.39 (Mayo 2026)

### Actualizado
- **Ráfaga de clics**: evalúa ofertas visibles y acepta si cumplen criterio; sin scroll; el intervalo normal Smart/Basic queda suspendido y solo rige el ms de la ráfaga.

---

## v0.1.38 (Mayo 2026)

### Añadido
- **Return 2**: espera aleatoria entre pasos (seg mín–máx) y cooldown entre detecciones automáticas; durante el regreso el motor pausa clics/scroll y reanuda al terminar.
- **Ráfaga de clics**: intervalo aleatorio entre ráfagas (min), clic Refresh cada N ms durante X segundos; solo en pantalla de ofertas (misma regla que Clic Refresh).

### Actualizado
- Textos de ayuda: el botón ↩ de prueba es manual; el auto Return 2 solo con interruptor ON y pantallas detectadas fuera de lista.

---

## v0.1.37 (Mayo 2026)

### Añadido
- **Config → Botones flotantes**: sección con ON/OFF del bot, botón **pausa navegación** (⏸/▶) y botón **probar Return 2** (↩). Panel flotante apilado; arrastra cualquier botón para moverlo.
- **Pausa navegación**: pausa clics, scroll y Return 2 automático sin apagar el bot; ideal para revisar ventas o menús en Flex manualmente.
- **Probar Return 2**: ejecuta una vez el macro menú ≡ → Offers (misma lógica que la detección automática).

---

## v0.1.21 (Mayo 2026)

### Actualizado
- **Historial / log**: cada ciclo del grabber registra todas las ofertas visibles como VISTA; dedup por estación+$+$/h+duración+horario (ventana por defecto 90 min, hasta 4 h). RECHAZADA también deduplicada; al aceptar no se repite la misma fila.
- **Duración en log**: prioriza etiqueta `left_secondary_label` (1.5 hr, 90 min); ventana horaria con AM/PM correcto (evita 15 h cuando es 1.5 h / 10:30 AM–1:30 PM).
- **Return 2 offers**: detecta pantalla fuera de lista; abre menú ≡ (3 rayas) y pulsa Offers (macro ObserverTX-Return_2_Offers), no la pestaña Schedule.

### Corregido
- **Clic Refresh (Smart/Basic)**: el botón `primaryButton` sube al ancestro clickable; respeta Contiene/Exacto en pantalla y botón; overlay PichiX ya no bloquea el motor; Config → Clics aplica intervalo y Refresh al cambiar (sin esperar Guardar).
- **Flex en primer plano**: lógica OR (basta una señal: ventana activa Flex, cualquier ventana Flex, contenido/ids/ofertas, evento reciente); ya no bloquea si Android reporta otra app «activa» con Flex visible.
- **Config**: botón Guardar fijo abajo; solo el formulario hace scroll; secciones colapsables con chevron; scroll y foco estables al togglear/colapsar.

### Añadido
- **Mejor oferta en pantalla**: el grabber evalúa todas las filas visibles y toma la mejor (por defecto mayor $/h). Config → Clics: «Primera válida» o «Mejor de pantalla» + criterio ($/h, $ bloque, duración, empieza antes).
- **Historial estilo MakiX**: tarjetas de resumen, listado RecyclerView, export CSV/TXT, reset hoy, borrar todo, log diario detallado (`DayLogActivity`) con filtros por estado/estación e import/export.
- **Tarifas → Reglas**: al pulsar una regla, menú con **Duplicar regla** (copia justo debajo, con id nuevo y alias « (copia)»).

### Actualizado
- `OfferLogger` pasa a CSV (`pichix_offers_log.csv`) con estación, $/h, duración y trazas de tiempo.

---

## v0.1.20 (Mayo 2026)

### Corregido
- **Primer plano**: con «Solo con Flex en primer plano» activo, el motor (scroll, grabber, Refresh, return) se detiene al salir de Flex; cancela scroll pendiente.
- **Config**: switches y toggles conservan scroll y foco (no salta a otra sección al pulsar una opción).

### Actualizado
- Detección de Flex al frente: ventana activa, ventanas `isActive` y eventos `WINDOW_STATE_CHANGED` (por si Flex no notifica el foco).

---

## v0.1.19 (Mayo 2026)

### Añadido
- **Scroll automático** (Config → Clics): gestos aleatorios dentro de la zona del `list_recycler` (principio MakiX). Al llegar al final de la lista sube; al llegar arriba baja de nuevo.

### Actualizado
- Sustituido «Sin scroll» por «Scroll automático en la lista» (migración invierte el flag anterior).

---

## v0.1.18 (Mayo 2026)

### Actualizado
- **Refresh y scroll** salen de Tarifas → están en **Config → Clics del bot** (independientes de tarifa clásica o detallado).
- Dos opciones: «Pulsar Refresh cada ciclo» y «Sin scroll en la lista».
- El grabber (reglas + tomar bloques) funciona igual con cualquier modo de tarifa.

---

## v0.1.17 (Mayo 2026)

### Corregido
- **Solo refresh** ya no impide tomar bloques: tras Refresh el grabber evalúa ofertas (antes hacía `return` y nunca grababa).
- Por defecto «Solo refresh» desactivado (migración automática).
- Clic en oferta: sube desde `offer_pay` hasta `card` (ids `com.amazon.flex.rabbit`).
- Reglas detalladas: $/h usa `durationHours` de la tarjeta.
- Avisos en observador si reglas están en modo Clásico, lista vacía o ninguna oferta cumple.

---

## v0.1.16 (Mayo 2026)

### Añadido
- Config → **Aceptar automáticamente**: abre tarjeta de oferta, en Offer Details pulsa Schedule; si está off o modo simulación, solo abre detalle.
- Tras tomar/revisar un bloque el bot **pausa siempre** una vez.

### Corregido
- Clic en oferta usa `card` de la lista (no buscaba Schedule en la lista).

---

## v0.1.15 (Mayo 2026)

### Corregido
- Lista de ofertas Rabbit: estación desde `offer_station` (antes usaba `left_secondary_label` = duración).
- $/h con duración «3 hr 30 min» / «4 hr» desde `left_secondary_label`.
- Clic Refresh: prioriza `primaryButton` del footer Flex.
- Logcat: sin líneas duplicadas (archivo UI separado de Log.i).

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
