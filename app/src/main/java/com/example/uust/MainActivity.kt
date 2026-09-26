package com.example.uust

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private data class Floor(val name: String, val svg: String)
    private data class Building(val id: String, val name: String, val floors: List<Floor>)

    private lateinit var root: LinearLayout
    private var campusSvg = ""
    private var buildings = emptyList<Building>()
    private var screen = 0 // 0 — карта корпусов, 1 — этажи, 2 — план
    private var buildingId = ""
    private var floorName = ""
    private var currentWebView: WebView? = null
    private var loadError: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        try {
            campusSvg = readSvg("campus.svg")
            buildings = loadBuildings()
        } catch (error: Exception) {
            loadError = error.message ?: "Не удалось открыть SVG"
        }
        screen = savedInstanceState?.getInt("screen") ?: 0
        buildingId = savedInstanceState?.getString("building") ?: ""
        floorName = savedInstanceState?.getString("floor") ?: ""
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dp(), 12.dp(), 12.dp(), 12.dp())
            setBackgroundColor(0xFFF4F7F9.toInt())
        }
        setContentView(root)
        showScreen()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("screen", screen)
        outState.putString("building", buildingId)
        outState.putString("floor", floorName)
        super.onSaveInstanceState(outState)
    }

    private fun loadBuildings(): List<Building> {
        val ids = Regex("data-building=\"([A-Za-z0-9_]+)\"")
            .findAll(campusSvg).map { it.groupValues[1] }.distinct().toList()
        return ids.map { id ->
            val files = (assets.list("buildings/$id") ?: emptyArray())
                .filter { it.endsWith(".svg", ignoreCase = true) }
                .sortedWith(compareBy<String> { it.substringBefore('_').substringBefore('.').toIntOrNull() ?: 999 }
                    .thenBy { it })
            val floors = files.map { file ->
                Floor(floorTitle(file), readSvg("buildings/$id/$file"))
            }
            Building(id, buildingTitle(id), floors)
        }
    }

    private fun readSvg(path: String): String {
        val svg = assets.open(path).bufferedReader().use { it.readText() }
        require(Regex("<svg\\b", RegexOption.IGNORE_CASE).containsMatchIn(svg)) { "$path не является SVG" }
        return svg
    }

    private fun buildingTitle(id: String): String = if (id.startsWith("building_")) {
        "Корпус № ${id.removePrefix("building_").replace('_', ' ')}"
    } else id.replace('_', ' ')

    private fun floorTitle(file: String): String =
        "${file.substringBeforeLast('.').replace('_', ',')} этаж"

    private fun showScreen() {
        root.removeAllViews()
        currentWebView?.destroy()
        currentWebView = null

        if (loadError != null) {
            root.addView(label("Ошибка загрузки: $loadError", 18f))
            return
        }
        if (screen > 0) root.addView(button("← Назад") {
            screen--
            showScreen()
        })

        when (screen) {
            0 -> {
                root.addView(label("Корпуса", 25f))
                root.addView(label("Нажмите на корпус на карте", 15f))
                val web = campusView()
                currentWebView = web
                root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
            }
            1 -> {
                val building = buildings.firstOrNull { it.id == buildingId }
                    ?: return resetToCampus()
                root.addView(label(building.name, 25f))
                root.addView(label("Выберите этаж", 16f))
                val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                if (building.floors.isEmpty()) {
                    list.addView(label("Планы этажей пока не добавлены", 17f))
                }
                building.floors.forEach { floor ->
                    list.addView(button(floor.name) {
                        floorName = floor.name
                        screen = 2
                        showScreen()
                    })
                }
                root.addView(ScrollView(this).apply { addView(list) },
                    LinearLayout.LayoutParams(-1, 0, 1f))
            }
            else -> {
                val floor = buildings.firstOrNull { it.id == buildingId }
                    ?.floors?.firstOrNull { it.name == floorName }
                    ?: return resetToCampus()
                root.addView(label(floor.name, 22f))
                val web = floorView(floor.svg)
                currentWebView = web
                root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
            }
        }
    }

    private fun resetToCampus() {
        screen = 0
        showScreen()
    }

    private fun campusView(): WebView {
        val start = Regex("<svg\\b", RegexOption.IGNORE_CASE).find(campusSvg)!!.range.first
        val svg = campusSvg.substring(start)
        val html = """<!doctype html><html><head>
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <style>html,body{margin:0;width:100%;height:100%;background:#fff}svg{width:100%;height:100%}</style>
            </head><body>$svg<script>
            document.addEventListener('click',function(event){
              const node=event.target.closest('[data-building]');
              if(node) location.href='app://open?id='+encodeURIComponent(node.getAttribute('data-building'));
            });
            </script></body></html>"""
        return newWebView(javaScript = true).apply {
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    if (uri.scheme == "app" && uri.host == "open") {
                        val id = uri.getQueryParameter("id")
                        if (id != null && buildings.any { it.id == id }) {
                            view.post {
                                buildingId = id
                                screen = 1
                                showScreen()
                            }
                        }
                    }
                    return true
                }
            }
            loadDataWithBaseURL("https://appassets.androidplatform.net/", html,
                "text/html", "UTF-8", null)
        }
    }

    private fun floorView(svg: String): WebView {
        val encoded = Base64.encodeToString(svg.toByteArray(), Base64.NO_WRAP)
        val html = """<!doctype html><html><head>
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <style>html,body{margin:0;width:100%;height:100%;background:#fff}img{width:100%;height:100%;object-fit:contain}</style>
            </head><body><img src="data:image/svg+xml;base64,$encoded"></body></html>"""
        return newWebView(javaScript = false).apply {
            loadDataWithBaseURL("https://appassets.androidplatform.net/", html,
                "text/html", "UTF-8", null)
        }
    }

    private fun newWebView(javaScript: Boolean) = WebView(this).apply {
        setBackgroundColor(Color.WHITE)
        settings.javaScriptEnabled = javaScript
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.blockNetworkLoads = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
    }

    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(0xFF18364A.toInt())
        gravity = Gravity.CENTER_VERTICAL
        setPadding(6.dp(), 8.dp(), 6.dp(), 8.dp())
    }

    private fun button(value: String, click: () -> Unit) = Button(this).apply {
        text = value
        isAllCaps = false
        setOnClickListener { click() }
    }

    private fun Int.dp() = (this * resources.displayMetrics.density + 0.5f).toInt()
}
