package dev.fontlab;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Process;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only evidence; a matching version label alone never authorizes icon writes. */
final class IconCompatibility {
  static final File ICONS = new File("/data/bbkcore/icon_custom/0");
  private static final String[] FILES = {
      "/system/framework/framework.jar", "/system/framework/vivo-framework.jar",
      "/system/framework/framework-res.apk", "/system/framework/vivo-res.apk",
      "com.bbk.launcher2", IconJobs.PACKAGE, "/system/lib64/libthemeicon_vivolog.so"};
  private static final String[] HASHES = {
      "44e00324de1e51073c79f7ee19edc24c08f90e735e4dc7d956cdfd7655f2623b",
      "29a7db186e69b54fdd9c178e5c90bab9cb7e5c8ed28c2ac407ee9cc49cdeb23d",
      "6df66475c7b444f264e94e09dc76e9d1fb34aee5d1cc6e5bcaf41f05789413a6",
      "65f64ce520a5d15bf9fdfb02c2c77e13830c17a833f835f3e62dd5491825ddc5",
      "5e6e108235db57f034ce61ebdcf9ec53cf62b325fb5718fd483f01b2ce22e6f1",
      IconJobs.APK_HASH,
      "b3e45ee922b5519e71bab9e1aaa2c82331fb74d99ef01e463814200f75a5ab70"};

  static boolean known(JSONArray hashes) {
    if (hashes.length() != HASHES.length) return false;
    for (int i=0;i<HASHES.length;i++) if (!HASHES[i].equals(hashes.optString(i))) return false;
    return true;
  }

  static boolean allowed(JSONObject report, String approvedProfile) {
    return report.optBoolean("capable") && (report.optBoolean("tested_profile")
        || (!approvedProfile.isEmpty() && approvedProfile.equals(report.optString("profile"))));
  }
  static boolean cornerAdjustment(boolean known, JSONObject settings) {
    return known && settings.optInt("current_desktop_type")==1
        && settings.optInt("theme_icons_style_explore")==1
        && settings.optInt("explore_theme_icons_radius_style")==1
        && settings.optInt("theme_icons_radius_explore")==-1
        && settings.optInt("variable_icons_radius_explore")==-1
        && settings.optInt("variable_icons_raduis")==-1 && settings.optInt("theme_id")==151;
  }

  static String digest(File file) throws Exception {
    MessageDigest sha=MessageDigest.getInstance("SHA-256");
    try (InputStream input=Files.newInputStream(file.toPath())) {
      byte[] buffer=new byte[65536];int read;
      while ((read=input.read(buffer))!=-1) sha.update(buffer,0,read);
    }
    StringBuilder out=new StringBuilder();
    for (byte b:sha.digest()) out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
    return out.toString();
  }
  private static int setting(String key) throws Exception {
    java.lang.Process process=new ProcessBuilder("/system/bin/settings","--user","0","get","system",key)
        .redirectErrorStream(true).start();
    String value;
    try(InputStream input=process.getInputStream()) {
      value=new String(FullThemeStore.readBounded(input,4096),java.nio.charset.StandardCharsets.UTF_8).trim();
    }
    if(process.waitFor()!=0)throw new IllegalStateException("Cannot read icon style");
    if(value.isEmpty()||value.equals("null"))return -1;
    try{
      long parsed=Long.parseLong(value);
      return parsed>=Integer.MIN_VALUE&&parsed<=Integer.MAX_VALUE?(int)parsed:-2;
    }catch(NumberFormatException ignored){
      return -1;
    }
  }

  static JSONObject inspect(Context context) throws Exception {
    PackageManager pm=context.getPackageManager();JSONArray failures=new JSONArray(), hashes=new JSONArray();
    JSONObject artifacts=new JSONObject(), versions=new JSONObject(), settings=new JSONObject();
    for (String path:FILES) {
      try {
        File file;
        if (path.startsWith("/")) file=new File(path);
        else {
          android.content.pm.PackageInfo info=pm.getPackageInfo(path,0);
          versions.put(path,info.versionName);file=new File(info.applicationInfo.sourceDir);
          if (info.applicationInfo.splitSourceDirs!=null)for(String split:info.applicationInfo.splitSourceDirs) {
            String hash=digest(new File(split));hashes.put(hash);artifacts.put(path+"/"+new File(split).getName(),hash);
          }
        }
        String hash=digest(file);hashes.put(hash);artifacts.put(path,hash);
      } catch(Exception error) {hashes.put("");failures.put("Cannot inspect "+path);}
    }
    if (Build.VERSION.SDK_INT<31 || !java.util.Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a"))
      failures.put("Android 12+ on arm64 is required");
    try {
      Object am=Class.forName("android.app.ActivityManagerNative").getMethod("getDefault").invoke(null);
      Object user=am.getClass().getMethod("getCurrentUser").invoke(am);
      if (user.getClass().getField("id").getInt(user)!=0) failures.put("Switch to the main Android user");
    } catch(Exception error) {failures.put("Cannot verify the active Android user");}
    ResolveInfo home=pm.resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),PackageManager.MATCH_DEFAULT_ONLY);
    String launcher=home==null||home.activityInfo==null ? "" : home.activityInfo.packageName;
    if (!"com.bbk.launcher2".equals(launcher)) failures.put("Vivo's stock Home app must be the default launcher");
    try {
      Class<?> type=Class.forName("com.vivo.content.IconRedrawManger");
      java.lang.reflect.Field path=type.getDeclaredField("CUSTOM_ICON_DIR");path.setAccessible(true);
      if (!"/data/bbkcore/icon_custom/".equals(path.get(null))) throw new IllegalStateException();
      type.getDeclaredMethod("loadCustomIcon",Context.class,String.class,String.class,String.class);
      Object binder=Class.forName("android.app.AppGlobals").getMethod("getPackageManager").invoke(null);
      binder.getClass().getMethod("getComponentEnabledSetting",ComponentName.class,int.class);
      binder.getClass().getMethod("setComponentEnabledSetting",ComponentName.class,int.class,int.class,int.class,String.class);
      binder.getClass().getMethod("getApplicationEnabledSetting",String.class,int.class);
      binder.getClass().getMethod("setApplicationEnabledSetting",String.class,int.class,int.class,int.class,String.class);
      pm.getPackageInfo("com.vivo.permissionmanager",0);
    } catch(Exception|LinkageError error) {failures.put("Required native icon or refresh API is missing");}
    if (Files.isSymbolicLink(ICONS.toPath()) || !ICONS.isDirectory() || !ICONS.canRead()
        || !ICONS.canWrite() || !ICONS.getAbsolutePath().equals(ICONS.getCanonicalPath()))
      failures.put("Native custom-icon directory is unavailable");
    for (String key:new String[]{"current_desktop_type","theme_icons_style_explore",
        "explore_theme_icons_radius_style","theme_icons_radius_explore",
        "variable_icons_radius_explore","variable_icons_raduis","theme_id"})
      try {settings.put(key,setting(key));}
      catch(Exception error) {settings.put(key,-1);failures.put("Cannot inspect icon setting: "+key);}
    boolean known=known(hashes);
    JSONObject transparency=new JSONObject();boolean corners=cornerAdjustment(known,settings)||failures.length()==0&&IconRaster.nativeCornerAdjustment(context,settings,transparency);
    boolean backing=failures.length()==0&&IconRaster.nativeTileBacking(context,settings);
    String profile=IconSystemTask.hash(("icons-v4\n"+Build.FINGERPRINT+"\n"+hashes+"\n"+settings+"\n"+corners+"\n"+backing)
        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return new JSONObject().put("schema",1).put("app_version",BuildConfig.VERSION_NAME)
        .put("model",Build.MODEL).put("build",Build.DISPLAY).put("software_fingerprint",Build.FINGERPRINT)
        .put("sdk",Build.VERSION.SDK_INT).put("execution_uid",Process.myUid()).put("launcher",launcher)
        .put("versions",versions).put("artifacts",artifacts).put("icon_style",settings)
        .put("known_components",known).put("corner_adjustment",corners).put("corner_marker_size",cornerAdjustment(known,settings)?4:2).put("transparency_check",transparency).put("backing_normalization",backing).put("profile",profile)
        .put("tested_profile",known&&IconJobs.FINGERPRINT.equals(Build.FINGERPRINT))
        .put("capable",failures.length()==0).put("failures",failures)
        .put("scope","Read-only icon checks. No serial, account, installed-app list or font contents.");
  }
}
