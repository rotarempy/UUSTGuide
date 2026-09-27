package com.example.uust

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private var mapView: WebView? = null
    private var overviewSvg = ""
    private var detailedSvg = ""
    private var buildingIds = emptyList<Int>()
    private var floors = emptyList<Int>()
    private var detailed = false
    private var selectedFloor = 1
    private var focusedBuilding: Int? = null
    private var pendingSearch: String? = null
    private var searchText = ""
    private val filters = linkedMapOf(
        "Rooms" to "Номера", "Stairs" to "Лестницы", "WC" to "Туалеты",
        "Buffets" to "Буфеты", "Coffee" to "Кофе", "Wardrobe" to "Гардероб",
        "Rest" to "Отдых"
    )
    private val visibleFilters = mutableMapOf<String, Boolean>()
    private var loadError: String? = null

    private val dark get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    private val background get() = getColor(R.color.ui_background)
    private val surface get() = getColor(R.color.ui_surface)
    private val ink get() = getColor(R.color.ui_text)
    private val muted get() = getColor(R.color.ui_muted)
    private val accent get() = getColor(R.color.ui_accent)
    private val onAccent get() = getColor(R.color.ui_on_accent)
    private val border get() = getColor(R.color.ui_border)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        detailed = savedInstanceState?.getBoolean("detailed") ?: false
        selectedFloor = savedInstanceState?.getInt("floor") ?: 1
        focusedBuilding = savedInstanceState?.getInt("building", -1)?.takeIf { it > 0 }
        searchText = savedInstanceState?.getString("searchText") ?: ""
        filters.keys.forEach { visibleFilters[it] = savedInstanceState?.getBoolean("filter_$it") ?: true }
        try {
            overviewSvg = readSvg("map1.svg")
            detailedSvg = readSvg("map2.svg")
            buildingIds = Regex("id=\"building-(\\d+)\"")
                .findAll(overviewSvg).map { it.groupValues[1].toInt() }.distinct().sorted().toList()
            floors = Regex("<g\\s+id=\"\\d+-(\\d+)\"")
                .findAll(detailedSvg).map { it.groupValues[1].toInt() }.distinct().sorted().toList()
            require(buildingIds.isNotEmpty()) { "В map1.svg не найдены корпуса" }
            require(floors.isNotEmpty()) { "В map2.svg не найдены группы этажей" }
            if (selectedFloor !in floors) selectedFloor = floors.first()
        } catch (error: Exception) {
            loadError = error.message ?: "Не удалось открыть карты"
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp(), 16.dp(), 16.dp(), 16.dp())
            setOnApplyWindowInsetsListener { view, insets ->
                val sides = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    intArrayOf(bars.left, bars.top, bars.right, bars.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    val cutout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) insets.displayCutout else null
                    @Suppress("DEPRECATION")
                    intArrayOf(maxOf(insets.systemWindowInsetLeft, cutout?.safeInsetLeft ?: 0),
                        maxOf(insets.systemWindowInsetTop, cutout?.safeInsetTop ?: 0),
                        maxOf(insets.systemWindowInsetRight, cutout?.safeInsetRight ?: 0),
                        maxOf(insets.systemWindowInsetBottom, cutout?.safeInsetBottom ?: 0))
                }
                view.setPadding(16.dp() + sides[0], 16.dp() + sides[1],
                    16.dp() + sides[2], 16.dp() + sides[3])
                insets
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) window.setDecorFitsSystemWindows(false)
        setContentView(root)
        root.requestApplyInsets()
        showScreen()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("detailed", detailed)
        outState.putInt("floor", selectedFloor)
        outState.putInt("building", focusedBuilding ?: -1)
        outState.putString("searchText", searchText)
        filters.keys.forEach { outState.putBoolean("filter_$it", visibleFilters[it] != false) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        mapView?.destroy()
        mapView = null
        super.onDestroy()
    }

    private fun readSvg(path: String): String {
        val source = assets.open(path).bufferedReader().use { it.readText() }
        val start = Regex("<svg\\b", RegexOption.IGNORE_CASE).find(source)?.range?.first
            ?: error("$path не является SVG")
        return source.substring(start)
    }

    private fun showScreen() {
        root.removeAllViews()
        mapView?.destroy()
        mapView = null
        root.setBackgroundColor(background)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            (if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)

        root.addView(header())
        if (loadError != null) {
            root.addView(label(loadError ?: "Не удалось открыть карты", 16f, muted))
            return
        }
        root.addView(modeSelector())
        val web = createMapView()
        mapView = web
        val frame = FrameLayout(this).apply {
            background = shape(surface, 20, border)
            clipToOutline = true
            setPadding(2.dp(), 2.dp(), 2.dp(), 2.dp())
            addView(web, FrameLayout.LayoutParams(-1, -1))
        }
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            topMargin = 12.dp()
            bottomMargin = 14.dp()
        })
        root.addView(quickAccess())
    }

    private fun header(): LinearLayout = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(label("Карта кампуса", 23f, ink, bold = true),
            LinearLayout.LayoutParams(0, -2, 1f))
        addView(ImageView(this@MainActivity).apply {
            setImageResource(android.R.drawable.ic_menu_search)
            imageTintList = ColorStateList.valueOf(ink)
            contentDescription = "Поиск кабинета"
            background = RippleDrawable(ColorStateList.valueOf(accent and 0x55FFFFFF),
                shape(surface, 14, border), null)
            setPadding(11.dp(), 11.dp(), 11.dp(), 11.dp())
            isClickable = true
            isFocusable = true
            setOnClickListener { showSearchDialog() }
        }, LinearLayout.LayoutParams(44.dp(), 44.dp()).apply { rightMargin = 8.dp() })
    }

    private fun modeSelector(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, 14.dp(), 0, 0)
        addView(button("Карта", !detailed) {
            if (detailed) { detailed = false; showScreen() }
        }.apply { textSize = 12f; setPadding(4.dp(), 0, 4.dp(), 0) },
            LinearLayout.LayoutParams(0, 44.dp(), 1f).apply { rightMargin = 5.dp() })
        addView(button("Этажи", detailed) {
            if (!detailed) { detailed = true; showScreen() }
        }.apply { textSize = 12f; setPadding(4.dp(), 0, 4.dp(), 0) },
            LinearLayout.LayoutParams(0, 44.dp(), 1f).apply { rightMargin = if (detailed) 8.dp() else 0 })
        if (detailed) {
            val floorButton = button("$selectedFloor эт. ▾", false) {}
            floorButton.setPadding(5.dp(), 0, 5.dp(), 0)
            floorButton.setOnClickListener { anchor ->
                val menu = PopupMenu(this@MainActivity, anchor)
                floors.forEach { floor ->
                    menu.menu.add("$floor этаж").setOnMenuItemClickListener {
                        selectedFloor = floor
                        floorButton.text = "$floor эт. ▾"
                        mapView?.evaluateJavascript("window.selectFloor($floor)", null)
                        true
                    }
                }
                menu.show()
            }
            addView(floorButton, LinearLayout.LayoutParams(83.dp(), 44.dp()).apply { rightMargin = 8.dp() })
            addView(button("Слои", false) { showFiltersSheet() }.apply {
                setPadding(5.dp(), 0, 5.dp(), 0)
                contentDescription = "Показать фильтры карты"
            }, LinearLayout.LayoutParams(70.dp(), 44.dp()))
        }
    }

    // Replace only the compact controls; do not reload the SVG or reset its zoom.
    private fun refreshControls() {
        val old = root.getChildAt(1)
        root.removeViewAt(1)
        root.addView(modeSelector(), 1, old.layoutParams)
    }

    private fun showSearchDialog() {
        val dialog = Dialog(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 18.dp(), 20.dp(), 20.dp())
            background = shape(surface, 22, border)
            addView(label("Поиск кабинета", 20f, ink, bold = true),
                LinearLayout.LayoutParams(-1, 32.dp()))
            addView(label("Введите номер аудитории", 13f, muted),
                LinearLayout.LayoutParams(-1, 24.dp()))
        }
        val input = EditText(this).apply {
            setSingleLine(true)
            textSize = 15f
            setTextColor(ink)
            setHintTextColor(muted)
            hint = "Например, 6-101 или 101"
            setText(searchText)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            background = shape(this@MainActivity.background, 14, border)
            setPadding(13.dp(), 0, 13.dp(), 0)
        }
        content.addView(input, LinearLayout.LayoutParams(-1, 48.dp()).apply {
            topMargin = 14.dp()
            bottomMargin = 16.dp()
        })
        val submit: () -> Unit = {
            val query = input.text.toString().trim()
            if (query.isEmpty()) {
                input.error = "Введите номер кабинета"
            } else {
                searchText = query
                dialog.dismiss()
                searchRoom(query)
            }
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                submit()
                true
            } else false
        }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("Отмена", false) { dialog.dismiss() },
            LinearLayout.LayoutParams(0, 44.dp(), 1f).apply { rightMargin = 5.dp() })
        actions.addView(button("Найти", true) { submit() },
            LinearLayout.LayoutParams(0, 44.dp(), 1f).apply { leftMargin = 5.dp() })
        content.addView(actions)
        dialog.setContentView(content)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout((resources.displayMetrics.widthPixels - 40.dp()).coerceAtMost(420.dp()),
                WindowManager.LayoutParams.WRAP_CONTENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        input.requestFocus()
    }

    private fun showFiltersSheet() {
        val dialog = BottomSheetDialog(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 20.dp(), 20.dp(), 22.dp())
            background = shape(surface, 22)
            addView(label("Слои карты", 20f, ink, bold = true),
                LinearLayout.LayoutParams(-1, 42.dp()))
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        filters.forEach { (key, title) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(label(title, 16f, ink), LinearLayout.LayoutParams(0, -2, 1f))
            }
            val toggle = Switch(this).apply {
                isChecked = visibleFilters[key] != false
                contentDescription = "Показывать: $title"
                setOnCheckedChangeListener { _, checked ->
                    visibleFilters[key] = checked
                    mapView?.evaluateJavascript("window.setLayer('$key',$checked)", null)
                }
            }
            row.addView(toggle)
            row.setOnClickListener { toggle.isChecked = !toggle.isChecked }
            list.addView(row, LinearLayout.LayoutParams(-1, 48.dp()))
        }
        content.addView(ScrollView(this).apply { addView(list) })
        dialog.setContentView(content)
        dialog.show()
    }

    private fun searchRoom(query: String) {
        if (!detailed) {
            detailed = true
            focusedBuilding = null
            pendingSearch = query
            showScreen()
        } else {
            findRoomInMap(query)
        }
    }

    private fun findRoomInMap(query: String) {
        mapView?.evaluateJavascript("window.findRoom(${JSONObject.quote(query)})") { result ->
            val parts = result?.trim('"')?.split('|') ?: emptyList()
            if (parts.size != 2) {
                Toast.makeText(this, "Кабинет не найден", Toast.LENGTH_SHORT).show()
                return@evaluateJavascript
            }
            val building = parts[0].toIntOrNull()
            val floor = parts[1].toIntOrNull()
            if (building != null && floor != null) {
                focusedBuilding = building
                selectedFloor = floor
                visibleFilters["Rooms"] = true
                refreshControls()
            }
        }
    }

    private fun quickAccess(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(label("БЫСТРЫЙ ВЫБОР КОРПУСА", 11f, muted, bold = true).apply {
            letterSpacing = 0.12f
            setPadding(3.dp(), 0, 0, 9.dp())
        })
        val scroll = HorizontalScrollView(this@MainActivity).apply {
            isHorizontalScrollBarEnabled = false
        }
        val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("Все", selected = false) {
            focusedBuilding = null
            mapView?.evaluateJavascript("window.resetMap()", null)
        }, LinearLayout.LayoutParams(-2, 48.dp()).apply { rightMargin = 8.dp() })
        buildingIds.forEach { id ->
            row.addView(button("$id", selected = false) {
                focusedBuilding = id
                mapView?.evaluateJavascript("window.focusBuilding($id)", null)
            }, LinearLayout.LayoutParams(-2, 48.dp()).apply { rightMargin = 8.dp() })
        }
        scroll.addView(row)
        addView(scroll)
    }

    private fun createMapView(): WebView {
        val svg = if (detailed) detailedSvg else overviewSvg
        val firstFloor = selectedFloor
        val focus = focusedBuilding ?: 0
        val filterState = filters.keys.joinToString(",") { "\"$it\":${visibleFilters[it] != false}" }
        val color = String.format("#%06X", surface and 0xFFFFFF)
        val gridColor = getColor(R.color.ui_map_grid)
        val grid = "rgba(${Color.red(gridColor)},${Color.green(gridColor)}," +
            "${Color.blue(gridColor)},${Color.alpha(gridColor) / 255.0})"
        val html = """<!doctype html><html><head>
          <meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no">
          <style>html,body{width:100%;height:100%;margin:0;overflow:hidden;background:$color}
          body{display:flex;align-items:center;justify-content:center;
            background-image:linear-gradient($grid 1px,transparent 1px),
            linear-gradient(90deg,$grid 1px,transparent 1px);background-size:24px 24px}
          svg{width:100%;height:100%;display:block;touch-action:none}
          [id^="building-"],[id^="building-label-"]{cursor:pointer}
          *{-webkit-tap-highlight-color:transparent;-webkit-touch-callout:none;user-select:none}</style>
          </head><body>$svg<script>
          (function(){
            const map=document.querySelector('svg');
            const initial=map.viewBox.baseVal;
            const full={x:initial.x,y:initial.y,w:initial.width,h:initial.height};
            const detailed=${if (detailed) "true" else "false"};
            const floorGroups=[...map.querySelectorAll('g[id]')].filter(g=>/^\d+-\d+$/.test(g.id));
            const initialFilters={$filterState};
            let activeFloor=$firstFloor;
            function changeView(x,y,w,h){ map.setAttribute('viewBox',[x,y,w,h].join(' ')); }
            let highlight=null;
            function clearHighlight(){if(highlight){highlight.remove();highlight=null;}}
            window.resetMap=function(){clearHighlight();changeView(full.x,full.y,full.w,full.h);};
            window.selectFloor=function(floor){
              activeFloor=Number(floor);
              clearHighlight();
              floorGroups.forEach(g=>{g.style.display=g.id.endsWith('-'+activeFloor)?'':'none'});
            };
            window.setLayer=function(kind,visible){
              map.querySelectorAll('g[id]').forEach(g=>{
                if(g.id===kind || g.id.startsWith(kind+'_'))g.style.display=visible?'':'none';
              });
            };
            function bounds(element){
              const box=element.getBBox();
              const m=map.getCTM().inverse().multiply(element.getCTM());
              const pts=[[box.x,box.y],[box.x+box.width,box.y],
                [box.x,box.y+box.height],[box.x+box.width,box.y+box.height]].map(([x,y])=>{
                const p=map.createSVGPoint();p.x=x;p.y=y;return p.matrixTransform(m);
              });
              const xs=pts.map(p=>p.x),ys=pts.map(p=>p.y);
              const x=Math.min(...xs),y=Math.min(...ys);
              return {x,y,width:Math.max(...xs)-x,height:Math.max(...ys)-y};
            }
            function centerOn(b,w,h){
              const ratio=map.clientWidth/Math.max(1,map.clientHeight);
              if(w/h<ratio)w=h*ratio;else h=w/ratio;
              const bounded=limitWidth(w,h);
              h*=bounded/w;w=bounded;
              changeView(b.x+b.width/2-w/2,b.y+b.height/2-h/2,w,h);
            }
            // Show at least a small area around an ~80-unit map icon at maximum zoom.
            // At minimum zoom the complete campus stays close to its original size.
            function limitWidth(w,h){
              return Math.min(full.w*1.08,Math.max(w,220,160*w/h));
            }
            window.focusBuilding=function(id){
              id=Number(id);
              const shape=map.querySelector('[id="building-'+id+'"]');
              const plan=map.querySelector('g[id="'+id+'-'+activeFloor+'"]');
              const target=(detailed && plan) || shape;
              if(!target)return;
              clearHighlight();
              const b=bounds(target);
              if(!b.width || !b.height)return;
              // Always use the destination's preset scale, regardless of manual zoom.
              centerOn(b,b.width*1.16,b.height*1.16);
            };
            window.findRoom=function(query){
              const normalize=s=>String(s).toLowerCase().replace(/\s+/g,'')
                .replace(/[–—]/g,'-').replace(/a/g,'а');
              const needle=normalize(query);
              const room=[...map.querySelectorAll('text[id^="room-"]')].find(el=>{
                const name=normalize(el.textContent);
                return name===needle || name.split('-').slice(1).join('-')===needle;
              });
              if(!room)return '';
              let parent=room.parentElement,match=null;
              while(parent && parent!==map){
                match=/^(\d+)-(\d+)$/.exec(parent.id||'');
                if(match)break;
                parent=parent.parentElement;
              }
              if(!match)return '';
              window.selectFloor(Number(match[2]));
              window.setLayer('Rooms',true);
              const b=bounds(room);
              centerOn(b,Math.max(500,b.width*10),Math.max(380,b.height*14));
              highlight=document.createElementNS('http://www.w3.org/2000/svg','circle');
              highlight.setAttribute('cx',b.x+b.width/2);
              highlight.setAttribute('cy',b.y+b.height/2);
              highlight.setAttribute('r',Math.max(35,Math.max(b.width,b.height)*1.5));
              highlight.setAttribute('fill','none');
              highlight.setAttribute('stroke','#FF4268');
              highlight.setAttribute('stroke-width','10');
              highlight.setAttribute('pointer-events','none');
              map.appendChild(highlight);
              return match[1]+'|'+match[2];
            };
            function at(x,y){
              const p=map.createSVGPoint();p.x=x;p.y=y;
              return p.matrixTransform(map.getScreenCTM().inverse());
            }
            function pan(x1,y1,x2,y2){
              const p=at(x1,y1),q=at(x2,y2),b=map.viewBox.baseVal;
              changeView(b.x+p.x-q.x,b.y+p.y-q.y,b.width,b.height);
            }
            function zoomAt(factor,x,y){
              const p=at(x,y),b=map.viewBox.baseVal;
              const w=limitWidth(b.width*factor,b.height*factor);
              const h=b.height*w/b.width;
              const scale=w/b.width;
              changeView(p.x-(p.x-b.x)*scale,p.y-(p.y-b.y)*scale,w,h);
            }
            const pointers=new Map();let dragged=false;
            map.addEventListener('pointerdown',e=>{
              pointers.set(e.pointerId,{x:e.clientX,y:e.clientY});
              if(pointers.size>1)dragged=true;
            });
            map.addEventListener('pointermove',e=>{
              if(!pointers.has(e.pointerId))return;
              const old=pointers.get(e.pointerId),next={x:e.clientX,y:e.clientY};
              if(Math.hypot(next.x-old.x,next.y-old.y)>2)dragged=true;
              if(pointers.size===1){pan(old.x,old.y,next.x,next.y)}
              else if(pointers.size===2){
                const other=[...pointers.entries()].find(([id])=>id!==e.pointerId)[1];
                const oldDist=Math.hypot(old.x-other.x,old.y-other.y);
                const newDist=Math.hypot(next.x-other.x,next.y-other.y);
                const midOld={x:(old.x+other.x)/2,y:(old.y+other.y)/2};
                const midNew={x:(next.x+other.x)/2,y:(next.y+other.y)/2};
                if(newDist>1)zoomAt(oldDist/newDist,midOld.x,midOld.y);
                pan(midOld.x,midOld.y,midNew.x,midNew.y);
              }
              pointers.set(e.pointerId,next);
            });
            function endPointer(e){pointers.delete(e.pointerId);setTimeout(()=>{dragged=false},0)}
            map.addEventListener('pointerup',endPointer);
            map.addEventListener('pointercancel',endPointer);
            map.addEventListener('click',event=>{
              if(dragged){dragged=false;event.preventDefault();return;}
              let node=event.target;
              while(node && node!==map){
                const id=node.id||'';
                const match=/^building-(\d+)$/.exec(id)||
                    /^building-label-(\d+)$/.exec(id)||
                    (detailed?/^(\d+)-\d+$/.exec(id):null);
                if(match){
                  event.preventDefault();
                  location.href='app://focus?id='+match[1];
                  return;
                }
                node=node.parentElement;
              }
            });
            window.selectFloor($firstFloor);
            Object.entries(initialFilters).forEach(([kind,visible])=>window.setLayer(kind,visible));
            if($focus)requestAnimationFrame(()=>window.focusBuilding($focus));
          })();
          </script></body></html>"""
        return WebView(this).apply {
            setBackgroundColor(surface)
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.blockNetworkLoads = true
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            isLongClickable = false
            setOnLongClickListener { true }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    pendingSearch?.let { query ->
                        pendingSearch = null
                        view.post { findRoomInMap(query) }
                    }
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    if (uri.scheme == "app" && uri.host == "focus") {
                        val id = uri.getQueryParameter("id")?.toIntOrNull()
                        if (id != null && id in buildingIds) {
                            focusedBuilding = id
                            view.post { view.evaluateJavascript("window.focusBuilding($id)", null) }
                        }
                    }
                    return true
                }
            }
            loadDataWithBaseURL("https://appassets.androidplatform.net/", html,
                "text/html", "UTF-8", null)
        }
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
        }

    private fun button(value: String, selected: Boolean, click: () -> Unit): TextView =
        TextView(this).apply {
            text = value
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(14.dp(), 0, 14.dp(), 0)
            setTextColor(if (selected) onAccent else ink)
            background = RippleDrawable(ColorStateList.valueOf(accent and 0x55FFFFFF),
                shape(if (selected) accent else surface, 14, if (selected) null else border), null)
            isClickable = true
            isFocusable = true
            setOnClickListener { click() }
        }

    private fun shape(fill: Int, radius: Int, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius.dp().toFloat()
        setColor(fill)
        if (stroke != null) setStroke(1.dp(), stroke)
    }

    private fun Int.dp() = (this * resources.displayMetrics.density + 0.5f).toInt()
}
