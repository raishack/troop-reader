using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Authorization;
namespace Kavita.Server.Controllers;
[ApiController][Authorize][Route("api/[controller]")]
public class ReaderController : ControllerBase {
 public string? Marker {get;set;}
 [HttpGet("image")] public IActionResult GetImage(int chapterId,int page,string apiKey,bool extractPdf=false) => chapterId==4?File(new byte[]{137,80,78,71},"image/png"):NotFound();
 [HttpGet("still-required")] public IActionResult Other(string requiredText) => Ok(requiredText);
}
[ApiController][Authorize][Route("api/[controller]")]
public class ImageController : ControllerBase {
 [HttpGet("series-cover")] public IActionResult GetSeriesCoverImage(int seriesId,string apiKey)=>File(new byte[]{137,80,78,71},"image/png");
 [HttpGet("volume-cover")] public IActionResult GetVolumeCoverImage(int volumeId,string apiKey)=>File(new byte[]{137,80,78,71},"image/png");
 [HttpGet("chapter-cover")] public IActionResult GetChapterCoverImage(int chapterId,string apiKey)=>File(new byte[]{137,80,78,71},"image/png");
 [HttpGet("library-cover")] public IActionResult GetLibraryCoverImage(int libraryId,string apiKey)=>File(new byte[]{137,80,78,71},"image/png");
}
