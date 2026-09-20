package es.gamingtroop.reader

import android.view.MotionEvent
import android.graphics.drawable.ColorDrawable
import android.view.Window
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable fun DisplayTheme(prefs: DisplayPreferences, refresh: EinkRefresh, content: @Composable () -> Unit) {
    val mode=prefs.mode
    val accent=if(mode==DisplayMode.COLOR) Color(0xFF003F85) else Color.Black
    val colors=if(!mode.eink) darkColorScheme(primary=Color(0xFF77DCA2),secondary=Color(0xFF77DCA2),
        background=Color(0xFF101419),surface=Color(0xFF1C222A),onPrimary=Color(0xFF10261A)) else lightColorScheme(
        primary=accent,onPrimary=Color.White,secondary=accent,onSecondary=Color.White,
        background=Color.White,onBackground=Color.Black,surface=Color.White,onSurface=Color.Black,
        surfaceVariant=Color.White,onSurfaceVariant=Color.Black,surfaceTint=Color.Transparent,
        surfaceContainer=Color.White,surfaceContainerLow=Color.White,surfaceContainerHigh=Color.White,
        surfaceContainerHighest=Color.White,surfaceContainerLowest=Color.White,surfaceDim=Color.White,surfaceBright=Color.White,
        outline=Color.Black,outlineVariant=Color.Black,secondaryContainer=Color.White,onSecondaryContainer=Color.Black,
        primaryContainer=Color.White,onPrimaryContainer=Color.Black,
        error=if(mode==DisplayMode.MONO) Color.Black else Color(0xFF8C1600),onError=Color.White)
    val activity=LocalView.current.context as? MainActivity
    SideEffect {
        activity?.window?.let { w ->
            val c=androidx.core.view.WindowCompat.getInsetsController(w,w.decorView)
            c.isAppearanceLightStatusBars=mode.eink;c.isAppearanceLightNavigationBars=mode.eink
            w.navigationBarColor=if(mode.eink) android.graphics.Color.WHITE else android.graphics.Color.rgb(19,22,27)
            w.statusBarColor=w.navigationBarColor
        }
    }
    val standard=Typography()
    val type=if(!mode.eink) standard else standard.copy(
        bodySmall=standard.bodySmall.copy(fontSize=14.sp,lineHeight=20.sp),
        labelSmall=standard.labelSmall.copy(fontSize=14.sp,lineHeight=20.sp,fontWeight=FontWeight.Medium),
        labelMedium=standard.labelMedium.copy(fontSize=14.sp,lineHeight=20.sp,fontWeight=FontWeight.Medium))
    CompositionLocalProvider(LocalDisplayMode provides mode,LocalDisplayPreferences provides prefs,LocalEinkRefresh provides refresh,
        LocalOverscrollConfiguration provides if(mode.eink) null else androidx.compose.foundation.OverscrollConfiguration(),
        LocalRippleConfiguration provides if(mode.eink) null else RippleConfiguration()) {
        MaterialTheme(colorScheme=colors,typography=type) { DisplayWindow();content() }
    }
    LaunchedEffect(mode,prefs.cleaning,prefs.bigmeNative) { refresh.changed() }
}

/** The overlay belongs to each app window (including full-screen e-ink dialogs). */
@Composable fun DisplayWindow() {
    val view=LocalView.current
    val mode=LocalDisplayMode.current
    val refresh=LocalEinkRefresh.current
    DisposableEffect(view,mode,refresh) {
        val detach=refresh?.attach(view)
        val window=generateSequence(view as android.view.View?) { it.parent as? android.view.View }
            .filterIsInstance<DialogWindowProvider>().firstOrNull()?.window
        val old=window?.callback
        val wrapper=if(window!=null && old!=null) object: Window.Callback by old {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if(event.actionMasked==MotionEvent.ACTION_DOWN) refresh?.beginTouch()
                val result=old.dispatchTouchEvent(event)
                if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL) refresh?.endTouch()
                return result
            }
            override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
                val result=old.dispatchKeyEvent(event)
                if(event.action==android.view.KeyEvent.ACTION_UP) refresh?.request()
                return result
            }
        } else null
        if(wrapper!=null) window!!.callback=wrapper
        if(mode.eink) window?.let { w ->
            w.setWindowAnimations(0)
            w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setBackgroundDrawable(ColorDrawable(android.graphics.Color.WHITE))
            w.decorView.setPadding(0,0,0,0)
            w.decorView.elevation=0f
            w.setGravity(android.view.Gravity.TOP or android.view.Gravity.START)
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
            w.attributes=w.attributes.apply {
                x=0;y=0
                if(android.os.Build.VERSION.SDK_INT>=30) {
                    layoutInDisplayCutoutMode=android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    setFitInsetsTypes(0)
                } else if(android.os.Build.VERSION.SDK_INT>=28) {
                    layoutInDisplayCutoutMode=android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w,false)
            val bars=androidx.core.view.WindowCompat.getInsetsController(w,w.decorView)
            bars.isAppearanceLightStatusBars=true;bars.isAppearanceLightNavigationBars=true
        }
        onDispose { if(window?.callback===wrapper && old!=null) window.callback=old;detach?.invoke() }
    }
}

@Composable fun DisplaySettings() {
    val prefs=LocalDisplayPreferences.current ?: return
    val refresh=LocalEinkRefresh.current
    Column(Modifier.fillMaxWidth()) {
        Text(tr(R.string.tr_092),style=MaterialTheme.typography.titleMedium)
        DisplayMode.entries.forEach { mode ->
            DisplayChip(prefs.mode==mode,{ prefs.mode(mode) },{ Text(mode.label) },modifier=Modifier.fillMaxWidth())
        }
        if(prefs.mode.eink) {
            Text(tr(R.string.tr_093),style=MaterialTheme.typography.bodySmall)
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text(tr(R.string.tr_094),Modifier.weight(1f));DisplaySwitch(prefs.cleaning,prefs::cleaning)
            }
            if(refresh?.bigmeStatus==BigmeApiStatus.AVAILABLE) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(tr(R.string.tr_095),Modifier.weight(1f))
                    DisplaySwitch(prefs.bigmeNative,prefs::bigmeNative,Modifier.semantics { contentDescription=tr(R.string.tr_095) })
                }
                Text(tr(R.string.tr_096),style=MaterialTheme.typography.bodySmall)
            }
            Text(if(prefs.bigmeNative && refresh?.bigmeStatus==BigmeApiStatus.AVAILABLE)
                tr(R.string.tr_097)
            else if(refresh?.driverAvailable==true) tr(R.string.tr_098) else
                tr(R.string.tr_099),style=MaterialTheme.typography.bodySmall)
            if(refresh?.bigmeStatus==BigmeApiStatus.FAILED) Text(BigmeApiStatus.FAILED.label,style=MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick={ refresh?.request() },enabled=prefs.cleaning) { Text(tr(R.string.tr_100)) }
            var help by remember { mutableStateOf(false) }
            TextButton(onClick={ help=true }) { Text(tr(R.string.tr_101)) }
            if(help) DisplayAlertDialog(onDismissRequest={ help=false },title={ Text(tr(R.string.tr_102)) },
                text={ Column(Modifier.verticalScroll(rememberScrollState(),flingBehavior=displayFling())) {
                    Text(tr(R.string.tr_103))
                    Text(refresh?.bigmeStatus?.label ?: tr(R.string.tr_104))
                    Text(if(prefs.bigmeNative && refresh?.bigmeStatus==BigmeApiStatus.AVAILABLE)
                        tr(R.string.tr_105)
                    else if(refresh?.driverAvailable==true) tr(R.string.tr_106) else
                        tr(R.string.tr_107))
                    Text(tr(R.string.tr_108))
                    Text(tr(R.string.tr_109))
                    if(prefs.mode==DisplayMode.COLOR) Text(tr(R.string.tr_110))
                } },confirmButton={ TextButton(onClick={ help=false }) { Text(tr(R.string.tr_111)) } })
        }
    }
}

@Composable fun DisplaySwitch(checked: Boolean,onCheckedChange: ((Boolean)->Unit)?,modifier: Modifier=Modifier,enabled: Boolean=true) {
    val refresh=LocalEinkRefresh.current
    if(!LocalDisplayMode.current.eink) { Switch(checked,onCheckedChange,modifier,enabled=enabled);return }
    Box(modifier.sizeIn(minWidth=64.dp,minHeight=48.dp).padding(4.dp).border(2.dp,Color.Black,RoundedCornerShape(4.dp))
        .toggleable(checked,enabled=enabled,role=Role.Switch,onValueChange={ onCheckedChange?.invoke(it);refresh?.request() }),contentAlignment=Alignment.Center) {
        Text(if(checked) tr(R.string.tr_112) else tr(R.string.tr_113),color=Color.Black,fontWeight=if(checked) FontWeight.Bold else FontWeight.Normal)
    }
}
@Composable fun DisplayCheckbox(checked: Boolean,onCheckedChange: ((Boolean)->Unit)?,modifier: Modifier=Modifier,enabled: Boolean=true) {
    val refresh=LocalEinkRefresh.current
    if(!LocalDisplayMode.current.eink) { Checkbox(checked,onCheckedChange,modifier,enabled=enabled);return }
    Box(modifier.size(48.dp).toggleable(checked,enabled=enabled,role=Role.Checkbox,onValueChange={ onCheckedChange?.invoke(it);refresh?.request() }),contentAlignment=Alignment.Center) {
        Box(Modifier.size(26.dp).border(2.dp,Color.Black),contentAlignment=Alignment.Center) { if(checked) Icon(Icons.Outlined.Check,null,tint=Color.Black) }
    }
}
@Composable fun DisplayChip(selected: Boolean,onClick: ()->Unit,label: @Composable ()->Unit,modifier: Modifier=Modifier,
    enabled: Boolean=true,leadingIcon: (@Composable ()->Unit)?=null) {
    if(!LocalDisplayMode.current.eink) { FilterChip(selected,onClick,label,modifier,enabled,leadingIcon=leadingIcon);return }
    Row(modifier.padding(vertical=3.dp).heightIn(min=48.dp).border(if(selected) 3.dp else 1.dp,Color.Black,RoundedCornerShape(4.dp))
        .selectable(selected,enabled=enabled,role=Role.Tab,onClick=refreshAction(onClick)).padding(10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        if(selected) Icon(Icons.Outlined.Check,null,Modifier.size(20.dp))
        leadingIcon?.invoke();label()
    }
}
@Composable fun DisplayProgress(modifier: Modifier=Modifier) {
    if(!LocalDisplayMode.current.eink) LinearProgressIndicator(modifier) else Text("Cargando…",modifier,style=MaterialTheme.typography.bodySmall)
}
@Composable fun DisplayProgress(progress: ()->Float,modifier: Modifier=Modifier) {
    if(!LocalDisplayMode.current.eink) LinearProgressIndicator(progress,modifier) else Text("${(progress().coerceIn(0f,1f)*100).toInt()} %",modifier,style=MaterialTheme.typography.bodySmall)
}
@Composable fun DisplaySpinner(modifier: Modifier=Modifier) {
    if(!LocalDisplayMode.current.eink) CircularProgressIndicator(modifier) else Box(modifier,contentAlignment=Alignment.Center) { Text("…") }
}
@Composable fun DisplayCard(modifier: Modifier=Modifier,content: @Composable ColumnScope.()->Unit) {
    Card(modifier,border=if(LocalDisplayMode.current.eink) BorderStroke(1.dp,Color.Black) else null,content=content)
}
@Composable fun DisplayCard(onClick: ()->Unit,modifier: Modifier=Modifier,colors: CardColors=CardDefaults.cardColors(),content: @Composable ColumnScope.()->Unit) {
    Card(refreshAction(onClick),modifier,colors=colors,border=if(LocalDisplayMode.current.eink) BorderStroke(1.dp,Color.Black) else null,content=content)
}

@Composable fun DisplayAlertDialog(onDismissRequest: ()->Unit,confirmButton: @Composable ()->Unit,
    modifier: Modifier=Modifier,dismissButton: (@Composable ()->Unit)?=null,title: (@Composable ()->Unit)?=null,text: (@Composable ()->Unit)?=null) {
    if(!LocalDisplayMode.current.eink) {
        AlertDialog(onDismissRequest,confirmButton,modifier,dismissButton=dismissButton,title=title,text=text);return
    }
    Dialog(onDismissRequest,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        DisplayWindow()
        Surface(modifier.fillMaxSize(),color=Color.White) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(20.dp)) {
                title?.let { ProvideTextStyle(MaterialTheme.typography.titleLarge) { it() } }
                HorizontalDivider(Modifier.padding(vertical=12.dp),color=Color.Black)
                Box(Modifier.weight(1f,fill=false)) { text?.invoke() }
                Row(Modifier.fillMaxWidth().padding(top=12.dp),horizontalArrangement=Arrangement.End) { dismissButton?.invoke();confirmButton() }
            }
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun DisplayBottomSheet(onDismissRequest: ()->Unit,sheetState: SheetState,content: @Composable ColumnScope.()->Unit) {
    if(!LocalDisplayMode.current.eink) { ModalBottomSheet(onDismissRequest,sheetState=sheetState,content=content);return }
    Dialog(onDismissRequest,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        DisplayWindow()
        Surface(Modifier.fillMaxSize(),color=Color.White) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Column(Modifier.weight(1f)) { content() }
                TextButton(onClick=onDismissRequest,modifier=Modifier.align(Alignment.End)) { Text(tr(R.string.tr_115)) }
            }
        }
    }
}
@Composable fun RowScope.DisplayNavigationItem(selected: Boolean,onClick: ()->Unit,icon: @Composable ()->Unit,label: @Composable ()->Unit) {
    if(!LocalDisplayMode.current.eink) NavigationBarItem(selected,onClick,icon,label=label)
    else Column(Modifier.weight(1f).heightIn(min=64.dp).selectable(selected,role=Role.Tab,onClick=refreshAction(onClick))
        .border(if(selected) 2.dp else 0.dp,Color.Black).padding(4.dp),horizontalAlignment=Alignment.CenterHorizontally) { icon();label() }
}
@Composable fun DisplayImage(model: Any?,contentDescription: String?,modifier: Modifier=Modifier,contentScale: ContentScale=ContentScale.Fit,
    onSuccess: ((coil.compose.AsyncImagePainter.State.Success)->Unit)?=null,onError: ((coil.compose.AsyncImagePainter.State.Error)->Unit)?=null) {
    val mode=LocalDisplayMode.current;val refresh=LocalEinkRefresh.current
    coil.compose.AsyncImage(model,contentDescription,modifier,contentScale=contentScale,
        colorFilter=if(mode==DisplayMode.MONO) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) else null,
        onSuccess={ onSuccess?.invoke(it);refresh?.request(data=true) },onError=onError)
}

@Composable fun displayFling(): androidx.compose.foundation.gestures.FlingBehavior =
    if(!LocalDisplayMode.current.eink) androidx.compose.foundation.gestures.ScrollableDefaults.flingBehavior()
    else remember { object: androidx.compose.foundation.gestures.FlingBehavior {
        override suspend fun androidx.compose.foundation.gestures.ScrollScope.performFling(initialVelocity: Float) = 0f
    } }
@Composable fun DisplayDropdownMenu(expanded: Boolean,onDismissRequest: ()->Unit,content: @Composable ColumnScope.()->Unit) {
    if(!LocalDisplayMode.current.eink) DropdownMenu(expanded,onDismissRequest,content=content)
    else if(expanded) DisplayAlertDialog(onDismissRequest,confirmButton={ TextButton(onClick=onDismissRequest) { Text(tr(R.string.tr_115)) } },text={ Column { content() } })
}

// Explicit action hooks also cover TalkBack/keyboard/semantic activation, not just touch.
@Composable private fun refreshAction(action: ()->Unit): ()->Unit {
    val refresh=LocalEinkRefresh.current
    return { action();refresh?.request() }
}
@Composable fun DisplayButton(onClick: ()->Unit,modifier: Modifier=Modifier,enabled: Boolean=true,content: @Composable RowScope.()->Unit) {
    Button(refreshAction(onClick),modifier,enabled=enabled,content=content)
}
@Composable fun DisplayTextButton(onClick: ()->Unit,modifier: Modifier=Modifier,enabled: Boolean=true,content: @Composable RowScope.()->Unit) {
    TextButton(refreshAction(onClick),modifier,enabled=enabled,content=content)
}
@Composable fun DisplayOutlinedButton(onClick: ()->Unit,modifier: Modifier=Modifier,enabled: Boolean=true,content: @Composable RowScope.()->Unit) {
    OutlinedButton(refreshAction(onClick),modifier,enabled=enabled,content=content)
}
@Composable fun DisplayIconButton(onClick: ()->Unit,modifier: Modifier=Modifier,enabled: Boolean=true,content: @Composable ()->Unit) {
    IconButton(refreshAction(onClick),modifier,enabled=enabled,content=content)
}
