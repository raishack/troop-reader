# Arquitectura y contratos

Código en `app/src/main/java/es/gamingtroop/reader`:

| Área | Archivos |
|---|---|
| UI/entrada | MainActivity, UiSupport, Models |
| API/catálogo | Api, Catalog, Repository, Covers, Discovery |
| Persistencia/sync | Storage, BookmarkSync |
| Descarga/HTML local | Workers, OfflineHtml |
| Lector | ReaderScreen, ReaderGestures, PageTurnPolicy, PageCurl, ContinuousReader |
| Navegación/offline | ReaderNavigator, ReadingJourney, OfflineLibrary, OfflineIndex |
| EPUB/voz | EpubTools, EpubToolsUi, BookSpeaker, VoicePlayback |
| Funciones personales | PersonalLibrary, PersonalUi, ReadingBackup, ServerShelves |
| Actualización | AppUpdates, UpdateFeed, UpdateUi |
| E-ink | Eink, DisplayUi, BigmeRefresh |
| Diagnóstico/demo | Diagnostics, Demo |

## Invariantes

- Estado por servidor/cuenta, sesión cifrada mediante Android Keystore. No persistir contraseña ni registrar tokens.
- `AtomicFile` y no escribir estado idéntico. Borrar descargas no borra progreso/marcadores pendientes.
- Cancelación real: un worker viejo no puede reactivar cola ni sobrescribir intención nueva.
- HTML offline sin acceso externo ni puente JavaScript nativo; preservar saneamiento y límites de rutas.
- Mantener imagen anterior hasta que la siguiente esté lista; conservar zoom y encuadre del efecto Hoja. Bordes inmediatos, doble toque de zoom central.
- Lectura parcial no avanza sobre páginas ausentes ni finaliza contenido incompleto. Voz y pantalla no compiten por el progreso.
- Conflictos manuales por defecto. No hay garantía de compare-and-swap remota en Kavita.
- E-ink suspende preferencias normales sin borrarlas. Limpieza solo tras contenido estable, nunca bucle en reposo/segundo plano.

Funciones personales (favoritos, estadísticas, notas, ajustes por obra) no equivalen a sincronización con Kavita. Las colecciones del servidor son de consulta. Las copias de lectura excluyen libros, credenciales y activación nativa específica del dispositivo.
