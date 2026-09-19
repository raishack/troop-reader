# Implementación con Kavita

## Instalación normal

1. Instala una versión oficial de [Kavita](https://github.com/Kareadita/Kavita/releases) y crea bibliotecas con libros que puedas utilizar.
2. Publica mediante HTTPS con certificado válido. El proxy debe conservar cabeceras de autenticación, rutas y respuestas binarias. No desactives la validación TLS.
3. Crea un usuario normal con acceso a las bibliotecas y permiso **Download**. No hace falta dar permisos de administrador a Troop Reader.
4. Inicia sesión en la app con la URL HTTPS. Comprueba portada, una descarga manga y un EPUB con imágenes/fuentes, lectura offline y sincronización usando datos de prueba.

La app usa sesión por cabecera y renovación de sesión; no requiere introducir API keys ni publicarlas en URLs. No incluye un servidor Kavita ni un conector Yamtrack.

## Compatibilidad comprobada

| Servidor | Situación |
|---|---|
| 0.9.0.2 | La instalación ensayada necesitó hacer opcionales los parámetros `apiKey` de cinco rutas de imágenes. Herramienta histórica en `server-compat/0.9.0.2`. |
| 0.9.1.4 | Carátulas corregidas upstream. La instalación ensayada todavía necesitó el ajuste de `ReaderController.GetImage(apiKey)`, solo un parámetro. |
| Otras versiones | No certificadas: ejecutar pruebas antes de actualizar o modificar. No reaplicar una DLL antigua. |

Si una petición autenticada a `api/Reader/image` da 400 indicando `apiKey` requerido, consulta [server-compat](../server-compat/README.md). **No es un parche obligatorio para todo Kavita:** confirma primero versión, respuesta y causa. Un 401/403, falta de permisos o archivo ausente es otro problema.

El ajuste modifica metadatos de nulabilidad del parámetro, no elimina autenticación ni cambia las instrucciones del controlador. Se ensaya en copia; las peticiones anónimas deben seguir denegadas. No uses `apiKey` de relleno ni desactives autorización global.

## EPUB y conflictos

Desde alpha18 el cliente resuelve ciertas rutas alternativas de fuentes EPUB dentro del mismo libro. Si una descarga falla, conserva su cola y usa Reintentar; el detalle técnico se muestra en la ficha. No borrar progreso para solucionar un 400.

Conflictos manuales por defecto. «Dar prioridad a este móvil» solo envía cambios locales pendientes. Las restauraciones requieren comparación con el servidor, no aplicar una copia antigua ciegamente.

## Yamtrack

Es independiente de la app. La instalación validada usa un conector externo con una lista explícita `allowed_kavita_versions`. Ese campo pertenece al conector, **no** es un ajuste estándar que deba añadirse a cualquier Yamtrack. Ver [procedimiento de actualización](KAVITA-UPGRADE.md).
