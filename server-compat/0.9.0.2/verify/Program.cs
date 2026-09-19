using System.Reflection;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using System.Text.Encodings.Web;
using System.Security.Claims;
var assembly=Assembly.LoadFrom(Path.GetFullPath(args[0]));
bool patched=args[1]=="patched";
using var server=new TestServer(new WebHostBuilder().ConfigureServices(s=>{
 s.AddLogging();s.AddAuthentication("test").AddScheme<AuthenticationSchemeOptions,Auth>("test", _=>{});s.AddAuthorization();s.AddControllers().AddApplicationPart(assembly);
}).Configure(app=>{app.UseRouting();app.UseAuthentication();app.UseAuthorization();app.UseEndpoints(e=>e.MapControllers());}));
using var client=server.CreateClient();
int count=0;
async Task Check(string path,string? header,int status) {
 using var r=new HttpRequestMessage(HttpMethod.Get,path);
 if(header!=null)r.Headers.Add("x-api-key",header);
 using var result=await client.SendAsync(r);
 if((int)result.StatusCode!=status) throw new Exception(path+": "+result.StatusCode+" expected "+status);
 if(status==200 && result.Content.Headers.ContentType?.MediaType!="image/png") throw new Exception("Not an image");
 count++;
}
foreach(var path in new[]{"/api/Reader/image?chapterId=4&page=0&extractPdf=true", "/api/Image/series-cover?seriesId=4", "/api/Image/volume-cover?volumeId=4", "/api/Image/chapter-cover?chapterId=4", "/api/Image/library-cover?libraryId=4"}) {
 await Check(path,"test-valid",patched?200:400);
 await Check(path,null,401);
 await Check(path,"invalid",401);
 // Non-secret synthetic fixture value only: validates existing web-client query behavior.
 await Check(path+"&apiKey=test-valid",null,200);
}
await Check("/api/Reader/still-required","test-valid",400);
if(patched)await Check("/api/Reader/image?chapterId=99&page=0","test-valid",404);
Console.WriteLine($"PASS {count} HTTP checks ({args[1]}): media, authentication, required fields, legacy web contract");
public class Auth(IOptionsMonitor<AuthenticationSchemeOptions> o,ILoggerFactory l,UrlEncoder e):AuthenticationHandler<AuthenticationSchemeOptions>(o,l,e) {
 protected override Task<AuthenticateResult> HandleAuthenticateAsync() {
  string value=Request.Query.ContainsKey("apiKey")?Request.Query["apiKey"].ToString():Request.Headers["x-api-key"].ToString();
  return Task.FromResult(value=="test-valid"?AuthenticateResult.Success(new AuthenticationTicket(new ClaimsPrincipal(new ClaimsIdentity(new[]{new Claim(ClaimTypes.NameIdentifier,"1")},"test")),"test")):AuthenticateResult.NoResult());
 }
}
