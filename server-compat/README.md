# Compatibilidad de imágenes de Kavita

Herramientas **opcionales, no oficiales y específicas de versión**. No se ejecutan desde la app y no modifican servidores automáticamente.

- `0.9.0.2/patcher`: histórico, cinco parámetros de Reader/Image.
- `0.9.1.4/patcher`: solo `ReaderController.GetImage(apiKey)`, usado en el update documentado. Las carátulas de esta versión ya están corregidas upstream.

.NET SDK 10.0 y Mono.Cecil 0.11.6. Descarga el paquete oficial de la misma versión/arquitectura y conserva una copia íntegra. **No se distribuye una DLL parcheada ni se copia una DLL de otra versión.**

## Procedimiento para 0.9.1.4

1. Confirmar HTTP400 por parámetro apiKey requerido en imagen con sesión válida. Si funciona, no aplicar nada.
2. Verificar hash del paquete oficial. Comparar el DLL original con `0.9.1.4/INPUT-SHA256.txt` (referencia del paquete Windows x64 ensayado). Si difiere, detenerse e investigar; los patchers no implementan una allowlist de hashes ni prueban todas las plataformas.
3. Crear directorio de trabajo con DLL/dependencias originales y otro de salida. Nunca sobrescribir el input.

```sh
dotnet run --project server-compat/0.9.1.4/patcher -- /work/original/Kavita.Server.dll /work/output/Kavita.Server.dll
```

4. El programa rechaza ensamblados strong-name y comprueba identidad, IL y atributos de métodos. Solo cambia nulabilidad del parámetro; estas comprobaciones no sustituyen pruebas de permisos.
5. En la copia aislada y detenida, sustituir exclusivamente su DLL por la salida. Arrancar y probar: imagen autenticada 200, anónima 401, usuario sin acceso denegado, carátulas y resto de API. Comprobar también cliente web.
6. Solo tras ensayo y backup final desplegar el mismo paquete validado según [KAVITA-UPGRADE](../docs/KAVITA-UPGRADE.md). Guardar ambos hashes y revertir instalación/DB coherentes si falla.

## Fixture histórica 0.9.0.2

Prueba local de contrato, no servidor real (ejecutar desde raíz, crear directorio output):

```sh
mkdir -p build/compat-patched
dotnet build server-compat/0.9.0.2/fixture -o build/compat-original
dotnet run --project server-compat/0.9.0.2/patcher -- build/compat-original/Fixture.dll build/compat-patched/Fixture.dll
dotnet run --project server-compat/0.9.0.2/verify -- build/compat-original/Fixture.dll original
dotnet run --project server-compat/0.9.0.2/verify -- build/compat-patched/Fixture.dll patched
```

La fixture simula cinco rutas antiguas; no es una validación de la migración ni del controlador 0.9.1.4 real. Una nueva versión exige reevaluación.
