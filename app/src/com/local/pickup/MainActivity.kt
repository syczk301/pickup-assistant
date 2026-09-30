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
    private var carrierFilter = "全部"
    private var dark = false
    private var ink = 0
    private var muted = 0
    private var paper = 0
    private var bg = 0
    private var accent = 0
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var scanning = false
    private val listener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "parcels" && !scanning) handler.post { if (!isDestroyed) render() }
        }

    override fun onCreate(saved: Bundle?) {
        if (Store.prefs(this).getBoolean("dark", false))
            setTheme(android.R.style.Theme_Material_NoActionBar)
        super.onCreate(saved)
        updates = UpdateController(this)
        if (saved != null) {
            page = saved.getInt("page")
            filterTab = saved.getInt("filterTab")
            sort = saved.getInt("sort")
            query = saved.getString("query", "")
            carrierFilter = saved.getString("carrierFilter", "全部")
        } else sort = Store.prefs(this).getInt("sort", 0)
        Store.prefs(this).registerOnSharedPreferenceChangeListener(listener)
        ReminderReceiver.schedule(this)
        render()
    }

    override fun onSaveInstanceState(state: Bundle) {
        super.onSaveInstanceState(state)
        state.putInt("page", page)
        state.putInt("filterTab", filterTab)
        state.putInt("sort", sort)
        state.putString("query", query)
        state.putString("carrierFilter", carrierFilter)
    }

    override fun onDestroy() {
        if (::updates.isInitialized) updates.close()
        handler.removeCallbacksAndMessages(null)
        Store.prefs(this).unregisterOnSharedPreferenceChangeListener(listener)
        super.onDestroy()
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

    private fun space(v: LinearLayout, n: Int) {
        v.addView(View(this), LinearLayout.LayoutParams(1, dp(n)))
    }

    private fun card(parent: LinearLayout) =
        col().apply {
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = shape(paper, 20)
            parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }

    private fun heading(s: String) {
        body.addView(text(s, 14, muted, true).apply { setPadding(dp(2), dp(12), 0, dp(12)) })
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    private fun confirm(title: String, message: String, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ -> action() }
            .show()
    }

    private fun date(time: Long) = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(time))

    private fun render() {
        dark = Store.prefs(this).getBoolean("dark", false)
        ink = if (dark) 0xffeef5fc.toInt() else 0xff082b43.toInt()
        muted = if (dark) 0xffa9b7c6.toInt() else 0xff788695.toInt()
        paper = if (dark) 0xff1c2937.toInt() else Color.WHITE
        bg = if (dark) 0xff111c27.toInt() else if (page == 0) Color.WHITE else 0xfff5f7f9.toInt()
        accent = if (dark) 0xff8dceff.toInt() else 0xff1677dd.toInt()
        val top = if (dark) 0xff173449.toInt() else 0xffdff4f9.toInt()
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
            col().apply { setPadding(dp(18), dp(12), dp(18), dp(if (page == 0) 8 else 20)) }
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
            title.addView(text("取件助手", 11, muted))
            header.addView(title)
            space(header, 8)
            header.addView(text(if (page == 1) "你的包裹记录，一目了然。" else "短信在本机识别，取件记录保存在本机。", 13, muted))
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        body = col().apply { setPadding(dp(18), dp(4), dp(18), dp(20)) }
        scroll.addView(body)
        when (page) {
            0 -> home()
            1 -> stats()
            else -> settings()
        }
        val nav =
            row().apply {
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = shape(paper, 36)
                elevation = dp(8).toFloat()
            }
        root.addView(
            nav,
            LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(50), dp(12), dp(50), dp(20)) },
        )
        arrayOf("包裹", "统计", "设置").forEachIndexed { index, label ->
            val tab =
                text(label, 15, if (index == page) Color.WHITE else muted, index == page).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(14), 0, dp(14))
                    if (index == page)
                        background = shape(if (dark) 0xff314a61.toInt() else Color.BLACK, 28)
                    setOnClickListener {
                        page = index
                        render()
                    }
                }
            nav.addView(tab, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    private fun homeHeader(header: LinearLayout) {
        val all = Store.load(this)
        val pending = all.count { it.completed == 0L }
        val line = row()
        header.addView(line)
        repeat(2) { index ->
            val label = col()
            label.addView(
                text(
                        if (index == 0) "待取 ($pending)" else "已取 (${all.size - pending})",
                        22,
                        if (filterTab == index) ink else muted,
                        true,
                    )
                    .apply {
                        gravity = Gravity.CENTER
                        setPadding(0, dp(10), 0, dp(8))
                    }
            )
            label.addView(
                View(this).apply {
                    background =
                        shape(if (filterTab == index) 0xff42ddba.toInt() else Color.TRANSPARENT, 2)
                },
                LinearLayout.LayoutParams(dp(48), dp(3)).apply { gravity = Gravity.CENTER },
            )
            line.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
            label.setOnClickListener {
                filterTab = index
                render()
            }
        }
        line.addView(
            text("＋", 30, ink).apply {
                gravity = Gravity.CENTER
                contentDescription = "手动添加"
                setOnClickListener { edit(null) }
            },
            LinearLayout.LayoutParams(dp(48), dp(48)),
        )
        line.addView(
            text("≡", 30, ink).apply {
                gravity = Gravity.CENTER
                contentDescription = "粘贴短信识别"
                setOnClickListener { paste() }
            },
            LinearLayout.LayoutParams(dp(48), dp(48)),
        )
    }

    private fun home() {
        val controls = row()
        controls.addView(
            action(arrayOf("按时间", "按取件码", "按公司")[sort] + " ▾") {
                    sort = (sort + 1) % 3
                    Store.prefs(this).edit().putInt("sort", sort).apply()
                    render()
                }
                .apply {
                    setTextColor(Color.WHITE)
                    background = shape(Color.BLACK, 24)
                }
        )
        controls.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        controls.addView(
            action(if (carrierFilter == "全部") "筛选 ▾" else "$carrierFilter ▾") {
                AlertDialog.Builder(this)
                    .setTitle("快递公司筛选")
                    .setItems(withAll()) { _, index ->
                        carrierFilter = withAll()[index]
                        render()
                    }
                    .show()
            }
        )
        body.addView(controls)
        space(body, 12)
        val search =
            EditText(this).apply {
                setSingleLine()
                textSize = 13f
                setTextColor(ink)
                setHintTextColor(muted)
                hint = "搜索取件码、驿站、快递公司"
                setPadding(dp(14), dp(6), dp(14), dp(6))
                background = shape(if (dark) paper else 0xfff3f7f9.toInt(), 18)
                setText(query)
            }
        body.addView(search, LinearLayout.LayoutParams(-1, dp(42)))
        space(body, 18)
        listing = col()
        body.addView(listing)
        renderList()
        watch(search) {
            query = it
            renderList()
        }
        if (
            checkSelfPermission(Manifest.permission.RECEIVE_SMS) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            space(body, 10)
            body.addView(action("开启短信权限 · 自动提取新取件码", ::permissions))
        }
        if (Store.load(this).isNotEmpty()) {
            space(body, 12)
            body.addView(action(if (filterTab == 0) "全部取出" else "清空已取记录", ::batch))
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
            val empty = card(listing)
            space(empty, 20)
            empty.addView(text("□", 58, accent).apply { gravity = Gravity.CENTER })
            empty.addView(
                text(
                        if (query.isEmpty() && carrierFilter == "全部") {
                            if (filterTab == 0) "暂无待取包裹" else "还没有已取记录"
                        } else "没有匹配的包裹",
                        18,
                        ink,
                        true,
                    )
                    .apply { gravity = Gravity.CENTER }
            )
            space(empty, 10)
            empty.addView(
                text(
                        if (filterTab == 0) "收到快递短信后自动识别\n也可以手动添加或粘贴短信" else "完成取件后，可以在这里回看",
                        13,
                        muted,
                    )
                    .apply { gravity = Gravity.CENTER }
            )
            space(empty, 24)
            return
        }
        for (p in shown) {
            val c =
                card(listing).apply {
                    background =
                        shape(paper, 28).apply {
                            setStroke(dp(1), if (dark) 0xff354453.toInt() else 0xffe5e5e5.toInt())
                        }
                    elevation = dp(2).toFloat()
                }
            val top = row()
            top.addView(
                text(p.station.ifEmpty { p.carrier }, 17, ink, true).apply {
                    setSingleLine()
                    ellipsize = TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            top.addView(
                text(
                    date(if (p.completed == 0L) p.created else p.completed),
                    11,
                    if (p.completed == 0L) 0xff34bb62.toInt() else muted,
                )
            )
            c.addView(top)
            space(c, 4)
            c.addView(text(if (p.station.isEmpty()) "未填写取件地点" else p.carrier, 13, muted))
            space(c, 10)
            val line = row()
            line.addView(
                text(p.carrier.take(2), 12, Color.WHITE, true).apply {
                    gravity = Gravity.CENTER
                    background =
                        shape(
                            when {
                                p.carrier.contains("圆通") -> 0xff562686.toInt()
                                p.carrier.contains("顺丰") -> 0xff222222.toInt()
                                else -> 0xff1677ff.toInt()
                            },
                            7,
                        )
                },
                LinearLayout.LayoutParams(dp(32), dp(32)),
            )
            line.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
            line.addView(
                text(
                        p.code,
                        if (p.code.length > 9) 23 else 28,
                        if (p.completed == 0L) {
                            if (dark) ink else Color.BLACK
                        } else muted,
                        true,
                    )
                    .apply {
                        setSingleLine()
                        contentDescription = "取件码 ${p.code}，点击复制"
                        setOnClickListener { copy(p.code) }
                    },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            line.addView(
                text(if (p.completed == 0L) "○" else "✓", 28, accent).apply {
                    gravity = Gravity.CENTER
                    contentDescription = (if (p.completed == 0L) "标记已取 " else "恢复待取 ") + p.code
                    setOnClickListener { status(p.id, p.completed == 0L) }
                },
                LinearLayout.LayoutParams(dp(44), dp(44)),
            )
            c.addView(line)
            c.contentDescription = "包裹详情 ${p.code}"
            c.setOnClickListener { detail(p) }
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
        AlertDialog.Builder(this)
            .setTitle("${p.code} · ${p.carrier}")
            .setMessage(
                "驿站：${p.station.ifEmpty { "未填写" }}\n收到：${date(p.created)}" +
                    (if (p.completed > 0) "\n取件：${date(p.completed)}" else "") +
                    "\n来源：${p.source}\n\n${p.note}"
            )
            .setItems(
                arrayOf("复制取件码", "编辑信息", if (p.completed == 0L) "标记已取" else "恢复待取", "删除记录")
            ) { _, index ->
                when (index) {
                    0 -> copy(p.code)
                    1 -> edit(p)
                    2 -> status(p.id, p.completed == 0L)
                    else ->
                        confirm("删除包裹", "删除取件码 ${p.code}？") {
                            synchronized(Store) {
                                val all = Store.load(this)
                                all.removeAll { it.id == p.id }
                                Store.save(this, all)
                                render()
                            }
                        }
                }
            }
            .setNegativeButton("关闭", null)
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
            setSingleLine(!multi)
            if (multi) {
                minLines = 3
                gravity = Gravity.TOP
            }
            layout.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun edit(old: Store.Parcel?) {
        val layout = col().apply { setPadding(dp(20), dp(4), dp(20), dp(12)) }
        val code = field(layout, "取件码 *", old?.code.orEmpty(), false)
        layout.addView(text("快递公司 *", 13, muted, true).apply { setPadding(0, dp(12), 0, dp(6)) })
        val carrier =
            Spinner(this).apply {
                adapter =
                    ArrayAdapter(
                        this@MainActivity,
                        android.R.layout.simple_spinner_dropdown_item,
                        SmsParser.CARRIERS,
                    )
                if (old != null)
                    SmsParser.CARRIERS.indexOf(old.carrier).takeIf { it >= 0 }?.let(::setSelection)
            }
        layout.addView(carrier)
        val station = field(layout, "驿站名称 / 地址", old?.station.orEmpty(), false)
        val note = field(layout, "备注", old?.note.orEmpty(), true)
        val dialog =
            AlertDialog.Builder(this)
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
                            this.note = note.text.toString().trim()
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

    private fun paste() {
        val layout = col().apply { setPadding(dp(20), dp(6), dp(20), dp(12)) }
        val input =
            field(layout, "请粘贴完整快递短信", "", true).apply {
                minLines = 5
                hint = "【菜鸟驿站】包裹已到达南门驿站，取件码：8-2066，请及时取件。"
            }
        val result = text("识别结果会在这里显示", 13, muted)
        layout.addView(result)
        watch(input) {
            val results = SmsParser.parse(it)
            result.text =
                if (results.isEmpty()) "未识别到取件码，请补充完整短信"
                else results.joinToString("\n") { r -> "${r.carrier} · ${r.code}" }
        }
        val dialog =
            AlertDialog.Builder(this)
                .setTitle("短信识别")
                .setView(ScrollView(this).apply { addView(layout) })
                .setNegativeButton("取消", null)
                .setPositiveButton("识别并保存", null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(-1).setOnClickListener save@{
                val value = input.text.toString()
                if (SmsParser.parse(value).isEmpty()) {
                    input.error = "未识别到取件码"
                    return@save
                }
                val count = Store.ingest(this, value, System.currentTimeMillis())
                dialog.dismiss()
                render()
                toast(if (count > 0) "已添加 $count 个取件码" else "记录已存在，无需重复添加")
            }
        }
        dialog.show()
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
    ) {
        val layout =
            col().apply {
                setPadding(0, dp(12), 0, dp(12))
                addView(text("$title  ›", 16, ink, true))
                if (description != null) {
                    space(this, 5)
                    addView(text(description, 12, muted))
                }
                setOnClickListener { action() }
            }
        c.addView(layout)
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
                }
            }
        )
        c.addView(layout)
    }

    private fun requestNotification() {
        if (
            Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        )
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
    }

    private fun settings() {
        heading("自动识别与提醒")
        val c = card(body)
        toggle(c, "自动识别新短信", "需要接收短信权限", "auto_sms", true) {
            if (Store.prefs(this).getBoolean("auto_sms", true)) permissions()
        }
        settingRow(
            c,
            "短信与通知权限",
            if (
                checkSelfPermission(Manifest.permission.RECEIVE_SMS) ==
                    PackageManager.PERMISSION_GRANTED
            )
                "接收短信权限已开启"
            else "点击授权，开启自动提取",
            ::permissions,
        )
        settingRow(c, "扫描最近 30 天短信", if (scanning) "正在扫描…" else "仅识别快递相关短信，重复记录自动跳过", ::scan)
        toggle(c, "新包裹通知", "识别成功后发送取件提醒", "notify", true) {
            if (Store.prefs(this).getBoolean("notify", true)) requestNotification()
        }
        toggle(c, "每天提醒未取包裹", "系统省电可能延迟提醒", "remind", false) {
            ReminderReceiver.schedule(this)
            if (Store.prefs(this).getBoolean("remind", false)) requestNotification()
        }
        val hour = Store.prefs(this).getInt("hour", 18)
        val minute = Store.prefs(this).getInt("minute", 0)
        settingRow(c, "提醒时间", String.format(Locale.CHINA, "每天 %02d:%02d", hour, minute)) {
            TimePickerDialog(
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
        heading("显示与数据")
        val data = card(body)
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
        settingRow(data, "桌面小组件", "长按桌面 → 小组件 → 取件助手") {
            AlertDialog.Builder(this)
                .setTitle("桌面小组件")
                .setMessage("在桌面长按空白处，找到取件助手小组件。小组件显示待取数量和最近 3 个取件码；点击打开应用。")
                .setPositiveButton("知道了", null)
                .show()
        }
        heading("应用更新")
        val update = card(body)
        settingRow(update, "检查更新", "当前版本 ${updates.version()} · ${UpdateFiles.status(this)}") {
            updates.check(true)
        }
        toggle(update, "自动检查更新", "每天首次打开时检查新版本", "auto_update", true)
        settingRow(update, "更新地址", updates.source()) { updates.configure() }
        heading("关于")
        val about = card(body)
        about.addView(text("取件助手 ${updates.version()}", 18, ink, true))
        space(about, 8)
        about.addView(text("短信识别、取件管理与本地备份。短信和取件记录在本机处理，不上传。", 13, muted))
    }

    private fun permissions() {
        val missing =
            mutableListOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
                .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                .toMutableList()
        if (
            Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        )
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        if (missing.isEmpty()) {
            toast("短信及通知权限已开启")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("开启本地短信识别")
            .setMessage("接收短信权限用于提取新快递短信的取件码。读取短信权限用于手动扫描历史短信。短信只在设备上处理，不会上传。拒绝权限后仍可手动添加或粘贴识别。")
            .setNegativeButton("暂不开启", null)
            .setPositiveButton("继续") { _, _ -> requestPermissions(missing.toTypedArray(), 10) }
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
            AlertDialog.Builder(this)
                .setTitle("备份操作失败")
                .setMessage(e.message)
                .setPositiveButton("知道了", null)
                .show()
        }
    }
}
