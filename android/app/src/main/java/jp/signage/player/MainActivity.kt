package jp.signage.player

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors

/** 設定画面 */
class MainActivity : Activity() {
    companion object {
        const val EXTRA_FROM_PLAYER = "fromPlayer"
        private const val REQ_FOLDER = 1
        private const val REQ_STORAGE = 2
        private const val REQ_NOTIFICATION = 3
        private const val DEFAULT_OFFICE = "130000" // 東京都
        /** 2分割の比率の選択肢（区画1 の %） */
        private val SPLIT_CHOICES = listOf(25, 30, 40, 50, 60, 70, 75)
    }

    private lateinit var prefs: Prefs
    private lateinit var folderText: TextView
    private lateinit var scanResult: TextView
    private lateinit var secondsEdit: EditText
    private lateinit var weatherIntervalEdit: EditText
    private lateinit var weatherSecondsEdit: EditText
    private lateinit var weatherStatus: TextView
    private lateinit var officeSpinner: Spinner
    private lateinit var areaSpinner: Spinner
    private var offices: List<Office> = emptyList()
    /** フォルダを選択中の区画 */
    private var pendingZone = 0
    private val zoneFolderTexts = mutableMapOf<Int, TextView>()
    private val zoneRows = mutableListOf<View>()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var scanGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        folderText = findViewById(R.id.folderText)
        scanResult = findViewById(R.id.scanResult)
        secondsEdit = findViewById(R.id.secondsEdit)
        secondsEdit.setText(prefs.imageSeconds.toString())

        findViewById<Button>(R.id.pickFolder).setOnClickListener { pickFolder(0) }
        findViewById<Button>(R.id.pickFolderDirect).setOnClickListener { pickFolderDirect(0) }
        findViewById<Button>(R.id.useAppFolder).setOnClickListener { useAppFolder(0) }
        setupLayout()
        findViewById<Button>(R.id.startButton).setOnClickListener { startPlayer() }

        bindSwitch(R.id.shuffleSwitch, prefs.shuffle) { prefs.shuffle = it }
        bindSwitch(R.id.recursiveSwitch, prefs.recursive) { prefs.recursive = it; refreshFolder() }
        bindSwitch(R.id.soundSwitch, prefs.videoSound) { prefs.videoSound = it }
        bindSwitch(R.id.autoStartSwitch, prefs.autoStart) {
            prefs.autoStart = it
            if (it) ensureOverlayPermission()
        }

        setupClock()
        setupWeather()
        setupAdmin()

        val group = findViewById<RadioGroup>(R.id.orientationGroup)
        group.check(
            when (prefs.orientation) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE -> R.id.orientLandscape
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT -> R.id.orientPortrait
                else -> R.id.orientAuto
            }
        )
        group.setOnCheckedChangeListener { _, id ->
            prefs.orientation = when (id) {
                R.id.orientLandscape -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                R.id.orientPortrait -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }

        // 自動再生が有効なら、ランチャーから起動したときはそのまま再生画面へ
        val fromPlayer = intent.getBooleanExtra(EXTRA_FROM_PLAYER, false)
        if (savedInstanceState == null && !fromPlayer && prefs.autoStart) {
            startPlayer()
        }
    }

    private fun bindSwitch(id: Int, value: Boolean, onChange: (Boolean) -> Unit): Switch =
        findViewById<Switch>(id).apply {
            isChecked = value
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }

    override fun onResume() {
        super.onResume()
        refreshFolder()
        if (offices.isEmpty()) loadOffices()
        updateAdminInfo() // 電池の最適化の設定から戻ってきたとき
    }

    override fun onPause() {
        super.onPause()
        saveSeconds()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdownNow()
    }

    private fun saveSeconds() {
        secondsEdit.text.toString().toIntOrNull()?.let { prefs.imageSeconds = it }
        secondsEdit.setText(prefs.imageSeconds.toString())
        weatherIntervalEdit.text.toString().toIntOrNull()?.let { prefs.weatherIntervalMin = it }
        weatherIntervalEdit.setText(prefs.weatherIntervalMin.toString())
        weatherSecondsEdit.text.toString().toIntOrNull()?.let { prefs.weatherSeconds = it }
        weatherSecondsEdit.setText(prefs.weatherSeconds.toString())
    }

    private fun pickFolder(zone: Int) {
        pendingZone = zone
        // 管理画面からのアップロード・削除のため、書き込みの許可も求める
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        prefs.zoneFolder(zone)?.takeIf { it.scheme == "content" }
            ?.let { intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_FOLDER)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "フォルダ選択画面が無いため、端末内を直接参照します", Toast.LENGTH_LONG).show()
            pickFolderDirect(zone)
        }
    }

    /**
     * アプリ専用のフォルダ（Android/data/…/files/zoneN）を使う。
     * 権限なしで読み書きできるので、管理画面からのアップロード先に向く（アプリを削除すると中身も消える）
     */
    private fun useAppFolder(zone: Int) {
        val dir = getExternalFilesDir("zone${zone + 1}") ?: File(filesDir, "zone${zone + 1}")
        dir.mkdirs()
        setZoneFolder(zone, Uri.fromFile(dir))
        Toast.makeText(this, "アプリ専用フォルダを設定しました。管理画面から画像・動画を追加できます", Toast.LENGTH_LONG).show()
    }

    private fun setZoneFolder(zone: Int, uri: Uri) {
        prefs.setZoneFolder(zone, uri)
        releaseUnusedTreePermissions()
        refreshFolder()
        refreshZoneFolders()
    }

    // ---------------------------------------------------------------- 画面分割

    private fun setupLayout() {
        bindRadio(
            R.id.layoutGroup, prefs.layout,
            mapOf(
                Prefs.LAYOUT_SINGLE to R.id.layoutSingle,
                Prefs.LAYOUT_LEFT_RIGHT to R.id.layoutLeftRight,
                Prefs.LAYOUT_TOP_BOTTOM to R.id.layoutTopBottom,
                Prefs.LAYOUT_MAIN_SIDE to R.id.layoutMainSide,
            ),
        ) {
            prefs.layout = it
            updateZoneRows()
        }

        setupSplit()
        val container = findViewById<LinearLayout>(R.id.zonesContainer)
        val density = resources.displayMetrics.density
        for (i in 0 until Prefs.MAX_ZONES) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, (8 * density).toInt(), 0, 0)
            }
            val label = TextView(this).apply {
                textSize = 13f
                setTextColor(getColor(R.color.brand))
                tag = "label"
            }
            row.addView(label)
            val spinner = Spinner(this).apply {
                adapter = adapter(listOf("フォルダの画像・動画", "天気予報"))
                setSelection(prefs.zoneType(i))
                minimumHeight = (48 * density).toInt()
            }
            row.addView(spinner)

            // 区画2・3 はここでフォルダを選ぶ（区画1 は下の「再生フォルダ」）
            val folderRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            if (i > 0) {
                val folderText = TextView(this).apply { textSize = 14f }
                zoneFolderTexts[i] = folderText
                folderRow.addView(folderText)
                val buttons = LinearLayout(this)
                buttons.addView(Button(this).apply {
                    text = "フォルダを選択"
                    setOnClickListener { pickFolder(i) }
                })
                buttons.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                    text = "直接選択"
                    setOnClickListener { pickFolderDirect(i) }
                })
                buttons.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                    text = "アプリ専用"
                    setOnClickListener { useAppFolder(i) }
                })
                folderRow.addView(buttons)
            } else {
                folderRow.addView(TextView(this).apply {
                    text = "フォルダは下の「再生フォルダ（区画1・メイン）」で選びます。動画の音声と天気予報の差し込みは、最初のフォルダ区画で行います。"
                    textSize = 12f
                    setTextColor(0xFF9E9E9E.toInt())
                })
            }
            row.addView(folderRow)
            fun syncFolderRow() {
                folderRow.visibility = if (prefs.zoneType(i) == Prefs.ZONE_FOLDER) View.VISIBLE else View.GONE
            }
            syncFolderRow()
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    prefs.setZoneType(i, position)
                    syncFolderRow()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
            zoneRows += row
            container.addView(row)
        }
        updateZoneRows()
        refreshZoneFolders()
    }

    /** 分割方法に合わせて、区画の名前と表示する区画数を切り替える */
    private fun updateZoneRows() {
        val count = Prefs.zoneCount(prefs.layout)
        val names = Prefs.zoneNames(prefs.layout)
        zoneRows.forEachIndexed { i, row ->
            row.visibility = if (i < count) View.VISIBLE else View.GONE
            row.findViewWithTag<TextView>("label")?.text = "区画${i + 1}（${names.getOrElse(i) { "" }}）の表示内容"
        }

        // 2分割のときだけ比率を選ぶ（例: 左 70% : 右 30%）
        val split = count == 2
        findViewById<View>(R.id.splitRow).visibility = if (split) View.VISIBLE else View.GONE
        if (split) {
            val spinner = findViewById<Spinner>(R.id.splitSpinner)
            spinner.adapter = adapter(SPLIT_CHOICES.map { "${names[0]} $it% : ${names[1]} ${100 - it}%" })
            val current = SPLIT_CHOICES.minByOrNull { kotlin.math.abs(it - prefs.splitPercent) } ?: 50
            spinner.setSelection(SPLIT_CHOICES.indexOf(current))
        }
    }

    private fun setupSplit() {
        findViewById<Spinner>(R.id.splitSpinner).onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                SPLIT_CHOICES.getOrNull(position)?.let { prefs.splitPercent = it }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun refreshZoneFolders() {
        zoneFolderTexts.forEach { (i, view) ->
            view.text = prefs.zoneFolder(i)?.let { "フォルダ: " + MediaScanner.describe(it) } ?: "フォルダ: 未選択"
        }
    }

    // ---------------------------------------------------------------- 端末内を直接参照

    private fun storagePermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    private fun hasStoragePermission() =
        storagePermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** システムのフォルダ選択画面を使わず、アプリ内のブラウザでフォルダを選ぶ */
    private fun pickFolderDirect(zone: Int) {
        pendingZone = zone
        if (!hasStoragePermission()) {
            requestPermissions(storagePermissions(), REQ_STORAGE)
            return
        }
        val current = prefs.zoneFolder(zone)?.takeIf { it.scheme == "file" }?.path?.let(::File)
        FolderBrowser(this) { dir -> setZoneFolder(zone, Uri.fromFile(dir)) }.show(current)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_STORAGE) return
        if (hasStoragePermission()) {
            pickFolderDirect(pendingZone)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("写真と動画へのアクセス")
            .setMessage(
                "端末内の画像・動画を読み込むには、写真と動画へのアクセスを「すべて許可」してください。\n\n" +
                    "許可の画面が出ない場合は、端末の設定 → アプリ → サイネージ → 権限 から許可できます。"
            )
            .setPositiveButton("アプリの設定を開く") { _, _ ->
                runCatching {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
            }
            .setNegativeButton("閉じる", null)
            .show()
    }

    /** どの区画でも使っていないフォルダの永続アクセス権を解放 */
    private fun releaseUnusedTreePermissions() {
        val used = prefs.allZoneFolders()
        contentResolver.persistedUriPermissions
            .filter { it.uri !in used }
            .forEach { contentResolver.releasePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    // ---------------------------------------------------------------- 管理画面

    private val adminListener: (String) -> Unit = { event ->
        when (event) {
            AdminServer.EVENT_SERVER -> updateAdminInfo()
            AdminServer.EVENT_CONTENT -> refreshFolder()
            AdminServer.EVENT_SETTINGS -> recreate() // 別の端末で設定が変わったので表示を作り直す
        }
    }

    private fun setupAdmin() {
        bindSwitch(R.id.adminSwitch, prefs.adminEnabled) {
            prefs.adminEnabled = it
            AdminService.sync(this)
            // 常駐中の通知を表示するため（Android 13 以降）
            if (it && !isIgnoringBatteryOptimizations()) requestIgnoreBatteryOptimizations()
            if (it && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATION)
            }
            updateAdminInfo()
        }
        findViewById<Button>(R.id.adminBattery).setOnClickListener { requestIgnoreBatteryOptimizations() }
        findViewById<Button>(R.id.adminOpenLocal).setOnClickListener {
            // この端末自身で開けるかを確かめる（開ければサーバーは動いている → 開けない端末側はネットワークの問題）
            val url = "http://127.0.0.1:${AdminServer.port}/"
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, "ブラウザがありません", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.adminPinReset).setOnClickListener {
            prefs.resetAdminPin()
            updateAdminInfo()
        }
        updateAdminInfo()
    }

    private fun updateAdminInfo() {
        val info = findViewById<TextView>(R.id.adminInfo)
        val running = prefs.adminEnabled && AdminServer.isRunning
        findViewById<View>(R.id.adminPinReset).visibility = if (prefs.adminEnabled) View.VISIBLE else View.GONE
        findViewById<View>(R.id.adminOpenLocal).visibility = if (running) View.VISIBLE else View.GONE
        // 省電力の対象のままだと、再生画面を出していないときに外から接続できない端末がある
        val batteryLimited = prefs.adminEnabled && !isIgnoringBatteryOptimizations()
        findViewById<View>(R.id.adminBatteryNote).visibility = if (batteryLimited) View.VISIBLE else View.GONE
        findViewById<View>(R.id.adminBattery).visibility = if (batteryLimited) View.VISIBLE else View.GONE
        info.text = when {
            !prefs.adminEnabled -> "ON にすると、PC・スマホのブラウザから画像・動画の追加や削除、設定の変更ができます。"
            !AdminServer.isRunning -> "起動中…"
            else -> {
                val urls = AdminServer.localAddresses().map { "http://$it:${AdminServer.port}/" }
                buildString {
                    append(if (urls.isEmpty()) "Wi-Fi・LAN に接続されていません" else "ブラウザで開くアドレス：\n" + urls.joinToString("\n"))
                    append("\nPIN：${prefs.adminPin}")
                    append("\n\n同じネットワーク内の端末からのみ操作できます。")
                    append("\n開けない場合：アドレス末尾の :${AdminServer.port} まで入力しているか、")
                    append("PC・スマホが同じWi-Fi（ゲストWi-Fiではない）につながっているか確認してください。")
                }
            }
        }
    }

    private fun isIgnoringBatteryOptimizations() =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    /** 「電池の最適化を無視しますか？」の確認を出す。出せない端末は一覧の設定画面を開く */
    private fun requestIgnoreBatteryOptimizations() {
        val intents = listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
        )
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (e: ActivityNotFoundException) {
                // 次の方法を試す
            }
        }
        Toast.makeText(this, "設定画面を開けませんでした。端末の設定 → アプリ → サイネージ → バッテリー で「制限なし」にしてください", Toast.LENGTH_LONG).show()
    }

    override fun onStart() {
        super.onStart()
        AdminServer.addListener(adminListener)
        AdminService.sync(this)
        updateAdminInfo()
    }

    override fun onStop() {
        super.onStop()
        AdminServer.removeListener(adminListener)
    }

    // ---------------------------------------------------------------- 天気予報

    private fun setupClock() {
        bindSwitch(R.id.clockSwitch, prefs.clockEnabled) { prefs.clockEnabled = it }
        bindRadio(
            R.id.clockPositionGroup, prefs.clockPosition,
            mapOf(
                Prefs.CLOCK_TOP_RIGHT to R.id.clockTopRight,
                Prefs.CLOCK_BOTTOM_RIGHT to R.id.clockBottomRight,
                Prefs.CLOCK_TOP_LEFT to R.id.clockTopLeft,
                Prefs.CLOCK_BOTTOM_LEFT to R.id.clockBottomLeft,
            ),
        ) { prefs.clockPosition = it }
        bindRadio(
            R.id.clockSizeGroup, prefs.clockSize,
            mapOf(0 to R.id.clockSmall, 1 to R.id.clockMedium, 2 to R.id.clockLarge),
        ) { prefs.clockSize = it }
    }

    /** 値 → ラジオボタンID の対応でラジオグループを設定と結びつける */
    private fun bindRadio(groupId: Int, value: Int, ids: Map<Int, Int>, onChange: (Int) -> Unit) {
        val group = findViewById<RadioGroup>(groupId)
        ids[value]?.let(group::check)
        group.setOnCheckedChangeListener { _, id ->
            ids.entries.firstOrNull { it.value == id }?.let { onChange(it.key) }
        }
    }

    private fun setupWeather() {
        bindSwitch(R.id.weatherSwitch, prefs.weatherEnabled) { prefs.weatherEnabled = it }
        bindSwitch(R.id.weatherTimeSeriesSwitch, prefs.weatherTimeSeries) { prefs.weatherTimeSeries = it }
        weatherIntervalEdit = findViewById(R.id.weatherIntervalEdit)
        weatherSecondsEdit = findViewById(R.id.weatherSecondsEdit)
        weatherStatus = findViewById(R.id.weatherStatus)
        officeSpinner = findViewById(R.id.officeSpinner)
        areaSpinner = findViewById(R.id.areaSpinner)
        weatherIntervalEdit.setText(prefs.weatherIntervalMin.toString())
        weatherSecondsEdit.setText(prefs.weatherSeconds.toString())

        officeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val office = offices.getOrNull(position) ?: return
                if (office.code != prefs.weatherOffice) {
                    prefs.weatherOffice = office.code
                    prefs.weatherCity = null
                    prefs.weatherArea = null
                }
                showCities(office)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        areaSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val office = offices.getOrNull(officeSpinner.selectedItemPosition) ?: return
                selectCity(office, position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        findViewById<Button>(R.id.weatherPreview).setOnClickListener {
            saveSeconds()
            if (prefs.weatherOffice == null) {
                Toast.makeText(this, "地方の一覧を取得できていません", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_WEATHER_NOW, true))
        }
    }

    private fun loadOffices() {
        weatherStatus.text = "地方の一覧を取得中…"
        io.execute {
            val list = Weather.offices(this)
            main.post {
                if (isDestroyed) return@post
                offices = list
                if (list.isEmpty()) {
                    weatherStatus.text = "地方の一覧を取得できません。インターネット接続を確認してください。"
                    return@post
                }
                weatherStatus.text = ""
                if (prefs.weatherOffice == null) prefs.weatherOffice = DEFAULT_OFFICE
                officeSpinner.adapter = adapter(list.map { it.name })
                officeSpinner.setSelection(list.indexOfFirst { it.code == prefs.weatherOffice }.coerceAtLeast(0))
            }
        }
    }

    /** 市区町村の一覧（「千代田区 — 東京地方」のように予報を出している地域も併記） */
    private fun showCities(office: Office) {
        if (office.cities.isEmpty()) {
            // 市区町村の情報が無い場合は地域で選ぶ
            areaSpinner.adapter = adapter(office.areas.map { it.second })
        } else {
            areaSpinner.adapter = adapter(office.cities.map { "${it.name}　— ${it.areaName}" })
        }
        val index = if (office.cities.isEmpty()) {
            office.areas.indexOfFirst { it.first == prefs.weatherArea }
        } else {
            office.cities.indexOfFirst { it.code == prefs.weatherCity }
                .takeIf { it >= 0 } ?: office.cities.indexOfFirst { it.areaCode == prefs.weatherArea }
        }.coerceAtLeast(0)
        areaSpinner.setSelection(index)
        selectCity(office, index)
    }

    private fun selectCity(office: Office, position: Int) {
        val city = office.cities.getOrNull(position)
        if (city != null) {
            prefs.weatherCity = city.code
            prefs.weatherCityName = city.name
            prefs.weatherArea = city.areaCode
            prefs.weatherAreaName = city.areaName
        } else {
            val area = office.areas.getOrNull(position) ?: return
            prefs.weatherCity = null
            prefs.weatherCityName = null
            prefs.weatherArea = area.first
            prefs.weatherAreaName = area.second
        }
    }

    private fun adapter(items: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_FOLDER || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        // 再起動後も読めるように権限を永続化し、使わなくなったフォルダの権限は解放
        FolderStore.takePermission(this, uri, data.flags)
        setZoneFolder(pendingZone, uri)
    }

    /** 選択中フォルダの表示と、中身の件数・一覧の更新 */
    private fun refreshFolder() {
        val uri = prefs.folderUri
        val gen = ++scanGeneration
        if (uri == null) {
            folderText.text = "未選択"
            scanResult.text = ""
            return
        }
        folderText.text = MediaScanner.describe(uri)
        scanResult.text = "読み込み中…"
        val recursive = prefs.recursive
        io.execute {
            val result = runCatching { MediaScanner.scan(contentResolver, uri, recursive) }
            main.post {
                if (gen != scanGeneration || isDestroyed) return@post
                val items = result.getOrNull()
                scanResult.text = when {
                    items == null -> "フォルダを読み込めません。もう一度選択してください。"
                    items.isEmpty() -> "再生できる画像・動画がありません"
                    else -> buildString {
                        append("画像 ${items.count { !it.isVideo }} 件 / 動画 ${items.count { it.isVideo }} 件\n")
                        items.take(100).forEachIndexed { i, it ->
                            append("${i + 1}. [${if (it.isVideo) "動画" else "画像"}] ${it.name}\n")
                        }
                        if (items.size > 100) append("… ほか ${items.size - 100} 件")
                    }
                }
            }
        }
    }

    private fun startPlayer() {
        saveSeconds()
        val missing = (0 until Prefs.zoneCount(prefs.layout))
            .filter { prefs.zoneType(it) == Prefs.ZONE_FOLDER && prefs.zoneFolder(it) == null }
        if (missing.isNotEmpty()) {
            val names = missing.joinToString("・") { "${it + 1}" }
            Toast.makeText(this, "区画$names のフォルダを選択してください", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    /** Android 10 以降、電源ON時に画面を自動で開くには「他のアプリの上に表示」の許可が必要 */
    private fun ensureOverlayPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(this)) return
        AlertDialog.Builder(this)
            .setTitle("権限が必要です")
            .setMessage("端末の電源ON時に自動で再生を始めるには「他のアプリの上に重ねて表示」を許可してください。\n\n次の画面でアプリ一覧が出た場合は「サイネージ」を選んで ON にしてください。\n（アプリ起動時の自動再生はこの権限なしでも動作します）")
            .setPositiveButton("設定を開く") { _, _ ->
                try {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    )
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(this, "設定画面を開けませんでした", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("あとで", null)
            .show()
    }
}
