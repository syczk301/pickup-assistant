package com.local.pickup;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

/** Exposes only the already verified update APK, with temporary read grants. */
public class UpdateFileProvider extends ContentProvider {
  @Override public boolean onCreate(){return true;}
  private File checked(Uri uri)throws FileNotFoundException{
    if(!uri.toString().equals("content://"+getContext().getPackageName()+".updates/apk")||!Store.prefs(getContext()).getBoolean("update_ready",false))throw new FileNotFoundException("更新文件不可用");
    try {File file=UpdateFiles.file(getContext(),UpdateFiles.pending(getContext()));if(!file.isFile())throw new IOException();return file;}catch(IOException e){throw new FileNotFoundException("更新文件不可用");}
  }
  @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{if(!"r".equals(mode))throw new FileNotFoundException("只允许读取");return ParcelFileDescriptor.open(checked(uri),ParcelFileDescriptor.MODE_READ_ONLY);}
  @Override public String getType(Uri uri){return "application/vnd.android.package-archive";}
  @Override public Cursor query(Uri uri,String[]projection,String selection,String[]args,String order){try{File f=checked(uri);String[]cols=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;MatrixCursor c=new MatrixCursor(cols);Object[]row=new Object[cols.length];for(int i=0;i<cols.length;i++)row[i]=OpenableColumns.DISPLAY_NAME.equals(cols[i])?f.getName():OpenableColumns.SIZE.equals(cols[i])?f.length():null;c.addRow(row);return c;}catch(FileNotFoundException e){return null;}}
  @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
  @Override public int delete(Uri u,String s,String[]a){throw new UnsupportedOperationException();}
  @Override public int update(Uri u,ContentValues v,String s,String[]a){throw new UnsupportedOperationException();}
}
