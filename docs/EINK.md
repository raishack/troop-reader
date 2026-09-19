# Pantallas de tinta electrónica · alpha21

## Activar

**Ajustes → Tipo de pantalla**, también en Opciones de lectura y en el acceso sin cuenta:

- **Pantalla normal:** conserva el comportamiento anterior.
- **Tinta electrónica B/N:** interfaz blanca/negra e imágenes en escala de grises, sin convertir fotografías a blanco/negro puro ni eliminar sus tonos.
- **Tinta electrónica color:** texto negro y acentos oscuros sobre blanco, conservando los colores del contenido. Los estados usan también texto, marcas y bordes; nunca solo color.

Es una preferencia **del dispositivo**, no de una cuenta o una obra. No viaja en las copias de datos de lectura. Una actualización conserva el modo elegido y no activa destellos en un móvil por defecto.

## Comportamiento de lectura e interfaz

- Hoja y el modo vertical continuo de imágenes quedan temporalmente suspendidos; el modo continuo se presenta como página completa. Las preferencias guardadas no se modifican y reaparecen al volver a Pantalla normal.
- Se mantienen el zoom, la dirección de lectura, la doble página, la pinza, los bordes y las teclas opcionales de volumen.
- En EPUB, avanzar/retroceder primero desplaza una pantalla de texto, con un solapamiento del 10 %, sin animación; solo llega a la sección siguiente al alcanzar el final. Retroceder desde el comienzo abre el final de la sección anterior. El índice sigue saltando a secciones/párrafos concretos.
- Sin indicadores giratorios ni ondulaciones de pulsación. Interruptores y selecciones estáticos, con objetivos de 48 dp y marcas explícitas. Menús/diálogos opacos a pantalla completa, sin entrada deslizante ni fondo oscurecido translúcido.
- Texto de lectura mínimo de 18 px CSS y etiquetas del tema de al menos 14 sp; respeta el tamaño de letra de Android. Enlaces subrayados, anotaciones identificables por subrayado/borde aun en B/N. Los originales descargados nunca se alteran.
- Sin desplazamiento por inercia en las listas de la app. Al mover una imagen ampliada o texto, se limpia al finalizar el gesto, no continuamente durante la interacción.
- Los controles del lector solo se ocultan con otro toque o al navegar; no desaparecen por temporizador mientras los miras.
- Se respeta el reposo de pantalla configurado en Android. El lector no mantiene la pantalla encendida indefinidamente en este modo.
- Guardado EPUB por evento al terminar el desplazamiento, salir o pausar; sin consulta periódica del párrafo mientras el lector e-ink está quieto.

## Refresco: límites que importan

**Repintar una vista Android NO equivale a ordenar un ciclo físico completo del panel.** Los modos de onda y sus tiempos pertenecen al controlador/firmware. Android no ofrece una orden pública universal de refresco e-ink.

Con **Limpiar al navegar** activado (predeterminado dentro de los perfiles e-ink):

1. Se solicita limpieza después de cada navegación, página lista, apertura/cierre de menú, vuelta a primer plano o gesto terminado. Los eventos de carga de carátulas se agrupan para evitar una ráfaga por cada imagen. Se espera a un dibujo estable (aprox. 180–220 ms); no se refresca cada fotograma.
2. **BOOX compatible:** se intenta la extensión `View.repaintEverything(int)` con modo GC16 completo, leyendo los valores del propio firmware (`android.onyx.ViewUpdateHelper`). No se adivinan constantes ni se activan A2/DU/modos rápidos. Se comprueba el fabricante y la disponibilidad de clases/métodos. No se usa root, se cambian ajustes globales ni se eluden restricciones de Android. Si el acceso falla, se pasa a compatibilidad.
3. **Bigme (experimental y opcional):** detecta las extensiones públicas `xrz.framework.manager.XrzEinkManager.forceGlobalRefresh(int)` y el campo `EinkRefreshMode.EINK_CLEAN_MODE`. Lee el modo del firmware, sin números copiados de otro dispositivo. La detección no solicita refrescos. Si la firma y el campo coinciden, aparece **Refresco Bigme experimental**, desactivado inicialmente. Al activarlo, se pide limpieza global después de navegar. Un fallo desactiva la opción y usa el repintado compatible; un cambio en la identificación del firmware requiere activarla de nuevo. No usa root ni cambia modos globales del fabricante. La evidencia procede de otro Bigme: **no está validado en B751C S / Android 14 / 1.7.0**. Consulta [el informe Bigme](BIGME-B751C-S.md).
4. **Resto de dispositivos / API ausente o no activada:** se repinta toda la ventana de la app con negro, blanco y contenido (120 ms por fase en B/N; 180 ms en color). Es una ayuda visual de compatibilidad, **no una garantía de refresco físico ni de eliminación de ghosting**. No cambia el zoom ni el progreso. No controla el teclado, diálogos del sistema o apps externas.

El botón **Limpiar pantalla ahora** usa el mismo mecanismo. Se puede desactivar la limpieza y conservar el tema. La limpieza tiene un destello intencional y añade espera: es distinta del fallo involuntario de pantalla negra corregido en alpha12. No hay ciclos repetidos en reposo ni en segundo plano; se cancelan al pausar, cambiar de modo o cerrar la ventana.

Una llamada aceptada por el firmware no confirma qué onda aplicó realmente. Los emuladores solo verifican interfaz, solicitudes y lógica; **no pueden medir ghosting ni validar refresco físico**. Se conoce el lector objetivo Bigme B751C, S Color, Android 14, sistema 1.7.0; sigue pendiente una comprobación en ese panel real. No se afirma compatibilidad física certificada con BOOX, Bigme, Meebook, PocketBook ni otros fabricantes.

## Ajustes del lector físico

- Para lectura estable y cómic, usar **Normal/Calidad** y configurar refresco completo en cada cambio de página si el fabricante lo ofrece. A2/Rápido/X favorecen movimiento pero sacrifican detalle y pueden dejar más residuos.
- Revisar la optimización por app del fabricante: puede sustituir la política de refresco, realzar negro, filtrar colores o forzar animaciones. No duplicar agresivamente la limpieza del sistema y de la app si el resultado es demasiado lento.
- El color no es un indicador fiable por sí solo: resolución, filtro de color, iluminación frontal y contraste varían entre paneles. No aplicar umbrales binarios a mapas/ilustraciones ni filtros de saturación automáticos. El tema color no pretende calibrar el panel.
- El tiempo correcto de refresco depende del controlador, tecnología de pantalla, temperatura y firmware. Los tiempos de compatibilidad de la app son iniciales, no parámetros eléctricos del panel.
- Solo lectores que ejecuten apps **Android 8+**. Kindle y Kobo con sus sistemas habituales no pueden instalar esta APK.
- No se modifica luz frontal, temperatura de color, brillo global, permisos del sistema ni gestión de batería del dispositivo.

## Referencias técnicas consultadas (19-09-2026)

- [BOOX: guía de desarrollo e-ink](https://github.com/onyx-intl/OnyxAndroidDemo/blob/master/doc/Eink-Develop-Guide.md): contraste, evitar transparencias y animaciones, paginación y tamaños de controles.
- [BOOX: actualización de pantalla](https://github.com/onyx-intl/OnyxAndroidDemo/blob/master/doc/EPD-Screen-Update.md): GU parcial, REGAL parcial y GC completo.
- [BOOX: modos de actualización](https://github.com/onyx-intl/OnyxAndroidDemo/blob/master/doc/EPD-Update-Mode.md): compromiso entre velocidad, detalle y ghosting.
- [BOOX: EpdController](https://github.com/onyx-intl/OnyxAndroidDemo/blob/master/doc/EpdController.md).
- Firmas/constantes contrastadas con el SDK oficial `onyxsdk-device:1.1.11`, obtenido por HTTPS de repo.boox.com. No se incluye ese binario ni sus dependencias antiguas en la app: el adaptador pequeño es independiente y falla de forma controlada si el firmware no lo expone.

- [Investigación comunitaria xrz / Bigme](https://github.com/imedwei/inksdk/blob/3373aa07506c0870ea229b9008cbb5fdbe8d706b/docs/bigme-sdk-reverse-engineered.md): firmas públicas y modos observados en HiBreak Plus, no certificación de B751C S. No se importa su SDK.
