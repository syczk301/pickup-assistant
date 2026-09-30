package com.local.pickup;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.os.*;
import java.io.*;
import java.util.*;

public final class UpdateFiles {
  public static File file(Context c,UpdateProtocol.Release r)throws IOException{
    File root=c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
    if(root==null)throw new IOException("无法访问下载目录");
    File dir=new File(root,"updates");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("无法创建下载目录");
    return new File(dir,"pickup-update-"+r.code+".apk");
  }
  public static int code(PackageInfo info){return Build.VERSION.SDK_INT>=28?(int)info.getLongVersionCode():info.versionCode;}
  public static UpdateProtocol.Release pending(Context c)throws IOException{
    return UpdateProtocol.parse(Store.prefs(c).getString("update_release",""),c.getPackageName());
  }
  private static Set<String> signatures(PackageInfo i){
    Signature[] a=Build.VERSION.SDK_INT>=28&&i.signingInfo!=null?i.signingInfo.getApkContentsSigners():i.signatures;
    Set<String> out=new HashSet<>();if(a!=null)for(Signature s:a)out.add(s.toCharsString());return out;
  }
  public static void verify(Context c,File file,UpdateProtocol.Release r)throws Exception{
    UpdateProtocol.verifyBytes(file,r);
    int flags=Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;
    PackageManager pm=c.getPackageManager();PackageInfo archive=pm.getPackageArchiveInfo(file.getPath(),flags),own=pm.getPackageInfo(c.getPackageName(),flags);
    if(archive==null||!own.packageName.equals(archive.packageName)||code(archive)!=r.code||code(archive)<=code(own)||!r.name.equals(archive.versionName))throw new IOException("安装包的应用或版本不匹配");
    Set<String> expected=signatures(own);if(expected.isEmpty()||!expected.equals(signatures(archive)))throw new IOException("安装包签名与当前应用不一致");
    if(archive.applicationInfo!=null&&archive.applicationInfo.minSdkVersion>Build.VERSION.SDK_INT)throw new IOException("此更新需要更高版本的 Android");
  }
  public static String status(Context c){
    if(Store.prefs(c).getBoolean("update_ready",false))return "安装包已下载，点击安装";
    long id=Store.prefs(c).getLong("update_download_id",-1);
    if(id!=-1)return "正在下载，点击查看进度";
    String e=Store.prefs(c).getString("update_error","");return e.isEmpty()?"查看是否有新版本":e;
  }
}
