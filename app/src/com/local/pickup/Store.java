package com.local.pickup;

import android.content.*;
import java.util.*;
import org.json.*;

public final class Store {
  public static final class Parcel {
    public String id, code, carrier, station, note, source;
    public long created, completed;

    public JSONObject json() throws JSONException {
      JSONObject j = new JSONObject();
      j.put("id", id);
      j.put("code", code);
      j.put("carrier", carrier);
      j.put("station", station);
      j.put("note", note);
      j.put("source", source);
      j.put("created", created);
      j.put("completed", completed);
      return j;
    }

    public static Parcel from(JSONObject j) throws JSONException {
      Parcel p = new Parcel();
      p.id = j.getString("id");
      p.code = j.getString("code");
      p.carrier = j.optString("carrier", "其他");
      p.station = j.optString("station");
      p.note = j.optString("note");
      p.source = j.optString("source", "导入");
      p.created = j.getLong("created");
      p.completed = j.optLong("completed");
      if (!SmsParser.valid(p.code) || p.id.length() > 100 || p.created < 0 || p.completed < 0)
        throw new JSONException("无效记录");
      return p;
    }
  }

  public static android.content.SharedPreferences prefs(Context c) {
    return c.getSharedPreferences("pickup", Context.MODE_PRIVATE);
  }

  public static synchronized List<Parcel> load(Context c) {
    List<Parcel> a = new ArrayList<>();
    try {
      JSONArray j = new JSONArray(prefs(c).getString("parcels", "[]"));
      for (int i = 0; i < j.length(); i++) a.add(Parcel.from(j.getJSONObject(i)));
    } catch (JSONException e) {
      throw new IllegalStateException("本地取件记录损坏，请保留数据后恢复备份", e);
    }
    return a;
  }

  public static synchronized void save(Context c, List<Parcel> a) {
    JSONArray j = new JSONArray();
    try {
      for (Parcel p : a) j.put(p.json());
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
    if (!prefs(c).edit().putString("parcels", j.toString()).commit())
      throw new IllegalStateException("保存失败，存储空间可能不足");
    PickupWidget.refresh(c);
  }

  public static Parcel make(
      String code, String carrier, String station, String note, String source) {
    Parcel p = new Parcel();
    p.id = UUID.randomUUID().toString();
    p.code = code;
    p.carrier = carrier;
    p.station = station;
    p.note = note;
    p.source = source;
    p.created = System.currentTimeMillis();
    return p;
  }

  public static synchronized int ingest(Context c, String text, long time) {
    List<Parcel> a = load(c);
    int added = 0;
    for (SmsParser.Result r : SmsParser.parse(text)) {
      boolean duplicate = false;
      for (Parcel p : a)
        if (p.code.equalsIgnoreCase(r.code)
            && p.carrier.equals(r.carrier)
            && (p.completed == 0 || Math.abs(p.created - time) < 86400000L)) {
          duplicate = true;
          break;
        }
      if (!duplicate) {
        Parcel p = make(r.code, r.carrier, r.station, text, "短信识别");
        p.created = time;
        a.add(p);
        added++;
      }
    }
    if (added > 0) save(c, a);
    return added;
  }

  public static int pending(Context c) {
    int n = 0;
    for (Parcel p : load(c)) if (p.completed == 0) n++;
    return n;
  }
}
