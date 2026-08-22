package com.example.html5viewer

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URLConnection

/**
 * 端末内のローカルHTMLファイルを開いて表示するビュアー。
 *
 * 機能:
 * - 検索(ファイル名で絞り込み)
 * - お気に入り登録(フォルダをまたいでブックマーク)
 * - 複数フォルダの追加・切り替え
 * - ダウンロード/ドキュメント/ピクチャへのクイックアクセス(SAFピッカーの初期位置ヒント)
 * - ダークモード切り替え(アプリUI + WebViewコンテンツの自動ダーク化)
 * - フォールド端末/大画面での2ペインレイアウト(一覧+WebViewを同時表示、
 *   折りたたみ⇔展開時もWebViewの表示内容を保持したまま切り替え)
 */
class MainActivity : AppCompatActivity() {

    private data class FolderEntry(val uri: String, val name: String)
    private data class FavoriteEntry(val uri: String, val folderName: String, val path: String)
    private data class DisplayItem(
        val label: String,
        val folderUri: String,
        val folderName: String,
        val relativePath: String
    )

    private lateinit var prefs: SharedPreferences

    private lateinit var listContainer: LinearLayout
    private lateinit var webViewContainer: View
    private lateinit var selectFolderButton: Button
    private lateinit var shortcutDownloadsButton: Button
    private lateinit var shortcutDocumentsButton: Button
    private lateinit var shortcutPicturesButton: Button
    private lateinit var removableStorageLabel: TextView
    private lateinit var removableStorageScroll: View
    private lateinit var removableStorageContainer: LinearLayout
    private lateinit var folderSpinner: Spinner
    private lateinit var darkModeToggleButton: Button
    private lateinit var searchEditText: EditText
    private lateinit var favoritesOnlyCheckbox: CheckBox
    private lateinit var fileListView: ListView

    private lateinit var webView: WebView
    private lateinit var loadProgress: ProgressBar

    private var treeRoot: DocumentFile? = null
    private var currentFolderUri: String? = null
    private var currentFolderName: String? = null
    private val htmlFiles = mutableListOf<String>()
    private lateinit var folderScanProgress: ProgressBar
    // フォルダを連続で切り替えた際に、古いスキャン結果が後から画面に反映されるのを防ぐための世代カウンタ
    private var scanRequestId = 0

    private val folders = mutableListOf<FolderEntry>()
    private val favorites = mutableListOf<FavoriteEntry>()

    private var searchQuery: String = ""
    private var showFavoritesOnly: Boolean = false
    private var spinnerUpdating = false

    private var isTwoPane = false
    private var hasOpenedFile = false

    private lateinit var listAdapter: HtmlFileAdapter

    private val openTreeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                addFolder(uri)
            }
        }

    // SDカード/USBメモリなど、特定のストレージボリュームを起点にフォルダ選択画面を開くための起動用
    private val openTreeForVolumeLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri = result.data?.data
            if (result.resultCode == RESULT_OK && uri != null) {
                addFolder(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyStoredNightMode()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        listContainer = findViewById(R.id.list_container)
        webViewContainer = findViewById(R.id.webview_container)
        selectFolderButton = findViewById(R.id.select_folder_button)
        shortcutDownloadsButton = findViewById(R.id.shortcut_downloads_button)
        shortcutDocumentsButton = findViewById(R.id.shortcut_documents_button)
        shortcutPicturesButton = findViewById(R.id.shortcut_pictures_button)
        removableStorageLabel = findViewById(R.id.removable_storage_label)
        removableStorageScroll = findViewById(R.id.removable_storage_scroll)
        removableStorageContainer = findViewById(R.id.removable_storage_container)
        folderSpinner = findViewById(R.id.folder_spinner)
        darkModeToggleButton = findViewById(R.id.dark_mode_toggle_button)
        searchEditText = findViewById(R.id.search_edit_text)
        favoritesOnlyCheckbox = findViewById(R.id.favorites_only_checkbox)
        fileListView = findViewById(R.id.file_list_view)
        folderScanProgress = findViewById(R.id.folder_scan_progress)
        webView = findViewById(R.id.web_view)
        loadProgress = findViewById(R.id.load_progress)

        setupWebView()
        loadFoldersFromPrefs()
        loadFavoritesFromPrefs()

        listAdapter = HtmlFileAdapter()
        fileListView.adapter = listAdapter

        selectFolderButton.setOnClickListener {
            openTreeLauncher.launch(null)
        }
        shortcutDownloadsButton.setOnClickListener {
            openTreeLauncher.launch(buildInitialUriHint("Download"))
        }
        shortcutDocumentsButton.setOnClickListener {
            openTreeLauncher.launch(buildInitialUriHint("Documents"))
        }
        shortcutPicturesButton.setOnClickListener {
            openTreeLauncher.launch(buildInitialUriHint("Pictures"))
        }

        darkModeToggleButton.setOnClickListener { cycleNightMode() }

        searchEditText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString() ?: ""
                refreshList()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        favoritesOnlyCheckbox.setOnCheckedChangeListener { _, isChecked ->
            showFavoritesOnly = isChecked
            refreshList()
        }

        fileListView.setOnItemClickListener { _, _, position, _ ->
            val item = listAdapter.getItem(position) as DisplayItem
            openDisplayItem(item)
        }

        setupFolderSpinner()
        refreshRemovableStorageButtons()
        applyPaneMode()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    webView.canGoBack() -> webView.goBack()
                    !isTwoPane && webViewContainer.visibility == View.VISIBLE -> showList()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        // 前回開いていたフォルダを復元
        val lastUri = prefs.getString(KEY_CURRENT_FOLDER_URI, null)
        val lastEntry = folders.find { it.uri == lastUri } ?: folders.firstOrNull()
        if (lastEntry != null) {
            switchToFolder(lastEntry)
        } else {
            showList()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // フォールド端末の折りたたみ⇔展開などによる画面サイズ変化をActivity再生成なしで反映する
        applyPaneMode()
    }

    override fun onResume() {
        super.onResume()
        // アプリ起動後にSDカード/USBメモリを挿し直した場合にも反応させる
        refreshRemovableStorageButtons()
    }

    // ---------- 2ペイン(フォールド/大画面)レイアウト ----------

    private fun applyPaneMode() {
        val twoPane = resources.getBoolean(R.bool.two_pane)
        isTwoPane = twoPane

        val listParams = listContainer.layoutParams as LinearLayout.LayoutParams
        val webParams = webViewContainer.layoutParams as LinearLayout.LayoutParams

        if (twoPane) {
            listParams.width = resources.getDimensionPixelSize(R.dimen.list_pane_width)
            listParams.weight = 0f
            webParams.width = 0
            webParams.weight = 1f
            listContainer.layoutParams = listParams
            webViewContainer.layoutParams = webParams

            listContainer.visibility = View.VISIBLE
            webViewContainer.visibility = View.VISIBLE
        } else {
            listParams.width = LinearLayout.LayoutParams.MATCH_PARENT
            listParams.weight = 1f
            webParams.width = LinearLayout.LayoutParams.MATCH_PARENT
            webParams.weight = 0f
            listContainer.layoutParams = listParams
            webViewContainer.layoutParams = webParams

            if (hasOpenedFile) {
                listContainer.visibility = View.GONE
                webViewContainer.visibility = View.VISIBLE
            } else {
                listContainer.visibility = View.VISIBLE
                webViewContainer.visibility = View.GONE
            }
        }
    }

    // ---------- ダークモード ----------

    private fun applyStoredNightMode() {
        val p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val mode = p.getInt(KEY_NIGHT_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    private fun cycleNightMode() {
        val current = prefs.getInt(KEY_NIGHT_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        val next = when (current) {
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM -> AppCompatDelegate.MODE_NIGHT_NO
            AppCompatDelegate.MODE_NIGHT_NO -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        prefs.edit().putInt(KEY_NIGHT_MODE, next).apply()
        val label = when (next) {
            AppCompatDelegate.MODE_NIGHT_NO -> R.string.theme_mode_light
            AppCompatDelegate.MODE_NIGHT_YES -> R.string.theme_mode_dark
            else -> R.string.theme_mode_system
        }
        Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
        AppCompatDelegate.setDefaultNightMode(next)
        // AndroidManifestのconfigChangesにuiModeを含めているため、
        // setDefaultNightMode()だけではActivityが自動再生成されず、
        // テーマ色やWebViewのダーク化設定(isNightModeActive()はonCreate時のみ判定)が
        // 反映されない。明示的にrecreate()して確実に反映させる。
        recreate()
    }

    private fun isNightModeActive(): Boolean {
        val uiMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return uiMode == Configuration.UI_MODE_NIGHT_YES
    }

    // ---------- WebView ----------

    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mediaPlaybackRequiresUserGesture = false

        // ページ自体がダークモードに対応していない場合でも、可能なら自動でダーク表示にする
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, isNightModeActive())
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                loadProgress.progress = newProgress
                loadProgress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url
                return if (url.scheme == VIRTUAL_SCHEME && url.host == VIRTUAL_HOST) {
                    serveLocalResource(url.path ?: "")
                } else {
                    null
                }
            }
        }
    }

    // ---------- クイックアクセス(外部ストレージのショートカット) ----------

    /**
     * SAFの ACTION_OPEN_DOCUMENT_TREE ピッカーを、指定した共有フォルダ名の位置から
     * 開始させるためのヒントURIを作る(EXTRA_INITIAL_URIとして渡される)。
     * 最終的な保存先へのアクセス許可は、ユーザーがピッカーで
     * 「このフォルダを使用」を選ぶことで確定する。
     */
    private fun buildInitialUriHint(folderName: String): Uri {
        return DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            "primary:$folderName"
        )
    }

    /**
     * 接続中のSDカード/USBメモリ(リムーバブルなストレージボリューム)を検出し、
     * それぞれを起点にフォルダ選択画面を開けるボタンを動的に生成する。
     * StorageVolume#createOpenDocumentTreeIntent() を使うことで、
     * primary以外のボリューム(SDカードやUSB OTGドライブ)でも正しい初期位置を指定できる。
     */
    private fun refreshRemovableStorageButtons() {
        removableStorageContainer.removeAllViews()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // StorageVolume#createOpenDocumentTreeIntent() はAPI 26以上が必要
            removableStorageLabel.visibility = View.GONE
            removableStorageScroll.visibility = View.GONE
            return
        }

        val storageManager = getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        val removableVolumes = storageManager?.storageVolumes?.filter { it.isRemovable } ?: emptyList()

        if (removableVolumes.isEmpty()) {
            removableStorageLabel.visibility = View.GONE
            removableStorageScroll.visibility = View.GONE
            return
        }

        removableStorageLabel.visibility = View.VISIBLE
        removableStorageScroll.visibility = View.VISIBLE

        for ((index, volume) in removableVolumes.withIndex()) {
            val button = Button(this, null, android.R.attr.buttonStyleSmall)
            button.text = volume.getDescription(this) ?: getString(R.string.removable_storage_generic)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            if (index > 0) {
                params.marginStart = (6 * resources.displayMetrics.density).toInt()
            }
            button.layoutParams = params
            button.setOnClickListener {
                val intent = volume.createOpenDocumentTreeIntent()
                openTreeForVolumeLauncher.launch(intent)
            }
            removableStorageContainer.addView(button)
        }
    }

    // ---------- フォルダ管理 ----------

    private fun loadFoldersFromPrefs() {
        folders.clear()
        val json = prefs.getString(KEY_FOLDERS, null) ?: return
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            folders.add(FolderEntry(o.getString("uri"), o.getString("name")))
        }
    }

    private fun saveFoldersToPrefs() {
        val arr = JSONArray()
        for (f in folders) {
            val o = JSONObject()
            o.put("uri", f.uri)
            o.put("name", f.name)
            arr.put(o)
        }
        prefs.edit().putString(KEY_FOLDERS, arr.toString()).apply()
    }

    private fun setupFolderSpinner() {
        refreshFolderSpinnerAdapter()
        folderSpinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    if (spinnerUpdating) return
                    if (position in folders.indices) {
                        switchToFolder(folders[position])
                    }
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
    }

    private fun refreshFolderSpinnerAdapter() {
        val names = folders.map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerUpdating = true
        folderSpinner.adapter = adapter
        val idx = folders.indexOfFirst { it.uri == currentFolderUri }
        if (idx >= 0) folderSpinner.setSelection(idx)
        spinnerUpdating = false
    }

    private fun addFolder(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            // 既に権限を保持している場合などは無視
        }

        val root = DocumentFile.fromTreeUri(this, uri)
        if (root == null || !root.exists()) {
            Toast.makeText(this, R.string.open_folder_failed, Toast.LENGTH_SHORT).show()
            return
        }

        val entry = FolderEntry(uri.toString(), root.name ?: uri.toString())
        if (folders.none { it.uri == entry.uri }) {
            folders.add(entry)
            saveFoldersToPrefs()
        }
        refreshFolderSpinnerAdapter()
        switchToFolder(entry)
    }

    private fun switchToFolder(entry: FolderEntry) {
        val uri = Uri.parse(entry.uri)
        val root = DocumentFile.fromTreeUri(this, uri)
        if (root == null || !root.exists()) {
            Toast.makeText(this, R.string.folder_not_found, Toast.LENGTH_SHORT).show()
            return
        }

        treeRoot = root
        currentFolderUri = entry.uri
        currentFolderName = entry.name
        prefs.edit().putString(KEY_CURRENT_FOLDER_URI, entry.uri).apply()

        val idx = folders.indexOfFirst { it.uri == entry.uri }
        if (idx >= 0) {
            spinnerUpdating = true
            folderSpinner.setSelection(idx)
            spinnerUpdating = false
        }

        // SAF経由のフォルダ再帰スキャンはIPCを伴い、フォルダが大きいとメインスレッドを
        // ブロックしてANRの原因になり得るため、IOディスパッチャ上で実行する。
        val requestId = ++scanRequestId
        folderScanProgress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val scanned = withContext(Dispatchers.IO) {
                val result = mutableListOf<String>()
                scanForHtml(root, "", result)
                result.sort()
                result
            }

            // スキャン中に別のフォルダへ切り替えられていたら、この結果は破棄する
            if (requestId != scanRequestId) return@launch

            htmlFiles.clear()
            htmlFiles.addAll(scanned)
            folderScanProgress.visibility = View.GONE

            if (htmlFiles.isEmpty()) {
                Toast.makeText(this@MainActivity, R.string.no_html_found, Toast.LENGTH_LONG).show()
            }
            refreshList()
            showList()
        }
    }

    private fun scanForHtml(dir: DocumentFile, relativePath: String, out: MutableList<String>) {
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            val childRelPath = if (relativePath.isEmpty()) name else "$relativePath/$name"
            if (child.isDirectory) {
                scanForHtml(child, childRelPath, out)
            } else if (name.endsWith(".html", true) || name.endsWith(".htm", true)) {
                out.add(childRelPath)
            }
        }
    }

    // ---------- お気に入り ----------

    private fun loadFavoritesFromPrefs() {
        favorites.clear()
        val json = prefs.getString(KEY_FAVORITES, null) ?: return
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            favorites.add(FavoriteEntry(o.getString("uri"), o.getString("name"), o.getString("path")))
        }
    }

    private fun saveFavoritesToPrefs() {
        val arr = JSONArray()
        for (f in favorites) {
            val o = JSONObject()
            o.put("uri", f.uri)
            o.put("name", f.folderName)
            o.put("path", f.path)
            arr.put(o)
        }
        prefs.edit().putString(KEY_FAVORITES, arr.toString()).apply()
    }

    private fun isFavorite(folderUri: String, path: String): Boolean =
        favorites.any { it.uri == folderUri && it.path == path }

    private fun toggleFavorite(item: DisplayItem) {
        val existing = favorites.indexOfFirst { it.uri == item.folderUri && it.path == item.relativePath }
        if (existing >= 0) {
            favorites.removeAt(existing)
        } else {
            favorites.add(FavoriteEntry(item.folderUri, item.folderName, item.relativePath))
        }
        saveFavoritesToPrefs()
        refreshList()
    }

    // ---------- 一覧表示 ----------

    private fun refreshList() {
        val source: List<DisplayItem> = if (showFavoritesOnly) {
            favorites.map {
                DisplayItem(
                    label = "${it.folderName} / ${it.path}",
                    folderUri = it.uri,
                    folderName = it.folderName,
                    relativePath = it.path
                )
            }
        } else {
            val uri = currentFolderUri ?: ""
            val name = currentFolderName ?: ""
            htmlFiles.map { DisplayItem(label = it, folderUri = uri, folderName = name, relativePath = it) }
        }

        val filtered = if (searchQuery.isBlank()) {
            source
        } else {
            source.filter { it.label.contains(searchQuery, ignoreCase = true) }
        }

        listAdapter.items = filtered.sortedBy { it.label }
        listAdapter.notifyDataSetChanged()
    }

    private fun openDisplayItem(item: DisplayItem) {
        if (item.folderUri != currentFolderUri) {
            val target = folders.find { it.uri == item.folderUri }
            if (target == null) {
                Toast.makeText(this, R.string.folder_not_found, Toast.LENGTH_SHORT).show()
                return
            }
            switchToFolder(target)
        }
        loadHtmlFile(item.relativePath)
    }

    private fun loadHtmlFile(relativePath: String) {
        hasOpenedFile = true
        showWebView()
        val encodedPath = relativePath.split("/").joinToString("/") { Uri.encode(it) }
        webView.loadUrl("$VIRTUAL_SCHEME://$VIRTUAL_HOST/$encodedPath")
    }

    /** 仮想URLのパスをSAFツリー内のDocumentFileに解決し、InputStreamとして返す */
    private fun serveLocalResource(path: String): WebResourceResponse? {
        val root = treeRoot ?: return null
        val cleanPath = path.trimStart('/')
        if (cleanPath.isEmpty()) return notFoundResponse()

        var current: DocumentFile = root
        for (rawSegment in cleanPath.split("/")) {
            val segment = Uri.decode(rawSegment)
            current = current.findFile(segment) ?: return notFoundResponse()
        }

        return try {
            val stream = contentResolver.openInputStream(current.uri) ?: return notFoundResponse()
            val mime = current.type
                ?: URLConnection.guessContentTypeFromName(current.name)
                ?: "application/octet-stream"
            WebResourceResponse(mime, null, stream)
        } catch (e: Exception) {
            notFoundResponse()
        }
    }

    private fun notFoundResponse(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "UTF-8",
        404,
        "Not Found",
        emptyMap(),
        ByteArrayInputStream("Not Found".toByteArray())
    )

    private fun showList() {
        listContainer.visibility = View.VISIBLE
        if (!isTwoPane) {
            webViewContainer.visibility = View.GONE
            loadProgress.visibility = View.GONE
        }
    }

    private fun showWebView() {
        if (!isTwoPane) {
            listContainer.visibility = View.GONE
        }
        webViewContainer.visibility = View.VISIBLE
    }

    // ---------- アダプター ----------

    private inner class HtmlFileAdapter : BaseAdapter() {
        var items: List<DisplayItem> = emptyList()

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(this@MainActivity)
                .inflate(R.layout.list_item_html_file, parent, false)

            val item = items[position]
            val labelView = view.findViewById<TextView>(R.id.item_label)
            val starView = view.findViewById<TextView>(R.id.item_star)

            labelView.text = item.label
            val fav = isFavorite(item.folderUri, item.relativePath)
            starView.text = if (fav) "★" else "☆"
            starView.setOnClickListener { toggleFavorite(item) }

            return view
        }
    }

    companion object {
        private const val PREFS_NAME = "html5viewer_prefs"
        private const val KEY_FOLDERS = "folders"
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_CURRENT_FOLDER_URI = "current_folder_uri"
        private const val KEY_NIGHT_MODE = "night_mode"

        // shouldInterceptRequest を確実に発火させるため https スキームの仮想ホストを使う
        private const val VIRTUAL_SCHEME = "https"
        private const val VIRTUAL_HOST = "localfiles.htmlviewer.internal"
    }
}
