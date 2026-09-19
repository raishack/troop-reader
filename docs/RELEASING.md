# Releases, firma y forks

El código publicado corresponde al cliente alpha21. Esta publicación documental **no cambia la APK ni el feed existentes**.

## Canal original

El actualizador está restringido a `https://claw.raishack.es/troop-reader/` y exige mismo paquete, firma, versión ascendente, tamaño y SHA-256. Publicar un GitHub Release **no** actualiza por sí solo el catálogo de la app. Ver [APP-UPDATES](APP-UPDATES.md).

1. Incrementar versionCode/versionName; conservar certificado para poder actualizar sin borrar datos.
2. Ejecutar tests/lint y validar instalación encima con completos, parciales, progreso pendiente, marcadores y ajustes.
3. Publicar APK inmutable, fuentes GPL, notas y hashes.
4. Publicar `latest.json` al final con `scripts/publish-update-feed.py`; verifica APK HTTPS y firma con la publicación anterior.
5. Desde versión anterior comprobar detección, descarga, confirmación del instalador y datos después de abrir.

No se incluye ninguna clave privada de firma. Las alphas existentes son builds de desarrollo; conservar su certificado es responsabilidad del mantenedor. No prometer que una build debug local sea instalable encima de la distribuida.

## Fork independiente

Usa applicationId y canal propios, y configura firma de release estable fuera del repositorio. Revisa `UpdatePolicy.FEED`, la validación de host/ruta en `UpdateFeed.kt`, las pruebas y `BASE` en el publicador. No basta con cambiar una URL y no se debe apuntar usuarios de un fork al canal original. La app debe poder rechazar paquetes de otra firma.

Los artefactos debug de CI son solo para desarrollo. No contienen claves de firma del mantenedor. Nunca sobrescribir la APK de una versión ya anunciada; corregir con versionCode superior.
