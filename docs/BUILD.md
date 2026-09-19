# Compilación y pruebas

## Requisitos

JDK 17, Android SDK Platform 35, Build Tools 35.0.0, Platform Tools y acceso a Google Maven/Maven Central/Gradle. El wrapper usa Gradle 8.11.1; AGP 8.9.1; Kotlin 2.1.10. El proyecto puede abrirse en Android Studio compatible con AGP 8.9.1.

Configura `JAVA_HOME` a tu JDK y `ANDROID_HOME` a tu SDK, o crea `local.properties` con `sdk.dir=/ruta/al/sdk` (en Windows usar barras `/`). No se distribuyen SDK ni claves.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

En Windows usa `gradlew.bat`. Resultado: `app/build/outputs/apk/debug/app-debug.apk`. Es una compilación de desarrollo: la firma debug local NO es la de las APK del mantenedor y no permite actualizar encima de ellas. Para producción configura firma propia de release sin publicar claves.

## Pruebas instrumentadas

Usa exclusivamente emulador/dispositivo desechable, sin cuentas ni descargas personales. API 35 es la referencia utilizada. Instala una voz TTS offline para pruebas de audio; concede los permisos solicitados por Android. Las pruebas usan fixtures y MockWebServer, no bibliotecas privadas. Algunas comprueban orientación, escala de fuente, animaciones y audio: no interactúes con el emulador durante su ejecución.

El actualizador verifica la firma de la APK de ensayo. Debe generarse con la misma clave debug que la app de esta máquina, **no** distribuir la clave de quien publicó la app:

```sh
python3 scripts/prepare-update-fixture.py
./gradlew connectedDebugAndroidTest
```

El generador crea una APK sintética sin código, paquete de la app y versión 100000; la copia a `androidTest/assets`. Nunca instalarla en un equipo personal. No se incluye en la APK principal ni en Git.

Informes: `app/build/reports/tests`, `app/build/reports/lint-results-debug.html` y `app/build/outputs/androidTest-results`. Para un cambio de documentación basta verificar enlaces; los cambios en descarga/sync/lector necesitan sus pruebas específicas y una comprobación de actualización conservando datos.
