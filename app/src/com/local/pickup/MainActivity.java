package com.local.pickup;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.provider.Settings;
import android.provider.Telephony;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import org.json.*;

public class MainActivity extends Activity {
  private LinearLayout root, body, nav, listing;
  private int page = 0, filterTab = 0, sort = 0;
  private String query = "", carrierFilter = "全部";
  private boolean dark;
  private int ink, muted, paper, bg, accent;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private android.content.SharedPreferences.OnSharedPreferenceChangeListener listener;
  private boolean scanning = false;
  private UpdateController updates;

  private int dp(float n) {
    return (int) (n * getResources().getDisplayMetrics().density + .5f);
  }

  @Override
  public void onCreate(Bundle saved) {
    if (Store.prefs(this).getBoolean("dark", false))
      setTheme(android.R.style.Theme_Material_NoActionBar);
    super.onCreate(saved);
    updates = new UpdateController(this);
    if (saved != null) {
      page = saved.getInt("page");
      filterTab = saved.getInt("filterTab");
      query = saved.getString("query", "");
      carrierFilter = saved.getString("carrierFilter", "全部");
      sort = saved.getInt("sort");
    } else sort = Store.prefs(this).getInt("sort", 0);
    listener =
        (p, key) -> {
          if ("parcels".equals(key) && !scanning)
            handler.post(
                () -> {
                  if (!isDestroyed()) render();
                });
        };
    Store.prefs(this).registerOnSharedPreferenceChangeListener(listener);
    ReminderReceiver.schedule(this);
    render();
  }

  @Override
  public void onSaveInstanceState(Bundle b) {
    super.onSaveInstanceState(b);
    b.putInt("page", page);
    b.putInt("filterTab", filterTab);
    b.putInt("sort", sort);
    b.putString("query", query);
    b.putString("carrierFilter", carrierFilter);
  }

  @Override
  public void onDestroy() {
    if(updates != null) updates.close();
    Store.prefs(this).unregisterOnSharedPreferenceChangeListener(listener);
    super.onDestroy();
  }

  @Override
  public void onResume() {
    super.onResume();
    if (root != null) render();
    if(updates != null) updates.resume();
  }

  private GradientDrawable shape(int color, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    return d;
  }

  private LinearLayout col() {
    LinearLayout v = new LinearLayout(this);
    v.setOrientation(LinearLayout.VERTICAL);
    return v;
  }

  private LinearLayout row() {
    LinearLayout v = new LinearLayout(this);
    v.setOrientation(LinearLayout.HORIZONTAL);
    v.setGravity(Gravity.CENTER_VERTICAL);
    return v;
  }

  private TextView text(String s, float size, int color, boolean bold) {
    TextView v = new TextView(this);
    v.setText(s);
    v.setTextSize(size);
    v.setTextColor(color);
    if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    return v;
  }

  private TextView action(String s, Runnable fn) {
    TextView v = text(s, 14, accent, true);
    v.setGravity(Gravity.CENTER);
    v.setPadding(dp(14), dp(12), dp(14), dp(12));
    v.setBackground(shape(dark ? 0xff25384a : 0xffeaf2fa, 14));
    v.setMinHeight(dp(48));
    v.setOnClickListener(w -> fn.run());
    return v;
  }

  private void space(LinearLayout v, int n) {
    View s = new View(this);
    v.addView(s, new LinearLayout.LayoutParams(1, dp(n)));
  }

  private LinearLayout card(LinearLayout parent) {
    LinearLayout v = col();
    v.setPadding(dp(18), dp(18), dp(18), dp(18));
    v.setBackground(shape(paper, 20));
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.bottomMargin = dp(12);
    parent.addView(v, p);
    return v;
  }

  private void heading(String s) {
    TextView t = text(s, 14, muted, true);
    t.setPadding(dp(2), dp(12), 0, dp(12));
    body.addView(t);
  }

  private void toast(String s) {
    Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
  }

  private void confirm(String title, String msg, Runnable f) {
    new AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(msg)
        .setNegativeButton("取消", null)
        .setPositiveButton("确定", (d, w) -> f.run())
        .show();
  }

  private String date(long t) {
    return new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(t));
  }

  private void render() {
    dark = Store.prefs(this).getBoolean("dark", false);
    ink = dark ? 0xffeef5fc : 0xff082b43;
    muted = dark ? 0xffa9b7c6 : 0xff788695;
    paper = dark ? 0xff1c2937 : Color.WHITE;
    bg = dark ? 0xff111c27 : (page == 0 ? Color.WHITE : 0xfff5f7f9);
    accent = dark ? 0xff8dceff : 0xff1677dd;
    getWindow().setStatusBarColor(dark ? bg : 0xffdff4f9);
    getWindow().setNavigationBarColor(paper);
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            dark
                ? 0
                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    root = col();
    root.setFocusableInTouchMode(true);
    root.requestFocus();
    root.setBackground(
        new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            new int[] {dark ? 0xff173449 : 0xffdff4f9, bg, bg}));
    setContentView(root);
    LinearLayout header = col();
    header.setPadding(dp(18), dp(12), dp(18), dp(page == 0 ? 8 : 20));
    GradientDrawable gradient =
        new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            new int[] {dark ? 0xff173449 : 0xffdff4f9, bg});
    if (page != 0) header.setBackground(gradient);
    root.addView(header);
    if (page == 0) homeHeader(header);
    else {
      LinearLayout title = row();
      title.addView(
          text(page == 0 ? "取件助手" : page == 1 ? "取件统计" : "设置", 28, ink, true),
          new LinearLayout.LayoutParams(0, -2, 1));
      TextView tag = text("取件助手", 11, muted, false);
      title.addView(tag);
      header.addView(title);
      space(header, 8);
      header.addView(
          text(
              page == 0 ? "让每一个包裹，都有迹可循。" : page == 1 ? "你的包裹记录，一目了然。" : "短信在本机识别，取件记录保存在本机。",
              13,
              muted,
              false));
    }
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    body = col();
    body.setPadding(dp(18), dp(4), dp(18), dp(20));
    scroll.addView(body);
    if (page == 0) home();
    else if (page == 1) stats();
    else settings();
    nav = row();
    nav.setPadding(dp(12), dp(8), dp(12), dp(8));
    nav.setBackground(shape(paper, 36));
    nav.setElevation(dp(8));
    LinearLayout.LayoutParams navParams = new LinearLayout.LayoutParams(-1, -2);
    navParams.setMargins(dp(50), dp(12), dp(50), dp(20));
    root.addView(nav, navParams);
    String[] labels = {"包裹", "统计", "设置"};
    for (int i = 0; i < 3; i++) {
      final int p = i;
      TextView t = text(labels[i], 15, i == page ? Color.WHITE : muted, i == page);
      t.setGravity(Gravity.CENTER);
      t.setPadding(0, dp(14), 0, dp(14));
      if (i == page) t.setBackground(shape(dark ? 0xff314a61 : Color.BLACK, 28));
      nav.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
      t.setOnClickListener(
          v -> {
            page = p;
            render();
          });
    }
  }

  private void homeHeader(LinearLayout header) {
    int pending = 0;
    List<Store.Parcel> all = Store.load(this);
    for (Store.Parcel p : all) if (p.completed == 0) pending++;
    LinearLayout line = row();
    header.addView(line);
    for (int i = 0; i < 2; i++) {
      final int tab = i;
      LinearLayout label = col();
      TextView t =
          text(
              i == 0 ? "待取 (" + pending + ")" : "已取 (" + (all.size() - pending) + ")",
              22,
              filterTab == i ? ink : muted,
              true);
      t.setGravity(Gravity.CENTER);
      t.setPadding(0, dp(10), 0, dp(8));
      label.addView(t);
      View underline = new View(this);
      underline.setBackground(shape(filterTab == i ? 0xff42ddba : Color.TRANSPARENT, 2));
      LinearLayout.LayoutParams u = new LinearLayout.LayoutParams(dp(48), dp(3));
      u.gravity = Gravity.CENTER;
      label.addView(underline, u);
      line.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
      label.setOnClickListener(
          v -> {
            filterTab = tab;
            render();
          });
    }
    TextView add = text("＋", 30, ink, false);
    add.setGravity(Gravity.CENTER);
    add.setContentDescription("手动添加");
    line.addView(add, new LinearLayout.LayoutParams(dp(48), dp(48)));
    add.setOnClickListener(v -> edit(null));
    TextView sms = text("≡", 30, ink, false);
    sms.setGravity(Gravity.CENTER);
    sms.setContentDescription("粘贴短信识别");
    line.addView(sms, new LinearLayout.LayoutParams(dp(48), dp(48)));
    sms.setOnClickListener(v -> paste());
  }

  private void home() {
    LinearLayout controls = row();
    TextView order =
        action(
            (sort == 0 ? "按时间" : sort == 1 ? "按取件码" : "按公司") + " ▾",
            () -> {
              sort = (sort + 1) % 3;
              Store.prefs(this).edit().putInt("sort", sort).apply();
              render();
            });
    order.setTextColor(Color.WHITE);
    order.setBackground(shape(Color.BLACK, 24));
    controls.addView(order);
    View gap = new View(this);
    controls.addView(gap, new LinearLayout.LayoutParams(0, 1, 1));
    TextView filter =
        action(
            carrierFilter.equals("全部") ? "筛选 ▾" : carrierFilter + " ▾",
            () ->
                new AlertDialog.Builder(this)
                    .setTitle("快递公司筛选")
                    .setItems(
                        withAll(),
                        (d, n) -> {
                          carrierFilter = withAll()[n];
                          render();
                        })
                    .show());
    controls.addView(filter);
    body.addView(controls);
    space(body, 12);
    EditText search = new EditText(this);
    search.setSingleLine();
    search.setTextSize(13);
    search.setTextColor(ink);
    search.setHintTextColor(muted);
    search.setHint("搜索取件码、驿站、快递公司");
    search.setPadding(dp(14), dp(6), dp(14), dp(6));
    search.setBackground(shape(dark ? paper : 0xfff3f7f9, 18));
    search.setText(query);
    body.addView(search, new LinearLayout.LayoutParams(-1, dp(42)));
    space(body, 18);
    listing = col();
    body.addView(listing);
    renderList();
    search.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence x, int st, int count, int after) {}

          public void afterTextChanged(Editable x) {}

          public void onTextChanged(CharSequence x, int st, int before, int count) {
            query = x.toString();
            renderList();
          }
        });
    if (checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
      space(body, 10);
      body.addView(action("开启短信权限 · 自动提取新取件码", () -> permissions()));
    }
    if (!Store.load(this).isEmpty()) {
      space(body, 12);
      body.addView(action(filterTab == 0 ? "全部取出" : "清空已取记录", () -> batch()));
    }
  }

  private String[] withAll() {
    String[] a = new String[SmsParser.CARRIERS.length + 1];
    a[0] = "全部";
    System.arraycopy(SmsParser.CARRIERS, 0, a, 1, SmsParser.CARRIERS.length);
    return a;
  }

  private List<Store.Parcel> visible() {
    List<Store.Parcel> out = new ArrayList<>();
    String q = query.toLowerCase(Locale.ROOT);
    for (Store.Parcel p : Store.load(this))
      if ((filterTab == 0 ? p.completed == 0 : p.completed > 0)
          && (carrierFilter.equals("全部") || carrierFilter.equals(p.carrier))
          && (p.code + " " + p.carrier + " " + p.station + " " + p.note)
              .toLowerCase(Locale.ROOT)
              .contains(q)) out.add(p);
    Collections.sort(
        out,
        (a, b) ->
            sort == 0
                ? Long.compare(
                    filterTab == 0 ? b.created : b.completed,
                    filterTab == 0 ? a.created : a.completed)
                : sort == 1 ? natural(a.code, b.code) : a.carrier.compareTo(b.carrier));
    return out;
  }

  private int natural(String a, String b) {
    String[] aa = a.split("(?<=\\D)(?=\\d)|(?<=\\d)(?=\\D)"),
        bb = b.split("(?<=\\D)(?=\\d)|(?<=\\d)(?=\\D)");
    for (int i = 0; i < Math.min(aa.length, bb.length); i++) {
      int n;
      if (aa[i].matches("\\d+") && bb[i].matches("\\d+"))
        n = new java.math.BigInteger(aa[i]).compareTo(new java.math.BigInteger(bb[i]));
      else n = aa[i].compareToIgnoreCase(bb[i]);
      if (n != 0) return n;
    }
    return Integer.compare(aa.length, bb.length);
  }

  private void renderList() {
    listing.removeAllViews();
    List<Store.Parcel> shown = visible();
    if (shown.isEmpty()) {
      LinearLayout e = card(listing);
      space(e, 20);
      TextView icon = text("□", 58, accent, false);
      icon.setGravity(Gravity.CENTER);
      e.addView(icon);
      TextView t =
          text(
              query.isEmpty() && carrierFilter.equals("全部")
                  ? (filterTab == 0 ? "暂无待取包裹" : "还没有已取记录")
                  : "没有匹配的包裹",
              18,
              ink,
              true);
      t.setGravity(Gravity.CENTER);
      e.addView(t);
      space(e, 10);
      TextView d =
          text(filterTab == 0 ? "收到快递短信后自动识别\n也可以手动添加或粘贴短信" : "完成取件后，可以在这里回看", 13, muted, false);
      d.setGravity(Gravity.CENTER);
      e.addView(d);
      space(e, 24);
      return;
    }
    for (Store.Parcel p : shown) {
      LinearLayout c = card(listing);
      GradientDrawable background = shape(paper, 28);
      background.setStroke(dp(1), dark ? 0xff354453 : 0xffe5e5e5);
      c.setBackground(background);
      c.setElevation(dp(2));
      LinearLayout top = row();
      TextView station = text(p.station.isEmpty() ? p.carrier : p.station, 17, ink, true);
      station.setSingleLine();
      station.setEllipsize(android.text.TextUtils.TruncateAt.END);
      top.addView(station, new LinearLayout.LayoutParams(0, -2, 1));
      top.addView(
          text(
              date(p.completed == 0 ? p.created : p.completed),
              11,
              p.completed == 0 ? 0xff34bb62 : muted,
              false));
      c.addView(top);
      space(c, 4);
      c.addView(text(p.station.isEmpty() ? "未填写取件地点" : p.carrier, 13, muted, false));
      space(c, 10);
      LinearLayout line = row();
      TextView badge =
          text(p.carrier.substring(0, Math.min(2, p.carrier.length())), 12, Color.WHITE, true);
      badge.setGravity(Gravity.CENTER);
      badge.setBackground(
          shape(
              p.carrier.contains("圆通")
                  ? 0xff562686
                  : p.carrier.contains("顺丰") ? 0xff222222 : 0xff1677ff,
              7));
      line.addView(badge, new LinearLayout.LayoutParams(dp(32), dp(32)));
      View gap = new View(this);
      line.addView(gap, new LinearLayout.LayoutParams(dp(10), 1));
      TextView code =
          text(
              p.code,
              p.code.length() > 9 ? 23 : 28,
              p.completed == 0 ? (dark ? ink : Color.BLACK) : muted,
              true);
      code.setSingleLine();
      code.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
      code.setContentDescription("取件码 " + p.code + "，点击复制");
      code.setOnClickListener(v -> copy(p.code));
      line.addView(code, new LinearLayout.LayoutParams(0, -2, 1));
      TextView done = text(p.completed == 0 ? "○" : "✓", 28, accent, false);
      done.setGravity(Gravity.CENTER);
      done.setContentDescription((p.completed == 0 ? "标记已取 " : "恢复待取 ") + p.code);
      done.setOnClickListener(v -> status(p.id, p.completed == 0));
      line.addView(done, new LinearLayout.LayoutParams(dp(44), dp(44)));
      c.addView(line);
      c.setContentDescription("包裹详情 " + p.code);
      c.setOnClickListener(v -> detail(p));
    }
  }

  private void copy(String s) {
    ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
        .setPrimaryClip(ClipData.newPlainText("取件码", s));
    toast("取件码已复制");
  }

  private void status(String id, boolean done) {
    synchronized (Store.class) {
      List<Store.Parcel> a = Store.load(this);
      for (Store.Parcel p : a)
        if (p.id.equals(id)) p.completed = done ? System.currentTimeMillis() : 0;
      Store.save(this, a);
      render();
      toast(done ? "已完成取件" : "已恢复为待取");
    }
  }

  private void detail(Store.Parcel p) {
    new AlertDialog.Builder(this)
        .setTitle(p.code + " · " + p.carrier)
        .setMessage(
            "驿站："
                + (p.station.isEmpty() ? "未填写" : p.station)
                + "\n收到："
                + date(p.created)
                + (p.completed > 0 ? "\n取件：" + date(p.completed) : "")
                + "\n来源："
                + p.source
                + "\n\n"
                + p.note)
        .setItems(
            new String[] {"复制取件码", "编辑信息", p.completed == 0 ? "标记已取" : "恢复待取", "删除记录"},
            (d, n) -> {
              if (n == 0) copy(p.code);
              else if (n == 1) edit(p);
              else if (n == 2) status(p.id, p.completed == 0);
              else
                confirm(
                    "删除包裹",
                    "删除取件码 " + p.code + "？",
                    () -> {
                      synchronized (Store.class) {
                        List<Store.Parcel> a = Store.load(this);
                        a.removeIf(x -> x.id.equals(p.id));
                        Store.save(this, a);
                        render();
                      }
                    });
            })
        .setNegativeButton("关闭", null)
        .show();
  }

  private EditText field(LinearLayout layout, String label, String value, boolean multi) {
    TextView t = text(label, 13, muted, true);
    t.setPadding(0, dp(8), 0, dp(6));
    layout.addView(t);
    EditText e = new EditText(this);
    e.setText(value);
    e.setTextColor(ink);
    e.setTextSize(16);
    e.setSingleLine(!multi);
    if (multi) {
      e.setMinLines(3);
      e.setGravity(Gravity.TOP);
    }
    layout.addView(e, new LinearLayout.LayoutParams(-1, -2));
    return e;
  }

  private void edit(Store.Parcel old) {
    LinearLayout l = col();
    l.setPadding(dp(20), dp(4), dp(20), dp(12));
    EditText code = field(l, "取件码 *", old == null ? "" : old.code, false);
    TextView t = text("快递公司 *", 13, muted, true);
    t.setPadding(0, dp(12), 0, dp(6));
    l.addView(t);
    Spinner carrier = new Spinner(this);
    ArrayAdapter<String> adapter =
        new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, SmsParser.CARRIERS);
    carrier.setAdapter(adapter);
    if (old != null)
      for (int i = 0; i < SmsParser.CARRIERS.length; i++)
        if (SmsParser.CARRIERS[i].equals(old.carrier)) carrier.setSelection(i);
    l.addView(carrier);
    EditText station = field(l, "驿站名称 / 地址", old == null ? "" : old.station, false);
    EditText note = field(l, "备注", old == null ? "" : old.note, true);
    ScrollView scroll = new ScrollView(this);
    scroll.addView(l);
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle(old == null ? "添加取件码" : "编辑包裹")
            .setView(scroll)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create();
    dialog.setOnShowListener(
        v ->
            dialog
                .getButton(-1)
                .setOnClickListener(
                    w -> {
                      synchronized (Store.class) {
                        String c = code.getText().toString().trim();
                        if (!SmsParser.valid(c)) {
                          code.setError("请输入 3–12 位字母、数字或短横线，不能是手机号");
                          return;
                        }
                        List<Store.Parcel> a = Store.load(this);
                        String company = carrier.getSelectedItem().toString();
                        for (Store.Parcel p : a)
                          if (p.completed == 0
                              && p.code.equalsIgnoreCase(c)
                              && p.carrier.equals(company)
                              && (old == null || !p.id.equals(old.id))) {
                            code.setError("已有相同的待取记录");
                            return;
                          }
                        Store.Parcel p = old == null ? Store.make(c, company, "", "", "手动添加") : old;
                        p.code = c;
                        p.carrier = company;
                        p.station = station.getText().toString().trim();
                        p.note = note.getText().toString().trim();
                        if (old == null) a.add(p);
                        else
                          for (int i = 0; i < a.size(); i++)
                            if (a.get(i).id.equals(old.id)) a.set(i, p);
                        Store.save(this, a);
                        dialog.dismiss();
                        render();
                        toast("已保存");
                      }
                    }));
    dialog.show();
  }

  private void paste() {
    LinearLayout l = col();
    l.setPadding(dp(20), dp(6), dp(20), dp(12));
    EditText e = field(l, "请粘贴完整快递短信", "", true);
    e.setMinLines(5);
    e.setHint("【菜鸟驿站】包裹已到达南门驿站，取件码：8-2066，请及时取件。");
    TextView result = text("识别结果会在这里显示", 13, muted, false);
    l.addView(result);
    e.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int st, int c, int a) {}

          public void afterTextChanged(Editable x) {}

          public void onTextChanged(CharSequence s, int st, int b, int c) {
            List<SmsParser.Result> a = SmsParser.parse(s.toString());
            StringBuilder msg = new StringBuilder();
            for (SmsParser.Result r : a)
              msg.append(r.carrier).append(" · ").append(r.code).append("\n");
            result.setText(a.isEmpty() ? "未识别到取件码，请补充完整短信" : msg.toString());
          }
        });
    ScrollView scroll = new ScrollView(this);
    scroll.addView(l);
    AlertDialog d =
        new AlertDialog.Builder(this)
            .setTitle("短信识别")
            .setView(scroll)
            .setNegativeButton("取消", null)
            .setPositiveButton("识别并保存", null)
            .create();
    d.setOnShowListener(
        v ->
            d.getButton(-1)
                .setOnClickListener(
                    w -> {
                      String s = e.getText().toString();
                      if (SmsParser.parse(s).isEmpty()) {
                        e.setError("未识别到取件码");
                        return;
                      }
                      int n = Store.ingest(this, s, System.currentTimeMillis());
                      d.dismiss();
                      render();
                      toast(n > 0 ? "已添加 " + n + " 个取件码" : "记录已存在，无需重复添加");
                    }));
    d.show();
  }

  private void batch() {
    List<Store.Parcel> selection = visible();
    if (selection.isEmpty()) {
      toast("当前没有可操作的记录");
      return;
    }
    Set<String> ids = new HashSet<>();
    for (Store.Parcel p : selection) ids.add(p.id);
    confirm(
        filterTab == 0 ? "全部取出" : "清空已取记录",
        "将处理当前筛选结果中的 " + ids.size() + " 个包裹。",
        () -> {
          synchronized (Store.class) {
            List<Store.Parcel> a = Store.load(this);
            if (filterTab == 0) {
              for (Store.Parcel p : a)
                if (ids.contains(p.id)) p.completed = System.currentTimeMillis();
            } else a.removeIf(p -> ids.contains(p.id));
            Store.save(this, a);
            render();
          }
        });
  }

  private void stats() {
    List<Store.Parcel> a = Store.load(this);
    int done = 0, recent = 0;
    long duration = 0, now = System.currentTimeMillis();
    Map<String, Integer> counts = new TreeMap<>();
    for (Store.Parcel p : a) {
      if (p.completed > 0) {
        done++;
        duration += Math.max(0, p.completed - p.created);
      }
      if (now - p.created < 7 * 86400000L) recent++;
      counts.put(p.carrier, counts.getOrDefault(p.carrier, 0) + 1);
    }
    LinearLayout summary = card(body);
    summary.addView(text("累计包裹", 14, muted, false));
    summary.addView(text(String.valueOf(a.size()), 48, ink, true));
    space(summary, 12);
    summary.addView(text("已取 " + done + " 件  ·  待取 " + (a.size() - done) + " 件", 16, ink, true));
    heading("最近的取件情况");
    LinearLayout c = card(body);
    c.addView(text("近 7 天收到：" + recent + " 件", 17, ink, true));
    space(c, 12);
    c.addView(
        text(
            "平均取件耗时："
                + (done == 0
                    ? "暂无数据"
                    : String.format(Locale.CHINA, "%.1f 小时", duration / (double) done / 3600000)),
            15,
            muted,
            false));
    heading("快递公司分布");
    LinearLayout distribution = card(body);
    if (counts.isEmpty()) distribution.addView(text("添加包裹后显示统计", 15, muted, false));
    for (Map.Entry<String, Integer> e : counts.entrySet()) {
      LinearLayout line = row();
      line.addView(text(e.getKey(), 14, ink, false), new LinearLayout.LayoutParams(0, -2, 1));
      line.addView(text(e.getValue() + " 件", 14, accent, true));
      distribution.addView(line);
      ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
      bar.setMax(Math.max(1, a.size()));
      bar.setProgress(e.getValue());
      bar.setProgressTintList(android.content.res.ColorStateList.valueOf(accent));
      distribution.addView(bar, new LinearLayout.LayoutParams(-1, dp(16)));
      space(distribution, 12);
    }
    body.addView(text("统计基于本机当前保留的记录；删除记录会同步改变统计。", 12, muted, false));
  }

  private void settingRow(LinearLayout c, String title, String description, Runnable action) {
    LinearLayout l = col();
    l.setPadding(0, dp(12), 0, dp(12));
    l.addView(text(title + "  ›", 16, ink, true));
    if (description != null) {
      space(l, 5);
      l.addView(text(description, 12, muted, false));
    }
    c.addView(l);
    l.setOnClickListener(v -> action.run());
  }

  private void toggle(
      LinearLayout c, String title, String desc, String key, boolean def, Runnable fn) {
    LinearLayout l = row();
    l.setPadding(0, dp(10), 0, dp(10));
    LinearLayout labels = col();
    labels.addView(text(title, 16, ink, true));
    space(labels, 4);
    labels.addView(text(desc, 12, muted, false));
    l.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
    Switch s = new Switch(this);
    s.setContentDescription(title);
    s.setChecked(Store.prefs(this).getBoolean(key, def));
    l.addView(s);
    c.addView(l);
    s.setOnCheckedChangeListener(
        (v, checked) -> {
          Store.prefs(this).edit().putBoolean(key, checked).apply();
          if (fn != null) fn.run();
        });
  }

  private void settings() {
    heading("自动识别与提醒");
    LinearLayout c = card(body);
    toggle(
        c,
        "自动识别新短信",
        "需要接收短信权限",
        "auto_sms",
        true,
        () -> {
          if (Store.prefs(this).getBoolean("auto_sms", true)) permissions();
        });
    settingRow(
        c,
        "短信与通知权限",
        checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
            ? "接收短信权限已开启"
            : "点击授权，开启自动提取",
        () -> permissions());
    settingRow(c, "扫描最近 30 天短信", scanning ? "正在扫描…" : "仅识别快递相关短信，重复记录自动跳过", () -> scan());
    toggle(
        c,
        "新包裹通知",
        "识别成功后发送取件提醒",
        "notify",
        true,
        () -> {
          if (Build.VERSION.SDK_INT >= 33
              && Store.prefs(this).getBoolean("notify", true)
              && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                  != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 10);
        });
    toggle(
        c,
        "每天提醒未取包裹",
        "系统省电可能延迟提醒",
        "remind",
        false,
        () -> {
          ReminderReceiver.schedule(this);
          if (Build.VERSION.SDK_INT >= 33
              && Store.prefs(this).getBoolean("remind", false)
              && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                  != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 10);
        });
    int h = Store.prefs(this).getInt("hour", 18), m = Store.prefs(this).getInt("minute", 0);
    settingRow(
        c,
        "提醒时间",
        String.format(Locale.CHINA, "每天 %02d:%02d", h, m),
        () ->
            new TimePickerDialog(
                    this,
                    (v, hh, mm) -> {
                      Store.prefs(this).edit().putInt("hour", hh).putInt("minute", mm).apply();
                      ReminderReceiver.schedule(this);
                      render();
                    },
                    h,
                    m,
                    true)
                .show());
    settingRow(
        c,
        "系统应用设置",
        "权限、自启动及电池管理",
        () ->
            startActivity(
                new Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + getPackageName()))));
    heading("显示与数据");
    LinearLayout data = card(body);
    toggle(data, "深色模式", "切换页面背景与文字配色", "dark", false, () -> recreate());
    settingRow(
        data,
        "导出本地备份",
        "JSON 格式，包含完整取件记录",
        () -> {
          Intent i =
              new Intent(Intent.ACTION_CREATE_DOCUMENT)
                  .setType("application/json")
                  .addCategory(Intent.CATEGORY_OPENABLE)
                  .putExtra(
                      Intent.EXTRA_TITLE,
                      "pickup-backup-"
                          + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.CHINA).format(new Date())
                          + ".json");
          startActivityForResult(i, 21);
        });
    settingRow(
        data,
        "导入备份",
        "先验证完整文件，再合并；按记录 ID 去重",
        () ->
            startActivityForResult(
                new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .setType("*/*")
                    .addCategory(Intent.CATEGORY_OPENABLE),
                22));
    settingRow(
        data,
        "桌面小组件",
        "长按桌面 → 小组件 → 取件助手",
        () ->
            new AlertDialog.Builder(this)
                .setTitle("桌面小组件")
                .setMessage("在桌面长按空白处，找到取件助手小组件。小组件显示待取数量和最近 3 个取件码；点击打开应用。")
                .setPositiveButton("知道了", null)
                .show());
    heading("应用更新");
    LinearLayout updateCard = card(body);
    settingRow(updateCard, "检查更新", "当前版本 "+updates.version()+" · "+UpdateFiles.status(this), () -> updates.check(true));
    toggle(updateCard, "自动检查更新", "每天首次打开时检查新版本", "auto_update", true, null);
    settingRow(updateCard, "更新地址", updates.source(), () -> updates.configure());
    heading("关于");
    LinearLayout about = card(body);
    about.addView(text("取件助手 "+updates.version(),18,ink,true));
    space(about,8);
    about.addView(text("短信识别、取件管理与本地备份。短信和取件记录在本机处理，不上传。",13,muted,false));
  }

  private void permissions() {
    ArrayList<String> p = new ArrayList<>();
    for (String s : new String[] {Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS})
      if (checkSelfPermission(s) != PackageManager.PERMISSION_GRANTED) p.add(s);
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.POST_NOTIFICATIONS);
    if (p.isEmpty()) {
      toast("短信及通知权限已开启");
      return;
    }
    new AlertDialog.Builder(this)
        .setTitle("开启本地短信识别")
        .setMessage("接收短信权限用于提取新快递短信的取件码。读取短信权限用于手动扫描历史短信。短信只在设备上处理，不会上传。拒绝权限后仍可手动添加或粘贴识别。")
        .setNegativeButton("暂不开启", null)
        .setPositiveButton("继续", (d, w) -> requestPermissions(p.toArray(new String[0]), 10))
        .show();
  }

  @Override
  public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(request, permissions, results);
    render();
    if (request == 11) {
      if (checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED)
        scan();
      else toast("未获得读取权限，可使用粘贴识别");
    }
  }

  private void scan() {
    if (scanning) return;
    if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[] {Manifest.permission.READ_SMS}, 11);
      return;
    }
    confirm(
        "扫描历史短信",
        "读取最近 30 天的短信，仅保存识别到的取件码及其原始快递短信。",
        () -> {
          scanning = true;
          toast("正在扫描短信…");
          new Thread(
                  () -> {
                    int count = 0;
                    String error = null;
                    try (Cursor cursor =
                        getContentResolver()
                            .query(
                                Telephony.Sms.Inbox.CONTENT_URI,
                                new String[] {"body", "date"},
                                "date >= ?",
                                new String[] {
                                  String.valueOf(System.currentTimeMillis() - 30 * 86400000L)
                                },
                                "date ASC")) {
                      if (cursor != null)
                        while (cursor.moveToNext())
                          count += Store.ingest(this, cursor.getString(0), cursor.getLong(1));
                    } catch (Exception e) {
                      error = e.getMessage();
                    }
                    final int n = count;
                    final String failure = error;
                    handler.post(
                        () -> {
                          scanning = false;
                          if (isDestroyed()) return;
                          render();
                          toast(failure == null ? "扫描完成，新增 " + n + " 条记录" : "扫描失败：" + failure);
                        });
                  },
                  "sms-scan")
              .start();
        });
  }

  @Override
  public void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (result != RESULT_OK || data == null || data.getData() == null) return;
    try {
      if (request == 21) {
        JSONObject j = new JSONObject();
        j.put("format", "pickup-local-v1");
        JSONArray a = new JSONArray();
        for (Store.Parcel p : Store.load(this)) a.put(p.json());
        j.put("parcels", a);
        try (OutputStream out = getContentResolver().openOutputStream(data.getData(), "wt")) {
          if (out == null) throw new IOException("无法写入文件");
          out.write(j.toString(2).getBytes(StandardCharsets.UTF_8));
        }
        toast("备份已导出");
      } else if (request == 22) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream in = getContentResolver().openInputStream(data.getData())) {
          if (in == null) throw new IOException("无法读取文件");
          byte[] buffer = new byte[8192];
          int n;
          while ((n = in.read(buffer)) != -1) {
            bytes.write(buffer, 0, n);
            if (bytes.size() > 5 * 1024 * 1024) throw new IOException("备份不能超过 5 MB");
          }
        }
        JSONObject j = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        if (!"pickup-local-v1".equals(j.getString("format"))) throw new IOException("不支持的备份格式");
        JSONArray a = j.getJSONArray("parcels");
        if (a.length() > 10000) throw new IOException("记录不能超过 10000 条");
        List<Store.Parcel> incoming = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) incoming.add(Store.Parcel.from(a.getJSONObject(i)));
        confirm(
            "导入备份",
            "已验证 " + incoming.size() + " 条记录，是否合并到本机？",
            () -> {
              synchronized (Store.class) {
                List<Store.Parcel> local = Store.load(this);
                Set<String> ids = new HashSet<>();
                for (Store.Parcel p : local) ids.add(p.id);
                int n = 0;
                for (Store.Parcel p : incoming)
                  if (ids.add(p.id)) {
                    local.add(p);
                    n++;
                  }
                Store.save(this, local);
                render();
                toast("已导入 " + n + " 条记录");
              }
            });
      }
    } catch (Exception e) {
      new AlertDialog.Builder(this)
          .setTitle("备份操作失败")
          .setMessage(e.getMessage())
          .setPositiveButton("知道了", null)
          .show();
    }
  }
}
