package dev.fontlab;

import android.system.Os;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Successful trials survive process restarts and theme-directory replacements. */
final class VerificationStore {
  static File icons(){return new File("/data/bbkcore/theme/1999901992");}
  private static File file(File root)throws Exception {
    if(Files.isSymbolicLink(root.toPath())||!root.isDirectory()||!root.getCanonicalFile().equals(root.getAbsoluteFile())||Os.lstat(root.getPath()).st_uid!=Os.getuid())throw new IOException("Unrecognized compatibility storage");
    File file=new File(root,"proofs.json");if(Files.exists(file.toPath(),LinkOption.NOFOLLOW_LINKS)){
      var stat=Os.lstat(file.getPath());if(!file.isFile()||Files.isSymbolicLink(file.toPath())||stat.st_uid!=Os.getuid()||stat.st_nlink!=1||file.length()>16384)throw new IOException("Unrecognized compatibility proof");
    }return file;
  }
  private static JSONObject read(File root)throws Exception {
    if(!Files.exists(root.toPath(),LinkOption.NOFOLLOW_LINKS))return new JSONObject();File file=file(root);
    if(!file.exists())throw new IOException("Incomplete compatibility storage");
    JSONObject value=new JSONObject(new String(Files.readAllBytes(file.toPath()),java.nio.charset.StandardCharsets.UTF_8));if(value.optInt("schema")!=1||!value.optString("owner").equals("VFONT_VERIFICATION_1"))throw new IOException("Unknown compatibility storage");
    return value.getJSONObject("profiles");
  }
  static boolean has(File root,String kind,String profile)throws Exception {
    if(!profile.matches("[a-f0-9]{64}"))return false;
    return read(root).optBoolean(kind+":"+profile);
  }
  static boolean recorded(File root,String kind,String profile)throws Exception {return read(root).has(kind+":"+profile);}
  static void save(File root,String kind,String profile,boolean approved)throws Exception {
    if(!kind.matches("font|fast|theme")||!profile.matches("[a-f0-9]{64}"))throw new IOException("Invalid compatibility profile");
    JSONObject profiles=read(root);String key=kind+":"+profile;profiles.remove(key);profiles.put(key,approved);
    while(profiles.length()>64)profiles.remove(profiles.keys().next());
    if(!root.exists()){if(!root.mkdir())throw new IOException("Cannot save compatibility checks");Os.chmod(root.getPath(),0700);}
    File target=file(root);Os.chmod(root.getPath(),0700);
    IconSystemTask.writeJson(target,new JSONObject().put("schema",1).put("owner","VFONT_VERIFICATION_1").put("profiles",profiles));Os.chmod(target.getPath(),0600);FullThemeStore.sync(root);
  }
}
