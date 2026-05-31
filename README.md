# PichiX

Asistente Android para **Amazon Flex** (diseño y flujo inspirados en [MakiX](https://github.com/zilongsys/MakiX)).

## Repositorio

- GitHub: [zilongsys/PichiX](https://github.com/zilongsys/PichiX)
- Cuenta/organización: misma que MakiX (`zilongsys`)

## Versión

Formato `x.y.z` (como MakiX): el segmento **z** sube en cada compilación (`compile*Kotlin` / `assemble*`). Al llegar a **100**, sube **y** y **z** vuelve a **0**.

Valores en `app/version.properties`; no editar `versionName` en `build.gradle`.

## Compilar

```bash
./gradlew :app:assembleDebug
```

## Pestañas

| Pestaña | Función |
|---------|---------|
| Home | Estado del bot |
| Config | Paquete Flex, accesibilidad, notificaciones |
| Tarifas | Criterios rápidos o reglas detalladas |
| Alertas | Acciones por texto de notificación Flex |
| Historial | Registro de bloques |
| Simulador / Revisión | En desarrollo |

La pestaña **Filtros** no aplica: filtros y exclusiones van en **Tarifas**.
