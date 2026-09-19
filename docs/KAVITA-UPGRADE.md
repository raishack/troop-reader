# Actualización controlada de Kavita y sus integraciones

Procedimiento basado en la migración validada **0.9.0.2 → 0.9.1.4 (19/09/2026)**. No contiene rutas, cuentas ni copias de la instalación privada. No es un script para ejecutar sin adaptación.

## 1. Inventario y plan de reversión

- Registrar versión/binarios y hashes, SO/arquitectura, servicio/usuario, directorio de configuración y base, medios, proxy y almacenamiento persistente.
- Identificar conector Yamtrack, temporizador, configuración, estado de matching y su DB; anotar versiones admitidas y estado sano antes del cambio.
- Descargar paquete oficial exacto y contrastar el hash publicado. Leer notas y requisitos de migración; registrar posible invalidación de sesiones.
- Definir qué invalida el despliegue (integridad, autorización, medios, progreso o sync) y cómo restaurar **binarios, configuración y DB anteriores juntos**. No hacer downgrade de solo el ejecutable sobre DB migrada.

## 2. Copias y ensayo aislado

- Crear copia coherente SQLite con API de backup o servicio detenido; no copiar solo el `.db` activo ignorando WAL/SHM.
- Para el corte final, pausar el conector y esperar a que termine su ejecución; detener Kavita antes de tomar la copia final. Incluir claves de protección de datos y configuración en el backup privado, nunca en Git.
- Conservar instalación anterior completa, registro de servicio y dependencias de arranque. Resguardar DB/configuración/estado del conector. Verificar `PRAGMA integrity_check` y hashes donde proceda.
- Ensayar con copias separadas de DB, configuración y medios; puerto no público, tareas y notificaciones desactivadas, sin acceso de escritura a originales. La copia de ensayo puede recibir escrituras ficticias: **no es una base para desplegar en producción**.
- Migrar la copia; comprobar usuarios/bibliotecas y huellas del progreso/marcadores antes de escribir pruebas.

## 3. Matriz de aceptación

| Área | Comprobación |
|---|---|
| Servicio/proxy | Arranque estable, health, HTTPS con certificado validado, URL externa y arranque automático |
| Autenticación | Sesión/renovación, permisos de cuenta, imagen anónima rechazada |
| Medios | Carátulas serie/tomo/archivo, páginas inicial/intermedia/final de manga/PDF |
| EPUB | Secciones inicial/intermedia/final, índice, imágenes, CSS y fuentes reales |
| Progreso | Guardar/releer manga y posición EPUB **solo en copia** |
| Marcadores | Crear/releer/borrar página e índice personal EPUB **solo en copia** |
| Yamtrack | Versión admitida, autenticación, historial y dry-run del conector completo |

Si hace falta un ajuste, reconstruirlo sobre el binario candidato y volver a validar autorización. No reutilizar DLL parcheadas de versiones anteriores. Guardar hashes original/corregido y código exacto del ajuste.

## 4. Corte y verificación

- Pausar sync; backup final coherente. Conservar versión anterior y desplegar paquete validado con **datos finales de producción**, no datos del ensayo.
- Conservar identidad y rutas del servicio/proxy. Arrancar/migrar y comprobar integridad y huellas de lectura.
- Repetir comprobaciones de lectura sin escrituras ficticias en producción, a través de HTTPS real. Un ensayo por localhost no valida el proxy público.
- Ampliar únicamente la versión explícita del conector que se ha probado. Si configuración es un archivo bind-mounted, preservar el inode o recrear controladamente el montaje: renombrar el archivo del host puede dejar al contenedor leyendo el anterior.
- Dry-run y luego un ciclo real autorizado del conector. Confirmar contadores, ausencia de errores y temporizador activo. No cambiar matching para ocultar fallos de versión.
- Desactivar el watchdog de reversión solo tras aceptación. Detener ensayo, cerrar puerto/regla temporal y registrar copias, resultados, límites y procedimiento de recuperación.

## 5. Reversión

Ante fallo: pausar sync, detener servicio, guardar DB/configuración nuevas para rescatar lecturas recientes, restaurar instalación anterior **completa y coherente**, restaurar configuración/estado del conector si procede, validar servicio/API/sync y reactivar temporizador. Una reversión tardía puede perder cambios posteriores al backup; no ejecutarla ciegamente.

## Resultado histórico y límites

La migración citada conservó las huellas de progreso/marcadores y ambas DB pasaron integridad. API autenticada y proxy HTTPS correctos. Yamtrack: dry-run y ciclo real con 3 elementos sin cambios, 0 errores; timer reactivado. Se ensayaron escrituras solo en copia. Se muestrearon páginas/secciones: no toda la biblioteca ni un nuevo recorrido físico desde el móvil. La app permaneció en alpha21.
