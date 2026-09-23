# Microbench enganches dashboard (tras assembleDebug)

En este entorno a veces no hay JDK. Con `JAVA_HOME` y el APK/clases compiladas,
mide el costo real de `PichixDashboard.onBotEvent` / `onFileLog` con el enlace activo
(`DashboardLink.configure(..., enabled=true)` o pantalla Conexión con PC).

Umbral: media < 20 µs en 10 000 llamadas (`System.nanoTime()`).

Ejemplo (Kotlin, p. ej. desde un test instrumentado o un botón debug temporal):

```kotlin
fun benchHooks(n: Int = 10_000): String {
    // warmup
    repeat(1_000) { PichixDashboard.onBotEvent("scan", "warmup") }
    val t0 = System.nanoTime()
    repeat(n) { PichixDashboard.onBotEvent("scan", "bench $it") }
    val avgUs = (System.nanoTime() - t0) / n / 1000.0
    return "onBotEvent avgUs=$avgUs"
}
```
