import com.local.pickup.UpdateProtocol;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import org.json.JSONObject;

public class UpdateProtocolTest {
  private static int count;
  private static JSONObject valid() throws Exception {
    return new JSONObject().put("schemaVersion",1).put("packageName","com.local.pickup").put("versionCode",3).put("versionName","0.2.1").put("apkUrl","https://example.com/app.apk").put("sizeBytes",3).put("sha256","ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad").put("minSdk",26);
  }
  private static void rejected(JSONObject j)throws Exception{
    try{UpdateProtocol.parse(j.toString(),"com.local.pickup");throw new AssertionError("Accepted invalid metadata: "+j);}catch(IOException expected){count++;}
  }
  public static void main(String[]args)throws Exception{
    UpdateProtocol.Release r=UpdateProtocol.parse(valid().toString(),"com.local.pickup");if(r.code!=3||!r.name.equals("0.2.1"))throw new AssertionError();count++;
    rejected(valid().put("schemaVersion",2));rejected(valid().put("packageName","other"));rejected(valid().put("versionCode",0));rejected(valid().put("versionCode",2147483648L));rejected(valid().put("sizeBytes",0));rejected(valid().put("sizeBytes",UpdateProtocol.MAX_APK+1));rejected(valid().put("sha256","bad"));rejected(valid().put("apkUrl","http://example.com/app.apk"));rejected(valid().put("apkUrl","https://user:password@example.com/app.apk"));rejected(valid().put("versionName",""));rejected(valid().put("minSdk",25));
    File file=File.createTempFile("update-test-",".apk");try{Files.write(file.toPath(),new byte[]{97,98,99});UpdateProtocol.verifyBytes(file,r);count++;Files.write(file.toPath(),new byte[]{97,98,100});try{UpdateProtocol.verifyBytes(file,r);throw new AssertionError("Accepted bad hash");}catch(IOException expected){count++;}Files.write(file.toPath(),new byte[]{97});try{UpdateProtocol.verifyBytes(file,r);throw new AssertionError("Accepted bad size");}catch(IOException expected){count++;}}finally{file.delete();}
    if(args.length>0){UpdateProtocol.Release live=UpdateProtocol.fetch(args[0],"com.local.pickup");if(live.code<2)throw new AssertionError();count++;}
    System.out.println("PASS: "+count+" update protocol checks");
  }
}
