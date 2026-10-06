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
import android.widget.ScrollView
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
        private const val REQ_NOTIFICATION = 3
        private const val DEFAULT_OFFICE = "130000" // 東京都
        /** 2分割の比率の選択肢（区画1 の %） */
        private val SPLIT_CHOICES = listOf(25, 30, 40, 50, 60, 70, 75)
        /** メイン＋サイドの比率の選択肢（メインの %・サイド1 の %） */
        private val MAIN_CHOICES = listOf(50, 60, 65, 70, 75, 80)
        private val SIDE_CHOICES = listOf(30, 40, 50, 60, 70)
        private val THIRD_CHOICES = listOf(20, 25, 30, 33, 34, 40, 50)

        /** 選択肢に、いま設定されている値（管理画面で 1% 刻みに決めた値など）も加える。入れないと、画面を開いたときに近い選択肢へ書き換わってしまう */
        private fun withCurrent(base: List<Int>, value: Int) = (base + value).distinct().sorted()

        private fun nearest(choices: List<Int>, value: Int) =
            choices.indices.minByOrNull { kotlin.math.abs(choices[it] - value) } ?: 0
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
    private val zoneRows = mutableListOf<View>()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var scanGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        shownSettingsVersion = prefs.settingsVersion

        folderText = findViewById(R.id.folderText)
        scanResult = findViewById(R.id.scanResult)
        secondsEdit = findViewById(R.id.secondsEdit)
        secondsEdit.setText(prefs.imageSeconds.toString())

        setupLayout()
        findViewById<Button>(R.id.startButton).setOnClickListener { startPlayer() }

        bindSwitch(R.id.shuffleSwitch, prefs.shuffle) { prefs.shuffle = it }
        setupFitMode()
        bindSwitch(R.id.soundSwitch, prefs.videoSound) { prefs.videoSound = it }
        bindSwitch(R.id.videoCompatSwitch, prefs.videoCompat) { prefs.videoCompat = it }
        bindSwitch(R.id.videoMultiSoftSwitch, prefs.videoMultiSoft) { prefs.videoMultiSoft = it }
        bindSwitch(R.id.autoStartSwitch, prefs.autoStart) {
            prefs.autoStart = it
            if (it) ensureOverlayPermission()
        }

        setupClock()
        setupWeather()
        setupAdmin()

        // テレビのリモコン操作向け：選択中の項目を枠で表示し、先頭から始める
        val scroll = findViewById<ScrollView>(R.id.settingsScroll)
        RemoteFocus.install(this, scroll)
        RemoteFocus.focusFirst(scroll, findViewById(R.id.orientAuto))

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

        // 画面を回す（テレビ専用）
        val rotateLabel = findViewById<View>(R.id.rotateLabel)
        val rotateGroup = findViewById<RadioGroup>(R.id.rotateGroup)
        if (PlayerActivity.isTv(this)) {
            rotateGroup.check(when (prefs.screenRotate) { 1 -> R.id.rotateRight; 2 -> R.id.rotateLeft; else -> R.id.rotateNone })
            rotateGroup.setOnCheckedChangeListener { _, id ->
                prefs.screenRotate = when (id) { R.id.rotateRight -> 1; R.id.rotateLeft -> 2; else -> 0 }
            }
        } else {
            rotateLabel.visibility = View.GONE
            rotateGroup.visibility = View.GONE
            findViewById<View>(R.id.rotateNote).visibility = View.GONE
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
        offerPendingUpdate()
    }

    /** 管理画面から送られた更新の確認画面を、自動で開けなかったとき、アプリを開いたこの画面から進められるようにする */
    private fun offerPendingUpdate() {
        val confirm = AppUpdater.pendingConfirm ?: return
        AlertDialog.Builder(this)
            .setTitle("アプリの更新があります")
            .setMessage("管理画面から、新しい版のアプリが送られています。更新を進めますか？")
            .setPositiveButton("更新する") { _, _ ->
                AppUpdater.pendingConfirm = null
                try { startActivity(confirm) } catch (e: Exception) {
                    Toast.makeText(this, "確認画面を開けませんでした。管理画面からもう一度送ってください", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("あとで", null)
            .show()
    }

    override fun onPause() {
        super.onPause()
        if (!reloadingFromAdmin) saveSeconds()
    }

    /**
     * 管理画面で設定が変わったとき、画面を作り直して新しい値を表示する。
     * 作り直す前の入力欄・スイッチの状態を引き継ぐと、その復元で受け取った設定が古い値に戻ってしまうため、
     * 画面の状態は保存しない。
     */
    private var reloadingFromAdmin = false

    /** この画面が表示している設定の番号（管理画面で変わっていたら表示し直す） */
    private var shownSettingsVersion = -1

    private fun reloadFromAdmin() {
        reloadingFromAdmin = true
        recreate()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (reloadingFromAdmin) {
            outState.putBoolean("reloaded", true) // 自動再生を再度始めないよう、空でない状態として渡す
            return
        }
        super.onSaveInstanceState(outState)
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

    // ---------------------------------------------------------------- 画面分割

    private fun setupLayout() {
        bindRadio(
            R.id.layoutGroup, prefs.layout,
            mapOf(
                Prefs.LAYOUT_SINGLE to R.id.layoutSingle,
                Prefs.LAYOUT_LEFT_RIGHT to R.id.layoutLeftRight,
                Prefs.LAYOUT_TOP_BOTTOM to R.id.layoutTopBottom,
                Prefs.LAYOUT_MAIN_SIDE to R.id.layoutMainSide,
                Prefs.LAYOUT_COLUMNS3 to R.id.layoutColumns3,
                Prefs.LAYOUT_ROWS3 to R.id.layoutRows3,
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
                adapter = adapter(listOf("フォルダの画像・動画", "天気予報", "Web ページ", "ニュース（RSS）"))
                setSelection(prefs.zoneType(i))
                minimumHeight = (48 * density).toInt()
            }
            row.addView(spinner)

            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    prefs.setZoneType(i, position)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
            zoneRows += row
            container.addView(row)
        }
        updateZoneRows()
    }

    /** 分割方法に合わせて、区画の名前と表示する区画数を切り替える */
    private fun updateZoneRows() {
        val count = Prefs.zoneCount(prefs.layout)
        val names = Prefs.zoneNames(prefs.layout)
        zoneRows.forEachIndexed { i, row ->
            row.visibility = if (i < count) View.VISIBLE else View.GONE
            row.findViewWithTag<TextView>("label")?.text = "区画${i + 1}（${names.getOrElse(i) { "" }}）の表示内容"
        }

        // 分割しているときは比率を選ぶ（例: 左 70% : 右 30%、メイン 70% : サイド 30%）
        val equalThirds = prefs.layout == Prefs.LAYOUT_COLUMNS3 || prefs.layout == Prefs.LAYOUT_ROWS3
        findViewById<View>(R.id.splitRow).visibility = if (count >= 2) View.VISIBLE else View.GONE
        val spinner = findViewById<Spinner>(R.id.splitSpinner)
        val label = findViewById<TextView>(R.id.splitLabel)
        val threeZones = prefs.layout == Prefs.LAYOUT_MAIN_SIDE || equalThirds
        findViewById<View>(R.id.splitLabel2).visibility = if (threeZones) View.VISIBLE else View.GONE
        findViewById<View>(R.id.splitSpinner2).visibility = if (threeZones) View.VISIBLE else View.GONE
        if (equalThirds) {
            label.text = "区画1の大きさ"
            findViewById<TextView>(R.id.splitLabel2).text = "区画2の大きさ（区画3は残り）"
            thirdChoices = withCurrent(THIRD_CHOICES, prefs.splitA)
            spinner.adapter = adapter(thirdChoices.map { "${names[0]} $it%" })
            spinner.setSelection(thirdChoices.indexOf(prefs.splitA).coerceAtLeast(0))
            val second = findViewById<Spinner>(R.id.splitSpinner2)
            thirdChoices2 = withCurrent(THIRD_CHOICES, prefs.splitB)
            second.adapter = adapter(thirdChoices2.map { "${names[1]} $it%" })
            second.setSelection(thirdChoices2.indexOf(prefs.splitB).coerceAtLeast(0))
        } else if (count == 2) {
            label.text = "画面の比率（区画1 : 区画2）"
            splitChoices = withCurrent(SPLIT_CHOICES, prefs.splitPercent)
            spinner.adapter = adapter(splitChoices.map { "${names[0]} $it% : ${names[1]} ${100 - it}%" })
            spinner.setSelection(splitChoices.indexOf(prefs.splitPercent).coerceAtLeast(0))
        } else if (prefs.layout == Prefs.LAYOUT_MAIN_SIDE) {
            findViewById<TextView>(R.id.splitLabel2).text = "サイドの比率（区画2 : 区画3）"
            label.text = "メインとサイドの比率（区画1 : 区画2・3）"
            mainChoices = withCurrent(MAIN_CHOICES, prefs.mainPercent)
            spinner.adapter = adapter(mainChoices.map { "メイン $it% : サイド ${100 - it}%" })
            spinner.setSelection(mainChoices.indexOf(prefs.mainPercent).coerceAtLeast(0))
            val side = findViewById<Spinner>(R.id.splitSpinner2)
            sideChoices = withCurrent(SIDE_CHOICES, prefs.sidePercent)
            side.adapter = adapter(sideChoices.map { "サイド1 $it% : サイド2 ${100 - it}%" })
            side.setSelection(sideChoices.indexOf(prefs.sidePercent).coerceAtLeast(0))
        }
    }

    /** 表示方法の選択肢（表示順）と FitMode の値 */
    private val fitChoices = listOf(
        FitMode.AUTO to "おまかせ（なるべく余白を残さない）",
        FitMode.FIT_BLUR to "全体を表示し、余白をぼかした背景で埋める",
        FitMode.FILL to "画面いっぱい（はみ出す部分は切り取り）",
        FitMode.FIT to "全体を表示（余白は黒）",
    )

    private fun setupFitMode() {
        val spinner = findViewById<Spinner>(R.id.fitSpinner)
        spinner.adapter = adapter(fitChoices.map { it.second })
        spinner.setSelection(fitChoices.indexOfFirst { it.first == prefs.fitMode }.coerceAtLeast(0))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                fitChoices.getOrNull(position)?.let { prefs.fitMode = it.first }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private var splitChoices = SPLIT_CHOICES
    private var mainChoices = MAIN_CHOICES
    private var sideChoices = SIDE_CHOICES
    private var thirdChoices = THIRD_CHOICES
    private var thirdChoices2 = THIRD_CHOICES

    private fun isEqualThirds() = prefs.layout == Prefs.LAYOUT_COLUMNS3 || prefs.layout == Prefs.LAYOUT_ROWS3

    private fun setupSplit() {
        findViewById<Spinner>(R.id.splitSpinner).onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (prefs.layout == Prefs.LAYOUT_MAIN_SIDE) {
                    mainChoices.getOrNull(position)?.let { prefs.mainPercent = it }
                } else if (isEqualThirds()) {
                    thirdChoices.getOrNull(position)?.let { prefs.splitA = it }
                } else {
                    splitChoices.getOrNull(position)?.let { prefs.splitPercent = it }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        findViewById<Spinner>(R.id.splitSpinner2).onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isEqualThirds()) thirdChoices2.getOrNull(position)?.let { prefs.splitB = it }
                else sideChoices.getOrNull(position)?.let { prefs.sidePercent = it }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // ---------------------------------------------------------------- 管理画面

    private val adminListener: (String) -> Unit = { event ->
        when (event) {
            AdminServer.EVENT_SERVER -> updateAdminInfo()
            AdminServer.EVENT_CONTENT -> refreshFolder()
            AdminServer.EVENT_SETTINGS -> reloadFromAdmin() // 別の端末で設定が変わったので表示を作り直す
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
            val url = "${AdminServer.scheme}://127.0.0.1:${AdminServer.port}/"
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, "ブラウザがありません", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.groupButton).setOnClickListener { editGroupCode() }
        bindSwitch(R.id.updateSwitch, prefs.allowRemoteUpdate) {
            prefs.allowRemoteUpdate = it
            updateAdminInfo()
        }
        findViewById<Button>(R.id.updateUnknown).setOnClickListener {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                )
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, "設定画面を開けません。端末の設定から、このアプリに「不明なアプリのインストール」を許可してください", Toast.LENGTH_LONG).show()
            }
        }
        findViewById<Button>(R.id.adminPinReset).setOnClickListener { editPin() }
        findViewById<Button>(R.id.adminAccessReset).setOnClickListener {
            // 誰も操作できなくなったとき用。この端末の画面からだけ解除できる
            AlertDialog.Builder(this)
                .setTitle("操作できる端末の制限を解除")
                .setMessage("MAC アドレスによる制限を解除し、登録した端末の一覧を消します。PIN は変わりません。")
                .setPositiveButton("解除する") { _, _ ->
                    prefs.resetAccess()
                    updateAdminInfo()
                    Toast.makeText(this, "制限を解除しました", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("キャンセル", null)
                .show()
        }
        findViewById<Button>(R.id.adminName).setOnClickListener { editDeviceName() }
        updateAdminInfo()
    }

    private fun updateAdminInfo() {
        val info = findViewById<TextView>(R.id.adminInfo)
        // リモコンで操作する TV では、アドレスの文字にフォーカスが当たらないようにする（タッチ操作の端末は、コピーできるよう選択可能）
        if (!PlayerActivity.isTv(this)) {
            if (!info.isTextSelectable) info.setTextIsSelectable(true)
        } else {
            info.isFocusable = false
        }
        val running = prefs.adminEnabled && AdminServer.isRunning
        findViewById<View>(R.id.adminPinReset).visibility = if (prefs.adminEnabled) View.VISIBLE else View.GONE
        findViewById<View>(R.id.adminName).visibility = if (prefs.adminEnabled) View.VISIBLE else View.GONE
        findViewById<View>(R.id.groupButton).visibility = if (prefs.adminEnabled) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.groupButton).text = if (prefs.groupCode.isEmpty()) "グループコードを設定" else "グループコードを変更・解除"
        findViewById<View>(R.id.updateSwitch).visibility = if (prefs.adminEnabled) View.VISIBLE else View.GONE
        findViewById<View>(R.id.updateNote).visibility = if (prefs.adminEnabled) View.VISIBLE else View.GONE
        findViewById<View>(R.id.updateUnknown).visibility =
            if (prefs.adminEnabled && prefs.allowRemoteUpdate && !AppUpdater.canInstall(this)) View.VISIBLE else View.GONE
        findViewById<View>(R.id.adminAccessReset).visibility = if (prefs.macLock || prefs.allowedMacs.isNotEmpty()) View.VISIBLE else View.GONE
        findViewById<View>(R.id.adminOpenLocal).visibility = if (running) View.VISIBLE else View.GONE
        // 省電力の対象のままだと、再生画面を出していないときに外から接続できない端末がある
        val batteryLimited = prefs.adminEnabled && !isIgnoringBatteryOptimizations()
        findViewById<View>(R.id.adminBatteryNote).visibility = if (batteryLimited) View.VISIBLE else View.GONE
        findViewById<View>(R.id.adminBattery).visibility = if (batteryLimited) View.VISIBLE else View.GONE
        info.text = when {
            !prefs.adminEnabled -> "ON にすると、PC・スマホのブラウザから画像・動画の追加や削除、設定の変更ができます。"
            !AdminServer.isRunning -> "起動中…"
            else -> {
                val urls = AdminServer.localAddresses().map { "${AdminServer.scheme}://$it:${AdminServer.port}/" }
                buildString {
                    append(if (urls.isEmpty()) "Wi-Fi・LAN に接続されていません" else "ブラウザで開くアドレス：\n" + urls.joinToString("\n"))
                    append("\nPIN：${prefs.adminPin}")
                    append("\n通信：HTTP（暗号化なし）")
                    append("\n端末名：${prefs.deviceName}")
                    append("\nグループ：" + if (prefs.groupCode.isEmpty()) "未設定（PIN だけで操作できます）" else "設定済み（コードを知る端末だけ操作できます）")
                    if (prefs.macLock) append("\n操作できる端末：MAC アドレスで制限中（${prefs.allowedMacs.size} 台）")
                    append("\n\n同じネットワークのほかのサイネージ端末も、管理画面の「端末一覧」に自動で表示されます。")
                    append("\n\n同じネットワーク内の端末からのみ操作できます。")
                    append("\n開けない場合：アドレス末尾の :${AdminServer.port} まで入力しているか、")
                    append("PC・スマホが同じWi-Fi（ゲストWi-Fiではない）につながっているか確認してください。")
                }
            }
        }
    }

    /** グループ（組織）コードを決める・解除する。同じコードを持つ端末・管理画面だけが操作できる */
    private fun editGroupCode() {
        val input = EditText(this).apply {
            hint = if (prefs.groupCode.isEmpty()) "英数字と - _ の 8〜32 文字" else "新しいコード（空にすると解除）"
            setSingleLine()
            filters = arrayOf(android.text.InputFilter.LengthFilter(32))
        }
        AlertDialog.Builder(this)
            .setTitle("グループ（組織）コード")
            .setMessage("同じコードを持つ端末・管理画面だけが操作できます（PIN に加えて必要）。グループの全端末に、同じコードを設定してください。空にすると解除します。")
            .setView(input)
            .setPositiveButton("決定") { _, _ ->
                val code = input.text.toString().trim()
                if (code.isNotEmpty() && !GroupCode.valid(code)) {
                    Toast.makeText(this, "英数字と - _ だけの 8〜32 文字にしてください", Toast.LENGTH_LONG).show()
                } else {
                    prefs.groupCode = code
                    AdminServer.restart(this) // 見つけ合いの識別子を変える
                    updateAdminInfo()
                    Toast.makeText(this, if (code.isEmpty()) "グループコードを解除しました" else "グループコードを設定しました", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    /** PIN を自分で決める（複数台を同じ PIN にそろえると、管理画面で一度に操作しやすい） */
    private fun editPin() {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(android.text.InputFilter.LengthFilter(6))
            hint = "6桁の数字"
        }
        AlertDialog.Builder(this)
            .setTitle("PINを変更")
            .setMessage("複数台を使う場合は、すべての端末を同じ PIN にしておくと管理画面での操作が楽になります。")
            .setView(input)
            .setPositiveButton("設定") { _, _ ->
                if (prefs.setAdminPin(input.text.toString())) {
                    updateAdminInfo()
                } else {
                    Toast.makeText(this, "PIN は6桁の数字にしてください", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("ランダムに作る") { _, _ ->
                prefs.resetAdminPin()
                updateAdminInfo()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    /** 管理画面の端末一覧に出す名前（例: 入口、レジ横） */
    private fun editDeviceName() {
        val input = EditText(this).apply {
            setText(prefs.deviceName)
            filters = arrayOf(android.text.InputFilter.LengthFilter(40))
            setSelectAllOnFocus(true)
        }
        AlertDialog.Builder(this)
            .setTitle("端末名を変更")
            .setMessage("管理画面の端末一覧に表示される名前です（例：入口、レジ横）。")
            .setView(input)
            .setPositiveButton("設定") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    prefs.deviceName = name
                    if (AdminServer.isRunning) Peers.start(this, AdminServer.port) // 新しい名前で登録し直す
                    updateAdminInfo()
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
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
        // 再生画面などを表示している間に管理画面で設定が変わっていたら、表示し直す
        if (prefs.settingsVersion != shownSettingsVersion) {
            reloadFromAdmin()
            return
        }
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

    /** ライブラリ（アプリ専用の保存場所）の状況の表示 */
    private fun refreshFolder() {
        val gen = ++scanGeneration
        folderText.text = "アプリ専用の保存場所（管理画面から、登録・削除と、区画への配置を行います）"
        scanResult.text = "読み込み中…"
        io.execute {
            val result = runCatching { Library.summary(this) }
            val zones = runCatching { (0 until Prefs.zoneCount(prefs.layout)).map { Library.entries(this, it).size } }.getOrNull()
            main.post {
                if (gen != scanGeneration || isDestroyed) return@post
                val (total, unused) = result.getOrNull() ?: run { scanResult.text = "読み込めません"; return@post }
                scanResult.text = buildString {
                    append("ライブラリ：画像・動画 ${total} 件")
                    if (unused > 0) append("（どの区画にも配置していないもの ${unused} 件）")
                    zones?.forEachIndexed { i, n -> append("\n区画${i + 1}：${n} 件を配置") }
                }
            }
        }
    }

    private fun startPlayer() {
        saveSeconds()
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
