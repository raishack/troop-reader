package es.gamingtroop.reader

import kotlinx.serialization.Serializable
import kotlinx.coroutines.*
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Serializable data class ServerShelf(val id: Int,val title: String="",val name: String="",val itemCount: Int=0) {
    val label get()=title.ifBlank { name }
}
@Serializable data class ServerListItem(val id: Int,val order: Int=0,val chapterId: Int,val seriesId: Int,
    val seriesName: String="",val seriesFormat: Int=0,val libraryId: Int=0,val title: String="",val chapter: Chapter?=null) {
    fun series()=Series(seriesId,seriesName,libraryId,seriesFormat)
}
@Serializable data class ServerShelves(val lists: List<ServerShelf> = emptyList(),val collections: List<ServerShelf> = emptyList(),
    val items: Map<Int,List<ServerListItem>> = emptyMap(),val members: Map<Int,List<Series>> = emptyMap(),val checkedAt: Long=0)

object Shelves {
    suspend fun refresh(repo: Repository,key: String) = withContext(Dispatchers.IO) {
        val api=repo.api(key);val lists=mutableListOf<ServerShelf>();var page=1
        while(true) {
            val batch=cancellableApiCall { codec.decodeFromString<List<ServerShelf>>(api.text("api/ReadingList/lists?pageNumber=$page&pageSize=100","{}")) }
            lists+=batch
            if(batch.size<100) break
            require(++page<=1000)
        }
        val collections=cancellableApiCall { api.get<List<ServerShelf>>("api/Collection") }
        ensureActive();check(repo.active()?.key==key)
        repo.store(key).update { it.copy(serverShelves=it.serverShelves.copy(lists=lists.distinctBy { l -> l.id },collections=collections,checkedAt=System.currentTimeMillis())) }
    }
    suspend fun load(repo: Repository,key: String,id: Int,collection: Boolean) = withContext(Dispatchers.IO) {
        require(id>0);val api=repo.api(key)
        if(collection) {
            val all=mutableListOf<Series>();var p=1
            while(true) {
                val batch=cancellableApiCall { api.get<List<Series>>("api/Series/series-by-collection?collectionId=$id&pageNumber=$p&pageSize=100") }
                all+=batch;if(batch.size<100)break;require(++p<=1000)
            }
            ensureActive();check(repo.active()?.key==key)
            repo.store(key).update { it.copy(serverShelves=it.serverShelves.copy(members=it.serverShelves.members+(id to all.distinctBy { b -> b.id }))) }
        } else {
            val items=cancellableApiCall { api.get<List<ServerListItem>>("api/ReadingList/items?readingListId=$id") }.sortedBy { it.order }
            ensureActive();check(repo.active()?.key==key)
            repo.store(key).update { it.copy(serverShelves=it.serverShelves.copy(items=it.serverShelves.items+(id to items))) }
        }
    }
}
@Composable fun ServerShelvesPanel(repo: Repository,account: Account,state: LocalState,open: (Series)->Unit) {
    var selected by remember { mutableStateOf<ServerShelf?>(null) };var collections by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) };var error by remember { mutableStateOf<String?>(null) }
    val scope=rememberCoroutineScope()
    fun operation(block: suspend ()->Unit) { scope.launch { busy=true;error=null;try { block() } catch(e: CancellationException) { throw e } catch(_: Exception) { error="No se pudo consultar Kavita. Se conserva la última copia disponible." } finally { busy=false } } }
    LaunchedEffect(Unit) { if(state.serverShelves.checkedAt==0L && account.server!="https://demo.invalid") operation { Shelves.refresh(repo,account.key) } }
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Listas y colecciones de Kavita",style=MaterialTheme.typography.titleLarge)
        Text("Consulta del servidor y copia offline de lo que abras. No modifica tus listas online ni las colecciones personales.")
        Row { DisplayChip(!collections,{ selected=null;collections=false },{ Text("Listas") });Spacer(Modifier.width(8.dp));DisplayChip(collections,{ selected=null;collections=true },{ Text("Colecciones") }) }
        DisplayOutlinedButton(enabled=!busy,onClick={ operation { Shelves.refresh(repo,account.key);selected?.let { Shelves.load(repo,account.key,it.id,collections) } } }) { Text("Actualizar desde Kavita") }
        if(busy) DisplayProgress(Modifier.fillMaxWidth())
        error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        val shelf=selected
        if(shelf==null) {
            val list=if(collections) state.serverShelves.collections else state.serverShelves.lists
            if(list.isEmpty() && !busy) Text("No hay listas disponibles en la copia actual.")
            LazyColumn(Modifier.heightIn(max=420.dp), flingBehavior=displayFling()) { items(list,key={ it.id }) { item -> DisplayTextButton(onClick={ selected=item;operation { Shelves.load(repo,account.key,item.id,collections) } }) { Text(item.label) } } }
        } else {
            DisplayTextButton(onClick={ selected=null }) { Text("Volver a las listas") };Text(shelf.label)
            LazyColumn(Modifier.heightIn(max=420.dp), flingBehavior=displayFling()) {
                if(collections) items(state.serverShelves.members[shelf.id].orEmpty(),key={ it.id }) { s -> DisplayTextButton(onClick={ open(s) }) { Text(s.name) } }
                else items(state.serverShelves.items[shelf.id].orEmpty(),key={ it.id }) { item -> DisplayTextButton(onClick={ open(item.series()) }) {
                    Column(Modifier.fillMaxWidth()) { Text(item.seriesName);Text(item.chapter?.label ?: item.title,style=MaterialTheme.typography.bodySmall) }
                } }
            }
        }
    }
}
