# PichiX — Historial de cambios

Formato de versión: `x.y.z` — `z` (PATCH) sube de **0 a 100** al entregar cambios; al llegar a 100, `y` (+1) y `z` = 0 (igual si `y` llega a 100 con `x` +1).

La versión vive en `app/version.properties`. Compilar **no** la modifica; usar `./gradlew :app:bumpVersion` (o editar el archivo) solo cuando haya cambios reales.

**Convención de entradas:** en cada versión se listan cambios como **Añadido**, **Actualizado**, **Corregido** o **Eliminado**.

---

## v0.2.11 (Junio 2026)

### Actualizado
- **Captura más rápida**: ráfaga espera la lista tras Refresh (no evalúa la pantalla anterior); el grabber no interfiere durante toma (detalle/Schedule); cache de view-ids y una sola lectura de lista por ciclo (menos recorridos Accessibility).

---

## v0.2.10 (Junio 2026)

### Añadido
- **Config → Comportamiento Flex**: «Seguir tras oferta perdida» (activo por defecto) — si no pudo tomar el bloque, el bot sigue intentando. «Pausar al tomar bloque» controla la pausa tras aceptar con éxito.

---

## v0.2.9 (Junio 2026)

### Añadido
- **Alertas**: secciones colapsables (General, Nueva regla, Reglas). Cada regla también se expande/colapsa.
- **Volumen de alerta**: slider 0–100 %, opción «Forzar volumen al alertar» (canal alarma + sube volumen del sistema) y vibración.

### Actualizado
- **Tema**: colores del SeekBar, toggles segmentados y botones alineados con tema claro/oscuro.

---

## v0.2.8 (Junio 2026)

### Actualizado
- **Alertas**: cada regla tiene su selector **Notificación / Mensaje Flex / Ambos** (nueva regla, edición y tarjeta de regla). Sección reorganizada: General, Nueva regla, Reglas configuradas. Estado de acceso a notificaciones y accesibilidad.

---

## v0.2.7 (Junio 2026)

### Corregido
- **Confirmación al tomar**: evalúa toast, banner y notificación por separado (evita falsos «unavailable»). Aceptada si Flex muestra scheduled en cualquiera de esas fuentes.
- **Alertas «offer scheduled»**: también se evalúan en mensajes flotantes in-app (accesibilidad), no solo en notificaciones del sistema.

### Añadido
- **Alertas → Origen**: cada regla indica si vigila **Notificación**, **Mensaje Flex** (flotante/banner) o **Ambos**.

---

## v0.2.6 (Junio 2026)

### Corregido
- **Tomar oferta**: tras pulsar Schedule el bot espera el mensaje de Flex. **Aceptada** solo si aparece scheduled/programado; **Perdida** si block unavailable. El motivo en el log es el texto que muestra Flex.

---

## v0.2.5 (Junio 2026)

### Corregido
- **Historial**: intento de tomar sin éxito → **Perdida** (💨 MISS): no abre tarjeta, detalle no carga, Schedule no encontrado, bloque ya no disponible, etc. **Rechazada** queda solo para descarte explícito; cancelación en Flex → **Cancelada**.

---

## v0.2.4 (Junio 2026)

### Corregido
- **Historial**: ofertas que no cumplen reglas/tarifas se registran como **Vista** (👁), no Rechazada. Rechazada queda para cuando se descarta la oferta (Cancel, detalle distinto con acción cancelar, fallo al tomar, etc.).

### Añadido
- **Reanalizar tras Refresh** (Config → Clics): tras pulsar Refresh, relee la pantalla con reintentos rápidos y evalúa ofertas en el mismo ciclo (activado por defecto).

---

## v0.2.3 (Junio 2026)

### Corregido
- **Tomar oferta sin espera**: eliminado el retardo fijo de 650 ms. Tras abrir la tarjeta, valida al instante; si Offer Details aún no cargó, reintenta automáticamente (hasta 50 ciclos, sin pausa configurable) y pulsa Schedule en cuanto la verificación es correcta.

---

## v0.2.2 (Junio 2026)

### Corregido
- **Espera antes de llamar**: el campo configurable ya no retrasa Schedule ni la toma de oferta; la oferta se procesa al abrir detalle (650 ms fijos internos). **Espera antes de llamar (ms)** vive en «Llamar al tomar bloque» y aplica a Schedule, notificación programada y alertas con «Llamar».

### Actualizado
- Respaldos con la clave antigua `flex_offer_take_detail_delay_ms` migran a `call_on_block_delay_ms`.

---

## v0.2.1 (Junio 2026)

### Añadido
- **Sonido al pulsar oferta**: repetitions, probar, detener y tono del sistema (como alertas).
- **Espera en detalle (ms)**: configurable antes de validar / Schedule tras abrir Offer Details.

### Actualizado
- Log de tarifas: «Ninguna regla de tarifa coincide» sin nombre de estación.
- Cabecera del bot: bandeja **HORAS** (suma de horas de bloques aceptados), no millas.

---

## v0.2.0 (Junio 2026)

### Añadido
- **Config → Si lista y detalle no coinciden** (Offer Details, pantalla de Schedule/Cancel): toggle exclusivo **Sonido y quedarse** vs **Cancelar oferta** (Cancel + confirmación); sonido de aviso configurable.

### Corregido
- **Rechazo falso en detalle**: si la lista ya validó la oferta y el pago coincide, se reutilizan datos de la lista cuando Flex no parsea bien el detalle.
- Log de rechazo en detalle incluye qué se leyó en pantalla (`Leído: pago=…, horario=…`).

---

## v0.1.100 (Junio 2026)

### Añadido
- **Verificación lista ↔ detalle** al tomar oferta: compara pago, estación y horario; rechaza si el bloque ya no está disponible o los datos no coinciden (clic erróneo / oferta que desapareció).
- **Sonido al pulsar oferta** en Config (junto a Aceptar automáticamente): opcional, con tono o archivo propio.
- **Log detallado**: motivo concreto al tomar o no tomar (criterio clásico, regla de tarifa, mismatch, Schedule, etc.).
- **Fecha del bloque** en historial y export TXT (formato corto, p. ej. «vie 15») junto al horario.

### Actualizado
- Las ofertas rechazadas en lista se registran siempre con el motivo específico (no solo «Criterio grabber»).

---

## v0.1.99 (Junio 2026)

### Actualizado
- **Paquete Flex (Config)**: valor por defecto `com.amazon.flex.rabbit, com.amazon.rabbit`; texto de ayuda e hint del campo aclaran el nombre real de la app y que se pueden listar varios paquetes separados por coma (notificaciones y detección de primer plano).

---

## v0.1.98 (Junio 2026)

### Corregido
- **Crash tras importar / al abrir Alertas**: `FlexAlertasFragment` usaba `settings` en `onCreateView` antes de inicializarlo; ahora se carga en `onCreate` (ViewPager puede crear la vista al recrear la actividad).

---

## v0.1.97 (Junio 2026)

### Corregido
- **Importar configuración**: cada clave se valida por separado; si un valor es inválido no se importa, se conserva el ajuste local previo y aparece en la pantalla de resultado como «omitido» con el motivo.
- **Crash tras importar**: `applyImportedConfig` con comprobación de fragmento adjunto, try/catch y `recreate()` del tema diferido; recarga de Config envuelta en try/catch; receptor de Home sin `requireContext()` si no está adjunto.

---

## v0.1.96 (Junio 2026)

### Corregido
- **Crash al abrir la app**: `SoundPickerHelper` se registraba en `onViewCreated` (demasiado tarde); ViewPager precarga Config y lanzaba `IllegalStateException`. Registro movido a `onCreate` en Config y Alertas.
- **Historial**: contenedor de ofertas vuelve a `LinearLayout` con estilo Card (FrameLayout directo con `orientation` rompía el layout).
- **Log bot / toggles**: inicialización del filtro tras crear el adapter; casts seguros en `SegmentedToggleStyle`.

---

## v0.1.95 (Junio 2026)

### Actualizado
- **Presentación por pestaña**: Home (estado → accesos → tema → respaldo al final); Config (menos secciones abiertas, nota banner Flex); Historial (KPIs desplazables + vacío); Alertas/Tarifas/Estadísticas/Log bot con toggles legibles en tema claro y oscuro (`SegmentedToggleStyle`).

### Corregido
- **Tema**: filtros del Log bot y rango de Estadísticas ya no usan fondo blanco fijo en modo oscuro; botones de texto con acento del tema; inputs de Alertas con colores de texto/hint correctos.

---

## v0.1.94 (Junio 2026)

### Corregido
- **Crash / estabilidad**: lectura del banner Flex envuelta en try/catch; servicio de accesibilidad no actúa antes de inicializar el lector; importación y recarga de Config sin `requireContext()` si el fragmento no está adjunto; toggles con `safeCheck` al rellenar el formulario; flag `suppressUiEvents` evita persist/markDirty durante recarga.
- **Foco en formularios**: `formFocusHost()` resuelve el ScrollView desde el control activo (Config, Home, etc.); restauración de foco bidireccional; secciones plegables conservan el campo seleccionado.

---

## v0.1.93 (Junio 2026)

### Corregido
- **Ráfaga + banner Flex**: detecta el mensaje in-app «You've tapped too many times…» (no es notificación). Frases corregidas (`tapped too many`, `too many times`, `for too long`). Se comprueba en cada tick de ráfaga, detiene la ráfaga y pausa el bot aunque «Pausar por bloqueo» esté desactivado. La ráfaga ya no depende de tener «Clic Refresh» activado.

---

## v0.1.92 (Junio 2026)

### Añadido
- **Llamar al tomar bloque**: en Config → EN FLEX activa la llamada, el número y cuándo marcar (al aceptar con Schedule o con la notificación «programado»). En Alertas, cada regla puede tener «Llamar al coincidir» para disparar solo con notificaciones concretas.

---

## v0.1.91 (Junio 2026)

### Corregido
- **Import config**: la importación ya no se pierde al recargar la pestaña Config — el estado guardado de los campos ya no sobrescribe los valores importados; tras importar se guarda en disco, se refresca Config en vivo (sin recrear toda la app salvo cambio de tema) y se sincronizan Home/servicios. Exportar guarda antes el formulario Config si está cargado.

---

## v0.1.90 (Junio 2026)

### Corregido
- **Import/export config (definitivo)**: exportación solo desde snapshot canónico de `AppSettings`; importación borra ajustes locales (preservando bot y flags de migración), decodifica JSON y restaura cada clave con el tipo correcto vía `restoreFromBackup` (Return, ráfaga, pausa, tarifas, etc.). La UI de Config ya no sobrescribe valores importados al recargar campos.

---

## v0.1.89 (Junio 2026)

### Corregido
- **Home — exportar/importar config**: el respaldo incluye todos los ajustes efectivos (snapshot de `AppSettings`), no solo claves ya escritas en disco; al importar se borran los ajustes locales (excepto estado del bot) antes de aplicar el archivo, con tipos correctos (enteros Return, ráfaga, pausa, etc.) y valores mostrados en el informe.
- **Config**: tras importar se recargan tiempos Return y el resto del formulario; al volver a la pestaña se refrescan los segundos de paso/cooldown Return.

---

## v0.1.88 (Junio 2026)

### Corregido
- **Home — importar config**: parseo robusto (JSON con BOM, valores planos o tipados), verificación de guardado y ventana con listado de ajustes importados y omitidos antes de recargar la app.

---

## v0.1.87 (Junio 2026)

### Corregido
- **Versión**: ya no sube en cada compilación; solo con `./gradlew :app:bumpVersion` o editando `version.properties` al entregar cambios.

---

## v0.1.83 (Mayo 2026)

### Añadido
- **Home**: exportar / importar toda la configuración (JSON) con confirmación; respaldo tipado de `pichix_settings`.

### Corregido
- **Config**: compilación al guardar con switch «Aceptar automáticamente» (smart cast).

---

## v0.1.82 (Mayo 2026)

### Añadido
- **Home — acceso rápido**: switch «Aceptar automáticamente» sincronizado con Config → EN FLEX (mismo patrón que Return 2).

---

## v0.1.81 (Mayo 2026)

### Corregido
- **Estadísticas de ofertas**: el scroll ya no salta arriba al ordenar tablas, cambiar filtro de periodo o pulsar Analizar; se conserva posición y foco en el control usado (misma lógica que Config/Tarifas).

---

## v0.1.78 (Mayo 2026)

### Corregido
- **Log del bot — ráfaga**: todos los eventos durante una sesión se guardan con `burstGroupId`; migración automática de bloques `@@BURST@@` antiguos a líneas individuales en archivo.
- **Estadísticas — estaciones**: código completo entre paréntesis (sin paréntesis), incluyendo textos con paréntesis internos.

### Actualizado
- **Estadísticas**: tablas a ancho completo con bordes, filas alternas (zebra), cabeceras en negrita con títulos legibles; resumen superior con etiquetas en negrita y más separación.

---

## v0.1.77 (Mayo 2026)

### Corregido
- **Log del bot — ráfaga**: cada evento se guarda por separado (como el resto); la UI solo agrupa visualmente bajo una línea contenedora expandible/contraíble.

### Actualizado
- **Estadísticas**: estaciones por código corto; tablas ordenables por columna (toque en cabecera) en lugar de listado de texto.

---

## v0.1.76 (Mayo 2026)

### Añadido
- **Log del día**: filtro por $/h con precios redondeados (20.50 → $20/h).
- **Estadísticas**: botones Exportar / Hoy / Todo con confirmación; filtro de periodo resaltado; leyendas en negrita, máximos en negrita y mayor máximo en verde; bloque «Ofertas por $/h (redondeado)».
- **Log del bot**: filtro por tipo de evento; paginación «Cargar más»; corrección de agrupación de ráfagas.

### Corregido
- **Log del bot — ráfagas**: ya no mezcla eventos ajenos ni deja la sesión abierta tras «Ráfaga finalizada».

---

## v0.1.75 (Mayo 2026)

### Añadido
- **Log del bot — borrar Hoy / Todo**: botones con diálogo de confirmación (como Historial); texto descriptivo en una sola línea.

---

## v0.1.73 (Mayo 2026)

### Añadido
- **Log del bot — scroll del listado**: cada scroll automático en la lista de ofertas se registra (categoría SCROLL); barra de desplazamiento en el log; botones ↑ Inicio / ↓ Reciente; auto-scroll al final solo si no has subido manualmente.

---

## v0.1.72 (Mayo 2026)

### Añadido
- **Log del bot — ráfaga agrupada**: mientras dura el modo ráfaga se muestra una sola línea resumen; toque para desplegar el detalle; doble toque en cualquier línea de la ráfaga para volver a comprimir.

---

## v0.1.71 (Mayo 2026)

### Actualizado
- **Log del bot**: encolado async sin bloquear el motor; buffer RAM (~200 eventos / ~280 KB) con volcado a `pichix_bot_events.log` al llenarse; UI con debounce y lectura en hilo de fondo.

---

## v0.1.68 (Mayo 2026)

### Corregido
- **Clic Refresh / Offers**: revertido el bucle de clics a `rootInActiveWindow` (v0.1.62); lectura multi-ventana solo para pausa por banner; eliminado bloqueo del grabber por `handleBlockingScreen` y campos extra de accesibilidad que falseaban la coincidencia.

---

## v0.1.67 (Mayo 2026)

### Corregido
- **Clic Refresh / pantalla Offers**: regresión v0.1.64 — lectura une ventana activa + ventanas Flex; motor usa siempre Contiene sin mayúsculas; fallback por ids de lista de ofertas cuando el tab no aparece en el árbol.

---

## v0.1.66 (Mayo 2026)

### Actualizado
- **Estadísticas de ofertas**: botones con estilo legible; selector Todo / Un día / Rango con calendario Material; filtro por fecha en el analizador.

---

## v0.1.64 (Mayo 2026)

### Añadido
- **Log del bot** (pestaña lateral): registro en vivo de activación, pausas, intentos de oferta, cambios de pantalla, ráfaga, Return y clics.
- **Estadísticas de ofertas** (pestaña lateral): botón Actualizar con ofertas por día, mejores franjas horarias, días de la semana y estaciones top (desde CSV).

### Corregido
- **Demasiados clics / captcha**: aviso flotante dibujado sobre Flex (snackbar/banner), no ventana modal — lectura desde todas las ventanas Flex + hint/stateDescription; reacción más rápida a cambios de texto en pantalla.

---

## v0.1.62 (Mayo 2026)

### Actualizado
- **Config — reorganización completa**: orden por flujo del usuario (permisos → Flex → control → ritmo → en Flex → pantalla → pausas → diagnóstico); banner de permisos; hints colapsables unificados en Overlay, Pause y Log; sección Clics dividida en Ritmo y Pantalla/ofertas; títulos en español; footer con aviso de guardado.

---

## v0.1.59 (Mayo 2026)

### Corregido
- **Config — foco y scroll**: retención unificada en hints colapsables, nota del motor, disparadores Return 2, toggles de visibilidad (Basic/Smart, ráfaga, offer rank) y al volver del selector de sonidos; eliminado doble `runRetainingScrollAndFocus` en motor de clics; RecyclerView y filas de disparador ya no roban foco.

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
