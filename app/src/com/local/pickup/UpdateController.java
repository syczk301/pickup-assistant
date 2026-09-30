package com.local.pickup;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.widget.*;
import java.io.*;
import java.util.concurrent.*;

public final class UpdateController {
  public static final String DEFAULT_SOURCE="https://github.com/syczk301/pickup-assistant/releases/latest/download/update.json";
  private final Activity activity;
  private final Context context;
  private final Handler handler=new Handler(Looper.getMainLooper());
  private final ExecutorService worker=Executors.newSingleThreadExecutor();
  private boolean checking=false,verifying=false,closed=false;
  public UpdateController(Activity a){activity=a;context=a.getApplicationContext();}
  private boolean live(){return !closed&&!activity.isFinishing()&&!activity.isDestroyed();}
  private void ui(Runnable r){handler.post(()->{if(live())r.run();});}
  public void close(){closed=true;handler.removeCallbacksAndMessages(null);worker.shutdownNow();}
  public String source(){return Store.prefs(context).getString("update_source",DEFAULT_SOURCE);}
  public String version(){try{return context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName;}catch(Exception e){return "";}}
  private int versionCode(){try{return UpdateFiles.code(context.getPackageManager().getPackageInfo(context.getPackageName(),0));}catch(Exception e){return 0;}}
  private void message(String title,String message){if(live())new AlertDialog.Builder(activity).setTitle(title).setMessage(message).setPositiveButton("知道了",null).show();}
  public void configure(){
    EditText input=new EditText(activity);input.setSingleLine();input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);input.setText(source());input.setPadding(32,24,32,24);
    AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("更新地址").setMessage("填写 HTTPS 版本文件地址。更换地址会取消正在下载的更新。").setView(input).setNegativeButton("取消",null).setNeutralButton("恢复默认",(d,w)->setSource(DEFAULT_SOURCE)).setPositiveButton("保存",null).create();
    dialog.setOnShowListener(v->dialog.getButton(-1).setOnClickListener(w->{String value=input.getText().toString().trim();try{UpdateProtocol.https(value);setSource(value);dialog.dismiss();}catch(IOException e){input.setError(e.getMessage());}}));dialog.show();
  }
  private void setSource(String value){cancelDownload();Store.prefs(context).edit().putString("update_source",value).putLong("update_last_check",0).putInt("update_prompted",0).apply();Toast.makeText(activity,"更新地址已保存",Toast.LENGTH_SHORT).show();}
  private void cancelDownload(){synchronized(UpdateFiles.class){long id=Store.prefs(context).getLong("update_download_id",-1);if(id!=-1)((DownloadManager)context.getSystemService(Context.DOWNLOAD_SERVICE)).remove(id);try{UpdateFiles.file(context,UpdateFiles.pending(context)).delete();}catch(Exception ignored){}Store.prefs(context).edit().putLong("update_download_id",-1).putBoolean("update_ready",false).remove("update_release").remove("update_error").commit();}}
  public void resume(){
    try{UpdateProtocol.Release r=UpdateFiles.pending(context);if(r.code<=versionCode())cancelDownload();}catch(IOException ignored){}
    if(Store.prefs(context).getBoolean("update_install_permission",false)){
      Store.prefs(context).edit().putBoolean("update_install_permission",false).apply();
      if(activity.getPackageManager().canRequestPackageInstalls())install();else message("安装权限未开启","可以稍后在检查更新中继续安装。");return;
    }
    long id=Store.prefs(context).getLong("update_download_id",-1);
    if(id!=-1){worker.execute(()->{UpdateDownloadReceiver.complete(context,id);ui(()->offerReady(false));});}
    else if(Store.prefs(context).getBoolean("update_ready",false))offerReady(false);
    else if(Store.prefs(context).getBoolean("auto_update",true)&&!source().isEmpty()&&System.currentTimeMillis()-Store.prefs(context).getLong("update_last_check",0)>86400000L)check(false);
  }
  public void check(boolean manual){
    if(Store.prefs(context).getBoolean("update_ready",false)){offerReady(true);return;}
    if(Store.prefs(context).getLong("update_download_id",-1)!=-1){progress();return;}
    if(checking){if(manual)Toast.makeText(activity,"正在检查更新…",Toast.LENGTH_SHORT).show();return;}
    final String url=source();if(url.isEmpty()){configure();return;}
    checking=true;Store.prefs(context).edit().putLong("update_last_check",System.currentTimeMillis()).apply();
    if(manual)Toast.makeText(activity,"正在检查更新…",Toast.LENGTH_SHORT).show();
    worker.execute(()->{try{
      UpdateProtocol.Release release=UpdateProtocol.fetch(url,context.getPackageName());
      ui(()->{checking=false;if(!url.equals(source()))return;if(release.code<=versionCode()){if(manual)message("已是最新版本","当前版本 "+version());}else if(release.minSdk>Build.VERSION.SDK_INT){if(manual)message("暂不支持此更新","新版本需要 Android API "+release.minSdk+" 或更高版本。");}else if(manual||Store.prefs(context).getInt("update_prompted",0)!=release.code){Store.prefs(context).edit().putInt("update_prompted",release.code).apply();new AlertDialog.Builder(activity).setTitle("发现新版本 "+release.name).setMessage((release.notes.isEmpty()?"有新版本可用。":release.notes)+String.format(java.util.Locale.CHINA,"\n\n下载大小：%.2f MB",release.size/1048576.0)).setNegativeButton("稍后",null).setPositiveButton("下载更新",(d,w)->download(release)).show();}});
    }catch(Exception e){ui(()->{checking=false;if(manual)message("检查更新失败",e.getMessage()==null?"请检查网络后重试":e.getMessage());});}});
  }
  private void download(UpdateProtocol.Release r){
    try{synchronized(UpdateFiles.class){
      if(Store.prefs(context).getLong("update_download_id",-1)!=-1){progress();return;}
      File f=UpdateFiles.file(context,r);if(f.exists()&&!f.delete())throw new IOException("无法清理旧安装包");
      DownloadManager dm=(DownloadManager)context.getSystemService(Context.DOWNLOAD_SERVICE);
      DownloadManager.Request request=new DownloadManager.Request(Uri.parse(r.url)).setTitle("取件助手 "+r.name).setDescription("正在下载更新").setMimeType("application/vnd.android.package-archive").setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED).setAllowedOverRoaming(false).setDestinationUri(Uri.fromFile(f));
      Store.prefs(context).edit().putString("update_release",r.json).putBoolean("update_ready",false).putInt("update_ready_prompted",0).putString("update_error","").commit();
      long id=dm.enqueue(request);Store.prefs(context).edit().putLong("update_download_id",id).commit();
    }progress();}catch(Exception e){message("无法开始下载",e.getMessage());}
  }
  private void progress(){
    AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("下载更新").setMessage("正在下载，完成后会校验安装包。").setNegativeButton("取消下载",(d,w)->cancelDownload()).setPositiveButton("后台下载",null).create();dialog.show();
    Runnable poll=new Runnable(){@Override public void run(){
      if(!live()||!dialog.isShowing())return;
      long id=Store.prefs(context).getLong("update_download_id",-1);
      if(Store.prefs(context).getBoolean("update_ready",false)){dialog.dismiss();offerReady(true);return;}
      if(id==-1){dialog.dismiss();String error=Store.prefs(context).getString("update_error","");if(!error.isEmpty())message("下载更新失败",error);return;}
      try(Cursor c=((DownloadManager)context.getSystemService(Context.DOWNLOAD_SERVICE)).query(new DownloadManager.Query().setFilterById(id))){
        if(c==null||!c.moveToFirst()){dialog.dismiss();cancelDownload();message("下载已中断","请重新检查更新并下载。");return;}
        int status=c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));long bytes=c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),total=c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
        dialog.setMessage(status==DownloadManager.STATUS_PAUSED?"网络不可用或下载暂停，恢复网络后将继续。":status==DownloadManager.STATUS_SUCCESSFUL?"下载完成，正在校验…":total>0?"下载进度 "+(bytes*100/total)+"%":"正在下载…");
        if((status==DownloadManager.STATUS_SUCCESSFUL||status==DownloadManager.STATUS_FAILED)&&!verifying){verifying=true;worker.execute(()->{UpdateDownloadReceiver.complete(context,id);ui(()->verifying=false);});}
      }catch(Exception e){dialog.dismiss();message("无法读取下载进度",e.getMessage());return;}
      handler.postDelayed(this,600);
    }};handler.post(poll);
  }
  private void offerReady(boolean manual){
    if(!live()||!Store.prefs(context).getBoolean("update_ready",false))return;
    try{UpdateProtocol.Release r=UpdateFiles.pending(context);if(!manual&&Store.prefs(context).getInt("update_ready_prompted",0)==r.code)return;Store.prefs(context).edit().putInt("update_ready_prompted",r.code).apply();new AlertDialog.Builder(activity).setTitle("更新已准备好").setMessage("取件助手 "+r.name+" 已完成校验。安装将保留已有取件记录。").setNegativeButton("稍后",null).setPositiveButton("安装",(d,w)->install()).show();}catch(IOException e){message("更新不可用",e.getMessage());}
  }
  private void install(){
    if(!activity.getPackageManager().canRequestPackageInstalls()){
      new AlertDialog.Builder(activity).setTitle("允许安装更新").setMessage("请在接下来的系统设置中允许取件助手安装应用，返回后继续安装。").setNegativeButton("取消",null).setPositiveButton("前往设置",(d,w)->{try{Store.prefs(context).edit().putBoolean("update_install_permission",true).apply();activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+context.getPackageName())));}catch(Exception e){Store.prefs(context).edit().putBoolean("update_install_permission",false).apply();message("无法打开安装设置",e.getMessage());}}).show();return;
    }
    worker.execute(()->{try{UpdateProtocol.Release r=UpdateFiles.pending(context);UpdateFiles.verify(context,UpdateFiles.file(context,r),r);ui(()->{try{Uri uri=Uri.parse("content://"+context.getPackageName()+".updates/apk");Intent install=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);install.setClipData(ClipData.newRawUri("安装包",uri));activity.startActivity(install);}catch(Exception e){message("无法打开安装程序",e.getMessage());}});}catch(Exception e){ui(()->{Store.prefs(context).edit().putBoolean("update_ready",false).apply();message("安装包校验失败",e.getMessage());});}});
  }
}
