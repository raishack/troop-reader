# Kavita image compatibility

**Optional, unofficial, version-specific tools.** They are not executed by the app and never modify servers automatically.

- `0.9.0.2/patcher`: historical adjustment to five Reader/Image parameters.
- `0.9.1.4/patcher`: only `ReaderController.GetImage(apiKey)`, used in the documented upgrade. Cover routes are already fixed upstream in that version.

Requires .NET SDK 10.0 and Mono.Cecil 0.11.6. Download the official package for the exact version/architecture and preserve a complete copy. **No patched DLL is distributed; never copy a DLL from another version.**

## Procedure for 0.9.1.4

1. Confirm HTTP 400 due to a required apiKey parameter on an image request with a valid session. If it works already, do not patch.
2. Verify the official package hash. Compare the original DLL with `0.9.1.4/INPUT-SHA256.txt` (reference from the tested Windows x64 package). If different, stop and investigate: the patchers do not enforce a hash allowlist or test every platform.
3. Prepare a working directory with the original DLL/dependencies and a separate output directory. Never overwrite the input.

```sh
dotnet run --project server-compat/0.9.1.4/patcher -- /work/original/Kavita.Server.dll /work/output/Kavita.Server.dll
```

4. The program rejects strong-named assemblies and checks identity, IL and method attributes. It changes only parameter nullability; these checks do not replace permission tests.
5. Replace only the DLL in the stopped isolated copy. Start it and test: authenticated image 200, anonymous image 401, inaccessible library denied, covers and remaining API. Check the web client too.
6. Only after rehearsal and final backup, deploy the validated package using [KAVITA-UPGRADE](../docs/KAVITA-UPGRADE.md). Save both hashes; restore a consistent installation/database if anything fails.

## Historical 0.9.0.2 fixture

Local contract test, not a real server; run from repository root:

```sh
mkdir -p build/compat-patched
dotnet build server-compat/0.9.0.2/fixture -o build/compat-original
dotnet run --project server-compat/0.9.0.2/patcher -- build/compat-original/Fixture.dll build/compat-patched/Fixture.dll
dotnet run --project server-compat/0.9.0.2/verify -- build/compat-original/Fixture.dll original
dotnet run --project server-compat/0.9.0.2/verify -- build/compat-patched/Fixture.dll patched
```

This fixture simulates five old routes, not the migration or the real 0.9.1.4 controller. Every new version requires reassessment.
