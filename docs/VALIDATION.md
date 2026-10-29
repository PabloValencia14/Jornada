# Validación de Jornada 1.0.0

29 de octubre de 2026.

## Compilación

- JDK 17, Gradle 8.11.1, Android Gradle Plugin 8.9.1, Kotlin 2.0.21, SDK 35.
- `testDebugUnitTest assembleDebug assembleDebugAndroidTest assembleRelease lintRelease`: correctos.
- 14 pruebas unitarias, 0 fallos. Se cubren vigencias, jornada partida, pausas, turno nocturno, cambio de hora, tolerancia sin alterar marcas, días exentos, justificaciones, solapamientos, anulaciones, tramos sin fichajes, preservación de segundos y Excel.
- Lint release: 0 errores, 6 advertencias. Se refieren a versiones más recientes de SDK/bibliotecas, configuración adicional de backups para Android anteriores a 12 y la sugerencia de usar la extensión KTX para transacciones. La copia está desactivada con `allowBackup=false` y reglas explícitas para Android 12+; las transacciones se gestionan con `try/finally`.
- Firma del APK verificada con `apksigner`. La clave privada permanece fuera del repositorio.
- APK: 1.227.632 bytes, con reducción de código y recursos activada.
- Inspección del manifiesto del APK: no solicita Internet, ubicación ni almacenamiento general. Solo contiene el permiso interno de receptores no exportados de AndroidX.

## Android real

Xiaomi Pad 7, modelo 2410CRP4CG, Android API 36. Todas las órdenes se dirigieron explícitamente a esa tablet.

La prueba de integración utiliza únicamente la variante `dev.jornada.debug`; no inserta datos de demostración en la versión publicada. Comprobaciones correctas:

- Persistencia SQLite después de cerrar y abrir la base de datos.
- Historial con el antes y el después de una corrección, y su representación legible.
- Copia JSON válida y rechazo de formato incompatible sin modificar datos.
- Rechazo de dos entradas abiertas.
- Restauración transaccional y conservación automática de una copia previa.

Desde la interfaz se probaron entrada, salida, persistencia al cerrar y volver a abrir la aplicación y guardado del Excel con el selector de documentos Android. El archivo obtenido se abrió con `openpyxl` fuera de Android: cinco hojas y los dos fichajes de prueba correctos. La biblioteca no se incluye en la app.

La APK firmada de distribución quedó instalada en la tablet con su base de datos vacía.

## Revisión visual

Capturas nativas de tablet a su tamaño original y de una ventana de 1080×2400 a 420 dpi, simulando anchura de teléfono sobre la misma tablet. Se revisaron Hoy, Calendario, Horario e Informe; tema claro, oscuro y tamaño de letra 1,3. Se restauraron resolución, densidad, rotación, tema y tamaño de letra previos.

La revisión se realizó en la sesión principal, sin revisor independiente. No se ejecutó detector web sobre código Android. Se utilizaron los criterios nativos de Impeccable y Material 3. Resultado visual: `ship` para las pantallas y estados capturados.

## Límites de la evidencia

No se ha probado un teléfono físico ni una matriz de fabricantes/versiones Android. La comprobación móvil utiliza dimensiones de teléfono en la tablet; no es un emulador de teléfono independiente.

Una lectura puntual de la APK de distribución en primer plano mostró aproximadamente 108 MiB PSS en la tablet. Es una medida de esa sesión, no una garantía de RAM, consumo de batería o rendimiento sostenido. No se ha medido autonomía ni comportamiento durante semanas de uso.

El reloj del dispositivo proporciona las marcas. El historial es una ayuda de revisión, no una garantía contra la manipulación de quien controla el dispositivo.
