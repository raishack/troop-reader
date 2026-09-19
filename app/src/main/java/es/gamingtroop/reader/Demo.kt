package es.gamingtroop.reader

import android.content.Context
import android.graphics.*
import java.io.File

/** Debug-only, opt-in fixture. Never changes an existing signed-in account. */
object Demo {
    fun install(context: Context) {
        if(!BuildConfig.DEBUG) return
        val repo=context.repository()
        if(repo.active()!=null) return
        val a=Account("https://demo.invalid",999,"Demostración offline","fixture-not-a-real-token",roles=listOf("Download"),version="datos de prueba")
        val store=repo.store(a.key)
        val series=listOf(Series(1,"El jardín de las mareas",1,3,4),Series(2,"Horizonte de papel",2,1,6),Series(3,"Atlas de islas imaginarias",1,3,4),Series(4,"Noche en la estación",2,1,6))
        val chapters=series.associate { s ->
            val c=Chapter(s.id*100,s.id,titleName=if(s.format==3) "Libro completo" else "Tomo 1",pages=if(s.format==3)4 else 6,format=s.format)
            val dir=store.chapterDir(c.id).apply { mkdirs() }
            for(page in 0 until c.pages) {
                if(s.format==3) File(dir,"$page.html").writeText("<div><h1 id='chapter-${page}'>${page+1}. El viaje</h1>"+(0..18).joinToString("") { i -> "<p id='p-$i'>${if(i==0) "Contenido de demostración, creado para las pruebas de Troop Reader." else "La ciudad despertaba junto al mar. Cada página abría una ventana a un paisaje nuevo, y cada camino encontraba su lugar entre los recuerdos. No había conexión, pero la historia seguía acompañándoles."}</p>" }+"</div>")
                else image(File(dir,"$page.img"),"${s.name}\nPágina ${page+1}",s.id,page,true)
            }
            image(store.coverFile(s.id),s.name,s.id,0,false)
            c.id to SavedChapter(c,s,s.format==3,true,c.pages,dir.walkTopDown().filter { it.isFile }.sumOf { it.length() },"Disponible sin conexión", if(s.format==3)(0..3).map { Toc("${it+1}. El viaje", "#chapter-$it",it) } else emptyList())
        }
        store.update { LocalState(libraries=listOf(Library(1,"Libros"),Library(2,"Manga")),series=series,chapters=chapters,progress=chapters.mapValues { (id,b) -> Progress(b.series.libraryId,b.series.id,b.chapter.volumeId,id,if(b.epub)1 else 0) },syncMessage="Modo demostración · sin conexión al servidor") }
        repo.vault.save(a)
    }
    private fun image(file:File,title:String,index:Int,page:Int,comic:Boolean) {
        val b=Bitmap.createBitmap(600,if(comic)850 else 880,Bitmap.Config.ARGB_8888)
        val c=Canvas(b);val p=Paint(Paint.ANTI_ALIAS_FLAG)
        val colors=intArrayOf(Color.rgb(38,76,68),Color.rgb(64,48,75),Color.rgb(39,68,90),Color.rgb(95,53,43))
        c.drawColor(if(comic)Color.rgb(245,241,228) else colors[(index-1)%4])
        p.color=if(comic)Color.rgb(35,40,42) else Color.rgb(187,218,182)
        p.style=Paint.Style.STROKE;p.strokeWidth=if(comic)8f else 3f
        for(i in 0..5) c.drawCircle(300f,260f,45f+i*28,p)
        c.drawRect(32f,32f,568f,if(comic)818f else 848f,p)
        p.style=Paint.Style.FILL;p.textSize=42f;p.typeface=Typeface.create("serif",Typeface.BOLD)
        val words=title.split(' ');var line="";var y=555f
        for(word in words) { if((line+word).length>21){c.drawText(line,60f,y,p);y+=55;line=""};line+="$word " }
        c.drawText(line,60f,y,p);p.textSize=22f;c.drawText(if(comic) "MANGA DE PRUEBA · ${page+1}" else "BIBLIOTECA DE DEMOSTRACIÓN",60f,785f,p)
        file.parentFile?.mkdirs();file.outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) };b.recycle()
    }
}
