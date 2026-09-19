using Mono.Cecil;
using System.Security.Cryptography;
var allowed = new Dictionary<string,string[]> {
 ["Kavita.Server.Controllers.ReaderController"] = ["GetImage"],
 ["Kavita.Server.Controllers.ImageController"] = ["GetChapterCoverImage", "GetVolumeCoverImage", "GetSeriesCoverImage", "GetLibraryCoverImage"]
};
if(args.Length != 2) throw new Exception("input.dll output.dll required");
var resolver = new DefaultAssemblyResolver();
resolver.AddSearchDirectory(Path.GetDirectoryName(Path.GetFullPath(args[0]))!);
resolver.AddSearchDirectory(Path.GetDirectoryName(typeof(object).Assembly.Location)!);
var parameters = new ReaderParameters { AssemblyResolver=resolver };
using var asm = AssemblyDefinition.ReadAssembly(args[0], parameters);
if(asm.Name.HasPublicKey) throw new Exception("Refuse strong-named assembly");
var module = asm.MainModule;
var nullableCtor = module.GetTypeReferences().FirstOrDefault(t=>t.FullName == "System.Runtime.CompilerServices.NullableAttribute");
if(nullableCtor == null) throw new Exception("NullableAttribute not found");
var ctor = new MethodReference(".ctor", module.TypeSystem.Void, nullableCtor) { HasThis = true };
ctor.Parameters.Add(new ParameterDefinition(module.TypeSystem.Byte));
int patched=0;
foreach(var entry in allowed) {
 var type=module.GetType(entry.Key) ?? throw new Exception("Missing controller");
 foreach(var name in entry.Value) {
  var m=type.Methods.Single(m=>m.Name==name);
  var p=m.Parameters.Single(p=>p.Name=="apiKey" && p.ParameterType.FullName=="System.String");
  if(m.CustomAttributes.Any(a=>a.AttributeType.Name=="AllowAnonymousAttribute")) throw new Exception("Unexpected anonymous method");
  foreach(var attr in p.CustomAttributes.Where(a=>a.AttributeType.FullName=="System.Runtime.CompilerServices.NullableAttribute").ToList()) p.CustomAttributes.Remove(attr);
  var nullable=new CustomAttribute(ctor); nullable.ConstructorArguments.Add(new CustomAttributeArgument(module.TypeSystem.Byte,(byte)2));
  p.CustomAttributes.Add(nullable);
  Console.WriteLine("Nullable-only metadata: " + type.Name + "." + name + "(apiKey)");patched++;
 }
}
if(patched!=5) throw new Exception("Unexpected patch count");
asm.Write(args[1]);
// Round trip verification: no method IL, signature, assembly identity or auth attributes changed.
using var after=AssemblyDefinition.ReadAssembly(args[1]);
using var before=AssemblyDefinition.ReadAssembly(args[0]);
string IL(MethodDefinition m)=>m.HasBody?string.Join("|",m.Body.Instructions.Select(i=>i.ToString())):"";
foreach(var type in before.MainModule.Types) {
 var dest=after.MainModule.Types.Single(t=>t.FullName==type.FullName);
 foreach(var method in type.Methods) {
  var other=dest.Methods.Single(m=>m.FullName==method.FullName);
  if(IL(method)!=IL(other)) throw new Exception("IL modified: "+method.FullName);
  if(!method.CustomAttributes.Select(a=>a.AttributeType.FullName).SequenceEqual(other.CustomAttributes.Select(a=>a.AttributeType.FullName))) throw new Exception("Method attributes modified");
 }
}
if(before.Name.FullName!=after.Name.FullName) throw new Exception("Identity changed");
Console.WriteLine("PASS: exactly five apiKey nullability metadata changes; method bodies and authorization unchanged.");
Console.WriteLine("SHA256="+Convert.ToHexString(SHA256.HashData(File.ReadAllBytes(args[1]))).ToLowerInvariant());
