# Troop Reader

Cliente Android independiente para **Kavita**, centrado en lectura offline de EPUB, manga, cómic y PDF. Interfaz en español; Kotlin y Jetpack Compose. **Versión alpha: 0.1.0-alpha21**, Android 8.0+ (API 26). GPL-3.0. No es una aplicación oficial de Kavita.

## Instalar y conectar

1. Descarga la APK de [Releases](https://github.com/raishack/troop-reader/releases) o del [canal del mantenedor](https://claw.raishack.es/troop-reader/).
2. Instálala encima de una versión anterior **sin desinstalar ni borrar datos**. Android solicita confirmar la instalación.
3. Introduce la URL HTTPS de **tu** Kavita y una cuenta con acceso a las bibliotecas y permiso de descarga. El formulario propone el servidor del proyecto; cámbialo por el tuyo. No incluye acceso ni cuentas compartidas.
4. Descarga una obra o usa «Ver demostración sin cuenta». Comprueba lectura offline, reanudación y sincronización.
5. Lee la [guía de Kavita](docs/KAVITA.md): ciertas versiones requieren un ajuste de servidor para servir imágenes con autenticación por cabecera.

Las futuras versiones del canal original se detectan en Ajustes → Actualizaciones. La APK sigue requiriendo confirmación de Android; no se instala silenciosamente.

## Funciones

- Biblioteca por obra y tomos, carátulas offline, descarga por selección y cola con pausa/reanudación.
- Lectura mientras descarga, sin saltar sobre contenido todavía ausente.
- Bordes por defecto, arrastre opcional, pinza y doble toque central; zoom conservado, RTL, doble página y efecto Hoja opcional.
- Vertical continuo, selector de tomos/páginas, miniaturas, índice EPUB y salto directo.
- Progreso y marcadores con sincronización y conflictos manuales por defecto; prioridad local opcional.
- Favoritos, colecciones personales, seguimiento de novedades, descarga anticipada optativa y ajustes por obra.
- EPUB: búsqueda local, subrayados/notas exportables, diccionario mediante app compatible y voz offline en segundo plano.
- Botones de volumen opcionales, orientación, estadísticas, copia/restauración y diagnóstico exportable.
- Listas/colecciones de Kavita de consulta con caché. No edición remota.
- [Tinta electrónica B/N y color](docs/EINK.md); integración [Bigme experimental](docs/BIGME-B751C-S.md).

## Documentación

- [Compilar y ejecutar pruebas](docs/BUILD.md)
- [Configurar Kavita y resolver incompatibilidades](docs/KAVITA.md)
- [Actualizar Kavita con ensayo, Yamtrack y reversión](docs/KAVITA-UPGRADE.md)
- [Arquitectura, datos y contratos](docs/ARCHITECTURE.md)
- [Publicar actualizaciones / forks y firmas](docs/RELEASING.md)
- [Protocolo del actualizador](docs/APP-UPDATES.md)
- [Validación y limitaciones](docs/VALIDATION.md)
- [Contribuir](CONTRIBUTING.md) · [Seguridad](SECURITY.md) · [Licencias](NOTICE.md)

## Límites importantes

Kavita no ofrece escritura condicional para todos los datos de lectura: la detección de conflictos reduce, pero no elimina, las carreras entre clientes. Borrar descargas conserva progreso y marcadores. Las copias de lectura **no incluyen libros ni contraseñas**.

La voz depende de un motor/voz instalado. El diccionario requiere una app compatible con Procesar texto. No hay OCR, búsqueda de texto en manga/PDF, edición de listas remotas ni soporte completo SSO/2FA.

Un emulador no valida el ghosting de un panel e-ink. El refresco Bigme es opt-in y aún no está confirmado físicamente en B751C S / Android 14 / firmware 1.7.0. El repintado negro/blanco no garantiza un refresco físico.

## Desarrollo rápido

JDK 17, Android SDK 35 y Build Tools 35.0.0. Configura `ANDROID_HOME` o `local.properties` (no versionarlo).

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

No uses dispositivos con lecturas reales para los tests instrumentados. Consulta [BUILD](docs/BUILD.md) antes de ejecutarlos.
