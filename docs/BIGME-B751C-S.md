# Bigme B751C · S Color · Android 14 · sistema 1.7.0

## Identificación

Datos leídos de Acerca del dispositivo: modelo **B751C**, código **S Color**, Android **14**, sistema **1.7.0**. La variante comercial coincide con [B751C S del fabricante](https://store.bigme.vip/products/bigme-b751c-s-upgraded-7inch-color-ereder-with-android-14-os). La referencia inicial B7 queda corregida. No se conserva el número de serie.

La ficha oficial indica 7 pulgadas, 300 ppp en blanco/negro y 150 ppp en color. El tema recomendado es **Tinta electrónica color**; no se fuerza mayor saturación, blanco/negro binario ni un modo rápido. El texto negro, las líneas firmes y los estados con etiquetas se conservan en ambas variantes del tema.

## Alpha21: soporte condicionado al firmware, no certificado

- La app comprueba las clases públicas del framework **xrz**, sin asumir que el nombre comercial o la versión Android bastan. No depende de que Build.MANUFACTURER diga Bigme: algunos modelos reportan alps.
- Contrato exigido: clase pública `xrz.framework.manager.XrzEinkManager`, método público estático `void forceGlobalRefresh(int)` y campo público estático final `int EINK_CLEAN_MODE` de `xrz.framework.manager.EinkRefreshMode`.
- No se adivina un modo numérico, no se recurre a GC16 de otro controlador si falta CLEAN, ni se habilitan APIs privadas. No hay comandos root, llamadas shell, permisos nuevos o cambios persistentes de modos del sistema.
- **Refresco Bigme experimental** aparece solo si se encuentra ese contrato. Está desactivado inicialmente; activarlo autoriza solicitudes de limpieza durante la navegación. La detección por sí sola nunca llama a forceGlobalRefresh.
- Si una llamada produce un error capturable, se desactiva la opción de forma persistente y vuelve a compatibilidad, sin reintentos en bucle. No se puede capturar desde Java un fallo fatal del propio código nativo del fabricante; por eso no se activa automáticamente basándose en la marca.
- La opción es local al dispositivo y no se incluye en copias de lectura. Al cambiar el identificador de firmware/API de Android deja de estar activa. No requiere volver a iniciar sesión ni altera libros, zoom o progreso.
- **Compatibilidad y refresco** muestra el estado; **Mi espacio → Diagnóstico** exporta solo estado, perfil, activación y contador de solicitudes aceptadas, no modelo, serial, identificador de compilación ni cuenta.

## Cómo comprobarlo en el lector

1. Actualizar encima a alpha21. En Ajustes → Tipo de pantalla elegir **Tinta electrónica color** y **Limpiar al navegar**.
2. Si aparece **Refresco Bigme experimental**, activarlo y usar **Limpiar pantalla ahora**. Comparar páginas con texto e ilustraciones y abrir/cerrar menús. Desactivarlo si no mejora o no responde correctamente.
3. Si no aparece, abrir **Compatibilidad y refresco** para ver si la API falta o está bloqueada. No se anuncia refresco nativo en ese caso; se mantiene el repintado compatible. Puede exportarse el diagnóstico sin datos personales.
4. Revisar los modos de calidad y refresco completo por aplicación del propio Bigme, si están disponibles. Evitar combinar un modo rápido del sistema con la expectativa de imagen limpia; no se conocen los nombres exactos del menú del firmware 1.7.0.

## Evidencia y límites

Fuente de las firmas: [investigación comunitaria sobre HiBreak Plus, revisión fijada](https://github.com/imedwei/inksdk/blob/3373aa07506c0870ea229b9008cbb5fdbe8d706b/docs/bigme-sdk-reverse-engineered.md). No es un SDK oficial ni una prueba en B751C S. Que funcione desde UID shell no demuestra los permisos de una aplicación normal. La app comprueba el contrato y captura rechazos en su propio proceso, sin saltarse controles.

Los tests automatizados usan un controlador simulado y un emulador sin API Bigme. Verifican exclusión por defecto, llamada simbólica, reversión, interfaz y conservación de datos, **no limpieza física ni ausencia de ghosting**. Aún no hay resultado de un panel B751C S real. Tampoco se deduce el modo de onda ejecutado a partir de una llamada void aceptada.
