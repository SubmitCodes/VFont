package dev.fontlab;

import android.app.WallpaperManager;
import android.content.*;
import android.graphics.Rect;
import android.net.Uri;
import android.os.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import org.json.*;

/** The finite configuration footprint of VFont's full-theme activation. */
final class FullThemeState {
  private static final Map<String,List<String>> SETTINGS=Map.of(
      "system",List.of("theme_id","theme_style","theme_gray_value","lock_screen_theme_id","lock_screen_theme_id_2","lock_screen_theme_id_3","lock_screen_theme_tradition_id","lock_screen_zip_id","launcher_widget_radius","launcher_widget_radius_nx1","desktop_wallpaper_res","lockscreen_wallpaper_res","vivo_keyguard_not_allow_shadow_colorfade","isThirdThemeIcon","explore_theme_icons_radius_style","theme_icons_style_explore","deform_icons_size_explore","explore_launcher_icons_radius_style"),
      "secure",List.of("launcher_folder_blur_style","cur_icon_style_res_name","cur_icon_style_res_from_themes","lock_screen_theme_id_os","deskreport_wallpaper_data","vivo_type_change_wallpaper","second_unlock_wallpaper_type","wallpaper_apply_info_lock","unlock_wallpaper_type","lock_report_wallpaper_data","wallpaper_apply_info","isThirdThemeIcon","deform_icons_size"),
      "global",List.of("lock_screen_theme_resid","explore_theme_icons_radius_style","theme_icons_style_explore","explore_launcher_icons_radius_style"));
  private interface ProviderAction<T>{T run(ContentProviderClient client) throws Exception;}
  private static <T>T provider(Context context,String authority,ProviderAction<T> action) throws Exception {
    Object manager=Class.forName("android.app.ActivityManagerNative").getMethod("getDefault").invoke(null);IBinder token=new Binder();
    Object holder=manager.getClass().getMethod("getContentProviderExternal",String.class,int.class,IBinder.class,String.class).invoke(manager,authority,0,token,"VFontFullTheme");
    if(holder==null)throw new IOException("Native theme provider is unavailable");
    try{
      var ctor=ContentProviderClient.class.getDeclaredConstructor(ContentResolver.class,Class.forName("android.content.IContentProvider"),String.class,boolean.class);ctor.setAccessible(true);
      try(ContentProviderClient client=(ContentProviderClient)ctor.newInstance(context.getContentResolver(),holder.getClass().getField("provider").get(holder),authority,false)){return action.run(client);}
    }finally{manager.getClass().getMethod("removeContentProviderExternalAsUser",String.class,IBinder.class,int.class).invoke(manager,authority,token,0);}
  }
  static final List<String> THEME_PACKAGES=List.of("com.bbk.theme","com.vivo.theme","com.bbk.lite.theme");
  static String themePackage(Context context){
    var pm=context.getPackageManager();
    for(String pkg:THEME_PACKAGES){try{pm.getApplicationInfo(pkg,0);return pkg;}catch(Exception ignored){}}
    return "com.bbk.theme";
  }
  static Set<Integer> themeStoreUids(Context context){
    var pm=context.getPackageManager();Set<Integer> uids=new LinkedHashSet<>();
    for(String pkg:THEME_PACKAGES){try{uids.add(pm.getApplicationInfo(pkg,0).uid);}catch(Exception ignored){}}
    return uids;
  }
  static String get(Context context,String table,String key) throws Exception {
    if(!SETTINGS.getOrDefault(table,List.of()).contains(key))throw new IllegalArgumentException("Unknown theme setting");
    return provider(context,"settings",client->{Bundle args=new Bundle();args.putInt("_user",0);return client.call("GET_"+table,key,args).getString("value");});
  }
  private static void put(Context context,String table,String key,String value) throws Exception {
    if(!SETTINGS.getOrDefault(table,List.of()).contains(key))throw new IllegalArgumentException("Unknown theme setting");
    provider(context,"settings",client->{if(value==null)client.delete(Uri.parse("content://settings/"+table),"name=?",new String[]{key});else {Bundle args=new Bundle();args.putInt("_user",0);args.putString("value",value);client.call("PUT_"+table,key,args);}return null;});
    if(!Objects.equals(value,get(context,table,key)))throw new IOException("Native theme setting was not saved: "+key);
  }
  private static void tryPut(Context context,String table,String key,String value,List<String> warnings){
    try{put(context,table,key,value);}catch(Exception error){if(warnings!=null&&!warnings.contains(key))warnings.add(key);}
  }
  private static JSONObject settings(Context context) throws Exception {
    JSONObject out=new JSONObject();for(var table:SETTINGS.entrySet()){JSONObject fields=new JSONObject();for(String key:table.getValue())fields.put(key,Objects.requireNonNullElse(get(context,table.getKey(),key),JSONObject.NULL));out.put(table.getKey(),fields);}return out;
  }
  static void repairMetadata(Context context,JSONObject before,JSONObject expected) throws Exception {
    for(String table:FullThemeStore.keys(before))for(String key:FullThemeStore.keys(before.getJSONObject(table)))
      if(!Objects.equals(optional(expected.getJSONObject(table),key),get(context,table,key)))throw new IOException("Wallpaper metadata changed since the recovery check; original values were kept");
    for(String table:FullThemeStore.keys(before))for(String key:FullThemeStore.keys(before.getJSONObject(table)))put(context,table,key,optional(before.getJSONObject(table),key));
  }
  private static byte[] bytes(File file) throws Exception {
    if(Files.isSymbolicLink(file.toPath())||!file.isFile()||file.length()>8L*1024*1024)throw new IOException("The current wallpaper cannot be backed up within the supported limit");
    byte[] bytes=Files.readAllBytes(file.toPath());if(bytes.length>8*1024*1024)throw new IOException("Wallpaper backup size changed");return bytes;
  }
  private static String dump() throws Exception {
    java.lang.Process process=new ProcessBuilder("/system/bin/dumpsys","wallpaper").redirectErrorStream(true).start();
    try{if(!process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS))throw new IOException("Cannot inspect current wallpapers");byte[] data=FullThemeStore.readBounded(process.getInputStream(),524289);if(process.exitValue()!=0||data.length>524288)throw new IOException("Unsupported wallpaper state");return new String(data,java.nio.charset.StandardCharsets.UTF_8);}
    finally{if(process.isAlive())process.destroyForcibly();}
  }
  private static JSONObject capturePaper(String side,String dump,WallpaperManager manager,boolean backup){
    String label=side.equals("home")?"home":"lock screen";
    int which=side.equals("home")?WallpaperManager.FLAG_SYSTEM:WallpaperManager.FLAG_LOCK;
    int id=-1;try{id=manager.getWallpaperId(which);}catch(Exception ignored){}
    JSONObject paper=new JSONObject();
    try{
      paper.put("static",false).put("id",id);
      String start=side.equals("home")?"System wallpaper state:":"Lock wallpaper state:";
      int index=dump.indexOf(start);
      if(index<0){paper.put("reason","The current "+label+" wallpaper cannot be backed up individually; set a static "+label+" wallpaper first if changing wallpapers.");return paper;}
      String section=dump.substring(index+start.length());int end=section.indexOf("wallpaper state:");if(end>=0)section=section.substring(0,end);
      if(!section.contains("ComponentInfo{com.android.systemui/com.android.systemui.wallpapers.ImageWallpaper}")&&!section.contains("ComponentInfo{com.android.systemui/.wallpapers.ImageWallpaper}")){
        paper.put("reason","Full-theme wallpaper changes require a static "+label+" wallpaper so the original can be restored.");return paper;
      }
      Matcher crop=Pattern.compile("mCropHint=Rect\\((-?[0-9]+), (-?[0-9]+) - (-?[0-9]+), (-?[0-9]+)\\)").matcher(section);
      if(!crop.find()){paper.put("reason","Cannot retain the current "+label+" wallpaper crop.");return paper;}
      JSONArray rect=new JSONArray();for(int i=1;i<=4;i++)rect.put(Integer.parseInt(crop.group(i)));
      File original=new File("/data/system/users/0/"+(side.equals("home")?"wallpaper_orig":"wallpaper_lock_orig"));
      byte[] data=bytes(original);String sha=IconSystemTask.hash(data);
      paper.put("static",true).put("sha256",sha).put("crop",rect).put("id",id);
      if(backup)paper.put("data",Base64.getEncoder().encodeToString(data));
      return paper;
    }catch(Exception error){
      try{paper.put("reason",error.getMessage()!=null?error.getMessage():"Unsupported "+label+" wallpaper");}catch(JSONException ignored){}
      return paper;
    }
  }
  static String staticWallpaperError(JSONObject captured,Set<String> parts){
    JSONObject papers=captured==null?null:captured.optJSONObject("papers");if(papers==null)return "Cannot inspect current wallpapers.";
    for(String side:List.of("home","lock")){
      boolean needed=parts==null||parts.contains(side.equals("home")?"home":"lockpaper");
      if(!needed)continue;
      JSONObject paper=papers.optJSONObject(side);
      if(paper==null||!paper.optBoolean("static",false))return paper!=null&&paper.has("reason")?paper.optString("reason"):"Full-theme Apply currently requires static home and lock wallpapers so both can be restored exactly.";
    }
    return null;
  }
  static JSONObject capture(Context context,boolean backup) throws Exception {
    JSONObject out=new JSONObject().put("settings",settings(context)),papers=new JSONObject();String dump=dump();WallpaperManager manager=WallpaperManager.getInstance(context);
    for(String side:List.of("home","lock"))papers.put(side,capturePaper(side,dump,manager,backup));
    Object factory=Class.forName("vivo.app.VivoFrameworkFactory").getMethod("getFrameworkFactoryImpl").invoke(null);
    Object icons=factory.getClass().getMethod("getThemeIconManager").invoke(factory);
    out.put("icon_path",icons.getClass().getMethod("getThemePath",int.class,int.class).invoke(icons,0,0));
    out.put("resource_path",icons.getClass().getMethod("getThemePath",int.class,int.class).invoke(icons,1,0));
    out.put("papers",papers);return out;
  }
  private static String optional(JSONObject object,String key){return object.isNull(key)?null:object.optString(key,null);}
  private static boolean same(String a,String b){
    if(Objects.equals(a,b))return true;
    try{return new JSONObject(a).toString().equals(new JSONObject(b).toString());}catch(Exception ignored){return false;}
  }
  static boolean appearancePreference(String table,String key){
    return table.equals("secure")&&List.of("launcher_folder_blur_style","isThirdThemeIcon","deform_icons_size").contains(key)
        ||table.equals("system")&&List.of("launcher_widget_radius","launcher_widget_radius_nx1","isThirdThemeIcon","explore_theme_icons_radius_style","theme_icons_style_explore","deform_icons_size_explore","explore_launcher_icons_radius_style").contains(key)
        ||table.equals("global")&&List.of("explore_theme_icons_radius_style","theme_icons_style_explore","explore_launcher_icons_radius_style").contains(key);
  }
  static void verifyManaged(Context context,JSONObject original,JSONObject managed) throws Exception {
    verifyManagedSettings(original,managed,settings(context));
  }
  static void verifyManagedSettings(JSONObject original,JSONObject managed,JSONObject now) throws Exception {
    // Wallpaper changes are independent of the managed theme files and their first recovery backup.
    for(String table:SETTINGS.keySet())for(String key:SETTINGS.get(table)){
      String before=optional(original.getJSONObject("settings").getJSONObject(table),key),want=optional(managed.getJSONObject("settings").getJSONObject(table),key),got=optional(now.getJSONObject(table),key);
      if(!appearancePreference(table,key)&&!selectedSetting(key,Set.of("home","lockpaper"))&&!same(before,want)&&!same(want,got))throw new IOException("Theme settings changed after Apply ("+key+"); the saved original was kept");
    }
  }
  static JSONObject recoveryConfig(Context context,JSONObject original,JSONObject managed) throws Exception {
    JSONObject result=new JSONObject(original.toString());
    for(String table:SETTINGS.keySet())for(String key:SETTINGS.get(table)){
      String before=optional(original.getJSONObject("settings").getJSONObject(table),key),after=optional(managed.getJSONObject("settings").getJSONObject(table),key);
      String now=get(context,table,key);
      if(!original.getJSONObject("settings").getJSONObject(table).has(key)||same(before,after)&&!appearancePreference(table,key))result.getJSONObject("settings").getJSONObject(table).put(key,Objects.requireNonNullElse(now,JSONObject.NULL));
    }
    return result;
  }
  static void verifyInterrupted(Context context,JSONObject original,JSONObject metadata) throws Exception {
    JSONObject now=capture(context,false);
    for(String side:List.of("home","lock")){
      JSONObject gotPaper=now.getJSONObject("papers").getJSONObject(side),beforePaper=original.getJSONObject("papers").getJSONObject(side);
      if(!beforePaper.optBoolean("static",true)&&!metadata.has(side+"_sha256")){
        if(gotPaper.optBoolean("static",false)||gotPaper.optInt("id",-1)!=beforePaper.optInt("id",-1))throw new IOException("Wallpaper changed during the interrupted Apply; saved originals were kept");
        continue;
      }
      String got=gotPaper.optString("sha256",""),before=beforePaper.optString("sha256","");
      if(!got.equals(before)&&!got.equals(metadata.optString(side+"_sha256")))throw new IOException("Wallpaper changed during the interrupted Apply; saved originals were kept");
    }
  }
  private static void keyguard(Context context,String type,String value) throws Exception {
    provider(context,"com.vivo.keyguard.keyguardstyle",client->{Bundle args=new Bundle();args.putString("type",type);args.putString(type,value);client.update(Uri.parse("content://com.vivo.keyguard.keyguardstyle"),new ContentValues(),args);return null;});
  }
  static int nextGeneration(int generation){return generation==-1||generation==Integer.MAX_VALUE?1:generation+1;}
  private static void configuration() throws Exception {
    Object manager=Class.forName("android.app.ActivityManagerNative").getMethod("getDefault").invoke(null);
    Object current=manager.getClass().getMethod("getConfiguration").invoke(manager);
    int generation=(Integer)current.getClass().getMethod("getThemeId").invoke(current);
    // Zero is ignored by Vivo; assetsSeq also invalidates drawables retained by System UI views.
    int next=nextGeneration(generation);
    var assets=current.getClass().getField("assetsSeq");int nextAssets=nextGeneration(assets.getInt(current));
    Object change=current.getClass().getConstructor().newInstance();change.getClass().getMethod("setThemeId",int.class).invoke(change,next);assets.setInt(change,nextAssets);
    manager.getClass().getMethod("updateConfiguration",current.getClass()).invoke(manager,change);
    Object updated=manager.getClass().getMethod("getConfiguration").invoke(manager);
    if((Integer)updated.getClass().getMethod("getThemeId").invoke(updated)!=next||assets.getInt(updated)!=nextAssets)throw new IOException("Vivo did not invalidate theme resources; saved originals were kept");
  }
  @android.annotation.SuppressLint("MissingPermission") // Invoked only by the validated UID1000 worker; the app has no wallpaper-write permission.
  private static void setPaper(Context context,String side,byte[] bytes,JSONArray crop) throws Exception {
    Rect rect=crop==null?null:new Rect(crop.getInt(0),crop.getInt(1),crop.getInt(2),crop.getInt(3));if(rect!=null&&rect.isEmpty())rect=null;
    int which=side.equals("home")?WallpaperManager.FLAG_SYSTEM:WallpaperManager.FLAG_LOCK;
    try(InputStream in=new ByteArrayInputStream(bytes)){WallpaperManager.getInstance(context).setStream(in,rect,true,which);}
    File output=new File("/data/system/users/0/"+(side.equals("home")?"wallpaper_orig":"wallpaper_lock_orig"));
    if(!IconSystemTask.hash(bytes).equals(IconSystemTask.hash(bytes(output))))throw new IOException("Wallpaper readback did not match; the saved original was kept");
  }
  private static String themeIdOrDefault(String id,String contextLabel) throws IOException {
    if(id==null||id.isEmpty()||"null".equals(id))return "-1";
    if(!id.matches("-?[0-9]{1,20}"))throw new IOException("The "+contextLabel+" native theme identity needs review ("+id+")");
    return id;
  }
  static void activate(Context context,JSONObject metadata) throws Exception {
    JSONObject appearance=null;
    if(metadata.optBoolean("adjustable_icons")){
      for(String table:List.of("system","secure"))tryPut(context,table,"isThirdThemeIcon","0",null);
      for(String table:List.of("system","global"))tryPut(context,table,"theme_icons_style_explore","1",null);
      appearance=settings(context);
    }
    if(metadata.optBoolean("partial")){
      String id=themeIdOrDefault(get(context,"system","theme_id"),"current"),style=get(context,"system","theme_style");
      if(metadata.has("lock_file"))activateLock(context,metadata);
      for(String side:List.of("home","lock"))if(metadata.has(side+"_wallpaper"))setPaper(context,side,bytes(new File("/data/bbkcore/theme/wallpaper",metadata.getString(side+"_wallpaper"))),null);
      Set<String> parts=ThemeParts.selection(metadata.getJSONArray("components"));
      JSONObject before=settings(context);configuration();refresh(context,id,style,parts.contains("icons")||parts.contains("launcher"));
      if(metadata.optBoolean("native_resources")&&!capture(context,false).getString("resource_path").equals("/data/bbkcore/theme/"))throw new IOException("The native renderer did not select the imported shared resources; saved originals were kept");
      for(String table:SETTINGS.keySet())for(String key:SETTINGS.get(table))if(appearancePreference(table,key))tryPut(context,table,key,optional(before.getJSONObject(table),key),null);
      return;
    }
    put(context,"system","theme_id",String.valueOf(metadata.getInt("config_id")));
    String style=new File("/data/bbkcore/theme/vivo").isFile()?"whole":"general";put(context,"system","theme_style",style);tryPut(context,"system","theme_gray_value","-1",null);
    activateLock(context,metadata);
    for(String side:List.of("home","lock"))if(metadata.has(side+"_wallpaper"))setPaper(context,side,bytes(new File("/data/bbkcore/theme/wallpaper",metadata.getString(side+"_wallpaper"))),null);
    configuration();refresh(context,String.valueOf(metadata.getInt("config_id")),style,true);
    if(appearance!=null)for(String table:SETTINGS.keySet())for(String key:SETTINGS.get(table))if(appearancePreference(table,key))tryPut(context,table,key,optional(appearance.getJSONObject(table),key),null);
    JSONObject active=capture(context,false);String path=active.getString("icon_path");
    if(!path.equals("/data/bbkcore/theme/")&&!path.equals("/data/bbkcore/theme/style/1/"))throw new IOException("The native renderer did not select this theme");
    if(!active.getString("resource_path").equals("/data/bbkcore/theme/"))throw new IOException("The native renderer did not select the imported shared resources; saved originals were kept");
  }
  private static void activateLock(Context context,JSONObject metadata) throws Exception {
    if(metadata.has("lock_file")){
      String path="/data/bbkcore/unlock/"+metadata.getString("lock_file");
      put(context,"system","lock_screen_theme_id","-12");put(context,"system","lock_screen_theme_id_2",path);put(context,"system","lock_screen_theme_id_3",metadata.getString("lock_id"));
      put(context,"global","lock_screen_theme_resid",metadata.getString("id"));keyguard(context,"keyguard_online_path",path);
      String saved=get(context,"secure","lock_screen_theme_id_os");if(saved==null||!saved.contains(path))throw new IOException("The lock-screen engine did not accept this theme");
    }
  }
  static String restore(Context context,JSONObject original) throws Exception {
    JSONObject settings=original.getJSONObject("settings");List<String> warnings=new ArrayList<>();
    String keyguard=optional(settings.getJSONObject("secure"),"lock_screen_theme_id_os");
    if(keyguard!=null&&!keyguard.isEmpty())try{keyguard(context,"keyguard_style",keyguard);}catch(Exception ignored){warnings.add("lock_screen_theme_id_os");}
    for(String table:SETTINGS.keySet())for(String key:SETTINGS.get(table))if(settings.getJSONObject(table).has(key))tryPut(context,table,key,optional(settings.getJSONObject(table),key),warnings);
    for(String side:List.of("home","lock")){
      JSONObject paper=original.getJSONObject("papers").getJSONObject(side);
      if(!paper.optBoolean("static",true))continue;
      byte[] data=Base64.getDecoder().decode(paper.getString("data"));
      if(!paper.getString("sha256").equals(IconSystemTask.hash(data)))throw new IOException("Wallpaper recovery checksum mismatch");setPaper(context,side,data,paper.getJSONArray("crop"));
    }
    String id=themeIdOrDefault(optional(settings.getJSONObject("system"),"theme_id"),"original");
    configuration();refresh(context,id,optional(settings.getJSONObject("system"),"theme_style"),true);
    Thread.sleep(1200);
    // Vivo's launcher may reset blur and widget radii during its theme broadcast.
    for(String table:SETTINGS.keySet())for(String key:SETTINGS.get(table))if(settings.getJSONObject(table).has(key))tryPut(context,table,key,optional(settings.getJSONObject(table),key),warnings);
    Thread.sleep(500);
    tryPut(context,"secure","vivo_type_change_wallpaper",optional(settings.getJSONObject("secure"),"vivo_type_change_wallpaper"),warnings);
    tryPut(context,"secure","launcher_folder_blur_style",optional(settings.getJSONObject("secure"),"launcher_folder_blur_style"),warnings);
    JSONObject current=capture(context,false);
    List<String> drifted=new ArrayList<>();
    for(String table:SETTINGS.keySet())for(String key:SETTINGS.get(table))if(settings.getJSONObject(table).has(key)&&!same(optional(settings.getJSONObject(table),key),optional(current.getJSONObject("settings").getJSONObject(table),key)))drifted.add(key);
    for(String side:List.of("home","lock")){
      JSONObject origPaper=original.getJSONObject("papers").getJSONObject(side);
      if(!origPaper.optBoolean("static",true))continue;
      if(!origPaper.getString("sha256").equals(current.getJSONObject("papers").getJSONObject(side).optString("sha256","")))throw new IOException("Wallpaper recovery is incomplete");
    }
    return drifted.isEmpty()?null:"Note: minor ROM settings differed after restore ("+String.join(", ",drifted)+").";
  }
  static boolean selectedSetting(String key,Set<String> parts){
    return parts.contains("home")&&List.of("desktop_wallpaper_res","deskreport_wallpaper_data","wallpaper_apply_info","vivo_type_change_wallpaper").contains(key)
        ||parts.contains("lockpaper")&&List.of("lockscreen_wallpaper_res","second_unlock_wallpaper_type","unlock_wallpaper_type","lock_report_wallpaper_data","wallpaper_apply_info_lock","vivo_type_change_wallpaper").contains(key)
        ||parts.contains("lock")&&(key.startsWith("lock_screen_")||key.equals("vivo_keyguard_not_allow_shadow_colorfade"))
        ||parts.contains("icons")&&List.of("isThirdThemeIcon","explore_theme_icons_radius_style","cur_icon_style_res_name","cur_icon_style_res_from_themes","theme_icons_style_explore","deform_icons_size_explore","deform_icons_size","explore_launcher_icons_radius_style").contains(key)
        ||parts.contains("launcher")&&(key.equals("launcher_folder_blur_style")||key.startsWith("launcher_widget_radius"));
  }
  static String restoreSelected(Context context,JSONObject original,JSONObject before,Set<String> parts)throws Exception {
    JSONObject desired=new JSONObject(before.getJSONObject("settings").toString()),saved=original.getJSONObject("settings");
    for(String table:SETTINGS.keySet())for(String key:SETTINGS.get(table))if(selectedSetting(key,parts)&&saved.getJSONObject(table).has(key))desired.getJSONObject(table).put(key,saved.getJSONObject(table).get(key));
    List<String> warnings=new ArrayList<>();
    if(parts.contains("lock")){
      String keyguard=optional(saved.getJSONObject("secure"),"lock_screen_theme_id_os");if(keyguard!=null&&!keyguard.isEmpty())try{keyguard(context,"keyguard_style",keyguard);}catch(Exception ignored){warnings.add("lock_screen_theme_id_os");}
    }
    for(String side:List.of("home","lock"))if(parts.contains(side.equals("home")?"home":"lockpaper")){
      JSONObject paper=original.getJSONObject("papers").getJSONObject(side);
      if(!paper.optBoolean("static",true))throw new IOException(paper.optString("reason","The original "+side+" wallpaper was not a static image."));
      byte[] data=Base64.getDecoder().decode(paper.getString("data"));
      if(!paper.getString("sha256").equals(IconSystemTask.hash(data)))throw new IOException("Wallpaper recovery checksum mismatch");setPaper(context,side,data,paper.getJSONArray("crop"));
    }
    for(String table:SETTINGS.keySet())for(String key:SETTINGS.get(table))tryPut(context,table,key,optional(desired.getJSONObject(table),key),warnings);
    String id=themeIdOrDefault(optional(desired.getJSONObject("system"),"theme_id"),"current");
    configuration();refresh(context,id,optional(desired.getJSONObject("system"),"theme_style"),parts.contains("icons")||parts.contains("launcher"));Thread.sleep(1200);
    for(String table:SETTINGS.keySet())for(String key:SETTINGS.get(table))tryPut(context,table,key,optional(desired.getJSONObject(table),key),warnings);
    JSONObject now=capture(context,false);
    if(parts.contains("icons")&&!original.getString("icon_path").equals(now.getString("icon_path")))throw new IOException("Original icon rendering path was not restored; the backup was kept");
    List<String> drifted=new ArrayList<>();
    for(String table:SETTINGS.keySet())for(String key:SETTINGS.get(table))if(!same(optional(desired.getJSONObject(table),key),optional(now.getJSONObject("settings").getJSONObject(table),key)))drifted.add(key);
    for(String side:List.of("home","lock")){
      boolean selected=parts.contains(side.equals("home")?"home":"lockpaper");JSONObject want=(selected?original:before).getJSONObject("papers").getJSONObject(side),got=now.getJSONObject("papers").getJSONObject(side);
      if(!want.optBoolean("static",true)){
        if(got.optBoolean("static",false)||got.optInt("id",-1)!=want.optInt("id",-1))throw new IOException("Selected wallpaper recovery is incomplete; the backup was kept");
        continue;
      }
      if(!got.optBoolean("static",true)||!want.getString("sha256").equals(got.optString("sha256",""))||!want.getJSONArray("crop").toString().equals(got.getJSONArray("crop").toString())||!selected&&want.getInt("id")!=got.getInt("id"))throw new IOException("Selected wallpaper recovery is incomplete; the backup was kept");
    }
    return drifted.isEmpty()?null:"Note: minor ROM settings differed after restore ("+String.join(", ",drifted)+").";
  }
  private static void refresh(Context context,String id,String style,boolean rebuildLauncher) throws Exception {
    java.lang.Process process=new ProcessBuilder("/system/bin/am","broadcast","--user","0","-a","intent.action.theme.changed","--es","pkg",themePackage(context),"--es","themeId",id,"--es","style",style==null?"general":style).redirectErrorStream(true).start();
    try{if(!process.waitFor(20,java.util.concurrent.TimeUnit.SECONDS))throw new IOException("Native theme refresh is incomplete");String receipt=new String(FullThemeStore.readBounded(process.getInputStream(),4097),java.nio.charset.StandardCharsets.UTF_8);if(process.exitValue()!=0||!receipt.contains("Broadcast completed: result="))throw new IOException("Native theme refresh was rejected");}
    finally{if(process.isAlive())process.destroyForcibly();}
    var pm=context.getPackageManager();
    for(var entry:pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0)){
      ComponentName component=new ComponentName(entry.activityInfo.packageName,entry.activityInfo.name);
      if(IconRefresh.supported(pm,component))IconRefresh.refresh(pm,component);
    }
    IconRefresh.refreshSidebar(context);settleLauncher(context,rebuildLauncher);
  }
  private static ComponentName focusedActivity() throws Exception {
    Object manager=Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
    Object task=manager.getClass().getMethod("getFocusedRootTaskInfo").invoke(manager);
    return task==null?null:(ComponentName)task.getClass().getField("topActivity").get(task);
  }
  private static void activity(String... args) throws Exception {
    List<String> command=new ArrayList<>(List.of("/system/bin/am","start","--user","0"));command.addAll(List.of(args));
    java.lang.Process process=new ProcessBuilder(command).redirectErrorStream(true).start();
    try{if(!process.waitFor(15,java.util.concurrent.TimeUnit.SECONDS)||process.exitValue()!=0)throw new IOException("The home screen did not finish refreshing");}
    finally{if(process.isAlive())process.destroyForcibly();}
  }
  private static void settleLauncher(Context context,boolean rebuild) throws Exception {
    ComponentName previous=focusedActivity();
    var resolved=context.getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
    if(resolved==null||resolved.activityInfo==null)throw new IOException("The default home app is unavailable");
    String launcher=resolved.activityInfo.packageName;
    try{
      if(rebuild){
        // Theme broadcasts can leave the drawer's fallback icons cached with the previous mask.
        Object manager=Class.forName("android.app.ActivityManagerNative").getMethod("getDefault").invoke(null);
        manager.getClass().getMethod("forceStopPackage",String.class,int.class).invoke(manager,launcher,0);
      }
      // A background launcher postpones its configuration callback, which resets widget radii on next Home.
      activity("-a",Intent.ACTION_MAIN,"-c",Intent.CATEGORY_HOME);
      long deadline=SystemClock.uptimeMillis()+10000;boolean ready=false;
      do{ComponentName current=focusedActivity();if(current!=null&&current.getPackageName().equals(launcher)){ready=true;break;}Thread.sleep(100);}while(SystemClock.uptimeMillis()<deadline);
      if(!ready)throw new IOException("Open the home screen to finish native theme recovery");
      Thread.sleep(2000);
    }finally{
      if(previous!=null&&previous.getPackageName().equals("dev.fontlab")&&List.of("dev.fontlab.MainActivity","dev.fontlab.ThemesActivity","dev.fontlab.IconsActivity").contains(previous.getClassName()))
        activity("--activity-reorder-to-front","--activity-single-top","-n",previous.flattenToString());
    }
  }
}
