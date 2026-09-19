# Publicación y actualizaciones de Troop Reader

Desde alpha15 la app consulta `https://claw.raishack.es/troop-reader/latest.json`.
El origen es independiente de Kavita y no recibe sus credenciales.

## En el móvil

- Comprobación al abrir o volver a primer plano, como máximo una cada seis horas;
  búsqueda manual inmediata en Ajustes → Actualizaciones de la app.
- WorkManager comprueba aproximadamente cada doce horas en segundo plano. Android
  puede aplazarlo por batería, falta de red o cierre forzado.
- Descarga automática activada inicialmente, solo en red no medida (normalmente
  Wi-Fi). Se puede desactivar sin perder la detección. La descarga manual pide
  confirmación del uso de la conexión actual, incluidos datos móviles.
- Aviso en la barra superior y notificación si Android lo permite. Ningún diálogo
  automático tapa el lector ni inicia una instalación en segundo plano.
- Android requiere autorizar Troop Reader como origen de instalación la primera
  vez y confirmar cada actualización. No es instalación silenciosa ni requiere root.
- Tras interrupciones se vuelve a descargar la APK; nunca se instala el parcial.
  El contenido de lectura usa otro directorio y no se toca.
- SHA-256 y tamaño, paquete, versión, Android mínimo y certificado coincidente se
  comprueban antes de marcarla lista y otra vez antes de abrir el instalador.
- FileProvider privado comparte únicamente las APK del directorio de actualizaciones,
  con permiso temporal de lectura. Los archivos de cuentas no son compartibles.

## Para publicar cada versión futura

1. Incrementar **versionCode** y versionName en `app/build.gradle.kts`; conservar
   el identificador de paquete y la clave de firma de las alphas anteriores.
2. Compilar, validar y entregar APK, fuente GPL, notas e informe como hasta ahora.
3. Publicar la APK con un nombre nuevo e inmutable bajo `/troop-reader/`.
4. **Publicar `latest.json` al final**, mediante `scripts/publish-update-feed.py`.
   El script extrae la versión y Android mínimo del APK, verifica su firma, exige
   versión ascendente y misma firma que la publicación anterior, calcula tamaño
   y hash, comprueba la descarga HTTPS y sustituye el catálogo atómicamente.
5. Conservar las APK anteriores. No anunciar una versión sin APK ya disponible;
   no reutilizar un versionCode ni reemplazar bytes de una versión publicada.

Esquema 1: `schemaVersion`, `packageName`, `versionCode`, `versionName`, `minSdk`,
`apkUrl`, `sizeBytes`, `sha256`, `notes`. Catálogo máximo 32 KiB; APK máximo 200 MiB.
El cliente rechaza HTTP, dominios/puertos distintos, parámetros de URL y
redirecciones. TLS usa la validación normal de Android; no se omiten certificados.

Para alpha14 o anteriores hace falta instalar alpha15 manualmente una vez.
No desinstalar ni borrar datos. Las versiones siguientes llegan por este flujo.

## Pruebas

`ReaderTestRunner` desactiva solamente las consultas automáticas durante pruebas
instrumentadas: nunca contactan el feed de producción. Los tests usan MockWebServer
y una APK diminuta generada localmente con la misma clave debug, solo en `androidTest/assets`.
La APK de ensayo no forma parte de la app distribuida. Para regenerarla, compilar
un proyecto Android mínimo con el mismo applicationId, versionCode 100000,
versionName `0.1.0-fixture100000`, minSdk 26 y un application `android:hasCode="false"`.
Usar únicamente el emulador desechable, nunca un móvil con lecturas reales.

En este repositorio no se versiona la APK de prueba. Ejecutar `python3 scripts/prepare-update-fixture.py` antes de los tests instrumentados. Ver [BUILD](BUILD.md).
