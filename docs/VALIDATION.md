# Validación y pendientes

Resultados archivados de alpha21: **181 pruebas locales y 9 pruebas Android dirigidas distintas**, lint sin errores. Las pruebas Android completas de alpha20 no se repitieron íntegramente en alpha21; no sumar ejecuciones repetidas como cobertura nueva. Actualización alpha20→21 comprobada con 25 archivos de ensayo conservados, perfil e-ink preservado y refresco Bigme inicialmente desactivado.

Los resultados históricos no equivalen a ejecutar esta suite en cualquier máquina. El repositorio permite reproducir las pruebas; requiere regenerar la APK de ensayo con firma propia y preparar el emulador según BUILD.

Pendientes: validar ghosting/latencia en Bigme físico; rendimiento con grandes bibliotecas, batería y descargas largas en hardware; aceptación de las funciones recientes en dispositivos del usuario. No prometer eliminación total de ghosting ni inmunidad a conflictos simultáneos de servidor.

El ensayo Kavita 0.9.1.4 se describe en KAVITA-UPGRADE. Es validación de API y conector, no una nueva suite Android completa.

## Revisión de publicación GitHub (19/09/2026)

La copia pública se compiló desde su directorio separado: 181 pruebas de lógica pasadas, lint sin errores y APK debug generada. Fuentes de `app/src/main` idénticas al proyecto alpha21. Se probó la generación local de la APK de ensayo y se comprobó su firma contra la app local. No se volvió a ejecutar toda la suite de Android en esta publicación documental. El análisis Gitleaks no encontró secretos.
