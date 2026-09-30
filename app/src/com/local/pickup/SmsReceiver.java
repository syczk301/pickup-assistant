package com.local.pickup;

import android.content.*;
import android.provider.Telephony;
import android.telephony.SmsMessage;

public class SmsReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context c, Intent i) {
    if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(i.getAction())
        || !Store.prefs(c).getBoolean("auto_sms", true)) return;
    StringBuilder text = new StringBuilder();
    long time = System.currentTimeMillis();
    for (SmsMessage s : Telephony.Sms.Intents.getMessagesFromIntent(i)) {
      text.append(s.getMessageBody());
      time = s.getTimestampMillis();
    }
    int n = Store.ingest(c, text.toString(), time);
    if (n > 0 && Store.prefs(c).getBoolean("notify", true))
      ReminderReceiver.notify(c, "新的快递", "识别到 " + n + " 个取件码，当前 " + Store.pending(c) + " 件待取");
  }
}
