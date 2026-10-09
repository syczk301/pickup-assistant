package com.local.pickup

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.provider.Telephony
import android.text.*
import android.view.*
import android.widget.*
import java.io.*
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.*
import org.json.*

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var listing: LinearLayout
    private lateinit var updates: UpdateController
    private var page = 0
    private var filterTab = 0
    private var sort = 0
    private var query = ""
    private var searchOpen = false
    private var searchInput: EditText? = null
    private var carrierFilter = "全部"
    private var dark = false
    private var ink = 0
    private var muted = 0
    private var paper = 0
    private var bg = 0
    private var accent = 0
    private val expandedSettings = mutableSetOf<String>()
    private val settingsSummaries = mutableListOf<() -> Unit>()
    private val handler = Handler(Looper.getMainLooper())
    private var imageRecognizer: ImageRecognizer? = null
    private var imageResult: ((Uri) -> Unit)? = null
    private var addSnapshot: (() -> Bundle)? = null
    private var addDraft: Bundle? = null
    private var pendingImage: Uri? = null
    private var updateStatus: TextView? = null
    @Volatile private var scanning = false
    private val listener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "parcels" && !scanning) handler.post { if (!isDestroyed) render() }
            if (key in setOf("update_phase", "update_error", "update_ready")) handler.post {
                if (!isDestroyed) updateStatus?.text = "当前版本 ${updates.version()} · ${UpdateFiles.status(this)}"
            }
        }

    override fun onCreate(saved: Bundle?) {
        if (Store.prefs(this).getBoolean("dark", false))
            setTheme(R.style.AppThemeDark)
        super.onCreate(saved)
        updates = UpdateController(this)
        if (saved != null) {
            page = saved.getInt("page")
            filterTab = saved.getInt("filterTab")
            sort = saved.getInt("sort")
            query = saved.getString("query", "")
            searchOpen = saved.getBoolean("searchOpen", query.isNotEmpty())
            carrierFilter = saved.getString("carrierFilter", "全部")
            expandedSettings.addAll(saved.getStringArrayList("expandedSettings") ?: emptyList())
            addDraft = saved.getBundle("addDraft")
            pendingImage = saved.getString("pendingImage")?.let(Uri::parse)
        } else sort = Store.prefs(this).getInt("sort", 0)
        Store.prefs(this).registerOnSharedPreferenceChangeListener(listener)
        ReminderReceiver.schedule(this)
        render()
        if (addDraft != null) handler.post { if (!isDestroyed) addParcel() }
    }

    override fun onSaveInstanceState(state: Bundle) {
        super.onSaveInstanceState(state)
        state.putInt("page", page)
        state.putInt("filterTab", filterTab)
        state.putInt("sort", sort)
        state.putString("query", query)
        state.putBoolean("searchOpen", searchOpen)
        state.putString("carrierFilter", carrierFilter)
        state.putStringArrayList("expandedSettings", ArrayList(expandedSettings))
        state.putBundle("addDraft", addSnapshot?.invoke() ?: addDraft)
        state.putString("pendingImage", pendingImage?.toString())
    }

    override fun onDestroy() {
        imageRecognizer?.close()
        AppDialogs.close(this)
        if (::updates.isInitialized) updates.close()
        handler.removeCallbacksAndMessages(null)
        Store.prefs(this).unregisterOnSharedPreferenceChangeListener(listener)
        super.onDestroy()
    }

    override fun onPause() {
        if (::updates.isInitialized) updates.pause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::root.isInitialized) render()
        if (::updates.isInitialized) updates.resume()
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density + .5f).toInt()

    private fun shape(color: Int, radius: Int) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    private fun col() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun row() =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

    private fun text(s: String, size: Int, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            this.text = s
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }

    private fun action(s: String, fn: () -> Unit) =
        text(s, 14, accent, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = shape(if (dark) 0xff25384a.toInt() else 0xffeaf2fa.toInt(), 14)
            minHeight = dp(48)
            setOnClickListener { fn() }
        }

    private fun icon(resource: Int, color: Int, label: String, click: (() -> Unit)? = null) =
        ImageView(this).apply {
            setImageResource(resource)
            imageTintList = ColorStateList.valueOf(color)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = label
            setPadding(dp(10), dp(10), dp(10), dp(10))
            if (click != null) setOnClickListener { click() }
        }

    private fun divider(parent: LinearLayout) {
        parent.addView(View(this).apply { setBackgroundColor(if (dark) 0xff2c3c4b.toInt() else 0xffeaf0f4.toInt()) }, LinearLayout.LayoutParams(-1, dp(1)))
    }

    private fun space(v: LinearLayout, n: Int) {
        v.addView(View(this), LinearLayout.LayoutParams(1, dp(n)))
    }

    private fun card(parent: LinearLayout) =
        col().apply {
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = shape(paper, 16)
            parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }

    private fun heading(s: String) {
        body.addView(text(s, 14, muted, true).apply { setPadding(dp(2), dp(12), 0, dp(12)) })
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    private fun confirm(title: String, message: String, action: () -> Unit) {
        AppDialogs.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ -> action() }
            .show()
    }

    private fun date(time: Long) = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(time))

    private fun render() {
        if (page != 0) hideSearchKeyboard()
        searchInput = null
        updateStatus = null
        settingsSummaries.clear()
        dark = Store.prefs(this).getBoolean("dark", false)
        ink = if (dark) 0xffeef5fc.toInt() else 0xff08263c.toInt()
        muted = if (dark) 0xffa9b7c6.toInt() else 0xff788695.toInt()
        paper = if (dark) 0xff1c2937.toInt() else Color.WHITE
        bg = if (dark) 0xff111c27.toInt() else if (page == 0) Color.WHITE else 0xfff5f7f9.toInt()
        accent = if (dark) 0xff8dceff.toInt() else 0xff1677ff.toInt()
        val top = if (dark) 0xff173449.toInt() else 0xffedf8fd.toInt()
        window.statusBarColor = if (dark) bg else top
        window.navigationBarColor = paper
        window.decorView.systemUiVisibility =
            if (dark) 0
            else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        root =
            col().apply {
                isFocusableInTouchMode = true
                requestFocus()
                background =
                    GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(top, bg, bg),
                    )
            }
        setContentView(root)
        val header =
            col().apply { setPadding(dp(20), dp(if (page == 0) 4 else 18), dp(20), dp(if (page == 0) 0 else 20)) }
        root.addView(header)
        if (page == 0) homeHeader(header)
        else {
            header.background =
                GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bg))
            val title = row()
            title.addView(
                text(if (page == 1) "取件统计" else "设置", 28, ink, true),
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            title.addView(text("拾件簿", 11, muted))
            header.addView(title)
            space(header, 8)
            header.addView(text(if (page == 1) "你的包裹记录，一目了然。" else "短信和图片在本机识别，取件记录保存在本机。", 13, muted))
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        body = col().apply { setPadding(dp(20), dp(4), dp(20), dp(16)); setBackgroundColor(bg) }
        scroll.addView(body)
        when (page) {
            0 -> home()
            1 -> stats()
            else -> settings()
        }
        divider(root)
        val nav = row().apply { setBackgroundColor(paper); setPadding(dp(16), dp(3), dp(16), dp(3)) }
        root.addView(nav, LinearLayout.LayoutParams(-1, dp(52)))
        val navIcons = intArrayOf(R.drawable.ic_tag, R.drawable.ic_stats, R.drawable.ic_settings)
        arrayOf("包裹", "统计", "设置").forEachIndexed { index, label ->
            val color = if (index == page) accent else muted
            val tab = col().apply {
                gravity = Gravity.CENTER
                contentDescription = label
                addView(icon(navIcons[index], color, label).apply { setPadding(dp(3), dp(3), dp(3), dp(3)) }, LinearLayout.LayoutParams(dp(30), dp(30)))
                addView(text(label, 12, color, index == page).apply { gravity = Gravity.CENTER })
                setOnClickListener { page = index; render() }
            }
            nav.addView(tab, LinearLayout.LayoutParams(0, -1, 1f))
        }
    }

    private fun homeHeader(header: LinearLayout) {
        val all = Store.load(this)
        val pending = all.count { it.completed == 0L }
        val title = row()
        title.addView(text("拾件簿", 26, ink, true).apply {
            includeFontPadding = false
            setSingleLine(); ellipsize = TextUtils.TruncateAt.END
            setHorizontallyScrolling(false)
            setAutoSizeTextTypeUniformWithConfiguration(18, 26, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        title.addView(icon(R.drawable.ic_search, accent, if (searchOpen) "收起搜索" else "搜索包裹") {
            if (searchOpen) closeSearch() else { searchOpen = true; render() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        title.addView(icon(R.drawable.ic_identity, accent, "身份码") { identityCode() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        title.addView(icon(R.drawable.ic_add, accent, "添加包裹") { addParcel() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(title)
        space(header, 4)
        val tabs = row()
        repeat(2) { index ->
            val selected = filterTab == index
            val tab = col().apply {
                gravity = Gravity.CENTER
                addView(text(if (index == 0) "待取 ($pending)" else "已取 (${all.size - pending})", 18, if (selected) accent else muted, true).apply {
                    gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(6)); includeFontPadding = false
                })
                addView(View(this@MainActivity).apply { background = shape(if (selected) accent else Color.TRANSPARENT, 2) }, LinearLayout.LayoutParams(dp(90), dp(2)))
                setOnClickListener { filterTab = index; render() }
            }
            tabs.addView(tab, LinearLayout.LayoutParams(0, -2, 1f))
        }
        header.addView(tabs)
        divider(header)
        if (searchOpen) {
            space(header, 6)
            val searchRow = row().apply { background = shape(if (dark) paper else 0xffe8f2f8.toInt(), 18) }
            searchRow.addView(icon(R.drawable.ic_search, muted, "包裹搜索"), LinearLayout.LayoutParams(dp(42), dp(48)))
            val search = EditText(this).apply {
                setSingleLine(); textSize = 15f; setTextColor(ink); setHintTextColor(muted)
                hint = "搜索取件码、驿站、快递公司"
                contentDescription = "搜索取件码、驿站、快递公司"
                minHeight = dp(48)
                setPadding(0, 0, 0, 0); background = null; setText(query)
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                setOnEditorActionListener { _, action, _ ->
                    if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { hideSearchKeyboard(); true } else false
                }
            }
            searchInput = search
            searchRow.addView(search, LinearLayout.LayoutParams(0, -2, 1f))
            searchRow.addView(icon(R.drawable.ic_close, muted, "关闭搜索") { closeSearch() }, LinearLayout.LayoutParams(dp(48), dp(48)))
            header.addView(searchRow)
            watch(search) { query = it; renderList() }
            search.post {
                if (!isDestroyed && !isFinishing && page == 0 && searchOpen && search === searchInput && search.isAttachedToWindow) {
                    search.requestFocus(); search.setSelection(search.text.length)
                    (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(search, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }
        val controls = row()
        fun control(label: String, color: Int, fn: () -> Unit): LinearLayout = row().apply {
            addView(text(label, 14, color, true))
            addView(icon(R.drawable.ic_expand, color, label).apply { setPadding(dp(3), 0, dp(3), 0) }, LinearLayout.LayoutParams(dp(22), dp(24)))
            setOnClickListener { fn() }
        }
        controls.addView(control(arrayOf("按时间", "按取件码", "按公司")[sort], accent) {
            AppDialogs.Builder(this).setTitle("排序方式").setSingleChoiceItems(arrayOf("按时间", "按取件码", "按公司"), sort) { dialog, index ->
                sort = index; Store.prefs(this).edit().putInt("sort", sort).apply(); dialog.dismiss(); render()
            }.show()
        })
        controls.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        controls.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        controls.addView(control(if (carrierFilter == "全部") "筛选" else carrierFilter, muted) {
            AppDialogs.Builder(this).setTitle("快递公司筛选").setItems(withAll()) { _, index -> carrierFilter = withAll()[index]; render() }.show()
        })
        header.addView(controls, LinearLayout.LayoutParams(-1, dp(40)))
    }

    private fun hideSearchKeyboard() {
        searchInput?.let {
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(it.windowToken, 0)
        }
    }

    private fun closeSearch() {
        hideSearchKeyboard()
        query = ""
        searchOpen = false
        render()
    }

    private fun home() {
        listing = col()
        body.addView(listing)
        renderList()
        if (checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            space(body, 16)
            body.addView(action("开启短信权限 · 自动提取新取件码", ::requestSmsPermission))
        }
        if (visible().isNotEmpty()) {
            space(body, 12)
            body.addView(text(if (filterTab == 0) "全部取出" else "清空已取记录", 13, muted).apply {
                gravity = Gravity.CENTER; minHeight = dp(48); setOnClickListener { batch() }
            })
        }
    }

    private fun watch(edit: EditText, change: (String) -> Unit) {
        edit.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {}

                override fun afterTextChanged(s: Editable?) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    change(s.toString())
                }
            }
        )
    }

    private fun withAll() = arrayOf("全部", *SmsParser.CARRIERS)

    private fun visible(): List<Store.Parcel> =
        Store.load(this)
            .filter {
                (if (filterTab == 0) it.completed == 0L else it.completed > 0L) &&
                    (carrierFilter == "全部" || carrierFilter == it.carrier) &&
                    "${it.code} ${it.carrier} ${it.station} ${it.note}".contains(query, true)
            }
            .sortedWith { a, b ->
                when (sort) {
                    0 ->
                        (if (filterTab == 0) b.created else b.completed).compareTo(
                            if (filterTab == 0) a.created else a.completed
                        )
                    1 -> natural(a.code, b.code)
                    else -> a.carrier.compareTo(b.carrier)
                }
            }

    private fun natural(a: String, b: String): Int {
        val split = Regex("(?<=\\D)(?=\\d)|(?<=\\d)(?=\\D)")
        val aa = a.split(split)
        val bb = b.split(split)
        for (i in 0 until minOf(aa.size, bb.size)) {
            val n =
                if (aa[i].matches(Regex("\\d+")) && bb[i].matches(Regex("\\d+")))
                    BigInteger(aa[i]).compareTo(BigInteger(bb[i]))
                else aa[i].compareTo(bb[i], true)
            if (n != 0) return n
        }
        return aa.size.compareTo(bb.size)
    }

    private fun renderList() {
        listing.removeAllViews()
        val shown = visible()
        if (shown.isEmpty()) {
            val empty = col().apply { gravity = Gravity.CENTER; setPadding(0, dp(56), 0, dp(40)) }
            empty.addView(icon(R.drawable.ic_tag, accent, "包裹"), LinearLayout.LayoutParams(dp(68), dp(68)))
            space(empty, 12)
            empty.addView(text(if (query.isNotEmpty() || carrierFilter != "全部") "没有匹配的包裹" else if (filterTab == 0) "暂无待取包裹" else "还没有已取记录", 18, ink, true).apply { gravity = Gravity.CENTER })
            space(empty, 10)
            empty.addView(text(if (filterTab == 0) "收到快递短信后自动识别\n也可以手动添加或粘贴短信" else "完成取件后，可以在这里回看", 13, muted).apply { gravity = Gravity.CENTER })
            listing.addView(empty, LinearLayout.LayoutParams(-1, -2))
            return
        }
        val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        val now = Calendar.getInstance()
        val today = dayFormat.format(now.time)
        now.add(Calendar.DAY_OF_YEAR, -1)
        val yesterday = dayFormat.format(now.time)
        val dayCounts = shown.groupingBy {
            dayFormat.format(Date(if (it.completed == 0L) it.created else it.completed))
        }.eachCount()
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        var lastDay = ""
        for (p in shown) {
            val time = if (p.completed == 0L) p.created else p.completed
            val day = dayFormat.format(Date(time))
            if (sort == 0 && day != lastDay) {
                val parcelYear = Calendar.getInstance().apply { timeInMillis = time }.get(Calendar.YEAR)
                val calendarDate = SimpleDateFormat(if (parcelYear == currentYear) "MM月dd日" else "yyyy年MM月dd日", Locale.CHINA).format(Date(time))
                val label = when (day) { today -> "今天"; yesterday -> "昨天"; else -> calendarDate }
                val count = dayCounts.getValue(day)
                val group = row().apply {
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                    background = shape(if (dark) 0xff203e54.toInt() else 0xffeaf3ff.toInt(), 8)
                    contentDescription = "日期分组 $label，$calendarDate，$count 件${if (filterTab == 0) "待取" else "已取"}包裹"
                }
                group.addView(View(this).apply { background = shape(accent, 2) }, LinearLayout.LayoutParams(dp(3), dp(18)).apply { rightMargin = dp(8) })
                group.addView(text(label, 17, if (dark) accent else 0xff1265d6.toInt(), true).apply {
                    includeFontPadding = false
                    setSingleLine()
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, -2, 1f))
                if (day == today || day == yesterday) {
                    group.addView(text(calendarDate, 12, if (dark) muted else 0xff526a83.toInt()).apply {
                        includeFontPadding = false
                        setPadding(dp(6), 0, dp(10), 0)
                    })
                }
                group.addView(text("$count 件", 12, if (dark) muted else 0xff526a83.toInt(), true).apply { includeFontPadding = false })
                listing.addView(group, LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(if (lastDay.isEmpty()) 2 else 10)
                    bottomMargin = dp(4)
                })
                lastDay = day
            }
            val item = col().apply {
                setPadding(0, dp(5), 0, dp(7))
                contentDescription = "包裹详情 ${p.code}，长按打开操作菜单"
                setOnClickListener { detail(p) }
                setOnLongClickListener { detail(p); true }
            }
            val summary = if (p.station.isEmpty()) p.carrier else if (p.station == p.carrier) p.station else "${p.station} · ${p.carrier}"
            item.addView(text(summary, 13, muted).apply {
                setPadding(0, 0, dp(4), 0)
                includeFontPadding = false
                setSingleLine(); ellipsize = TextUtils.TruncateAt.END
                contentDescription = "驿站 ${p.station.ifEmpty { "未填写" }}，快递公司 ${p.carrier}"
            }, LinearLayout.LayoutParams(-1, -2))
            space(item, 8)
            val content = GridLayout(this).apply { columnCount = 3; rowCount = 1 }
            val codeRow = row().apply { isBaselineAligned = false }
            val logo = CarrierLogos.view(this, p.carrier)
            codeRow.addView(logo, LinearLayout.LayoutParams(dp(CarrierLogos.widthDp(p.carrier)), dp(28)).apply { rightMargin = dp(8) })
            val code = text(p.code, if (p.code.length > 9) 23 else 28, if (p.completed == 0L) ink else muted, true).apply {
                setSingleLine(); ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
                setHorizontallyScrolling(false)
                setAutoSizeTextTypeUniformWithConfiguration(12, if (p.code.length > 9) 23 else 28, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                contentDescription = "取件码 ${p.code}，点击查看详情，长按打开操作菜单"
                setOnClickListener { detail(p) }
                setOnLongClickListener { detail(p); true }
            }
            codeRow.addView(code, LinearLayout.LayoutParams(0, -2, 1f))
            content.addView(codeRow, GridLayout.LayoutParams(GridLayout.spec(0, GridLayout.CENTER), GridLayout.spec(0, GridLayout.FILL, 1f)).apply { width = 0; height = -2 })
            content.addView(View(this).apply { setBackgroundColor(if (dark) 0xff2c3c4b.toInt() else 0xffeaf0f4.toInt()) }, GridLayout.LayoutParams(GridLayout.spec(0, GridLayout.CENTER), GridLayout.spec(1)).apply { width = dp(1); height = dp(42); leftMargin = dp(8); rightMargin = dp(6) })
            val statusIcon = icon(if (p.completed == 0L) R.drawable.ic_pending else R.drawable.ic_done, if (p.completed == 0L) muted else accent, "取件状态").apply { setPadding(dp(6), dp(6), dp(6), dp(6)) }
            val mark = FrameLayout(this).apply {
                contentDescription = (if (p.completed == 0L) "标记已取 " else "恢复待取 ") + p.code
                addView(statusIcon, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
                setOnClickListener { status(p.id, p.completed == 0L) }
            }
            content.addView(mark, GridLayout.LayoutParams(GridLayout.spec(0, GridLayout.CENTER), GridLayout.spec(2)).apply { width = dp(58); height = dp(48) })
            content.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                // Center the visible glyphs, rather than the font's asymmetric ascent/descent box.
                val bounds = android.graphics.Rect()
                code.paint.getTextBounds(p.code, 0, p.code.length, bounds)
                val center = mark.top + statusIcon.top + statusIcon.height / 2f
                logo.translationY = center - codeRow.top - logo.top - logo.height / 2f
                code.translationY = center - codeRow.top - code.top - code.baseline - bounds.exactCenterY()
            }
            item.addView(content)
            listing.addView(item)
            divider(listing)
        }
    }

    private fun copy(s: String) {
        (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(
            ClipData.newPlainText("取件码", s)
        )
        toast("取件码已复制")
    }

    private fun status(id: String, done: Boolean) =
        synchronized(Store) {
            val all = Store.load(this)
            all.filter { it.id == id }
                .forEach { it.completed = if (done) System.currentTimeMillis() else 0 }
            Store.save(this, all)
            render()
            toast(if (done) "已完成取件" else "已恢复为待取")
        }

    private fun detail(p: Store.Parcel) {
        val content = ImageParcelText.split(p.source, p.note)
        val options = mutableListOf("复制取件码", "编辑信息", if (p.completed == 0L) "标记已取" else "恢复待取", "删除记录")
        if (content.original.isNotBlank()) options.add("查看识别原文")
        AppDialogs.Builder(this)
            .setTitle("${p.code} · ${p.carrier}")
            .setMessage(
                "驿站：${p.station.ifEmpty { "未填写" }}\n收到：${date(p.created)}" +
                    (if (p.completed > 0) "\n取件：${date(p.completed)}" else "") +
                    "\n来源：${p.source}" +
                    (if (content.note.isNotBlank()) "\n\n备注：${content.note}" else "")
            )
            .setItems(
                options.toTypedArray()
            ) { _, index ->
                when (index) {
                    0 -> copy(p.code)
                    1 -> edit(p)
                    2 -> status(p.id, p.completed == 0L)
                    3 ->
                        confirm("删除包裹", "删除取件码 ${p.code}？") {
                            synchronized(Store) {
                                val all = Store.load(this)
                                all.removeAll { it.id == p.id }
                                Store.save(this, all)
                                render()
                            }
                        }
                    4 -> showOriginal(content.original) { detail(p) }
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showOriginal(original: String, back: (() -> Unit)? = null) {
        val content = col().apply {
            setPadding(dp(24), dp(4), dp(24), dp(16))
            addView(text(original, 14, ink).apply { setTextIsSelectable(true) })
        }
        AppDialogs.Builder(this).setTitle("图片识别原文")
            .setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton(if (back == null) "关闭" else "返回") { _, _ -> back?.invoke() }
            .show()
    }

    private fun field(
        layout: LinearLayout,
        label: String,
        value: String,
        multi: Boolean,
    ): EditText {
        layout.addView(text(label, 13, muted, true).apply { setPadding(0, dp(8), 0, dp(6)) })
        return EditText(this).apply {
            setText(value)
            setTextColor(ink)
            textSize = 16f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = shape(paper, 12).apply { setStroke(dp(1), if (dark) 0xff42566b.toInt() else 0xffdce4ed.toInt()) }
            setSingleLine(!multi)
            if (multi) {
                minLines = 3
                gravity = Gravity.TOP
            }
            layout.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun styleCarrier(spinner: Spinner, companies: Array<String> = SmsParser.CARRIERS) {
        spinner.setPopupBackgroundDrawable(shape(paper, 16))
        spinner.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, companies) {
            private fun option(position: Int, dropdown: Boolean): View {
                val name = getItem(position).orEmpty()
                return row().apply {
                    minimumHeight = dp(48)
                    setPadding(dp(if (dropdown) 12 else 4), dp(8), dp(12), dp(8))
                    if (dropdown) background = shape(if (dark) 0xff25384a.toInt() else 0xfff2f6fa.toInt(), 8)
                    addView(CarrierLogos.view(this@MainActivity, name), LinearLayout.LayoutParams(dp(60), dp(30)).apply { rightMargin = dp(10) })
                    addView(text(name, 15, if (dropdown && position == spinner.selectedItemPosition) accent else ink).apply {
                        includeFontPadding = false
                        setSingleLine()
                        ellipsize = TextUtils.TruncateAt.END
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                }
            }
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = option(position, false)
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View = option(position, true)
        }
    }

    private fun edit(old: Store.Parcel?) {
        if (old == null) { addParcel(); return }
        val layout = col().apply { setPadding(dp(20), dp(4), dp(20), dp(12)) }
        val code = field(layout, "取件码 *", old?.code.orEmpty(), false)
        layout.addView(text("快递公司 *", 13, muted, true).apply { setPadding(0, dp(12), 0, dp(6)) })
        val companies = if (old.carrier in SmsParser.CARRIERS) SmsParser.CARRIERS else SmsParser.CARRIERS + old.carrier
        val carrier =
            Spinner(this).apply {
                styleCarrier(this, companies)
                setSelection(companies.indexOf(old.carrier))
            }
        layout.addView(carrier)
        val station = field(layout, "驿站名称 / 地址", old?.station.orEmpty(), false)
        val imageContent = ImageParcelText.split(old.source, old.note)
        val note = field(layout, "备注", imageContent.note, true)
        val dialog =
            AppDialogs.Builder(this)
                .setTitle(if (old == null) "添加取件码" else "编辑包裹")
                .setView(ScrollView(this).apply { addView(layout) })
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(-1).setOnClickListener save@{
                synchronized(Store) {
                    val value = code.text.toString().trim()
                    if (!SmsParser.valid(value)) {
                        code.error = "请输入 3–12 位字母、数字或短横线，不能是手机号"
                        return@save
                    }
                    val all = Store.load(this)
                    val company = carrier.selectedItem.toString()
                    if (
                        all.any {
                            it.completed == 0L &&
                                it.code.equals(value, true) &&
                                it.carrier == company &&
                                it.id != old?.id
                        }
                    ) {
                        code.error = "已有相同的待取记录"
                        return@save
                    }
                    val p =
                        (old?.copy() ?: Store.make(value, company, "", "", "手动添加")).apply {
                            this.code = value
                            this.carrier = company
                            this.station = station.text.toString().trim()
                            this.note = if (old.source == "图片识别") ImageParcelText.join(note.text.toString(), imageContent.original)
                                else note.text.toString().trim()
                        }
                    if (old == null) all.add(p)
                    else {
                        val index = all.indexOfFirst { it.id == old.id }
                        if (index >= 0) all[index] = p
                    }
                    Store.save(this, all)
                    dialog.dismiss()
                    render()
                    toast("已保存")
                }
            }
        }
        dialog.show()
    }

    private fun addParcel() {
        val restored = addDraft
        addDraft = null
        var fromImage = restored?.getBoolean("fromImage", false) ?: false
        var imageOriginal = restored?.getString("imageOriginal")
            ?: if (fromImage) restored?.getString("sms", "").orEmpty() else ""
        val layout = col().apply { setPadding(dp(20), dp(8), dp(20), dp(16)) }
        fun section(label: String) {
            layout.addView(text(label, 17, ink, true).apply { setPadding(0, dp(8), 0, dp(6)); includeFontPadding = false })
        }
        fun entry(hintText: String, multiline: Boolean = false): EditText = EditText(this).apply {
            hint = hintText; textSize = 15f; setTextColor(ink); setHintTextColor(muted)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = shape(paper, 12).apply { setStroke(dp(1), if (dark) 0xff42566b.toInt() else 0xffdce4ed.toInt()) }
            setSingleLine(!multiline)
            if (multiline) { minLines = 3; maxLines = 5; gravity = Gravity.TOP }
            layout.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        section("短信 / 图片内容")
        val sms = entry("粘贴短信，或选择图片识别取件信息", true)
        val pasteButton = action("粘贴短信") {
            val clip = (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip
            val value = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this).toString() else ""
            if (value.isBlank()) toast("剪贴板没有文字") else { fromImage = false; imageOriginal = ""; sms.setText(value); sms.clearFocus() }
        }
        val imageButton = action("图片识别") {
            try {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }, 23)
            } catch (_: ActivityNotFoundException) { toast("当前系统没有图片选择器，请使用粘贴或手动添加") }
        }
        val inputs = row()
        inputs.addView(pasteButton, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(4) })
        inputs.addView(imageButton, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(4) })
        layout.addView(inputs, LinearLayout.LayoutParams(-1, -2))
        val originalButton = action("查看识别原文") { showOriginal(imageOriginal) }.apply { visibility = View.GONE }
        layout.addView(originalButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        layout.addView(text("图片在本机识别，不上传；请核对后保存", 11, muted).apply { setPadding(0, dp(6), 0, 0) })
        val result = text("也可以直接填写下方取件信息", 12, muted).apply { setPadding(0, dp(8), 0, dp(8)) }
        layout.addView(result)
        section("取件信息")
        val code = entry("取件码 *")
        val carrier = Spinner(this).apply {
            contentDescription = "快递公司"
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, SmsParser.CARRIERS)
            styleCarrier(this)
            setSelection(SmsParser.CARRIERS.lastIndex)
        }
        layout.addView(text("快递公司", 12, muted).apply { setPadding(dp(2), 0, 0, dp(4)) })
        carrier.background = shape(paper, 12).apply { setStroke(dp(1), if (dark) 0xff42566b.toInt() else 0xffdce4ed.toInt()) }
        carrier.setPadding(dp(10), 0, dp(10), 0)
        layout.addView(carrier, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        val station = entry("驿站名称 / 地址")
        val note = entry("备注（选填）")
        var parsed = emptyList<SmsParser.Result>()
        var drafts = mutableListOf<Store.Parcel>()
        var selected = 0
        fun remember() {
            drafts.getOrNull(selected)?.apply {
                this.code = code.text.toString().trim()
                this.carrier = carrier.selectedItem.toString()
                this.station = station.text.toString().trim()
                this.note = note.text.toString().trim()
            }
        }
        fun fill(r: Store.Parcel) {
            code.setText(r.code)
            carrier.setSelection(SmsParser.CARRIERS.indexOf(r.carrier).takeIf { it >= 0 } ?: SmsParser.CARRIERS.lastIndex)
            station.setText(r.station)
            note.setText(r.note)
        }
        lateinit var dialog: AlertDialog
        val reviewButton = action("切换核对取件码") {}.apply { visibility = View.GONE }
        layout.addView(reviewButton, layout.indexOfChild(result) + 1,
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        fun refreshReview() {
            reviewButton.visibility = if (drafts.size > 1) View.VISIBLE else View.GONE
            reviewButton.text = "切换核对 · 第 ${selected + 1}/${drafts.size} 个"
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.let { save ->
                val fullLabel = if (drafts.size > 1) "全部添加（${drafts.size}）" else "保存"
                val cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                fun width(button: TextView, label: String) = maxOf(button.minWidth.toFloat(),
                    button.paint.measureText(label) + button.paddingLeft + button.paddingRight)
                val available = minOf(resources.displayMetrics.widthPixels - dp(32), dp(520)) - dp(48)
                // Keep both actions in one row at large font sizes; the count remains explicit.
                val compact = if (drafts.size > 1) "添加${drafts.size}" else fullLabel
                save.text = if (cancel != null && width(save, fullLabel) + width(cancel, cancel.text.toString()) + dp(12) > available)
                    compact else fullLabel
            }
        }
        fun chooseDraft(index: Int) {
            selected = index
            fill(drafts[index])
            code.error = null
            refreshReview()
        }
        watch(sms) { value ->
            remember()
            val oldParsed = parsed
            val oldDrafts = drafts
            val current = oldParsed.getOrNull(selected)
            originalButton.visibility = if (fromImage && imageOriginal.isNotBlank()) View.VISIBLE else View.GONE
            val image = if (fromImage) ImageParcelParser.parse(value) else null
            parsed = image?.parcels ?: SmsParser.parse(value)
            drafts = parsed.map { candidate ->
                oldDrafts.getOrNull(oldParsed.indexOf(candidate)) ?: Store.Parcel(
                    code = candidate.code, carrier = candidate.carrier, station = candidate.station)
            }.toMutableList()
            selected = parsed.indexOf(current).coerceAtLeast(0)
            if (drafts.isNotEmpty()) fill(drafts[selected])
            else if (image != null) {
                if (image.station.isNotBlank()) station.setText(image.station)
                if (image.carrier != "其他") carrier.setSelection(SmsParser.CARRIERS.indexOf(image.carrier))
            }
            result.text = when {
                value.isBlank() -> "也可以直接填写下方取件信息"
                parsed.isEmpty() -> "未识别到取件码，可在下方手动填写"
                parsed.size == 1 -> "已识别取件信息，可修改后保存"
                else -> "识别到 ${parsed.size} 个取件码，可逐条核对后一次添加"
            }
            result.setTextColor(if (parsed.isEmpty()) muted else accent)
            refreshReview()
        }
        reviewButton.setOnClickListener {
            remember()
            if (drafts.size > 1) AppDialogs.Builder(this).setTitle("逐条核对取件信息")
                .setItems(drafts.map { "${it.code} · ${it.carrier}" }.toTypedArray()) { _, index -> chooseDraft(index) }.show()
        }
        dialog = AppDialogs.Builder(this).setTitle("添加包裹")
            .setView(ScrollView(this).apply { addView(layout) })
            .setNegativeButton("取消", null).setPositiveButton("保存", null).create()
        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            refreshReview()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener save@{
                remember()
                val reviewed = if (drafts.size > 1) drafts else listOf(Store.Parcel(
                    code = code.text.toString().trim(), carrier = carrier.selectedItem.toString(),
                    station = station.text.toString().trim(), note = note.text.toString().trim()))
                val invalid = reviewed.indexOfFirst { !SmsParser.valid(it.code) }
                if (invalid >= 0) {
                    if (drafts.size > 1) {
                        chooseDraft(invalid)
                        result.text = "第 ${invalid + 1} 个取件码无效，请核对后再全部添加"
                    }
                    code.error = "请输入 3–12 位字母、数字或短横线，不能是手机号"
                    return@save
                }
                val candidates = reviewed.map { draft -> Store.make(draft.code, draft.carrier, draft.station,
                    if (fromImage) ImageParcelText.join(draft.note, imageOriginal) else draft.note,
                    if (fromImage) "图片识别" else if (sms.text.isBlank()) "手动添加" else sms.text.toString()) }
                val count = Store.addReviewed(this, candidates)
                if (count == 0 && candidates.size == 1) { code.error = "已有相同的待取记录"; return@save }
                val skipped = candidates.size - count
                dialog.dismiss(); render()
                toast(when {
                    skipped > 0 -> "已添加 $count 个，跳过 $skipped 个重复记录"
                    count > 1 -> "已添加 $count 个取件码"
                    else -> "已保存"
                })
            }
        }
        addSnapshot = {
            remember()
            Bundle().apply {
                putString("sms", sms.text.toString()); putString("code", code.text.toString())
                putString("station", station.text.toString()); putString("note", note.text.toString())
                putInt("carrier", carrier.selectedItemPosition); putBoolean("fromImage", fromImage)
                putString("imageOriginal", imageOriginal)
                putString("reviewedDrafts", JSONArray().apply { drafts.forEach { put(it.json()) } }.toString())
                putInt("selectedDraft", selected)
            }
        }
        fun releaseImage(uri: Uri?) {
            if (uri != null) try { contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) { }
        }
        imageResult = { uri ->
            pendingImage = uri
            result.text = "正在本机识别图片…"
            val editable = listOf<View>(sms, code, carrier, station, note, pasteButton, imageButton, reviewButton)
            editable.forEach { it.isEnabled = false }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            val recognizer = imageRecognizer ?: ImageRecognizer(this).also { imageRecognizer = it }
            recognizer.recognize(uri) { recognized, error ->
                releaseImage(uri)
                pendingImage = null
                if (!isDestroyed && !isFinishing && dialog.isShowing) {
                    editable.forEach { it.isEnabled = true }
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    if (error != null) { result.text = error; result.setTextColor(muted) }
                    else if (recognized.isNullOrBlank()) { result.text = "图片中没有识别到文字，请换一张清晰截图或手动填写"; result.setTextColor(muted) }
                    else { fromImage = true; imageOriginal = recognized; sms.setText(ImageParcelText.preview(recognized)); sms.clearFocus() }
                }
            }
        }
        dialog.setOnDismissListener {
            imageRecognizer?.cancel(); imageResult = null; addSnapshot = null
            if (!isChangingConfigurations) releaseImage(pendingImage)
            pendingImage = null
        }
        dialog.show()
        restored?.let {
            sms.setText(if (fromImage && !it.containsKey("imageOriginal")) ImageParcelText.preview(imageOriginal) else it.getString("sms", "")); code.setText(it.getString("code", ""))
            station.setText(it.getString("station", "")); note.setText(it.getString("note", ""))
            carrier.setSelection(it.getInt("carrier", SmsParser.CARRIERS.lastIndex).coerceIn(0, SmsParser.CARRIERS.lastIndex))
            val savedDrafts = try { JSONArray(it.getString("reviewedDrafts", "[]")) } catch (_: JSONException) { JSONArray() }
            if (savedDrafts.length() == drafts.size && drafts.isNotEmpty()) {
                drafts = MutableList(savedDrafts.length()) { index ->
                    val saved = savedDrafts.optJSONObject(index) ?: JSONObject()
                    Store.Parcel(code = saved.optString("code"), carrier = saved.optString("carrier", "其他"),
                        station = saved.optString("station"), note = saved.optString("note"))
                }
                chooseDraft(it.getInt("selectedDraft", 0).coerceIn(0, drafts.lastIndex))
            } else remember()
        }
        pendingImage?.let { uri -> handler.post { if (dialog.isShowing) imageResult?.invoke(uri) } }
    }

    private fun identityCode() {
        AppDialogs.Builder(this).setTitle("打开身份码")
            .setItems(arrayOf("淘宝身份码", "菜鸟身份码", "拼多多身份码")) { _, index -> IdentityLauncher.open(this, index) }
            .setNegativeButton("取消", null).show()
    }

    private fun batch() {
        val ids = visible().map { it.id }.toSet()
        if (ids.isEmpty()) {
            toast("当前没有可操作的记录")
            return
        }
        confirm(if (filterTab == 0) "全部取出" else "清空已取记录", "将处理当前筛选结果中的 ${ids.size} 个包裹。") {
            synchronized(Store) {
                val all = Store.load(this)
                if (filterTab == 0)
                    all.filter { it.id in ids }
                        .forEach { it.completed = System.currentTimeMillis() }
                else all.removeAll { it.id in ids }
                Store.save(this, all)
                render()
            }
        }
    }

    private fun stats() {
        val all = Store.load(this)
        val done = all.filter { it.completed > 0 }
        val recent = all.count { System.currentTimeMillis() - it.created < 7 * 86400000L }
        val summary = card(body)
        summary.addView(text("累计包裹", 14, muted))
        summary.addView(text(all.size.toString(), 48, ink, true))
        space(summary, 12)
        summary.addView(text("已取 ${done.size} 件  ·  待取 ${all.size - done.size} 件", 16, ink, true))
        heading("最近的取件情况")
        val c = card(body)
        c.addView(text("近 7 天收到：$recent 件", 17, ink, true))
        space(c, 12)
        val average =
            if (done.isEmpty()) "暂无数据"
            else
                String.format(
                    Locale.CHINA,
                    "%.1f 小时",
                    done.sumOf { maxOf(0L, it.completed - it.created) }.toDouble() /
                        done.size /
                        3600000,
                )
        c.addView(text("平均取件耗时：$average", 15, muted))
        heading("快递公司分布")
        val distribution = card(body)
        if (all.isEmpty()) distribution.addView(text("添加包裹后显示统计", 15, muted))
        all.groupingBy { it.carrier }
            .eachCount()
            .toSortedMap()
            .forEach { (carrier, count) ->
                val line = row()
                line.addView(text(carrier, 14, ink), LinearLayout.LayoutParams(0, -2, 1f))
                line.addView(text("$count 件", 14, accent, true))
                distribution.addView(line)
                distribution.addView(
                    ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                        max = maxOf(1, all.size)
                        progress = count
                        progressTintList = ColorStateList.valueOf(accent)
                    },
                    LinearLayout.LayoutParams(-1, dp(16)),
                )
                space(distribution, 12)
            }
        body.addView(text("统计基于本机当前保留的记录；删除记录会同步改变统计。", 12, muted))
    }

    private fun settingRow(
        c: LinearLayout,
        title: String,
        description: String?,
        action: () -> Unit,
    ): TextView? {
        var detail: TextView? = null
        val layout =
            col().apply {
                setPadding(0, dp(12), 0, dp(12))
                addView(text("$title  ›", 16, ink, true))
                if (description != null) {
                    space(this, 5)
                    detail = text(description, 12, muted)
                    addView(detail)
                }
                setOnClickListener { action() }
            }
        c.addView(layout)
        return detail
    }

    private fun toggle(
        c: LinearLayout,
        title: String,
        description: String,
        key: String,
        default: Boolean,
        action: (() -> Unit)? = null,
    ) {
        val layout = row().apply { setPadding(0, dp(10), 0, dp(10)) }
        val labels = col()
        labels.addView(text(title, 16, ink, true))
        space(labels, 4)
        labels.addView(text(description, 12, muted))
        layout.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        layout.addView(
            Switch(this).apply {
                contentDescription = title
                isChecked = Store.prefs(this@MainActivity).getBoolean(key, default)
                setOnCheckedChangeListener { _, checked ->
                    Store.prefs(this@MainActivity).edit().putBoolean(key, checked).apply()
                    action?.invoke()
                    settingsSummaries.forEach { it() }
                }
            }
        )
        labels.setOnClickListener { (layout.getChildAt(1) as Switch).toggle() }
        c.addView(layout)
    }

    private fun requestNotification() {
        if (
            Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        )
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 12)
    }

    private fun settingsGroup(key: String, title: String, summary: () -> String): LinearLayout {
        val container = card(body).apply { setPadding(dp(18), 0, dp(18), 0) }
        val header = row().apply { minimumHeight = dp(80); setPadding(0, dp(14), 0, dp(14)); isFocusable = true }
        val labels = col()
        labels.addView(text(title, 17, ink, true))
        val detail = text(summary(), 12, muted).apply { setPadding(0, dp(5), 0, 0) }
        labels.addView(detail)
        header.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        val arrow = icon(R.drawable.ic_expand, muted, "").apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        header.addView(arrow, LinearLayout.LayoutParams(dp(32), dp(32)))
        container.addView(header)
        val content = col().apply { setPadding(0, 0, 0, dp(14)) }
        divider(content)
        container.addView(content)
        fun refresh() {
            val open = key in expandedSettings
            content.visibility = if (open) View.VISIBLE else View.GONE
            arrow.rotation = if (open) 180f else 0f
            detail.text = summary()
            header.contentDescription = "$title，${detail.text}，${if (open) "已展开，点击收起" else "已折叠，点击展开"}"
        }
        header.setOnClickListener {
            if (!expandedSettings.add(key)) expandedSettings.remove(key)
            refresh()
        }
        settingsSummaries.add(::refresh)
        refresh()
        return content
    }

    @Deprecated("Legacy Android back navigation")
    override fun onBackPressed() {
        if (::updates.isInitialized && updates.dismissPanel()) return
        if (page == 0 && searchOpen) closeSearch() else super.onBackPressed()
    }

    private fun settings() {
        val prefs = Store.prefs(this)
        val c = settingsGroup("sms", "短信识别") {
            if (prefs.getBoolean("auto_sms", true)) {
                if (checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED) "自动识别已开启 · 支持历史短信扫描"
                else "自动识别待授权 · 支持历史短信扫描"
            } else "自动识别已关闭 · 支持历史短信扫描"
        }
        toggle(c, "自动识别新短信", "需要接收短信权限", "auto_sms", true) {
            if (Store.prefs(this).getBoolean("auto_sms", true)) requestSmsPermission()
        }
        settingRow(
            c,
            "自动识别权限",
            if (
                checkSelfPermission(Manifest.permission.RECEIVE_SMS) ==
                    PackageManager.PERMISSION_GRANTED
            )
                "接收短信权限已开启"
            else "点击授权，开启自动提取",
            ::requestSmsPermission,
        )
        settingRow(c, "扫描最近 30 天短信", if (scanning) "正在扫描…" else "仅识别快递相关短信，重复记录自动跳过", ::scan)
        val reminders = settingsGroup("reminders", "通知与提醒") {
            val notify = if (prefs.getBoolean("notify", true)) "新包裹通知已开启" else "新包裹通知已关闭"
            val daily = if (prefs.getBoolean("remind", false)) String.format(Locale.CHINA, "每天 %02d:%02d 提醒", prefs.getInt("hour", 18), prefs.getInt("minute", 0)) else "每日提醒已关闭"
            "$notify · $daily"
        }
        toggle(reminders, "新包裹通知", "识别成功后发送取件提醒", "notify", true) {
            if (Store.prefs(this).getBoolean("notify", true)) requestNotification()
        }
        toggle(reminders, "每天提醒未取包裹", "系统省电可能延迟提醒", "remind", false) {
            ReminderReceiver.schedule(this)
            if (Store.prefs(this).getBoolean("remind", false)) requestNotification()
        }
        val hour = Store.prefs(this).getInt("hour", 18)
        val minute = Store.prefs(this).getInt("minute", 0)
        settingRow(reminders, "提醒时间", String.format(Locale.CHINA, "每天 %02d:%02d", hour, minute)) {
            AppDialogs.TimeDialog(
                    this,
                    { _, h, m ->
                        Store.prefs(this).edit().putInt("hour", h).putInt("minute", m).apply()
                        ReminderReceiver.schedule(this)
                        render()
                    },
                    hour,
                    minute,
                    true,
                )
                .show()
        }
        settingRow(c, "系统应用设置", "权限、自启动及电池管理") {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName"),
                )
            )
        }
        val data = settingsGroup("data", "显示与数据") { "${if (prefs.getBoolean("dark", false)) "深色模式" else "浅色模式"} · 本地备份 · 桌面小组件" }
        toggle(data, "深色模式", "切换页面背景与文字配色", "dark", false) { recreate() }
        settingRow(data, "导出本地备份", "JSON 格式，包含完整取件记录") {
            startActivityForResult(
                Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .setType("application/json")
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .putExtra(
                        Intent.EXTRA_TITLE,
                        "pickup-backup-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.CHINA).format(Date())}.json",
                    ),
                21,
            )
        }
        settingRow(data, "导入备份", "先验证完整文件，再合并；按记录 ID 去重") {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .setType("*/*")
                    .addCategory(Intent.CATEGORY_OPENABLE),
                22,
            )
        }
        settingRow(data, "桌面小组件", "长按桌面 → 小组件 → 拾件簿") {
            AppDialogs.Builder(this)
                .setTitle("桌面小组件")
                .setMessage("在桌面长按空白处，找到拾件簿小组件。小组件显示待取数量，常规双栏尺寸展示 6 个取件码；显示数量随高度和字体大小调整，点击打开应用。")
                .setPositiveButton("知道了", null)
                .show()
        }
        val update = settingsGroup("update", "应用更新") { "${updates.version()} · ${updates.channelLabel()} · ${if (prefs.getBoolean("auto_update", true)) "自动检查已开启" else "自动检查已关闭"}" }
        settingRow(update, "更新渠道", "${updates.channelLabel()} · 正式版经评审后发布，测试版提前体验") {
            AppDialogs.Builder(this).setTitle("选择更新渠道")
                .setMessage("正式版仅接收审核通过的更新。测试版用于提前体验；切回正式版不会自动降级。切换渠道会取消正在下载的更新。")
                .setSingleChoiceItems(arrayOf("正式版（推荐）", "测试版"), if (updates.channel() == UpdateProtocol.BETA) 1 else 0) { dialog, index ->
                    updates.changeChannel(if (index == 1) UpdateProtocol.BETA else UpdateProtocol.STABLE)
                    dialog.dismiss(); render()
                }.setNegativeButton("取消", null).show()
        }
        updateStatus = settingRow(update, "检查更新", "当前版本 ${updates.version()} · ${UpdateFiles.status(this)}") {
            updates.check(true)
        }
        toggle(update, "自动检查更新", "每天首次打开时检查新版本", "auto_update", true)
        val about = settingsGroup("about", "关于拾件簿") { "短信与取件记录仅保存在本机" }
        about.addView(text("拾件簿 ${updates.version()}", 18, ink, true))
        space(about, 8)
        about.addView(text("短信识别、取件管理与本地备份。短信和取件记录在本机处理，不上传。", 13, muted))
    }

    private fun requestSmsPermission() {
        if (checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED) {
            toast("接收短信权限已开启")
            return
        }
        AppDialogs.Builder(this)
            .setTitle("开启新短信识别")
            .setMessage("接收短信权限用于提取新收到的快递短信取件码。短信只在设备上处理，不会上传。拒绝后仍可手动添加或粘贴识别。")
            .setNegativeButton("暂不开启", null)
            .setPositiveButton("继续") { _, _ ->
                requestPermissions(arrayOf(Manifest.permission.RECEIVE_SMS), 10)
            }
            .show()
    }

    override fun onRequestPermissionsResult(
        request: Int,
        permissions: Array<out String>,
        results: IntArray,
    ) {
        super.onRequestPermissionsResult(request, permissions, results)
        render()
        if (request == 11) {
            if (
                checkSelfPermission(Manifest.permission.READ_SMS) ==
                    PackageManager.PERMISSION_GRANTED
            )
                scan()
            else toast("未获得读取权限，可使用粘贴识别")
        }
    }

    private fun scan() {
        if (scanning) return
        if (
            checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.READ_SMS), 11)
            return
        }
        confirm("扫描历史短信", "读取最近 30 天的短信，仅保存识别到的取件码及其原始快递短信。") {
            scanning = true
            toast("正在扫描短信…")
            Thread(
                    {
                        var count = 0
                        var failure: String? = null
                        try {
                            contentResolver
                                .query(
                                    Telephony.Sms.Inbox.CONTENT_URI,
                                    arrayOf("body", "date"),
                                    "date >= ?",
                                    arrayOf(
                                        (System.currentTimeMillis() - 30 * 86400000L).toString()
                                    ),
                                    "date ASC",
                                )
                                .use { cursor ->
                                    if (cursor != null)
                                        while (cursor.moveToNext()) count +=
                                            Store.ingest(
                                                this,
                                                cursor.getString(0),
                                                cursor.getLong(1),
                                            )
                                }
                        } catch (e: Exception) {
                            failure = e.message ?: "无法读取短信"
                        }
                        handler.post {
                            scanning = false
                            if (!isDestroyed) {
                                render()
                                toast(
                                    if (failure == null) "扫描完成，新增 $count 条记录" else "扫描失败：$failure"
                                )
                            }
                        }
                    },
                    "sms-scan",
                )
                .start()
        }
    }

    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (request == 23) {
            if (result == RESULT_OK) data?.data?.let { uri ->
                try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) { }
                pendingImage = uri
                if (imageResult != null) imageResult?.invoke(uri)
                else if (addDraft == null) addParcel()
            }
            return
        }
        val uri = data?.data ?: return
        if (result != RESULT_OK) return
        try {
            when (request) {
                21 -> {
                    val json =
                        JSONObject()
                            .put("format", "pickup-local-v1")
                            .put(
                                "parcels",
                                JSONArray().apply {
                                    Store.load(this@MainActivity).forEach { put(it.json()) }
                                },
                            )
                    (contentResolver.openOutputStream(uri, "wt") ?: throw IOException("无法写入文件"))
                        .use { it.write(json.toString(2).toByteArray(Charsets.UTF_8)) }
                    toast("备份已导出")
                }
                22 -> {
                    val bytes = ByteArrayOutputStream()
                    (contentResolver.openInputStream(uri) ?: throw IOException("无法读取文件")).use {
                        input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = input.read(buffer)
                            if (n == -1) break
                            bytes.write(buffer, 0, n)
                            if (bytes.size() > 5 * 1024 * 1024) throw IOException("备份不能超过 5 MB")
                        }
                    }
                    val json = JSONObject(bytes.toString("UTF-8"))
                    if (json.getString("format") != "pickup-local-v1") throw IOException("不支持的备份格式")
                    val all = json.getJSONArray("parcels")
                    if (all.length() > 10000) throw IOException("记录不能超过 10000 条")
                    val incoming = List(all.length()) { Store.Parcel.from(all.getJSONObject(it)) }
                    confirm("导入备份", "已验证 ${incoming.size} 条记录，是否合并到本机？") {
                        synchronized(Store) {
                            val local = Store.load(this)
                            val ids = local.map { it.id }.toMutableSet()
                            val added = incoming.filter { ids.add(it.id) }
                            local.addAll(added)
                            Store.save(this, local)
                            render()
                            toast("已导入 ${added.size} 条记录")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AppDialogs.Builder(this)
                .setTitle("备份操作失败")
                .setMessage(e.message)
                .setPositiveButton("知道了", null)
                .show()
        }
    }
}
