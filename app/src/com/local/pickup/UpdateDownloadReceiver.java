package com.local.pickup;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.os.Build;

public class UpdateDownloadReceiver extends BroadcastReceiver {
  @Override public void onReceive(Context c,Intent i){
    if(!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(i.getAction()))return;
    long id=i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID,-1);
    if(id==-1||id!=Store.prefs(c).getLong("update_download_id",-1))return;
    PendingResult pending=goAsync();new Thread(()->{try{complete(c,id);}finally{pending.finish();}},"update-verify").start();
  }
  public static boolean complete(Context c,long id){
    try {
      DownloadManager dm=(DownloadManager)c.getSystemService(Context.DOWNLOAD_SERVICE);
      try(Cursor cursor=dm.query(new DownloadManager.Query().setFilterById(id))){
        if(cursor==null||!cursor.moveToFirst())throw new java.io.IOException("下载记录不存在，请重试");
        int status=cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
        if(status==DownloadManager.STATUS_FAILED)throw new java.io.IOException("安装包下载失败，请重试");
        if(status!=DownloadManager.STATUS_SUCCESSFUL)return false;
      }
      synchronized(UpdateFiles.class){
        if(id!=Store.prefs(c).getLong("update_download_id",-1))return false;
        UpdateProtocol.Release r=UpdateFiles.pending(c);UpdateFiles.verify(c,UpdateFiles.file(c,r),r);
        Store.prefs(c).edit().putBoolean("update_ready",true).putLong("update_download_id",-1).putString("update_error","").commit();
        if(Build.VERSION.SDK_INT<33||c.checkSelfPermission("android.permission.POST_NOTIFICATIONS")==android.content.pm.PackageManager.PERMISSION_GRANTED){
          NotificationManager m=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);m.createNotificationChannel(new NotificationChannel("updates","应用更新",NotificationManager.IMPORTANCE_DEFAULT));
          m.notify(2,new Notification.Builder(c,"updates").setSmallIcon(R.drawable.ic_launcher).setContentTitle("取件助手 "+r.name+" 已下载").setContentText("打开应用，确认安装新版本").setContentIntent(ReminderReceiver.open(c)).setAutoCancel(true).build());
        }
        return true;
      }
    }catch(Exception e){
      synchronized(UpdateFiles.class){if(id==Store.prefs(c).getLong("update_download_id",-1)){Store.prefs(c).edit().putLong("update_download_id",-1).putBoolean("update_ready",false).putString("update_error",e.getMessage()==null?"更新下载失败":e.getMessage()).commit();}}
      return false;
    }
  }
}
