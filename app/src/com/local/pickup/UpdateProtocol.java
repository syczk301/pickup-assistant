package com.local.pickup;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import org.json.*;

/** Shared update protocol. Uses normal platform TLS verification, never uploads app data. */
public final class UpdateProtocol {
  public static final long MAX_APK = 200L * 1024 * 1024;
  public static final class Release {
    public final int code, minSdk;
    public final long size;
    public final String name, url, hash, notes, json;
    Release(int c,int sdk,long s,String n,String u,String h,String text,String raw){code=c;minSdk=sdk;size=s;name=n;url=u;hash=h;notes=text;json=raw;}
  }
  public static URL https(String value) throws IOException {
    try {
      URL u = new URL(value);
      if (!"https".equalsIgnoreCase(u.getProtocol()) || u.getHost().isEmpty() || u.getUserInfo()!=null || u.getRef()!=null)
        throw new IOException("更新地址必须是有效的 HTTPS 地址");
      return u;
    } catch (MalformedURLException e) {throw new IOException("更新地址格式不正确",e);}
  }
  public static Release parse(String raw,String packageName) throws IOException {
    try {
      JSONObject j=new JSONObject(raw);
      if(j.getInt("schemaVersion")!=1 || !packageName.equals(j.getString("packageName")))throw new IOException("版本文件与本应用不匹配");
      long code=j.getLong("versionCode"),size=j.getLong("sizeBytes");
      int min=j.optInt("minSdk",26);
      String name=j.getString("versionName"),url=j.getString("apkUrl"),hash=j.getString("sha256").toLowerCase(Locale.ROOT),notes=j.optString("releaseNotes","");
      https(url);
      if(code<1||code>Integer.MAX_VALUE||size<1||size>MAX_APK||min<26||min>100||name.trim().isEmpty()||name.length()>80||notes.length()>12000||!hash.matches("[0-9a-f]{64}"))throw new IOException("版本文件字段不完整或超出范围");
      return new Release((int)code,min,size,name,url,hash,notes,raw);
    }catch(JSONException e){throw new IOException("无法读取版本文件，请检查 JSON 内容",e);}
  }
  public static Release fetch(String source,String pkg) throws IOException {
    URL u=https(source);
    for(int redirects=0;redirects<=5;redirects++){
      HttpURLConnection c=(HttpURLConnection)u.openConnection();
      c.setConnectTimeout(15000);c.setReadTimeout(20000);c.setInstanceFollowRedirects(false);c.setRequestProperty("Accept","application/json");
      try {
        int status=c.getResponseCode();
        if(status>=300&&status<=399){String location=c.getHeaderField("Location");if(location==null)throw new IOException("更新地址跳转无效");u=https(new URL(u,location).toString());continue;}
        if(status!=200)throw new IOException("更新服务器返回 HTTP "+status);
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(InputStream in=c.getInputStream()){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){out.write(buffer,0,n);if(out.size()>131072)throw new IOException("版本文件过大");}}
        return parse(new String(out.toByteArray(),StandardCharsets.UTF_8),pkg);
      }finally{c.disconnect();}
    }
    throw new IOException("更新地址跳转次数过多");
  }
  public static void verifyBytes(File file,Release release)throws IOException {
    if(!file.isFile()||file.length()!=release.size)throw new IOException("安装包大小与版本文件不一致，请重新下载");
    try {
      MessageDigest digest=MessageDigest.getInstance("SHA-256");
      try(InputStream in=new FileInputStream(file)){byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)digest.update(buffer,0,n);}
      StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));
      if(!hex.toString().equals(release.hash))throw new IOException("安装包校验失败，请重新下载");
    }catch(java.security.NoSuchAlgorithmException e){throw new IOException(e);}
  }
}
