package com.nakyung.a2dock.tally

import com.nakyung.a2dock.BuildInfo
import com.nakyung.a2dock.Gate
import com.nakyung.a2dock.Fb
import com.nakyung.a2dock.SplashView

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Size
import android.view.Gravity
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.Executors

data class Item(val bd: Int, val lot: String, val kg: Int, val qty: Double, val gw: Double) {
    val core: String get() = lot.takeLast(15)
    val prefix: String get() = lot.dropLast(15)
}

/** 발신번호(=PL) 하나 */
class Booking(val coa: String, var plName: String, var items: List<Item>) {
    val byBd: Map<Int, Item> get() = items.associateBy { it.bd }
}

class CInfo(val cntr: String, val seal: String, val tare: String)
class CList(val coa: String, val conts: List<CInfo>, val bk: String, val carrier: String, val size: String, val date: String, val src: String = "")

/** 5개 묶음 */
class Group(val coa: String, val bds: MutableList<Int>, var cntr: String = "")

class TallyActivity : ComponentActivity() {

    private val per = 5

    // ---------- 데이터 ----------
    private var bookings = mutableListOf<Booking>()
    private var clists = LinkedHashMap<String, CList>()
    private var ranges = LinkedHashMap<String, IntArray>()
    private var groups = mutableListOf<Group>()
    private val history = mutableListOf<String>()            // "coa:bd"
    private var hdr = HashMap<String, HashMap<String, String>>()
    private var rangeText = ""
    private var exported = HashMap<String, String>()   // coa -> 보낸 시각
    private var byCore: Map<String, List<Pair<Booking, Item>>> = emptyMap()

    // ---------- 스캔 ----------
    private var screen = "setup"
    private var scanning = false
    private var camera: Camera? = null
    private var analysis: ImageAnalysis? = null
    private var provider: ProcessCameraProvider? = null
    private var torchOn = false
    private var zoomIdx = 0
    private val zooms = floatArrayOf(1f, 2f, 3f)
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val exec = Executors.newSingleThreadExecutor()
    private val seen = HashMap<String, MutableList<Long>>()
    private val lastNotice = HashMap<String, Long>()
    private var tone: ToneGenerator? = null
    private var orientationListener: OrientationEventListener? = null
    private var pendingPreview: PreviewView? = null
    private var doneShown = false

    private lateinit var topPanel: LinearLayout
    private lateinit var tvHeader: TextView
    private lateinit var slotsBox: LinearLayout
    private lateinit var tvProg: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnTorch: Button
    private lateinit var btnZoom: Button

    private val CONFIRM = 3
    private val WINDOW_MS = 2500L

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (!uris.isNullOrEmpty()) importSources(uris.map { u -> Pair(nameOf(u)) { contentResolver.openInputStream(u)!! } })
    }
    private val camPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else toast("카메라 권한이 필요합니다")
    }
    private val readPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) showRecent() else toast("파일 접근 권한이 필요합니다")
    }
    private var wantRecent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        PDFBoxResourceLoader.init(applicationContext)
        try { tone = ToneGenerator(AudioManager.STREAM_MUSIC, 100) } catch (e: Exception) { }
        loadState()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (screen == "splash") return          // 시작 화면 중에는 뒤로가기도 막음
                if (screen == "setup") finish() else showSetup()
            }
        })
        showSetup(); handleIncoming(intent)
    }

    // ======================= 시작 화면 (JJ) =======================
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
    }

    override fun onResume() {
        super.onResume()
        if (wantRecent && hasFileAccess()) { wantRecent = false; showRecent() }
        val pa = pendingApk
        if (pa != null && (Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls())) { pendingApk = null; installApk(pa); return }
        Gate.resume(this)   // 재고관리 앱의 인터넷·업데이트 확인
        startShare()
    }

    override fun onPause() {
        Gate.pause(this)
        shareGen++
        if (stateJson() != shareBase) requestSync(0)   // 나가기 전에 올리기
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        orientationListener?.disable()
        tone?.release()
        exec.shutdown()
    }

    private fun showSplash(next: () -> Unit) {
        screen = "splash"
        var done = false
        val go = { if (!done) { done = true; next() } }
        val v = SplashView(this) { go() }   // 누르기로 건너뛰기 없음 — 끝까지 재생
        setContentView(v)
    }

    // ======================= UI 헬퍼 =======================
    private val BG = Color.rgb(16, 18, 23)
    private val CARD = Color.rgb(29, 33, 41)
    private val FIELD = Color.rgb(40, 45, 56)
    private val PRIMARY = Color.rgb(47, 128, 237)
    private val TEAL = Color.rgb(16, 170, 150)
    private val DANGER = Color.rgb(205, 60, 70)
    private val GREY_BTN = Color.rgb(56, 63, 76)
    private val GREEN = Color.rgb(110, 226, 140)
    private val RED = Color.rgb(255, 110, 110)
    private val BLUE = Color.rgb(120, 190, 255)
    private val GOLD = Color.rgb(255, 205, 90)
    private val SUB = Color.rgb(160, 168, 184)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun round(color: Int, r: Int = 14, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun label(s: String, size: Float = 16f, color: Int = Color.WHITE, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = s; textSize = size; setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(3), 0, dp(3))
            setLineSpacing(0f, 1.1f)
        }

    private fun btn(s: String, color: Int = GREY_BTN, onClick: () -> Unit): Button =
        Button(this).apply {
            text = s; textSize = 16f; isAllCaps = false
            setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
            background = RippleDrawable(ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), round(color, 12), null)
            stateListAnimator = null
            minHeight = dp(50)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(5), 0, dp(5)) }
            setOnClickListener { onClick() }
        }

    private fun styleField(e: EditText): EditText = e.apply {
        background = round(FIELD, 10)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setTextColor(Color.WHITE); setHintTextColor(Color.rgb(110, 118, 134))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, dp(3), 0, dp(6)) }
    }

    private fun edit(hint: String, value: String, numeric: Boolean = false): EditText =
        styleField(EditText(this).apply {
            this.hint = hint; setText(value); textSize = 16f
            if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
        })

    private fun row(vararg views: View): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            views.forEachIndexed { i, v ->
                addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(if (i == 0) 0 else dp(4), dp(4), if (i == views.lastIndex) 0 else dp(4), dp(4))
                })
            }
        }

    private fun card(accent: Int? = null): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = round(CARD, 16, accent ?: Color.rgb(44, 50, 62))
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, dp(8), 0, dp(8)) }
    }

    private fun section(t: String): TextView = label(t, 13f, SUB, true).apply { setPadding(dp(2), dp(14), 0, dp(2)) }

    private fun header(title: String, sub: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(30, 64, 175), Color.rgb(13, 148, 136)))
            .apply { cornerRadius = dp(18).toFloat() }
        setPadding(dp(18), dp(18), dp(18), dp(18))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, 0, 0, dp(8)) }
        addView(label("📦  $title", 24f, Color.WHITE, true))
        addView(label(sub, 13f, Color.argb(220, 255, 255, 255)))
    }

    /** A2 앱과 같은 타일 (아이콘 + 이름 + 배지) */
    private fun tile(icon: String, name: String, accent: Int, badge: String = "", sub: String = "", onClick: () -> Unit): FrameLayout {
        val fr = FrameLayout(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = RippleDrawable(ColorStateList.valueOf(Color.argb(60, 255, 255, 255)),
                round(CARD, 18).apply { setStroke(dp(1), Color.rgb(48, 54, 68)) }, null)
            setPadding(dp(8), dp(14), dp(8), dp(12))
            addView(TextView(this@TallyActivity).apply {
                text = icon; textSize = 32f; gravity = Gravity.CENTER
                background = round(Color.argb(45, Color.red(accent), Color.green(accent), Color.blue(accent)), 40)
                layoutParams = LinearLayout.LayoutParams(dp(64), dp(64)).apply { gravity = Gravity.CENTER_HORIZONTAL }
            })
            addView(label(name, 16f, Color.WHITE, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) })
            if (sub.isNotBlank()) addView(label(sub, 11f, SUB).apply { gravity = Gravity.CENTER; maxLines = 1 })
            isClickable = true
            setOnClickListener { onClick() }
        }
        fr.addView(box, FrameLayout.LayoutParams(-1, -1))
        if (badge.isNotBlank()) fr.addView(TextView(this).apply {
            text = badge; textSize = 12f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD); background = round(accent, 14); setPadding(dp(8), dp(2), dp(8), dp(2)); minWidth = dp(28)
        }, FrameLayout.LayoutParams(-2, dp(26)).apply { gravity = Gravity.TOP or Gravity.END; setMargins(0, dp(8), dp(8), 0) })
        return fr
    }

    private fun tileRow(vararg t: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (v in t) addView(v, LinearLayout.LayoutParams(0, dp(140), 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
    }

    private fun secTitle(t: String, color: Int): TextView = label(t, 15f, color, true).apply {
        gravity = Gravity.CENTER; letterSpacing = 0.15f; setPadding(0, dp(14), 0, dp(2))
    }

    /** 상단: 제목 + 진행 숫자 */
    private fun heroTop(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(30, 64, 175), Color.rgb(13, 148, 136)))
            .apply { cornerRadius = dp(18).toFloat() }
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(6)) }
        val top = LinearLayout(this@TallyActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(label("고려아연 TALLY", 22f, Color.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(label("v${BuildInfo.BUILD}", 12f, Color.WHITE, true).apply { background = round(Color.argb(60, 0, 0, 0), 14); setPadding(dp(10), dp(3), dp(10), dp(3)) })
        addView(top)
        addView(label("패킹리스트 → 범위 → 스캔 → 컨 지정 → 엑셀", 12f, Color.argb(210, 255, 255, 255)))
        val target = bookings.sumOf { b -> if (ranges[b.coa] != null) rangeCount(b) else 0 }
        val done = groups.sumOf { it.bds.size }
        val conts = bookings.sumOf { cap(it.coa) }
        val nums = LinearLayout(this@TallyActivity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        for ((v, n) in listOf("${bookings.size}" to "발신번호", "$conts" to "컨테이너", "$done" to "스캔", (if (target > 0) "$target" else "-") to "목표")) nums.addView(LinearLayout(this@TallyActivity).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            addView(label(v, 24f, Color.WHITE, true).apply { gravity = Gravity.CENTER })
            addView(label(n, 12f, Color.argb(220, 255, 255, 255)).apply { gravity = Gravity.CENTER })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(nums)
        if (target > 0) addView(android.widget.ProgressBar(this@TallyActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = target; progress = minOf(done, target)
            progressTintList = ColorStateList.valueOf(Color.rgb(110, 226, 140))
            progressBackgroundTintList = ColorStateList.valueOf(Color.argb(80, 255, 255, 255))
        }, LinearLayout.LayoutParams(-1, dp(10)).apply { topMargin = dp(8) })
    }

    private fun chip(t: String, color: Int): TextView = label(t, 13f, Color.WHITE, true).apply {
        background = round(color, 20)
        setPadding(dp(10), dp(4), dp(10), dp(4))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, dp(4), 0, dp(4)) }
    }

    private fun docRow(icon: String, name: String, info: String, onDelete: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = round(FIELD, 10)
            setPadding(dp(10), dp(6), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(3), 0, dp(3)) }
            val tx = LinearLayout(this@TallyActivity).apply { orientation = LinearLayout.VERTICAL }
            tx.addView(label("$icon  $name", 13f, Color.WHITE, true))
            tx.addView(label(info, 12f, SUB))
            addView(tx, LinearLayout.LayoutParams(0, -2, 1f))
            addView(Button(this@TallyActivity).apply {
                text = "삭제"; textSize = 13f; isAllCaps = false; setTextColor(Color.WHITE)
                background = RippleDrawable(ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), round(DANGER, 10), null)
                stateListAnimator = null; minHeight = dp(40); minWidth = dp(64)
                setOnClickListener { onDelete() }
            }, LinearLayout.LayoutParams(-2, -2))
        }

    private fun watcher(f: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) { f() }
    }

    private fun fmt(n: Double): String = if (n == Math.floor(n)) String.format("%,d", n.toLong()) else String.format("%,.1f", n)

    private var curScroll: ScrollView? = null

    private fun page(root: LinearLayout) {
        val sv = ScrollView(this).apply { setBackgroundColor(BG); isFillViewport = true; addView(root) }
        curScroll = sv
        if (screen == "setup") {   // 첫 화면엔 재고관리 공통 아래 탭
            val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
            outer.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
            outer.addView(com.nakyung.a2dock.Ui.tabBar(this, "tally") { t -> goTab(t) })
            setContentView(outer)
        } else setContentView(sv)
    }

    private fun goTab(t: String) {
        saveState()
        startActivity(Intent(this, com.nakyung.a2dock.MainActivity::class.java).putExtra("tab", t)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
        overridePendingTransition(0, 0)
    }

    /** 같은 화면 다시 그리기 (스크롤 위치 유지) */
    private fun redraw() {
        val y = curScroll?.scrollY ?: 0
        when (screen) { "result" -> showResult(); "setup" -> showSetup(); "archive" -> showArchive(); "scan" -> { refreshScanUi(); return }; else -> return }
        curScroll?.post { curScroll?.scrollTo(0, y) }
    }

    private fun vbox(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(24))
        setBackgroundColor(BG)
    }

    private fun booking(coa: String) = bookings.firstOrNull { it.coa == coa }
    private fun cap(coa: String) = clists[coa]?.conts?.size ?: 0
    private fun inRange(coa: String, bd: Int): Boolean { val r = ranges[coa] ?: return true; return bd in r[0]..r[1] }
    private fun rangeCount(b: Booking): Int { val r = ranges[b.coa] ?: return b.items.size; return b.items.count { it.bd in r[0]..r[1] } }
    private fun groupLabel(g: Group): String = "${g.coa}-${groups.filter { it.coa == g.coa }.indexOf(g) + 1}묶음"
    private fun scannedCount(coa: String) = groups.filter { it.coa == coa }.sumOf { it.bds.size }

    // ======================= 설정 화면 =======================
    private fun showSetup() {
        stopCamera()
        screen = "setup"
        val root = vbox()
        val hd = heroTop()
        if (Fb.enabled) hd.addView(label(when {
            shareErr.isNotEmpty() -> "⚠ 공유 실패: $shareErr (자동으로 다시 시도)"
            shareOk -> "● 공유 중 · 다른 폰과 같은 탈리"
            else -> "공유: 받아오는 중…"
        }, 12f, if (shareErr.isNotEmpty()) Color.rgb(255, 200, 190) else Color.argb(220, 200, 255, 220)).apply { setPadding(0, dp(6), 0, 0) })
        hd.setOnClickListener {
            if (testUnlocked) return@setOnClickListener
            testTaps++
            if (testTaps >= 10) { testUnlocked = true; testTaps = 0; toast("🧪 테스트 모드가 열렸습니다"); redraw() }
            else if (testTaps >= 7) toast("테스트 모드까지 ${10 - testTaps}번")
        }
        root.addView(hd)
        newRelease?.let { r ->
            if (r.build > BuildInfo.BUILD)
                root.addView(btn("🔄  새 버전 ${r.build} 있음 → 업데이트", Color.rgb(200, 90, 20)) { startUpdate(r) })
        }

        // ---- 작업 타일 ----
        val tgt = bookings.sumOf { b -> if (ranges[b.coa] != null) rangeCount(b) else 0 }
        val dn = groups.sumOf { it.bds.size }
        val an0 = archRoot.listFiles()?.count { it.isDirectory } ?: 0
        root.addView(secTitle("작 업", Color.rgb(45, 212, 191)))
        root.addView(tileRow(
            tile("📷", "스캔", TEAL, if (tgt > 0) "$dn/$tgt" else if (dn > 0) "$dn" else "", if (dn > 0) "이어하기" else "시작") {
                if (bookings.isEmpty()) toast("패킹리스트를 먼저 불러오세요") else showScan()
            },
            tile("📋", "결과·엑셀", PRIMARY, "", "컨 지정·엑셀") { showResult() }
        ))
        root.addView(tileRow(
            tile("📂", "불러오기", Color.rgb(234, 179, 8), if (bookings.isNotEmpty()) "${bookings.size}" else "", "최근 파일") { openRecent() },
            tile("🗂", "보관함", Color.rgb(168, 85, 247), if (an0 > 0) "$an0" else "", "지난 작업") { showArchive() }
        ))
        root.addView(btn("파일 직접 선택") { pickFiles.launch(arrayOf("*/*")) }.apply { minHeight = dp(44); textSize = 14f })

        root.addView(secTitle("발 신 번 호", Color.rgb(96, 165, 250)))
        if (bookings.isEmpty()) root.addView(card().apply {
            addView(label("아직 패킹리스트가 없습니다.", 16f, Color.WHITE, true))
            addView(label("최근 파일에서 고르거나, 카톡에서 공유 → 탈리로 보내세요.", 14f, SUB))
        })
        for (b in bookings) {
            val c = cap(b.coa)
            val rc = rangeCount(b)
            val r = ranges[b.coa]
            val ok = c > 0 && r != null && rc == c * per
            val cd = card(if (ok) TEAL else null)
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            top.addView(label(b.coa, 22f, Color.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
            top.addView(chip(if (ok) "준비 완료" else "확인 필요", if (ok) TEAL else Color.rgb(180, 120, 20)))
            cd.addView(top)
            cd.addView(label("패킹리스트 ${b.items.size}개 · ${fmt(b.items.sumOf { it.gw })}kg", 14f, SUB))
            cd.addView(label(if (c > 0) "🚛 컨테이너 ${c}대 · ${clists[b.coa]?.carrier ?: ""}" else "🚛 컨테이너 목록 없음", 15f,
                if (c > 0) Color.WHITE else GOLD))
            clists[b.coa]?.let { cl -> cd.addView(label(cl.conts.joinToString("  ") { it.cntr }, 13f, BLUE)) }
            val rtxt = if (r != null) "🎯 범위 #${r[0]} ~ #${r[1]}  →  ${rc}개" + (if (c > 0) " / ${c}대×$per=${c * per}" + (if (rc == c * per) " ✔" else " ⚠") else "")
                       else "🎯 범위 미지정"
            cd.addView(label(rtxt, 15f, if (r != null && (c == 0 || rc == c * per)) GREEN else GOLD, true))
            if (c > 0) {
                val rem = b.items.size % c
                cd.addView(label("📦 기존화물·BD ${hval(b.coa, "existing")}" + if (rem != 0) "  ⚠ ${b.items.size}÷${c} 안 나눠짐" else "", 14f,
                    if (rem != 0) GOLD else SUB))
            }
            cd.addView(label(b.plName, 11f, Color.rgb(110, 118, 134)))
            root.addView(cd)
        }
        val orphan = clists.keys.filter { k -> bookings.none { it.coa == k } }
        if (orphan.isNotEmpty())
            root.addView(label("컨 목록만 있음(패킹리스트 없음): " + orphan.joinToString { "$it ${cap(it)}대" }, 13f, SUB))

        // ---- 불러온 문서 ----
        root.addView(section("📁 불러온 문서"))
        val dc = card()
        if (bookings.isEmpty() && clists.isEmpty()) dc.addView(label("불러온 문서가 없습니다", 14f, SUB))
        for (b in bookings) {
            dc.addView(docRow("📄", b.plName, "패킹리스트 · ${b.coa} · ${b.items.size}개") {
                val n = groups.filter { it.coa == b.coa }.sumOf { it.bds.size }
                AlertDialog.Builder(this).setTitle("패킹리스트 삭제")
                    .setMessage("${b.plName}\n\n${b.coa} 패킹리스트를 지웁니다." + if (n > 0) "\n⚠ 이 발신번호 탈리 기록 ${n}개도 같이 지워집니다." else "")
                    .setPositiveButton("삭제") { _, _ ->
                        bookings.remove(b); groups.removeAll { it.coa == b.coa }
                        history.removeAll { it.startsWith(b.coa + ":") }; exported.remove(b.coa)
                        rebuildIndex(); saveState(); redraw()
                    }.setNegativeButton("취소", null).show()
            })
        }
        val bySrc = clists.values.groupBy { it.src }
        for ((src, cls) in bySrc) {
            val nm = if (src.isEmpty()) "컨테이너 목록" else src
            dc.addView(docRow("📊", nm, "컨 목록 · " + cls.joinToString(", ") { "${it.coa} ${it.conts.size}대" }) {
                AlertDialog.Builder(this).setTitle("컨 목록 삭제")
                    .setMessage("$nm\n\n컨 목록을 지웁니다. (탈리 기록은 그대로)")
                    .setPositiveButton("삭제") { _, _ ->
                        cls.forEach { clists.remove(it.coa) }; saveState(); redraw()
                    }.setNegativeButton("취소", null).show()
            })
        }
        root.addView(dc)

        root.addView(section("화주 범위"))
        val rc2 = card()
        rc2.addView(label("화주가 준 내용을 그대로 붙여넣으세요", 14f, SUB))
        val eRange = styleField(EditText(this).apply {
            setText(rangeText); textSize = 17f
            hint = "81591   305~384\n81736   64~78"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3; gravity = Gravity.TOP or Gravity.START
        })
        rc2.addView(eRange)
        rc2.addView(btn("범위 적용", PRIMARY) {
            rangeText = eRange.text.toString()
            val n = parseRanges(rangeText)
            saveState(); toast("범위 ${n}건 적용"); redraw()
        })
        root.addView(rc2)

        if (testUnlocked) root.addView(btn("🧪  테스트 모드 (사진 없이)", Color.rgb(140, 60, 160)) {
            if (bookings.isEmpty()) toast("패킹리스트를 먼저 불러오세요") else showScan(true)
        })
        root.addView(section("업데이트"))
        val uc = card()
        uc.addView(label("현재 버전 ${BuildInfo.BUILD}", 14f, SUB))
        uc.addView(btn("🔄  업데이트 확인", PRIMARY) { Gate.check(true) })
        root.addView(uc)
        root.addView(section("초기화"))
        root.addView(row(
            btn("탈리 기록 지우기", Color.rgb(70, 40, 46)) {
                AlertDialog.Builder(this).setMessage("스캔 기록과 컨 지정이 모두 지워집니다.")
                    .setPositiveButton("지우기") { _, _ ->
                        archiveNow("지우기 전")
                        groups.clear(); history.clear(); seen.clear(); lastNotice.clear(); doneShown = false; exported.clear()
                        saveState(); showSetup()
                    }.setNegativeButton("취소", null).show()
            },
            btn("전부 지우기", DANGER) {
                AlertDialog.Builder(this).setMessage("패킹리스트, 컨 목록, 범위, 탈리 기록을 전부 지웁니다.")
                    .setPositiveButton("전부 지우기") { _, _ ->
                        archiveNow("지우기 전")
                        bookings.clear(); clists.clear(); ranges.clear(); groups.clear(); history.clear(); hdr.clear(); exported.clear()
                        rangeText = ""; doneShown = false; rebuildIndex(); saveState(); showSetup()
                    }.setNegativeButton("취소", null).show()
            }
        ))
        page(root)
    }

    private fun parseRanges(t: String): Int {
        val re = Regex("""(\d{3,8})\D{1,20}?(\d{1,4})\s*[~\-–—]\s*(\d{1,4})""")
        var n = 0
        for (m in re.findAll(t)) {
            val coa = m.groupValues[1]; val s = m.groupValues[2].toInt(); val e = m.groupValues[3].toInt()
            ranges[coa] = intArrayOf(minOf(s, e), maxOf(s, e)); n++
        }
        return n
    }

    // ======================= 파일 불러오기 =======================
    private fun nameOf(u: Uri): String {
        var name = u.lastPathSegment ?: "file"
        try {
            contentResolver.query(u, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) name = c.getString(i)
            }
        } catch (e: Exception) { }
        return name
    }

    @Suppress("DEPRECATION")
    private fun handleIncoming(i: Intent?) {
        if (i == null) return
        val uris = ArrayList<Uri>()
        when (i.action) {
            Intent.ACTION_SEND -> i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.add(it) }
            Intent.ACTION_SEND_MULTIPLE -> i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
            Intent.ACTION_VIEW -> i.data?.let { uris.add(it) }
            else -> return
        }
        i.action = null
        if (uris.isNotEmpty()) importSources(uris.map { u -> Pair(nameOf(u)) { contentResolver.openInputStream(u)!! } })
    }

    private fun importSources(srcs: List<Pair<String, () -> InputStream>>) {
        toast("불러오는 중…")
        Thread {
            val pdfs = ArrayList<Pair<String, List<Item>>>()
            val lists = ArrayList<Pair<String, List<Map<Int, String>>>>()
            val msgs = ArrayList<String>()
            for ((name, open) in srcs) {
                try {
                    val bytes = open().use { it.readBytes() }
                    if (bytes.size > 4 && bytes[0] == '%'.code.toByte() && bytes[1] == 'P'.code.toByte()) {
                        val text = PDDocument.load(bytes).use { doc ->
                            val st = PDFTextStripper(); st.setSortByPosition(true); st.getText(doc)
                        }
                        val items = parsePl(text)
                        if (items.isEmpty()) msgs.add("$name: LOT 번호 없음") else pdfs.add(Pair(name, items))
                    } else if (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
                        lists.add(Pair(name, XlsxRead.read(ByteArrayInputStream(bytes))))
                    } else msgs.add("$name: PDF·엑셀(xlsx)만 됩니다")
                } catch (e: Exception) {
                    msgs.add("$name: 읽기 실패 ${e.message}")
                }
            }
            runOnUiThread {
                for ((name, items) in pdfs) msgs.add(addBooking(name, items))
                for ((name, rows) in lists) msgs.add(applyContList(rows, name))
                rebuildIndex(); saveState()
                AlertDialog.Builder(this).setTitle("불러오기").setMessage(msgs.joinToString("\n"))
                    .setPositiveButton("확인", null).show()
                if (screen == "result" || screen == "scan") redraw() else showSetup()
            }
        }.start()
    }

    private fun parsePl(text: String): List<Item> {
        val num = """([\d,]+(?:\.\d+)?)"""
        val re = Regex("""(\d{1,4})\s+([A-Z]{1,3}\d{8,14}[A-Z]\d{6})\s+(\d+)\s+""" + num + """\s+""" + num + """\s+""" + num)
        val re2 = Regex("""(\d{1,4})\s+([A-Z]{1,3}\d{8,14}[A-Z]\d{6})\s+(\d+)\s+([\d,]+)""")
        val map = LinkedHashMap<Int, Item>()
        fun d(x: String) = x.replace(",", "").toDoubleOrNull() ?: 0.0
        for (m in re.findAll(text)) {
            val g = m.groupValues; val bd = g[1].toInt()
            if (!map.containsKey(bd)) map[bd] = Item(bd, g[2], d(g[4]).toInt(), 1.0, d(g[6]))
        }
        for (m in re2.findAll(text)) {
            val g = m.groupValues; val bd = g[1].toInt()
            if (!map.containsKey(bd)) map[bd] = Item(bd, g[2], d(g[4]).toInt(), 1.0, d(g[4]))
        }
        return map.values.sortedBy { it.bd }
    }

    private fun coaFromName(name: String): String =
        Regex("""_(\d{3,8})\.pdf$""", RegexOption.IGNORE_CASE).find(name)?.groupValues?.get(1)
            ?: Regex("""(\d{4,8})(?!.*\d)""").find(name.substringBeforeLast('.'))?.groupValues?.get(1)
            ?: name.substringBeforeLast('.')

    private fun addBooking(name: String, items: List<Item>): String {
        val coa = coaFromName(name)
        val old = booking(coa)
        if (old != null) { old.items = items; old.plName = name }
        else bookings.add(Booking(coa, name, items))
        return "패킹리스트 $coa: ${items.size}개" + if (old != null) " (교체)" else ""
    }

    private fun rebuildIndex() {
        val m = HashMap<String, MutableList<Pair<Booking, Item>>>()
        for (b in bookings) for (it in b.items) m.getOrPut(it.core) { mutableListOf() }.add(Pair(b, it))
        byCore = m
    }

    private fun normNum(v: String): String {
        val t = v.trim()
        if (t.isEmpty()) return t
        return try {
            if (Regex("""-?[\d.]+([eE][+-]?\d+)?""").matches(t)) java.math.BigDecimal(t).stripTrailingZeros().toPlainString() else t
        } catch (e: Exception) { t }
    }

    private fun serialToYmd(v: String): String {
        val d = v.trim().toDoubleOrNull() ?: return v.trim()
        val millis = (d.toLong() - 25569L) * 86400000L
        return java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(millis))
    }

    private fun applyContList(rows: List<Map<Int, String>>, src: String = ""): String {
        fun norm(x: String) = x.replace(" ", "").uppercase()
        val hi = rows.indexOfFirst { r -> r.values.any { norm(it) == "TARE" } && r.values.any { norm(it) in setOf("컨", "컨테이너", "CNTR", "CNTRNO") } }
        if (hi < 0) return "컨 목록: '컨 / 씰 / TARE' 제목 줄 없음"
        val header = rows[hi]
        fun col(vararg names: String): Int {
            val set = names.map { norm(it) }.toSet()
            return header.entries.firstOrNull { norm(it.value) in set }?.key ?: -1
        }
        val cC = col("컨", "컨테이너", "CNTR", "CNTR NO"); val sC = col("씰", "SEAL", "SEAL NO"); val tC = col("TARE")
        val coaC = col("발신번호", "COA"); val bkC = col("BK", "BK NO"); val lineC = col("LINE", "선사")
        val sizeC = col("사이즈", "SIZE"); val dateC = col("작업일자")
        fun g(r: Map<Int, String>, c: Int) = if (c < 0) "" else r[c] ?: ""
        val data = rows.drop(hi + 1).filter { g(it, cC).isNotBlank() }
        if (data.isEmpty()) return "컨 목록: 컨테이너 줄 없음"
        val byCoa = data.groupBy { normNum(g(it, coaC)) }
        val out = ArrayList<String>()
        for ((coa0, rs) in byCoa) {
            val coa = if (coa0.isEmpty() && bookings.size == 1) bookings[0].coa else coa0
            val f = rs.first()
            val z = normNum(g(f, sizeC))
            clists[coa] = CList(
                coa,
                rs.map { CInfo(g(it, cC).trim().uppercase(), normNum(g(it, sC)), normNum(g(it, tC))) },
                normNum(g(f, bkC)), g(f, lineC).trim(),
                if (z.isNotEmpty() && z.all { it.isDigit() }) "$z'" else z,
                if (g(f, dateC).isNotBlank()) serialToYmd(g(f, dateC)) else "",
                src
            )
            out.add("$coa ${rs.size}대")
        }
        return "컨 목록: " + out.joinToString(", ")
    }

    // ---------- 최근 파일 ----------
    private fun hasFileAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun openRecent() {
        if (hasFileAccess()) { showRecent(); return }
        AlertDialog.Builder(this).setTitle("파일 접근 허용")
            .setMessage("다운로드·카톡 폴더의 패킹리스트와 엑셀을 자동으로 찾으려면 '모든 파일 접근'을 한 번 허용해야 합니다.")
            .setPositiveButton("허용하러 가기") { _, _ ->
                if (Build.VERSION.SDK_INT >= 30) {
                    wantRecent = true
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                    } catch (e: Exception) {
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                } else readPerm.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }.setNegativeButton("취소", null).show()
    }

    @Suppress("DEPRECATION")
    private fun recentFiles(): List<File> {
        val root = Environment.getExternalStorageDirectory()
        val dirs = listOf("Download", "Download/KakaoTalk", "KakaoTalkDownload", "Documents", "Download/Kakaotalk", "DCIM/KakaoTalk")
            .map { File(root, it) }
        val since = System.currentTimeMillis() - 14L * 86400000L
        val out = LinkedHashMap<String, File>()
        fun walk(d: File, depth: Int) {
            val fs = try { d.listFiles() } catch (e: Exception) { null } ?: return
            for (f in fs) {
                if (f.isDirectory) { if (depth < 2) walk(f, depth + 1) }
                else {
                    val n = f.name.lowercase()
                    if ((n.endsWith(".pdf") || n.endsWith(".xlsx")) && f.lastModified() >= since) out[f.absolutePath] = f
                }
            }
        }
        dirs.forEach { if (it.isDirectory) walk(it, 0) }
        return out.values.sortedByDescending { it.lastModified() }.take(60)
    }

    private fun showRecent() {
        stopCamera()
        screen = "recent"
        val root = vbox()
        root.addView(header("최근 파일", "패킹리스트(PDF)와 컨 목록(엑셀)을 체크하고 불러오기 · 최근 14일"))
        val files = recentFiles()
        if (files.isEmpty()) root.addView(label("다운로드 폴더에 최근 PDF·엑셀이 없습니다.", 15f, GOLD))
        val tf = java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.KOREA)
        val checks = ArrayList<Pair<CheckBox, File>>()
        for (f in files) {
            val icon = if (f.name.lowercase().endsWith(".pdf")) "📄" else "📊"
            val cb = CheckBox(this).apply {
                text = "$icon  ${f.name}\n      ${tf.format(java.util.Date(f.lastModified()))}"
                setTextColor(Color.WHITE); textSize = 14f
                buttonTintList = ColorStateList.valueOf(TEAL)
                background = round(CARD, 12)
                setPadding(dp(6), dp(10), dp(10), dp(10))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, dp(4), 0, dp(4)) }
            }
            checks.add(Pair(cb, f)); root.addView(cb)
        }
        root.addView(btn("✔  체크한 파일 불러오기", TEAL) {
            val sel = checks.filter { it.first.isChecked }.map { it.second }
            if (sel.isEmpty()) toast("파일을 체크하세요")
            else {
                screen = "setup"
                importSources(sel.map { f -> Pair<String, () -> InputStream>(f.name) { FileInputStream(f) } })
            }
        })
        root.addView(btn("뒤로") { showSetup() })
        page(root)
    }

    // ======================= 스캔 화면 =======================
    private var testMode = false
    private var testTaps = 0
    private var testUnlocked = false
    private var quiet = false

    private fun showScan(test: Boolean = false) {
        if (bookings.isEmpty()) { showSetup(); return }
        stopCamera()
        screen = "scan"
        testMode = test

        topPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = round(Color.argb(215, 16, 18, 23), 18)
        }
        tvHeader = label("", 24f, bold = true)
        slotsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tvProg = label("", 15f, GOLD, true)
        if (test) topPanel.addView(chip("🧪 테스트 모드 — 카메라 없이 버튼으로 스캔", Color.rgb(140, 60, 160)))
        topPanel.addView(tvHeader); topPanel.addView(slotsBox); topPanel.addView(tvProg)

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(6))
            background = round(Color.argb(215, 16, 18, 23), 18)
        }
        tvStatus = label(if (test) "아래 버튼으로 스캔을 흉내 냅니다" else "번호가 보이게 비추면 자동 인식", 18f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; background = round(Color.argb(120, 60, 66, 80), 12); setPadding(dp(8), dp(10), dp(8), dp(10))
        }
        btnTorch = btn("🔦") { toggleTorch() }
        btnZoom = btn("1x") { cycleZoom() }
        bottom.addView(tvStatus)
        if (test) bottom.addView(row(btn("↩ 되돌리기", Color.rgb(90, 70, 40)) { undo() }, btn("⌨ 번호입력") { manualInput() }))
        else bottom.addView(row(btn("↩ 되돌리기", Color.rgb(90, 70, 40)) { undo() }, btn("⌨ 번호입력") { manualInput() }, btnTorch, btnZoom))
        bottom.addView(row(btn("📋 결과·컨지정", PRIMARY) { showResult() }, btn("⚙ 설정") { showSetup() }))

        if (test) {
            val lin = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG); setPadding(dp(10), dp(10), dp(10), dp(10)) }
            lin.addView(topPanel, LinearLayout.LayoutParams(-1, -2))
            val tb = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
            buildTestButtons(tb)
            lin.addView(ScrollView(this).apply { addView(tb) }, LinearLayout.LayoutParams(-1, 0, 1f))
            lin.addView(bottom, LinearLayout.LayoutParams(-1, -2))
            setContentView(lin)
            refreshScanUi()
            scanning = true
            return
        }

        val root = FrameLayout(this)
        val previewView = PreviewView(this)
        root.addView(previewView, FrameLayout.LayoutParams(-1, -1))
        root.addView(topPanel, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(dp(10), dp(10), dp(10), 0) })
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(10), 0, dp(10), dp(10)) })
        setContentView(root)
        refreshScanUi()
        scanning = true
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            startCamera(previewView)
        else {
            pendingPreview = previewView
            camPerm.launch(Manifest.permission.CAMERA)
        }
    }

    // ---------- 테스트 모드 ----------
    private fun nextUnscanned(b: Booking): Item? =
        b.items.filter { inRange(b.coa, it.bd) && groups.none { g -> g.coa == b.coa && g.bds.contains(it.bd) } }.randomOrNull()

    private fun testScan(b: Booking): Boolean {
        val it = nextUnscanned(b)
        if (it == null) { tvStatus.setTextColor(GOLD); tvStatus.text = "${b.coa}: 범위 안에 남은 화물 없음"; return false }
        lastNotice.clear()
        val before = history.size
        handleConfirmed(b, it)
        return history.size > before
    }

    private fun buildTestButtons(tb: LinearLayout) {
        tb.addView(section("화물 찍기 (실제 스캔과 같은 검사)"))
        for (b in bookings) {
            tb.addView(row(
                btn("📷 ${b.coa} 1개", TEAL) { testScan(b) },
                btn("⚡ ${b.coa} 5개", PRIMARY) {
                    quiet = true
                    var n = 0
                    while (n < per && testScan(b)) { n++; if (currentGroup() == null) break }
                    quiet = false
                    beep(ToneGenerator.TONE_PROP_ACK, 300)
                }
            ))
        }
        tb.addView(section("경고 테스트"))
        tb.addView(row(
            btn("🔁 중복 찍기") {
                val k = history.lastOrNull()
                if (k == null) toast("먼저 1개 찍으세요")
                else {
                    val b = booking(k.substringBeforeLast(':')); val it = b?.byBd?.get(k.substringAfterLast(':').toInt())
                    if (b != null && it != null) { lastNotice.clear(); handleConfirmed(b, it) }
                }
            },
            btn("🚫 범위 밖") {
                val pair = bookings.firstNotNullOfOrNull { b -> b.items.firstOrNull { !inRange(b.coa, it.bd) }?.let { Pair(b, it) } }
                if (pair == null) toast("범위 밖 화물이 없습니다 (범위를 먼저 지정)") else { lastNotice.clear(); handleConfirmed(pair.first, pair.second) }
            }
        ))
        tb.addView(row(
            btn("❓ 패킹리스트에 없는 LOT") { lastNotice.clear(); notice("xtest", "⚠ 패킹리스트에 없는 LOT …20991231Z999999", RED, error = true) },
            btn("🔀 묶음 중 섞기") {
                val g = currentGroup()
                val other = bookings.firstOrNull { it.coa != g?.coa }
                if (g == null || other == null) toast("묶음을 1~4개 채운 상태에서, 발신번호가 2개 이상일 때 됩니다")
                else { val it = nextUnscanned(other); if (it != null) { lastNotice.clear(); handleConfirmed(other, it) } }
            }
        ))
        tb.addView(section("한 번에"))
        tb.addView(btn("🧪 전체 범위 자동 채우기", Color.rgb(140, 60, 160)) {
            quiet = true
            for (b in bookings) {
                while (true) {
                    val g = currentGroup()
                    if (g != null && g.coa != b.coa) break
                    if (!testScan(b)) break
                }
            }
            quiet = false
            beep(ToneGenerator.TONE_PROP_ACK, 500)
        })
        tb.addView(btn("🚛 컨테이너 목록 순서대로 자동 지정") {
            var n = 0
            for (b in bookings) {
                val list = clists[b.coa]?.conts ?: continue
                for (g in groups.filter { it.coa == b.coa && it.cntr.isEmpty() }) {
                    val free = list.firstOrNull { c -> groups.none { it.coa == b.coa && it.cntr == c.cntr } } ?: break
                    g.cntr = free.cntr; n++
                }
            }
            saveState(); toast("컨 ${n}개 자동 지정 (테스트)")
        })
        tb.addView(label("※ 테스트 기록은 실제 기록과 같이 저장됩니다. 끝나면 설정 → '탈리 기록 지우기'를 누르세요.", 12f, SUB))
    }

    private fun startCamera(pv: PreviewView? = null) {
        val previewView = pv ?: pendingPreview ?: return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val p = future.get()
            provider = p
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val rs = ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build()
            val an = ImageAnalysis.Builder()
                .setResolutionSelector(rs)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            an.setAnalyzer(exec) { proxy -> analyze(proxy) }
            analysis = an
            try {
                p.unbindAll()
                camera = p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, an)
                torchOn = false; zoomIdx = 0
            } catch (e: Exception) {
                toast("카메라 시작 실패: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))

        if (orientationListener == null) {
            orientationListener = object : OrientationEventListener(this) {
                override fun onOrientationChanged(o: Int) {
                    if (o == OrientationEventListener.ORIENTATION_UNKNOWN) return
                    val r = when (o) {
                        in 45 until 135 -> Surface.ROTATION_270
                        in 135 until 225 -> Surface.ROTATION_180
                        in 225 until 315 -> Surface.ROTATION_90
                        else -> Surface.ROTATION_0
                    }
                    analysis?.targetRotation = r
                }
            }
        }
        orientationListener?.enable()
    }

    private fun stopCamera() {
        scanning = false
        orientationListener?.disable()
        try { provider?.unbindAll() } catch (e: Exception) { }
        camera = null; analysis = null
    }

    private fun toggleTorch() {
        val c = camera ?: return
        torchOn = !torchOn
        c.cameraControl.enableTorch(torchOn)
        btnTorch.text = if (torchOn) "🔦 끔" else "🔦"
    }

    private fun cycleZoom() {
        val c = camera ?: return
        zoomIdx = (zoomIdx + 1) % zooms.size
        val max = c.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
        val z = minOf(zooms[zoomIdx], max)
        c.cameraControl.setZoomRatio(z)
        btnZoom.text = "${z.toInt()}x"
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null || !scanning) { proxy.close(); return }
        val img = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        recognizer.process(img)
            .addOnSuccessListener { onText(it) }
            .addOnCompleteListener { proxy.close() }
    }

    // ---------- 번호 찾기 ----------
    private val digitFix = mapOf(
        'O' to '0', 'Q' to '0', 'D' to '0', 'U' to '0', 'C' to '0',
        'I' to '1', 'L' to '1', '|' to '1', '!' to '1', 'J' to '1',
        'Z' to '2', 'S' to '5', 'G' to '6', 'T' to '7', 'B' to '8', 'A' to '4'
    )
    private val sepFix = mapOf(
        '0' to "DOQ", '5' to "S", '7' to "T", '8' to "B", '2' to "Z",
        '1' to "ILT", '6' to "G", '4' to "A", '9' to "G"
    )
    private val sepAlt = mapOf('O' to "DQ", 'Q' to "DO", 'D' to "O", 'I' to "LT", 'L' to "I", 'J' to "T")

    private fun fixDigit(c: Char): Char? = if (c in '0'..'9') c else digitFix[c]

    /** 날짜8 + 글자1 + 숫자6 형태 → core, 그 앞 6글자(앞자리 구분용) */
    private fun findCores(raw: String, out: MutableMap<String, String>) {
        val t = raw.uppercase().filter { !it.isWhitespace() && it != '-' && it != '.' && it != '_' }
        if (t.length < 15) return
        for (i in 0..t.length - 15) {
            val sb = StringBuilder(15)
            var ok = true
            for (k in 0 until 15) {
                if (k == 8) { sb.append('?'); continue }
                val d = fixDigit(t[i + k])
                if (d == null) { ok = false; break }
                sb.append(d)
            }
            if (!ok || sb[0] != '2' || sb[1] != '0') continue
            val sep = t[i + 8]
            val cands = if (sep in 'A'..'Z') sep.toString() + (sepAlt[sep] ?: "") else (sepFix[sep] ?: continue)
            var matched: String? = null
            for (c in cands) {
                sb.setCharAt(8, c)
                if (byCore.containsKey(sb.toString())) { matched = sb.toString(); break }
            }
            val pre = t.substring(maxOf(0, i - 6), i)
            if (matched != null) {
                val old = out[matched]
                if (old == null || pre.length > old.length) out[matched] = pre
            } else if (sep in 'A'..'Z') {
                sb.setCharAt(8, sep); out.putIfAbsent("?" + sb.toString(), pre)
            }
        }
    }

    private fun resolve(core: String, pre: String): Pair<Booking, Item>? {
        val c = byCore[core] ?: return null
        if (c.size == 1) return c[0]
        val p4 = pre.takeLast(4).map { fixDigit(it) ?: '?' }.joinToString("")
        val m = c.filter { it.second.prefix.takeLast(4) == p4 }
        return if (m.size == 1) m[0] else null
    }

    private fun onText(vt: Text) {
        if (!scanning) return
        val hits = HashMap<String, String>()
        for (b in vt.textBlocks) {
            findCores(b.text, hits)
            for (l in b.lines) findCores(l.text, hits)
        }
        findCores(vt.text, hits)
        val now = SystemClock.elapsedRealtime()
        for ((core, pre) in hits) {
            val unknown = core.startsWith("?")
            val r = if (unknown) null else resolve(core, pre)
            val key = when {
                r != null -> "${r.first.coa}:${r.second.bd}"
                unknown -> "x$core"
                else -> "a$core"
            }
            val list = seen.getOrPut(key) { mutableListOf() }
            list.add(now)
            list.removeAll { now - it > WINDOW_MS }
            val need = if (r != null) CONFIRM else 4
            if (list.size < need) continue
            when {
                r != null -> handleConfirmed(r.first, r.second)
                unknown -> notice(key, "⚠ 패킹리스트에 없는 LOT …${core.drop(1)}", RED, error = true)
                else -> notice(key, "LOT 앞자리까지 보이게 비춰주세요 (…$core)", GOLD, error = false, silent = true)
            }
            if (!scanning) return
        }
    }

    private fun handleConfirmed(b: Booking, item: Item) {
        val g0 = groups.firstOrNull { it.coa == b.coa && it.bds.contains(item.bd) }
        if (g0 != null) {
            notice("d${b.coa}${item.bd}", "이미 스캔됨 ${b.coa} #${item.bd} → ${groupLabel(g0)}", Color.LTGRAY, error = false, silent = true)
            return
        }
        if (!inRange(b.coa, item.bd)) {
            val r = ranges[b.coa]!!
            notice("o${b.coa}${item.bd}", "⚠ 범위 밖 ${b.coa} #${item.bd} (#${r[0]}~#${r[1]})", RED, error = true)
            return
        }
        assign(b, item)
    }

    private fun notice(key: String, msg: String, color: Int, error: Boolean, silent: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (now - (lastNotice[key] ?: 0L) < 4000) return
        lastNotice[key] = now
        if (screen == "scan") { tvStatus.text = msg; tvStatus.setTextColor(color) } else toast(msg)
        if (!silent && error) { beep(ToneGenerator.TONE_SUP_ERROR, 400); vib(400) }
    }

    private fun currentGroup(): Group? = groups.lastOrNull()?.takeIf { it.bds.size < per }

    private fun assign(b: Booking, item: Item) {
        val key = "${b.coa}:${item.bd}"
        // 2차 중복 검사
        if (history.contains(key) || groups.any { it.coa == b.coa && it.bds.contains(item.bd) }) {
            notice("d$key", "이미 스캔됨 ${b.coa} #${item.bd}", Color.LTGRAY, error = false, silent = true)
            return
        }
        var g = currentGroup()
        if (g != null && g.coa != b.coa) {
            notice("m$key", "⚠ ${groupLabel(g)} 채우는 중 — ${b.coa} #${item.bd}는 묶음이 끝난 뒤에", RED, error = true)
            return
        }
        if (g == null) {
            val c = cap(b.coa)
            val cnt = groups.count { it.coa == b.coa }
            if (c > 0 && cnt >= c) {
                notice("f$key", "⚠ ${b.coa} 컨테이너 ${c}대 분량이 이미 다 찼습니다", RED, error = true)
                return
            }
            g = Group(b.coa, mutableListOf())
            groups.add(g)
        }
        g.bds.add(item.bd)
        history.add(key)
        // 3차 중복 검사
        if (dedupe() > 0) {
            beep(ToneGenerator.TONE_SUP_ERROR, 400); vib(400)
            toast("중복이 발견되어 자동 삭제했습니다")
            saveState(); refreshScanUi(); return
        }
        saveState()
        val label = groupLabel(g)
        if (screen == "scan") tvStatus.setTextColor(GREEN)
        if (g.bds.size >= per) {
            beep(ToneGenerator.TONE_PROP_ACK, 700); vib(700)
            if (screen == "scan") tvStatus.text = "✅ $label 완료! (#${item.bd})"
            flash(Color.argb(220, 0, 120, 255))
        } else {
            beep(ToneGenerator.TONE_PROP_BEEP, 150); vib(120)
            if (screen == "scan") tvStatus.text = "✔ ${b.coa} #${item.bd} → $label (${g.bds.size}/$per)"
            flash(Color.argb(220, 0, 150, 60))
        }
        refreshScanUi()
        checkAllDone()
    }

    private fun checkAllDone() {
        if (doneShown) return
        val all = bookings.filter { ranges.containsKey(it.coa) }
        if (all.isEmpty()) return
        if (all.all { scannedCount(it.coa) >= rangeCount(it) } && currentGroup() == null) {
            doneShown = true
            AlertDialog.Builder(this).setTitle("스캔 완료")
                .setMessage("지정한 범위를 모두 스캔했습니다. 컨테이너를 지정하세요.")
                .setPositiveButton("컨 지정하러 가기") { _, _ -> showResult() }
                .setNegativeButton("닫기", null).show()
        }
    }

    /** 같은 발신번호 안에서 같은 번호가 두 번 있으면 나중 것을 지움 */
    private fun dedupe(): Int {
        val seenKey = HashSet<String>()
        var removed = 0
        for (g in groups) {
            val it = g.bds.iterator()
            while (it.hasNext()) { if (!seenKey.add("${g.coa}:${it.next()}")) { it.remove(); removed++ } }
        }
        val lastG = groups.lastOrNull()
        groups.removeAll { it.bds.isEmpty() && it !== lastG }
        val h = LinkedHashSet(history)
        if (h.size != history.size) { history.clear(); history.addAll(h) }
        return removed
    }

    private fun flash(c: Int) {
        if (screen != "scan") return
        topPanel.background = round(c, 18)
        topPanel.postDelayed({ topPanel.background = round(Color.argb(215, 16, 18, 23), 18) }, 300)
    }

    private fun undo() {
        if (history.isEmpty()) { toast("되돌릴 기록 없음"); return }
        val key = history.removeAt(history.lastIndex)
        val coa = key.substringBeforeLast(':')
        val bd = key.substringAfterLast(':').toIntOrNull() ?: return
        val g = groups.lastOrNull { it.coa == coa && it.bds.contains(bd) }
        g?.bds?.remove(bd)
        groups.removeAll { it.bds.isEmpty() }
        seen.clear(); lastNotice.clear(); doneShown = false
        saveState()
        if (screen == "scan") { tvStatus.setTextColor(Color.YELLOW); tvStatus.text = "↩ $coa #$bd 취소됨" }
        refreshScanUi()
    }

    private fun manualInput() {
        val e = EditText(this).apply { hint = "번호 (여러 발신번호면: 81591 307)"; inputType = InputType.TYPE_CLASS_TEXT }
        AlertDialog.Builder(this).setTitle("번호 직접 입력").setView(e)
            .setPositiveButton("추가") { _, _ ->
                val nums = Regex("""\d+""").findAll(e.text.toString()).map { it.value }.toList()
                var b: Booking? = null
                var bd: Int? = null
                if (nums.size >= 2) { b = booking(nums[0]); bd = nums[1].toIntOrNull() }
                else if (nums.size == 1) {
                    bd = nums[0].toIntOrNull()
                    b = currentGroup()?.let { booking(it.coa) } ?: bookings.singleOrNull()
                }
                val item = if (b != null && bd != null) b.byBd[bd] else null
                when {
                    b == null -> toast("발신번호와 번호를 같이 입력하세요 (예: 81591 307)")
                    item == null -> toast("${b.coa} 패킹리스트에 없는 번호")
                    !inRange(b.coa, item.bd) -> toast("범위 밖 번호")
                    else -> assign(b, item)
                }
            }
            .setNegativeButton("취소", null).show()
    }

    private fun refreshScanUi() {
        if (screen != "scan") return
        val g = currentGroup()
        slotsBox.removeAllViews()
        if (g == null) {
            tvHeader.text = "새 묶음 — 첫 화물 대기"
        } else {
            tvHeader.text = "${groupLabel(g)}  (${g.bds.size}/$per)"
        }
        val b = g?.let { booking(it.coa) }
        for (k in 0 until per) {
            val bd = g?.bds?.getOrNull(k)
            val it = if (bd != null) b?.byBd?.get(bd) else null
            val slot = if (it != null) label("  ${k + 1}   #${it.bd}    …${it.core.takeLast(10)}    ${fmt(it.gw)}kg", 17f, Color.WHITE, true)
                       else label("  ${k + 1}   ─", 17f, Color.rgb(110, 118, 134))
            slot.background = round(if (it != null) Color.argb(200, 16, 120, 105) else Color.argb(150, 50, 56, 68), 10)
            slot.setPadding(dp(6), dp(7), dp(6), dp(7))
            slot.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(3), 0, dp(3)) }
            slotsBox.addView(slot)
        }
        tvProg.text = bookings.joinToString("\n") { bk ->
            val c = cap(bk.coa)
            val full = groups.count { it.coa == bk.coa && it.bds.size >= per }
            val r = ranges[bk.coa]
            "${bk.coa}: 묶음 $full/${if (c > 0) c else "?"}  ·  스캔 ${scannedCount(bk.coa)}/${rangeCount(bk)}" +
                    if (r != null) "  (#${r[0]}~#${r[1]})" else ""
        }
    }

    private fun beep(t: Int, ms: Int) { if (quiet) return; try { tone?.startTone(t, ms) } catch (e: Exception) { } }

    @Suppress("DEPRECATION")
    private fun vib(ms: Int) {
        if (quiet) return
        try {
            val v = getSystemService(VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
            else v.vibrate(ms.toLong())
        } catch (e: Exception) { }
    }

    // ======================= 결과 · 컨 지정 =======================
    private fun hdef(coa: String, f: String): String {
        val cl = clists[coa]
        val b = booking(coa)
        return when (f) {
            "date" -> cl?.date ?: ""
            "carrier" -> cl?.carrier ?: ""
            "bk" -> cl?.bk ?: ""
            "size" -> cl?.size?.takeIf { it.isNotEmpty() } ?: "20'"
            "existing" -> if (cl != null && b != null && cl.conts.isNotEmpty()) (b.items.size / cl.conts.size - per).toString() else ""
            else -> ""
        }
    }

    private fun hval(coa: String, f: String): String = hdr[coa]?.get(f) ?: hdef(coa, f)

    private fun resolveCntr(coa: String, input: String, self: Group): Pair<String?, String> {
        val q = input.trim().uppercase().replace(" ", "")
        if (q.isEmpty()) return Pair("", "")
        val list = clists[coa]?.conts ?: emptyList()
        var m = list.filter { it.cntr.endsWith(q) }
        if (m.isEmpty()) m = list.filter { it.cntr.contains(q) }
        if (m.isEmpty()) {
            return if (list.isEmpty() && q.length >= 10) Pair(q, "✔ $q (목록 없음)")
            else Pair(null, "✗ $coa 목록에 없음")
        }
        if (m.size > 1) return Pair(null, "여러 개: " + m.joinToString { it.cntr })
        val c = m[0]
        val other = groups.firstOrNull { it !== self && it.coa == coa && it.cntr == c.cntr }
        if (other != null) return Pair(null, "✗ ${c.cntr} 이미 ${groupLabel(other)}에 지정됨")
        return Pair(c.cntr, "✔ ${c.cntr} / 씰 ${c.seal} / TARE ${c.tare}")
    }

    private fun showResult() {
        stopCamera()
        screen = "result"
        val root = vbox()
        val unassigned = groups.count { it.cntr.isEmpty() }
        root.addView(header("결과 · 컨테이너 지정",
            "묶음 ${groups.size}개 · 컨 미지정 ${unassigned}개 · 중복 " + (if (dupCount() == 0) "없음" else "${dupCount()}건")))
        root.addView(btn("📄  전체 발신번호 엑셀 만들기", TEAL) { exportChecked(bookings.filter { b -> groups.any { it.coa == b.coa } }.map { it.coa }) })
        root.addView(row(btn("▶ 스캔 계속", PRIMARY) { showScan(testMode) }, btn("⚙ 설정") { showSetup() }))

        for (b in bookings) {
            val gs = groups.filter { it.coa == b.coa }
            val issues = verify(b)
            val cd = card(if (issues.isEmpty()) TEAL else null)
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            top.addView(label(b.coa, 22f, Color.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
            top.addView(chip("묶음 ${gs.size}/${cap(b.coa).takeIf { it > 0 } ?: "?"}", PRIMARY))
            cd.addView(top)
            exported[b.coa]?.let { cd.addView(chip("📤 엑셀 보냄 $it", Color.rgb(40, 120, 70))) }
            cd.addView(label(if (issues.isEmpty()) "✔ 범위 전체 들어감 · 중복 없음 · 컨 지정 완료" else "⚠ " + issues.joinToString("\n⚠ "),
                14f, if (issues.isEmpty()) GREEN else GOLD, true))
            cd.addView(btn("📄  ${b.coa} 엑셀만 만들기", if (issues.isEmpty()) TEAL else GREY_BTN) { exportChecked(listOf(b.coa)) })

            for ((gi, g) in gs.withIndex()) {
                val kg = g.bds.sumOf { b.byBd[it]?.gw ?: 0.0 }
                val gc = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = round(FIELD, 12, if (g.cntr.isNotEmpty()) TEAL else null)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(6), 0, dp(6)) }
                }
                gc.addView(label("${gi + 1}묶음  ·  ${g.bds.size}/$per  ·  ${fmt(kg)}kg", 16f, Color.WHITE, true))
                val tv = label(g.bds.sorted().joinToString("  ") { "#$it" } + "   ✎", 15f, BLUE)
                tv.setOnClickListener {
                    val arr = g.bds.sorted()
                    AlertDialog.Builder(this).setTitle("${groupLabel(g)}에서 뺄 번호")
                        .setItems(arr.map { "#$it  ${b.byBd[it]?.lot ?: ""}" }.toTypedArray()) { _, w ->
                            val bd = arr[w]
                            g.bds.remove(bd); history.remove("${b.coa}:$bd")
                            if (g.bds.isEmpty()) groups.remove(g)
                            doneShown = false; saveState(); redraw()
                        }.setNegativeButton("취소", null).show()
                }
                gc.addView(tv)
                val st = label(if (g.cntr.isNotEmpty()) resolveCntr(b.coa, g.cntr, g).second else "컨 미지정", 14f,
                    if (g.cntr.isNotEmpty()) GREEN else GOLD, true)
                val e = styleField(EditText(this).apply {
                    hint = "🚛 컨 끝 4자리 (예: 8644)"; setText(g.cntr); textSize = 17f
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                })
                e.background = round(CARD, 10)
                e.addTextChangedListener(watcher {
                    val (full, msg) = resolveCntr(b.coa, e.text.toString(), g)
                    if (full != null) { g.cntr = full; st.setTextColor(if (full.isEmpty()) GOLD else GREEN); st.text = if (full.isEmpty()) "컨 미지정" else msg }
                    else { g.cntr = ""; st.setTextColor(RED); st.text = msg }
                    gc.background = round(FIELD, 12, if (g.cntr.isNotEmpty()) TEAL else null)
                    saveState()
                })
                gc.addView(e); gc.addView(st)
                val list = clists[b.coa]?.conts ?: emptyList()
                if (list.isNotEmpty()) gc.addView(btn("📋 목록에서 고르기") {
                    val free = list.filter { c -> groups.none { it !== g && it.coa == b.coa && it.cntr == c.cntr } }
                    AlertDialog.Builder(this).setTitle("${groupLabel(g)} 컨테이너")
                        .setItems(free.map { "${it.cntr}   씰 ${it.seal}   ${it.tare}" }.toTypedArray()) { _, w ->
                            g.cntr = free[w].cntr; saveState(); redraw()
                        }.setNegativeButton("취소", null).show()
                })
                cd.addView(gc)
            }

            val cl = clists[b.coa]
            if (cl != null) {
                val used = gs.count { it.cntr.isNotEmpty() }
                cd.addView(section("컨테이너 목록 (${cl.conts.size}대 · 지정 $used)"))
                for ((ci, c) in cl.conts.withIndex()) {
                    val g = gs.firstOrNull { it.cntr == c.cntr }
                    val line = label("${ci + 1}. ${c.cntr}   씰 ${c.seal}   ${c.tare}\n      " +
                            (if (g != null) "→ ${gs.indexOf(g) + 1}묶음 ✔" else "미지정"), 14f,
                        if (g != null) GREEN else Color.WHITE, g != null)
                    line.background = round(if (g != null) Color.argb(90, 16, 170, 150) else FIELD, 10)
                    line.setPadding(dp(10), dp(6), dp(10), dp(6))
                    line.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(2), 0, dp(2)) }
                    cd.addView(line)
                }
            }

            val r = ranges[b.coa]
            val missing = b.items.filter { inRange(b.coa, it.bd) && gs.none { g -> g.bds.contains(it.bd) } }
            if (r != null && missing.isNotEmpty()) {
                cd.addView(label("미스캔 ${missing.size}개", 15f, RED, true))
                cd.addView(label(missing.joinToString(", ") { "#${it.bd}" }, 14f, Color.rgb(255, 170, 170)))
            }

            cd.addView(section("엑셀 정보"))
            val fields = listOf(
                "date" to "작업일자 (비우면 오늘)", "carrier" to "선사", "bk" to "BK NO", "size" to "SIZE",
                "existing" to "기존화물·BD", "vsl" to "VSL&VOY (공란)", "shoring" to "쇼링 (공란)"
            )
            for ((f, title) in fields) {
                cd.addView(label(title, 12f, SUB))
                val e = edit(title, hval(b.coa, f))
                e.addTextChangedListener(watcher {
                    hdr.getOrPut(b.coa) { HashMap() }[f] = e.text.toString().trim(); saveState()
                })
                cd.addView(e)
            }
            root.addView(cd)
        }
        page(root)
    }

    private fun dupCount(): Int {
        val all = groups.flatMap { g -> g.bds.map { "${g.coa}:$it" } }
        return all.size - all.toSet().size
    }

    // ======================= 엑셀 (CLP 양식) =======================
    private fun dateSerialAndYmd(raw0: String): Pair<Long, String> {
        val utc = java.util.TimeZone.getTimeZone("UTC")
        val f = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { timeZone = utc; isLenient = false }
        val raw = raw0.trim().replace('.', '-').replace('/', '-')
        val parsed = try { if (raw.isEmpty()) null else f.parse(raw) } catch (e: Exception) { null }
        val millis = parsed?.time ?: f.parse(java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()))!!.time
        val serial = millis / 86400000L + 25569L
        val ymd = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).apply { timeZone = utc }.format(java.util.Date(millis))
        return Pair(serial, ymd)
    }

    /** 발신번호 검사: 범위 전체·중복·범위 밖·묶음 미완성·컨 미지정 */
    private fun verify(b: Booking): List<String> {
        val out = ArrayList<String>()
        val gs = groups.filter { it.coa == b.coa }
        val all = gs.flatMap { it.bds }
        val dup = all.groupingBy { it }.eachCount().filter { it.value > 1 }.keys.sorted()
        if (dup.isNotEmpty()) out.add("중복 " + dup.joinToString(" ") { "#$it" })
        val r = ranges[b.coa]
        if (r == null) out.add("범위 미지정")
        else {
            val need = b.items.map { it.bd }.filter { it in r[0]..r[1] }.toSet()
            val notInPl = (r[0]..r[1]).filter { bd -> b.byBd[bd] == null }
            val missing = need.filter { it !in all }.sorted()
            val outside = all.filter { it !in r[0]..r[1] }.distinct().sorted()
            if (missing.isNotEmpty()) out.add("미스캔 ${missing.size}개 (" + missing.take(10).joinToString(" ") { "#$it" } + if (missing.size > 10) " …)" else ")")
            if (outside.isNotEmpty()) out.add("범위 밖 " + outside.joinToString(" ") { "#$it" })
            if (notInPl.isNotEmpty()) out.add("패킹리스트에 없는 범위 번호 ${notInPl.size}개")
        }
        val partial = gs.filter { it.bds.size < per }
        if (partial.isNotEmpty()) out.add("5개 안 찬 묶음 ${partial.size}개")
        val un = gs.count { it.cntr.isEmpty() }
        if (un > 0) out.add("컨 미지정 ${un}묶음")
        val c = cap(b.coa)
        if (c > 0 && gs.size != c) out.add("묶음 ${gs.size} / 컨 ${c}대")
        if (gs.isEmpty()) out.add("스캔 없음")
        return out
    }

    private fun exportChecked(coas: List<String>) {
        if (coas.isEmpty()) { toast("만들 발신번호가 없습니다"); return }
        val msg = StringBuilder()
        var bad = false
        for (coa in coas) {
            val b = booking(coa) ?: continue
            val r = ranges[coa]
            val iss = verify(b)
            if (iss.isEmpty()) msg.append("✔ $coa: 범위 #${r?.get(0)}~#${r?.get(1)} ${rangeCount(b)}개 모두 · 중복 없음 · 컨 ${groups.count { it.coa == coa }}대 지정\n")
            else { bad = true; msg.append("⚠ $coa: ").append(iss.joinToString(" / ")).append("\n") }
        }
        AlertDialog.Builder(this).setTitle(if (bad) "확인 필요" else "검사 통과")
            .setMessage(msg.toString().trim())
            .setPositiveButton(if (bad) "그래도 만들기" else "엑셀 만들기") { _, _ -> doExport(coas) }
            .setNegativeButton("취소", null).show()
    }

    private fun doExport(coas: List<String>) {
        try {
            val uris = ArrayList<Uri>()
            val names = ArrayList<String>()
            val done = ArrayList<String>()
            val files = ArrayList<File>()
            for (b in bookings.filter { it.coa in coas }) {
                val gs = groups.filter { it.coa == b.coa && it.bds.isNotEmpty() }
                if (gs.isEmpty()) continue
                val cl = clists[b.coa]
                val order = cl?.conts?.map { it.cntr } ?: emptyList()
                val sorted = gs.sortedWith(compareBy<Group> { if (it.cntr.isEmpty()) Int.MAX_VALUE else order.indexOf(it.cntr).let { i -> if (i < 0) Int.MAX_VALUE - 1 else i } })
                val xc = sorted.map { g ->
                    val ci = cl?.conts?.firstOrNull { it.cntr == g.cntr }
                    XCont(g.cntr, ci?.seal ?: "", ci?.tare ?: "",
                        g.bds.sorted().mapNotNull { b.byBd[it] }.map { XLot(it.bd, it.lot, 1.0, it.gw) })
                }
                val (serial, ymd) = dateSerialAndYmd(hval(b.coa, "date"))
                val ex = hval(b.coa, "existing")
                val h = XHeader(serial, hval(b.coa, "carrier"), hval(b.coa, "bk"), hval(b.coa, "vsl"), hval(b.coa, "shoring"),
                    b.coa, hval(b.coa, "size"), ex, ex)
                // 파일 이름: 작업일자 BK 피트 X 컨테이너대수 - 발신번호.xlsx  (예: 20261125 6473457150 20 X 16 - 81591.xlsx)
                val feet = hval(b.coa, "size").filter { it.isDigit() }.take(2).ifEmpty { "20" }
                val cnt = cap(b.coa).takeIf { it > 0 } ?: xc.size
                val bkName = hval(b.coa, "bk").trim().replace(Regex("[^0-9A-Za-z]"), "")
                val name = "$ymd $bkName $feet X $cnt - ${b.coa}.xlsx"
                val f = File(cacheDir, name)
                assets.open("template.xlsx").use { ins -> f.outputStream().use { os -> XlsxExport.build(ins, os, h, xc, per) } }
                uris.add(FileProvider.getUriForFile(this, "com.nakyung.a2dock.files", f))
                names.add(name)
                files.add(f)
                done.add(b.coa)
            }
            if (uris.isEmpty()) { toast("만들 내용이 없습니다"); return }
            val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, uris[0]) }
            else Intent(Intent.ACTION_SEND_MULTIPLE).apply { putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris) }
            i.type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val now = java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.KOREA).format(java.util.Date())
            done.forEach { exported[it] = now }
            saveState()
            archiveNow("엑셀", files, done)
            toast("엑셀 ${uris.size}개: " + names.joinToString(", "))
            startActivity(Intent.createChooser(i, "엑셀 공유"))
            if (screen == "result") redraw()
        } catch (e: Exception) {
            toast("엑셀 만들기 실패: ${e.message}")
        }
    }

    // ======================= 앱 업데이트 =======================
    private var newRelease: Release? = null
    private var updateChecked = false
    private var pendingApk: File? = null
    private val upPrefs by lazy { getSharedPreferences("update", MODE_PRIVATE) }
    private fun upRepo(): String = upPrefs.getString("repo", null)?.takeIf { it.isNotBlank() } ?: BuildInfo.REPO
    private fun upToken(): String = ""

    private fun checkUpdate(silent: Boolean) {
        if (!silent) toast("업데이트 확인 중…")
        Thread {
            try {
                val r = Updater.latest(upRepo(), upToken())
                runOnUiThread {
                    newRelease = r
                    if (r != null && r.build > BuildInfo.BUILD) {
                        if (screen == "setup") redraw()
                        showForceUpdate(r)
                    } else if (!silent) toast("최신 버전입니다 (${BuildInfo.BUILD})")
                }
            } catch (e: Exception) {
                if (!silent) runOnUiThread { toast("업데이트 확인 실패: ${e.message}") }
            }
        }.start()
    }

    // ---------- 강제 업데이트 ----------
    private var forceDlg: AlertDialog? = null
    private var updating = false

    private fun showForceUpdate(r: Release) {
        if (killed || updating || forceDlg?.isShowing == true) return
        forceDlg = AlertDialog.Builder(this).setTitle("업데이트 필요")
            .setMessage("새 버전 ${r.build}이(가) 있습니다.\n업데이트해야 사용할 수 있어요.\n(지금 버전 ${BuildInfo.BUILD})")
            .setCancelable(false)
            .setPositiveButton("업데이트") { _, _ -> startUpdate(r) }
            .setNegativeButton("종료") { _, _ -> saveState(); finishAndRemoveTask() }
            .show()
    }

    // ---------- 원격 사용 중지 (저장소의 control.txt 첫 줄이 STOP 이면) ----------
    private val ctrlPrefs by lazy { getSharedPreferences("ctrl", MODE_PRIVATE) }
    private var killed = false

    // ---------- 인터넷 필수: 켤 때·돌아올 때마다 서버 확인 ----------
    private var offlineDlg: AlertDialog? = null
    private var gating = false

    private fun showChecking() {
        screen = "checking"
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(BG)
            addView(label("고려아연 TALLY", 24f, Color.WHITE, true).apply { gravity = Gravity.CENTER })
            addView(label("연결 확인 중…", 15f, SUB).apply { gravity = Gravity.CENTER })
        })
    }

    /** 서버(깃허브)에 연결되면 사용 중지·업데이트를 확인하고 onOk, 안 되면 사용 불가 창 */
    private fun gate(onOk: () -> Unit) {
        if (gating || killed) return
        gating = true
        Thread {
            var online = false
            var stop = false
            var msg = ""
            var rel: Release? = null
            try {
                val c = java.net.URL("https://raw.githubusercontent.com/${upRepo()}/main/control.txt?t=${System.currentTimeMillis()}")
                    .openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                c.setRequestProperty("Cache-Control", "no-cache")
                val code = c.responseCode
                online = true
                if (code == 200) {
                    val lines = c.inputStream.bufferedReader().use { it.readText() }.trim().lines()
                    stop = lines.firstOrNull()?.trim()?.uppercase() == "STOP"
                    msg = lines.drop(1).joinToString("\n").trim()
                }
            } catch (e: Exception) { }
            if (online) try { rel = Updater.latest(upRepo(), upToken()) } catch (e: Exception) { }
            runOnUiThread {
                gating = false
                if (!online) { showOffline(onOk); return@runOnUiThread }
                offlineDlg?.dismiss()
                if (screen == "scan" && !testMode) scanning = true
                if (stop) { killApp(msg); return@runOnUiThread }
                if (ctrlPrefs.getBoolean("killed", false)) ctrlPrefs.edit().putBoolean("killed", false).apply()
                updateChecked = true
                newRelease = rel
                onOk()
                val r = rel
                if (r != null && r.build > BuildInfo.BUILD) showForceUpdate(r)
            }
        }.start()
    }

    private fun showOffline(onOk: () -> Unit) {
        scanning = false
        if (offlineDlg?.isShowing == true) return
        offlineDlg = AlertDialog.Builder(this).setTitle("인터넷 연결 필요")
            .setMessage("인터넷에 연결되어 있어야 사용할 수 있어요.\n와이파이나 데이터를 켜고 다시 시도하세요.\n(작업 기록은 그대로 저장되어 있어요)")
            .setCancelable(false)
            .setPositiveButton("다시 시도") { _, _ -> gate(onOk) }
            .setNegativeButton("종료") { _, _ -> saveState(); finishAndRemoveTask() }
            .show()
    }

    private fun checkControl() {
        Thread {
            try {
                val c = java.net.URL("https://raw.githubusercontent.com/${upRepo()}/main/control.txt?t=${System.currentTimeMillis()}")
                    .openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 10000; c.readTimeout = 10000
                c.setRequestProperty("Cache-Control", "no-cache")
                val code = c.responseCode
                val text = if (code == 200) c.inputStream.bufferedReader().use { it.readText() } else ""
                val lines = text.trim().lines()
                val stop = lines.firstOrNull()?.trim()?.uppercase() == "STOP"
                runOnUiThread {
                    if (stop) killApp(lines.drop(1).joinToString("\n").trim())
                    else if (code == 200 || code == 404) { if (ctrlPrefs.getBoolean("killed", false)) ctrlPrefs.edit().putBoolean("killed", false).apply() }
                }
            } catch (e: Exception) { }
        }.start()
    }

    /** 사용 중지: 앱 안의 기록을 지우고, 삭제하거나 종료하게 함 */
    private fun killApp(msg: String) {
        killed = true
        ctrlPrefs.edit().putBoolean("killed", true).putString("msg", msg).apply()
        for (n in listOf("tally2", "tally", "hdr", "update", "cam")) getSharedPreferences(n, MODE_PRIVATE).edit().clear().apply()
        try { cacheDir.deleteRecursively(); archRoot.deleteRecursively() } catch (e: Exception) { }
        bookings.clear(); clists.clear(); ranges.clear(); groups.clear(); history.clear(); hdr.clear(); exported.clear()
        stopCamera()
        forceDlg?.dismiss()
        setContentView(LinearLayout(this).apply { setBackgroundColor(BG) })
        AlertDialog.Builder(this).setTitle("사용이 중지된 앱입니다")
            .setMessage((if (msg.isNotBlank()) "$msg\n\n" else "") + "앱 안의 기록은 지워졌습니다.")
            .setCancelable(false)
            .setPositiveButton("앱 삭제") { _, _ ->
                try { startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))) } catch (e: Exception) { }
                finishAndRemoveTask()
            }
            .setNegativeButton("종료") { _, _ -> finishAndRemoveTask() }
            .show()
    }

    private fun startUpdate(r: Release) {
        updating = true
        val dlg = AlertDialog.Builder(this).setTitle("다운로드 중").setMessage("0%").setCancelable(false).show()
        Thread {
            try {
                val f = File(File(cacheDir, "apk"), "Tally-${r.build}.apk")
                Updater.download(r, upToken(), f) { p -> runOnUiThread { dlg.setMessage("$p%") } }
                runOnUiThread { dlg.dismiss(); installApk(f) }
            } catch (e: Exception) {
                runOnUiThread { dlg.dismiss(); updating = false; toast("다운로드 실패: ${e.message}"); showForceUpdate(r) }
            }
        }.start()
    }

    private fun installApk(f: File) {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            pendingApk = f
            toast("'이 출처 허용'을 켜고 돌아오면 설치가 시작됩니다")
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        saveState()
        updating = false
        val uri = FileProvider.getUriForFile(this, "com.nakyung.a2dock.files", f)
        startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    // ======================= 지난 작업 보관함 =======================
    private val archRoot: File get() = File(filesDir, "archive")
    private val KEEP_DAYS = 90

    private fun archDirs(): List<File> = archRoot.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name } ?: emptyList()
    private fun readMeta(d: File): JSONObject = try { JSONObject(File(d, "meta.json").readText()) } catch (e: Exception) { JSONObject() }

    /** 엑셀 만들 때·기록 지우기 전·불러오기 전에 지금 작업을 통째로 보관 */
    private fun archiveNow(kind: String, files: List<File> = emptyList(), coas: List<String> = emptyList()) {
        try {
            if (groups.none { it.bds.isNotEmpty() } && files.isEmpty()) return
            val js = stateJson()
            val latest = archDirs().firstOrNull()
            if (files.isEmpty() && latest != null && (try { File(latest, "state.json").readText() } catch (e: Exception) { "" }) == js) return  // 바뀐 게 없으면 또 안 쌓음
            val now = java.util.Date()
            val st = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(now)
            val cs = coas.ifEmpty { groups.filter { it.bds.isNotEmpty() }.map { it.coa }.distinct() }
            val key = cs.sorted().joinToString(",")
            if (kind == "엑셀") {  // 같은 날 같은 발신번호로 다시 만들면 예전 것을 새 것으로 교체
                archDirs().filter { it.name.startsWith(st.take(8)) }.forEach { d ->
                    val m = readMeta(d)
                    if (m.optString("kind") == "엑셀" && m.optString("key") == key) d.deleteRecursively()
                }
            }
            val d = File(archRoot, st).apply { mkdirs() }
            for (f in files) f.copyTo(File(d, f.name), overwrite = true)
            File(d, "state.json").writeText(js)
            File(d, "meta.json").writeText(JSONObject().apply {
                put("kind", kind); put("key", key)
                put("coas", JSONArray(cs.map { c -> "$c · 스캔 ${scannedCount(c)}개" + (cap(c).takeIf { it > 0 }?.let { " · 컨 ${it}대" } ?: "") }))
            }.toString())
            val cut = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date(now.time - KEEP_DAYS * 86400000L))
            archDirs().filter { it.name.take(8) < cut }.forEach { it.deleteRecursively() }
        } catch (e: Exception) { }
    }

    private fun showArchive() {
        stopCamera()
        screen = "archive"
        val root = vbox()
        root.addView(header("지난 작업 보관함", "엑셀 만들 때·기록 지울 때 자동 보관  ·  ${KEEP_DAYS}일 지나면 자동 삭제"))
        root.addView(btn("←  뒤로") { showSetup() })
        val dirs = archDirs()
        if (dirs.isEmpty()) root.addView(card().apply {
            addView(label("보관된 작업이 없습니다.", 16f, Color.WHITE, true))
            addView(label("엑셀을 만들면 여기에 자동으로 쌓여요.", 14f, SUB))
        })
        var lastDay = ""
        for (d in dirs) {
            val n = d.name
            val day = n.take(8)
            if (day != lastDay && day.length == 8) { lastDay = day; root.addView(section("📅 ${day.substring(0, 4)}.${day.substring(4, 6)}.${day.substring(6, 8)}")) }
            val m = readMeta(d)
            val kind = m.optString("kind", "엑셀")
            val xs = d.listFiles()?.filter { it.name.endsWith(".xlsx") }?.sortedBy { it.name } ?: emptyList()
            val cd = card(if (kind == "엑셀") TEAL else null)
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val t = if (n.length >= 13) "${n.substring(9, 11)}:${n.substring(11, 13)}" else n
            top.addView(label("🕒 $t", 20f, Color.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
            top.addView(chip(kind, if (kind == "엑셀") TEAL else Color.rgb(120, 90, 40)))
            cd.addView(top)
            m.optJSONArray("coas")?.let { a -> for (i in 0 until a.length()) cd.addView(label("📦 " + a.getString(i), 15f, Color.WHITE)) }
            for (x in xs) cd.addView(label("📄 ${x.name}", 12f, BLUE).apply { setOnClickListener { openXlsx(x) } })
            val bs = ArrayList<View>()
            if (xs.isNotEmpty()) bs.add(btn("공유", PRIMARY) { shareXlsx(xs) })
            if (File(d, "state.json").exists()) bs.add(btn("불러오기", TEAL) { restoreArchive(d, t) })
            bs.add(btn("삭제", DANGER) {
                AlertDialog.Builder(this).setMessage("$t 보관 기록을 지웁니다.")
                    .setPositiveButton("삭제") { _, _ -> d.deleteRecursively(); redraw() }
                    .setNegativeButton("취소", null).show()
            })
            cd.addView(row(*bs.toTypedArray()))
            root.addView(cd)
        }
        page(root)
    }

    private val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    private fun openXlsx(f: File) {
        try {
            val u = FileProvider.getUriForFile(this, "com.nakyung.a2dock.files", f)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(u, XLSX_MIME); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "엑셀 열기"))
        } catch (e: Exception) { toast("열 수 있는 앱이 없습니다") }
    }

    private fun shareXlsx(fs: List<File>) {
        try {
            val uris = ArrayList(fs.map { FileProvider.getUriForFile(this, "com.nakyung.a2dock.files", it) })
            val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, uris[0]) }
                    else Intent(Intent.ACTION_SEND_MULTIPLE).apply { putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris) }
            i.type = XLSX_MIME
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, "엑셀 공유"))
        } catch (e: Exception) { toast("공유 실패: ${e.message}") }
    }

    private fun restoreArchive(d: File, t: String) {
        val js = try { File(d, "state.json").readText() } catch (e: Exception) { toast("기록을 읽을 수 없습니다"); return }
        AlertDialog.Builder(this).setTitle("$t 작업 불러오기")
            .setMessage("지금 앱의 작업이 이 기록으로 바뀝니다.\n(지금 작업은 보관함에 먼저 보관돼요)")
            .setPositiveButton("불러오기") { _, _ ->
                archiveNow("불러오기 전")
                applyState(js)
                seen.clear(); lastNotice.clear(); doneShown = false
                saveState()
                toast("$t 작업을 불러왔습니다")
                showSetup()
            }.setNegativeButton("취소", null).show()
    }

    // ======================= 저장 =======================
    private fun saveState() {
        getSharedPreferences("tally2", MODE_PRIVATE).edit().putString("s", stateJson()).apply()
        requestSync()
    }

    // ======================= 서버 공유 (여러 폰이 같은 탈리) =======================
    // 서버 tally = {rev, by, s}. 올릴 땐 ETag 조건부 쓰기 → 그 사이 다른 폰이 썼으면 받아서 합친 뒤 다시 올림 (덮어쓰기 없음)
    private val sp by lazy { getSharedPreferences("tally_sync", MODE_PRIVATE) }
    private var shareBase: String?
        get() = sp.getString("base", null)
        set(v) { sp.edit().putString("base", v).apply() }
    private val hMain = android.os.Handler(android.os.Looper.getMainLooper())
    private var shareGen = 0
    private var syncBusy = false
    private var syncAgain = false
    private var shareErr = ""
    private var shareOk = false
    private val syncRun = Runnable { doSync() }

    private fun fbUrl(path: String) = "${BuildInfo.FB_URL.trim().trimEnd('/')}/$path.json?auth=${BuildInfo.FB_KEY.trim()}"

    private fun startShare() {
        if (!Fb.enabled) return
        val gen = ++shareGen
        requestSync(0)
        Fb.listen("tally/rev", { shareGen == gen }) { hMain.post { if (shareGen == gen) requestSync(300) } }
    }

    private fun requestSync(delay: Long = 700) {
        if (!Fb.enabled) return
        hMain.removeCallbacks(syncRun); hMain.postDelayed(syncRun, delay)
    }

    private fun doSync() {
        if (syncBusy) { syncAgain = true; return }
        syncBusy = true
        Thread {
            var et: String? = null; var body: String? = null; var err = ""
            try {
                val c = java.net.URL(fbUrl("tally")).openConnection() as java.net.HttpURLConnection
                c.setRequestProperty("X-Firebase-ETag", "true")
                c.connectTimeout = 15000; c.readTimeout = 40000
                if (c.responseCode != 200) throw Exception("HTTP ${c.responseCode}")
                et = c.getHeaderField("ETag")
                body = c.inputStream.bufferedReader().use { it.readText() }
            } catch (e: Exception) { err = e.message ?: "연결 실패" }
            val e2 = et; val b2 = body
            hMain.post { if (err.isNotEmpty() || e2 == null) syncFail(err.ifEmpty { "ETag 없음" }) else onFetched(e2, b2) }
        }.start()
    }

    private fun syncFail(msg: String) {
        syncBusy = false; shareErr = msg; shareOk = false
        if (screen == "setup") redraw()
        hMain.removeCallbacks(syncRun); hMain.postDelayed(syncRun, 10000)
    }

    private fun onFetched(etag: String, body: String?) {
        val so = try { if (body.isNullOrBlank() || body.trim() == "null") null else JSONObject(body) } catch (e: Exception) { null }
        val serverS = so?.optString("s")?.ifBlank { null }
        val serverRev = so?.optLong("rev") ?: 0L
        val local = stateJson()
        val merged = try { TallyMerge.merge(shareBase, local, serverS, per) } catch (e: Exception) { local }
        if (merged != local) {
            applyState(merged)
            getSharedPreferences("tally2", MODE_PRIVATE).edit().putString("s", stateJson()).apply()
            seen.clear(); lastNotice.clear()
            if (screen == "scan") refreshScanUi() else redraw()
        }
        val canon = stateJson()
        if (serverS != null && serverS == canon) { syncDone(canon); return }
        if (serverS != null && (try { TallyMerge.merge(null, serverS, null, per) } catch (e: Exception) { "" }) == (try { TallyMerge.merge(null, canon, null, per) } catch (e: Exception) { "x" })) { syncDone(canon); return }
        val rev = maxOf(serverRev, System.currentTimeMillis()) + 1
        val out = JSONObject().put("rev", rev).put("by", com.nakyung.a2dock.Fb.userName(this)).put("s", canon).toString()
        Thread {
            var code = -1
            try {
                val c = java.net.URL(fbUrl("tally")).openConnection() as java.net.HttpURLConnection
                c.requestMethod = "PUT"; c.doOutput = true
                c.connectTimeout = 15000; c.readTimeout = 40000
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.setRequestProperty("if-match", etag)
                c.outputStream.use { it.write(out.toByteArray(Charsets.UTF_8)) }
                code = c.responseCode
                try { (if (code in 200..299) c.inputStream else c.errorStream)?.close() } catch (e: Exception) { }
            } catch (e: Exception) { }
            hMain.post {
                when (code) {
                    200 -> syncDone(canon)
                    412 -> { syncBusy = false; doSync() }        // 그 사이 다른 폰이 씀 → 다시 받아 합치기
                    else -> syncFail("올리기 실패 ($code)")
                }
            }
        }.start()
    }

    private fun syncDone(canon: String) {
        shareBase = canon
        syncBusy = false
        val wasErr = shareErr.isNotEmpty() || !shareOk
        shareErr = ""; shareOk = true
        if (wasErr && screen == "setup") redraw()
        if (syncAgain || stateJson() != canon) { syncAgain = false; requestSync() }
    }

    private fun stateJson(): String {
        val o = JSONObject()
        val ba = JSONArray()
        for (b in bookings) {
            ba.put(JSONObject().apply {
                put("coa", b.coa); put("name", b.plName)
                put("items", JSONArray(b.items.map { "${it.bd}|${it.lot}|${it.kg}|${it.qty}|${it.gw}" }))
            })
        }
        o.put("bookings", ba)
        val ca = JSONArray()
        for (c in clists.values) {
            ca.put(JSONObject().apply {
                put("coa", c.coa); put("bk", c.bk); put("carrier", c.carrier); put("size", c.size); put("date", c.date); put("src", c.src)
                put("conts", JSONArray(c.conts.map { JSONArray(listOf(it.cntr, it.seal, it.tare)) }))
            })
        }
        o.put("clists", ca)
        val ra = JSONObject(); for ((k, v) in ranges) ra.put(k, JSONArray(listOf(v[0], v[1])))
        o.put("ranges", ra)
        val ga = JSONArray()
        for (g in groups) ga.put(JSONObject().apply { put("coa", g.coa); put("bds", JSONArray(g.bds)); put("cntr", g.cntr) })
        o.put("groups", ga)
        o.put("hist", JSONArray(history))
        val ha = JSONObject(); for ((k, m) in hdr) ha.put(k, JSONObject(m as Map<*, *>))
        o.put("hdr", ha)
        o.put("rangeText", rangeText)
        o.put("exported", JSONObject(exported as Map<*, *>))
        return o.toString()
    }

    private fun loadState() {
        val s = getSharedPreferences("tally2", MODE_PRIVATE).getString("s", null) ?: return
        applyState(s)
    }

    private fun applyState(s: String) {
        try {
            val o = JSONObject(s)
            bookings.clear()
            val ba = o.optJSONArray("bookings") ?: JSONArray()
            for (i in 0 until ba.length()) {
                val bo = ba.getJSONObject(i)
                val ia = bo.getJSONArray("items")
                val list = ArrayList<Item>()
                for (k in 0 until ia.length()) {
                    val p = ia.getString(k).split("|")
                    if (p.size >= 5) list.add(Item(p[0].toInt(), p[1], p[2].toInt(), p[3].toDouble(), p[4].toDouble()))
                }
                bookings.add(Booking(bo.getString("coa"), bo.optString("name"), list))
            }
            clists.clear()
            val ca = o.optJSONArray("clists") ?: JSONArray()
            for (i in 0 until ca.length()) {
                val co = ca.getJSONObject(i)
                val arr = co.getJSONArray("conts")
                val conts = (0 until arr.length()).map { k -> val a = arr.getJSONArray(k); CInfo(a.getString(0), a.getString(1), a.getString(2)) }
                clists[co.getString("coa")] = CList(co.getString("coa"), conts, co.optString("bk"), co.optString("carrier"), co.optString("size"), co.optString("date"), co.optString("src"))
            }
            ranges.clear()
            val ra = o.optJSONObject("ranges")
            ra?.keys()?.forEach { k -> val a = ra.getJSONArray(k); ranges[k] = intArrayOf(a.getInt(0), a.getInt(1)) }
            groups.clear()
            val ga = o.optJSONArray("groups") ?: JSONArray()
            for (i in 0 until ga.length()) {
                val go = ga.getJSONObject(i)
                val a = go.getJSONArray("bds")
                groups.add(Group(go.getString("coa"), MutableList(a.length()) { a.getInt(it) }, go.optString("cntr")))
            }
            history.clear()
            val ha = o.optJSONArray("hist") ?: JSONArray()
            for (i in 0 until ha.length()) history.add(ha.getString(i))
            hdr.clear()
            val ho = o.optJSONObject("hdr")
            ho?.keys()?.forEach { k ->
                val m = HashMap<String, String>(); val x = ho.getJSONObject(k)
                x.keys().forEach { f -> m[f] = x.getString(f) }
                hdr[k] = m
            }
            rangeText = o.optString("rangeText")
            exported.clear()
            o.optJSONObject("exported")?.let { ex -> ex.keys().forEach { k -> exported[k] = ex.getString(k) } }
            if (dedupe() > 0) saveState()
        } catch (e: Exception) { }
        rebuildIndex()
    }
}
