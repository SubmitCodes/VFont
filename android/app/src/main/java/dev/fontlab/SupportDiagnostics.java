package dev.fontlab;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/** Fixed read-only inventory. No backup dispatch, arbitrary paths, settings writes or directory walks. */
final class SupportDiagnostics {
  static final int MAX_REPORT=384*1024;
  static final long MAX_FILE=128L*1024*1024, MAX_BUNDLE=512L*1024*1024;
  static final String[] PACKAGES={"com.vivo.upslide","com.bbk.launcher2","com.bbk.theme",
      "com.vivo.theme","com.bbk.lite.theme","com.vivo.permissionmanager","com.android.systemui",
      "com.vivo.aod","com.vivo.livewallpaper.box"};
  private static final String[] STOCK={"/system/framework/framework.jar","/system/framework/vivo-framework.jar",
      "/system/framework/services.jar","/system/framework/vivo-services.jar","/system/framework/framework-res.apk",
      "/system/framework/vivo-res.apk","/system/lib64/libthemeicon_vivolog.so",
      "/system/etc/fonts.xml","/system/etc/fonts_default.xml","/system/etc/fonts_online.xml",
      "/system/etc/theme/style/1/launcher/iconsize.xml"};
  private static final String[] PATHS={"/system/fonts/VivoFont.ttf","/data/vfonts","/data/vfonts/VivoFont.ttf",
      "/system/etc/fonts.xml","/data/vfonts/fonts.xml","/system/etc/fonts_default.xml","/system/etc/fonts_online.xml",
      "/data/bbkcore/icon_custom/0","/data/bbkcore/theme","/data/bbkcore/unlock","/data/bbkcore/font",
      "/system/etc/theme","/system/etc/theme/style/1","/system/etc/theme/style/1/launcher/iconsize.xml",
      "/storage/emulated/0/Download","/sys/fs/selinux/enforce"};
  private static final String[] CLASSES={"vivo.app.backup.IVivoBackupManager","com.vivo.content.IconRedrawManger",
      "android.app.ActivityManagerNative","android.app.IActivityManager","android.content.res.Configuration"};

  static byte[] readBounded(java.io.InputStream in,int max) throws java.io.IOException {
    java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream(Math.min(max,8192));
    byte[] buffer=new byte[4096];int total=0;
    while(total<max) {
      int read=in.read(buffer,0,Math.min(buffer.length,max-total));
      if(read==-1)break;
      out.write(buffer,0,read);total+=read;
    }
    return out.toByteArray();
  }

  static String error(Throwable error) {
    while(error instanceof java.lang.reflect.InvocationTargetException && error.getCause()!=null)error=error.getCause();
    if(error instanceof ErrnoException) {
      int errno=((ErrnoException)error).errno;
      if(errno==OsConstants.ENOENT||errno==OsConstants.ENOTDIR)return "missing";
      if(errno==OsConstants.EACCES||errno==OsConstants.EPERM)return "inaccessible";
    }
    if(error instanceof SecurityException||error instanceof IllegalAccessException)return "inaccessible";
    if(error instanceof java.util.concurrent.TimeoutException)return "not_tested";
    if(error instanceof ClassNotFoundException||error instanceof NoSuchMethodException
        || error instanceof NoSuchFieldException||error instanceof PackageManager.NameNotFoundException)return "missing";
    return "error";
  }
  static String linkTarget(String target) {
    if(List.of(PATHS).contains(target)||target.equals("/system/fonts/Roboto-Regular.ttf"))return target;
    if(!target.contains("..")&&(target.startsWith("/system/")||target.startsWith("/system_ext/")
        ||target.startsWith("/product/")||target.startsWith("/vendor/")||target.startsWith("/apex/")))
      return target;
    if(target.startsWith("/data/vfonts/"))return "<custom-font target redacted>";
    return "<target redacted>";
  }
  static JSONObject path(String path) throws Exception {
    JSONObject out=new JSONObject().put("path",path);
    try {
      StructStat stat=Os.lstat(path);
      out.put("status","present").put("mode",Integer.toOctalString(stat.st_mode&07777))
          .put("owner_uid",stat.st_uid).put("owner_gid",stat.st_gid)
          .put("readable",new File(path).canRead()).put("writable",new File(path).canWrite());
      if(OsConstants.S_ISLNK(stat.st_mode))out.put("kind","symlink").put("target",linkTarget(Os.readlink(path)));
      else out.put("kind",OsConstants.S_ISDIR(stat.st_mode)?"directory":"file");
    }catch(Exception error){out.put("status",error(error));}
    return out;
  }
  private static JSONObject software() throws Exception {
    return new JSONObject().put("manufacturer",Build.MANUFACTURER).put("brand",Build.BRAND)
        .put("model",Build.MODEL).put("product",Build.PRODUCT).put("device",Build.DEVICE)
        .put("build",Build.DISPLAY).put("fingerprint",Build.FINGERPRINT).put("build_type",Build.TYPE)
        .put("android",Build.VERSION.RELEASE).put("sdk",Build.VERSION.SDK_INT)
        .put("security_patch",Build.VERSION.SECURITY_PATCH).put("abis",new JSONArray(List.of(Build.SUPPORTED_ABIS)));
  }
  private static String sha(File file,long deadline) throws Exception {
    MessageDigest digest=MessageDigest.getInstance("SHA-256");long total=0;
    try(FileInputStream in=new FileInputStream(file)) {
      byte[] buffer=new byte[65536];int count;
      while((count=in.read(buffer))!=-1) {
        total+=count;if(total>MAX_FILE||SystemClock.uptimeMillis()>deadline)throw new java.util.concurrent.TimeoutException();
        digest.update(buffer,0,count);
      }
    }
    StringBuilder out=new StringBuilder();for(byte b:digest.digest())out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();
  }
  private static JSONObject artifact(File file,String name,long deadline) throws Exception {
    JSONObject out=new JSONObject().put("artifact",name);
    try {
      StructStat stat=Os.stat(file.getPath());
      if(!OsConstants.S_ISREG(stat.st_mode))return out.put("status","unavailable");
      out.put("bytes",stat.st_size);
      if(stat.st_size>MAX_FILE||SystemClock.uptimeMillis()>deadline)return out.put("status","not_tested").put("reason","scan_limit");
      out.put("sha256",sha(file,deadline)).put("status","readable");
    }catch(Exception error){out.put("status",error(error));}
    return out;
  }
  private static JSONObject component(Context context,String pkg,long deadline) throws Exception {
    JSONObject out=new JSONObject().put("package",pkg);
    try {
      PackageInfo info=context.getPackageManager().getPackageInfo(pkg,PackageManager.GET_PERMISSIONS|PackageManager.GET_SIGNING_CERTIFICATES);
      ApplicationInfo app=info.applicationInfo;
      boolean system=(app.flags&(ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))!=0;
      out.put("status","present").put("version",info.versionName).put("version_code",info.getLongVersionCode())
          .put("uid",app.uid).put("system",system).put("persistent",(app.flags&ApplicationInfo.FLAG_PERSISTENT)!=0)
          .put("enabled",app.enabled).put("target_sdk",app.targetSdkVersion).put("min_sdk",app.minSdkVersion)
          .put("backup_agent",app.backupAgentName==null?JSONObject.NULL:app.backupAgentName)
          .put("allow_backup",(app.flags&ApplicationInfo.FLAG_ALLOW_BACKUP)!=0);
      if(!system)return out.put("status","not_stock");
      JSONArray permissions=new JSONArray();
      if(info.requestedPermissions!=null)for(String permission:info.requestedPermissions)
        if(permissions.length()<128)permissions.put(permission);
      out.put("requested_permissions",permissions);
      JSONArray certificates=new JSONArray();
      if(info.signingInfo!=null)for(android.content.pm.Signature signature:info.signingInfo.getApkContentsSigners())
        if(certificates.length()<8)certificates.put(IconSystemTask.hash(signature.toByteArray()));
      out.put("signing_certificates_sha256",certificates);
      JSONArray artifacts=new JSONArray();
      if(system)for(File file:packageFiles(app))artifacts.put(artifact(file,file.getName(),deadline));
      out.put("artifacts",artifacts);
    }catch(Exception error){out.put("status",error(error));}
    return out;
  }
  private static List<File> packageFiles(ApplicationInfo app) {
    List<File> files=new ArrayList<>();files.add(new File(app.sourceDir));
    if(app.splitSourceDirs!=null)for(String split:app.splitSourceDirs)if(files.size()<32)files.add(new File(split));
    return files;
  }
  private static JSONObject api(String name) throws Exception {
    JSONObject out=new JSONObject().put("class",name);
    try {
      Class<?> type=Class.forName(name,false,SupportDiagnostics.class.getClassLoader());JSONArray signatures=new JSONArray();
      if(name.equals("android.content.res.Configuration")) {
        for(String field:List.of("typefaceChanged","fontScale"))signatures.put(type.getField(field).getType().getName()+" "+field);
      }else for(java.lang.reflect.Method method:type.getDeclaredMethods()) {
        if(signatures.length()>=96)break;
        if(name.equals("android.app.ActivityManagerNative")&&!method.getName().equals("getDefault"))continue;
        if(name.equals("android.app.IActivityManager")
            &&!method.getName().equals("getConfiguration")
            &&!method.getName().equals("updatePersistentConfiguration"))continue;
        StringBuilder signature=new StringBuilder(method.getName()).append('(');
        for(Class<?> parameter:method.getParameterTypes())signature.append(parameter.getName()).append(',');
        signatures.put(signature.append(")->").append(method.getReturnType().getName()).toString());
      }
      out.put("status","present").put("signatures",signatures);
    }catch(Exception|LinkageError error){out.put("status",error(error));}
    return out;
  }
  private static JSONObject service() throws Exception {
    JSONObject out=new JSONObject().put("service","vivo_backup_service");
    try {
      IBinder binder=(IBinder)Class.forName("android.os.ServiceManager").getMethod("checkService",String.class).invoke(null,"vivo_backup_service");
      out.put("status",binder==null?"missing":"present");
      if(binder!=null)out.put("interface",binder.getInterfaceDescriptor());
    }catch(Exception error){out.put("status",error(error));}
    return out;
  }
  static String reviewStatus(boolean shell,boolean capable,boolean tested) {
    return !shell?"not_tested":!capable?"unavailable":tested?"reviewed":"experimental";
  }
  static JSONObject collect(Context context) throws Exception {
    long started=SystemClock.uptimeMillis(),deadline=started+20_000;
    JSONObject out=new JSONObject().put("scope",Process.myUid()==2000?"shell":"app")
        .put("execution_uid",Process.myUid()).put("software",software());
    JSONObject rom=new JSONObject();
    for(String key:List.of("ro.vivo.os.name","ro.vivo.os.version","ro.vivo.os.build.display.id",
        "ro.vivo.rom.style","ro.vivo.product.overseas","ro.product.country.region",
        "ro.vivo.product.version","ro.vendor.build.security_patch"))try {
      Object value=Class.forName("android.os.SystemProperties").getMethod("get",String.class).invoke(null,key);
      String text=String.valueOf(value);rom.put(key,text.isEmpty()?"missing":text.length()<=160?text:"unexpected_value");
    }catch(Exception error){rom.put(key,error(error));}
    out.put("rom_properties",rom);
    out.put("app_main_user",context.getApplicationInfo().uid/100000==0);
    try{out.put("memory_page_bytes",Os.sysconf(OsConstants._SC_PAGESIZE));}catch(Exception ignored){}
    boolean mainForeground=false;
    try {
      Object manager=Class.forName("android.app.ActivityManagerNative").getMethod("getDefault").invoke(null);
      Object user=manager.getClass().getMethod("getCurrentUser").invoke(manager);
      mainForeground=user.getClass().getField("id").getInt(user)==0;
      out.put("foreground_user",mainForeground?"main":"secondary");
    }catch(Exception error){out.put("foreground_user",error(error));}
    JSONArray paths=new JSONArray(),components=new JSONArray(),artifacts=new JSONArray(),apis=new JSONArray();
    for(String path:PATHS)paths.put(path(path));
    for(String pkg:PACKAGES)components.put(component(context,pkg,deadline));
    for(String path:STOCK)artifacts.put(artifact(new File(path),path,deadline));
    for(String name:CLASSES)apis.put(api(name));
    out.put("paths",paths).put("components",components).put("stock_artifacts",artifacts).put("apis",apis).put("backup_service",service());
    try(FileInputStream in=new FileInputStream("/proc/self/attr/current")) {
      String value=new String(readBounded(in,256),StandardCharsets.US_ASCII);
      java.util.regex.Matcher label=java.util.regex.Pattern.compile("u:r:([a-zA-Z0-9_]+):s0").matcher(value);
      out.put("selinux_domain",label.find()?label.group(1):"not_tested");
    }catch(Exception error){out.put("selinux_domain",error(error));}
    try(FileInputStream in=new FileInputStream("/sys/fs/selinux/enforce")) {
      int value=in.read();out.put("selinux_enforcing",value=='1'?"yes":value=='0'?"no":"not_tested");
    }catch(Exception error){out.put("selinux_enforcing",error(error));}
    if(Process.myUid()!=2000)try{
      android.content.res.Configuration cfg=context.getResources().getConfiguration();
      out.put("app_configuration_font_scale",cfg.fontScale);
      try{out.put("app_configuration_typeface_changed",android.content.res.Configuration.class.getField("typefaceChanged").getFloat(cfg));}catch(Exception ignored){}
    }catch(Exception ignored){}
    if(Process.myUid()==2000) {
      JSONObject settings=new JSONObject();
      for(String key:List.of("current_desktop_type","theme_icons_style_explore","explore_theme_icons_radius_style",
          "theme_icons_radius_explore","variable_icons_radius_explore","variable_icons_raduis","theme_id",
          "lock_screen_theme_id","launcher_folder_blur_style","deform_icons_size_explore")) {
        try {
          java.lang.Process command=new ProcessBuilder("/system/bin/settings","--user","0","get","system",key).redirectErrorStream(true).start();
          try {
            if(!command.waitFor(2,java.util.concurrent.TimeUnit.SECONDS)){settings.put(key,"not_tested");continue;}
            try(java.io.InputStream in=command.getInputStream()) {
              String value=new String(readBounded(in,128),StandardCharsets.US_ASCII).trim();
              if(command.exitValue()!=0)settings.put(key,"inaccessible");
              else if(value.equals("null"))settings.put(key,"missing");
              else if(value.matches("-?[0-9]{1,18}"))settings.put(key,Long.parseLong(value));
              else if(value.length()<=64&&value.matches("[A-Za-z0-9_.:/-]+"))settings.put(key,value);
              else settings.put(key,"unexpected_value");
            }
          }finally{command.destroy();}
        }catch(Exception error){settings.put(key,"unexpected_value");}
      }
      out.put("icon_style",settings);
    }
    try {
      android.content.pm.ResolveInfo home=context.getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),PackageManager.MATCH_DEFAULT_ONLY);
      String homePkg=home!=null&&home.activityInfo!=null?home.activityInfo.packageName:null;
      out.put("default_home","com.bbk.launcher2".equals(homePkg)?"originos_launcher":"other_or_unavailable");
      out.put("default_home_package",homePkg==null?"unavailable":homePkg);
      if(home!=null&&home.activityInfo!=null&&home.activityInfo.applicationInfo!=null)
        out.put("default_home_system",(home.activityInfo.applicationInfo.flags&(ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))!=0);
    }catch(Exception error){out.put("default_home","not_tested").put("default_home_package","not_tested");}
    boolean capable=false,tested=false;String transportProfile="";
    if(Process.myUid()==2000) {
      out.put("change_configuration_granted",context.checkPermission("android.permission.CHANGE_CONFIGURATION",Process.myPid(),Process.myUid())==PackageManager.PERMISSION_GRANTED);
      try {
        JSONObject transport=IconJobs.check(context);out.put("transport_checks",transport);
        capable=true;tested=transport.optBoolean("tested");
        transportProfile=transport.optString("transport_profile");
      }catch(Exception error){out.put("native_transport_reason",error instanceof NativeTransport.Unavailable
          ?((NativeTransport.Unavailable)error).code:error.getClass().getSimpleName());}
      out.put("font_checks",fontChecks(context,transportProfile,mainForeground));
    }
    out.put("native_transport",reviewStatus(Process.myUid()==2000,capable,tested))
        .put("native_execution","not_tested").put("themes","not_implemented");
    out.put("collection_ms",SystemClock.uptimeMillis()-started);
    return out;
  }
  private static JSONObject fontChecks(Context context,String transportProfile,boolean mainUser) throws Exception {
    JSONObject checks=new JSONObject();
    String maker=Build.MANUFACTURER==null?"":Build.MANUFACTURER.toLowerCase(java.util.Locale.ROOT);
    checks.put("maker_ok",maker.equals("vivo")||maker.equals("iqoo"));
    checks.put("sdk_ok",Build.VERSION.SDK_INT>=34);
    checks.put("foreground_user_ok",mainUser);
    for(String[] pair:new String[][]{{"vivo_font_link","/system/fonts/VivoFont.ttf"},
        {"fonts_xml_link","/system/etc/fonts.xml"},{"data_fonts_xml_link","/data/vfonts/fonts.xml"},
        {"active_font_target","/data/vfonts/VivoFont.ttf"}}) {
      try{checks.put(pair[0],linkTarget(Os.readlink(pair[1])));}catch(Exception error){checks.put(pair[0],error(error));}
    }
    try{Platform.checkFontRefresh();checks.put("refresh_api_status","ok");}
    catch(Exception error){checks.put("refresh_api_status",error(error));}
    if(transportProfile!=null&&transportProfile.matches("[a-f0-9]{64}")) {
      try{checks.put("font_verification_recorded",VerificationStore.has(new File("/data/local/tmp/vfont-verification-"+context.getApplicationInfo().uid),"font",transportProfile));}
      catch(Exception error){checks.put("font_verification_recorded",error(error));}
    }
    return checks;
  }
  static byte[] encode(JSONObject report) throws Exception {
    byte[] bytes=report.toString(2).getBytes(StandardCharsets.UTF_8);
    if(bytes.length>MAX_REPORT)throw new IllegalStateException("Support report exceeded its size limit");
    return bytes;
  }
  static JSONObject report(Context context,JSONObject app,JSONObject shell,JSONObject connection) throws Exception {
    JSONArray events=SupportLog.export(context);
    JSONObject report=new JSONObject().put("schema",1).put("app_version",BuildConfig.VERSION_NAME)
        .put("debug_build",BuildConfig.DEBUG)
        .put("app_apk",artifact(new File(context.getApplicationInfo().sourceDir),"VFont.apk",SystemClock.uptimeMillis()+3000))
        .put("created_utc",java.time.Instant.now().toString())
        .put("app_version_code",BuildConfig.VERSION_CODE).put("app",app).put("shizuku",connection)
        .put("shell",shell==null?new JSONObject().put("status","not_tested"):shell)
        .put("recent_events",events)
        .put("privacy","Manual export only. Fixed system components, software checks and sanitized VFont events. No serial, IMEI, accounts, app list, input names or contents.")
        .put("limitations","Read-only evidence does not establish native execution or recovery safety. No backup restore or undocumented transaction is invoked.");
    while(report.toString(2).getBytes(StandardCharsets.UTF_8).length>MAX_REPORT && events.length()>0) {
      events.remove(0);
    }
    encode(report);return report;
  }
  private static boolean codeFile(File file) throws Exception {
    String canonical=file.getCanonicalPath();
    return !Files.isSymbolicLink(file.toPath()) && (canonical.startsWith("/system/")||canonical.startsWith("/system_ext/")
        ||canonical.startsWith("/vendor/")||canonical.startsWith("/product/")||canonical.startsWith("/odm/")
        ||canonical.startsWith("/apex/")||canonical.startsWith("/data/app/")&&file.getName().matches("(base|split_[A-Za-z0-9_.-]+)\\.apk"));
  }
  static JSONObject bundle(Context context,OutputStream destination) throws Exception {
    long deadline=SystemClock.uptimeMillis()+180_000,total=0;JSONArray manifest=new JSONArray();int copied=0;
    List<File> files=new ArrayList<>();List<String> names=new ArrayList<>();
    for(String path:STOCK){files.add(new File(path));names.add(path.substring(1));}
    for(String pkg:PACKAGES)try {
      ApplicationInfo app=context.getPackageManager().getApplicationInfo(pkg,0);
      if((app.flags&(ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))!=0)
        for(File file:packageFiles(app)){files.add(file);names.add("packages/"+pkg+"/"+file.getName());}
    }catch(Exception error){manifest.put(new JSONObject().put("entry","packages/"+pkg).put("status",error(error)));}
    try(ZipOutputStream zip=new ZipOutputStream(destination)) {
      byte[] buffer=new byte[65536];
      for(int i=0;i<files.size();i++) {
        File file=files.get(i);JSONObject entry=new JSONObject().put("entry",names.get(i));manifest.put(entry);
        if(!file.isFile()||!file.canRead()){
          try{Os.stat(file.getPath());entry.put("status","inaccessible");}
          catch(Exception error){entry.put("status",error(error));}continue;
        }
        if(!codeFile(file)){entry.put("status","rejected");continue;}
        long size=file.length();
        if(SystemClock.uptimeMillis()>deadline){entry.put("status","skipped_timeout");continue;}
        if(size>MAX_FILE||total+size>MAX_BUNDLE){entry.put("status","skipped_limit");continue;}
        MessageDigest digest=MessageDigest.getInstance("SHA-256");long count=0;
        try(FileInputStream in=new FileInputStream(file)) {
          zip.putNextEntry(new ZipEntry(names.get(i)));int read;
          while((read=in.read(buffer))!=-1) {
            count+=read;if(count>size)throw new IllegalStateException("System bundle interrupted; discard the incomplete ZIP");
            zip.write(buffer,0,read);digest.update(buffer,0,read);
          }
          zip.closeEntry();
        }
        if(count!=size)throw new IllegalStateException("System component changed; discard the incomplete ZIP");
        StringBuilder hash=new StringBuilder();for(byte b:digest.digest())hash.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        entry.put("status","copied").put("bytes",count).put("sha256",hash.toString());total+=count;copied++;
      }
      JSONObject contents=new JSONObject().put("schema",1).put("software",software()).put("files",manifest)
          .put("scope","Allowlisted stock code only. No app data, custom fonts, icons, settings databases or personal files.");
      zip.putNextEntry(new ZipEntry("manifest.json"));zip.write(encode(contents));zip.closeEntry();
    }
    return new JSONObject().put("ok",true).put("files",copied).put("uncompressed_bytes",total).put("inventory",manifest);
  }
}
