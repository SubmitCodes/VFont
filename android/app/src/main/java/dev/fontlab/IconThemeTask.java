package dev.fontlab;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.BitmapFactory;
import android.os.Build;
import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.util.*;
import org.json.*;

/** Icon-only native themes. The fast backend remains preferred until a theme transaction exists. */
final class IconThemeTask {
  static boolean preferTheme(boolean fastCapable,boolean themePending){return themePending||!fastCapable;}
  private static File root() throws Exception {
    var field=Class.forName("com.vivo.content.VivoTheme").getDeclaredField("themeBasePath");field.setAccessible(true);
    File root=new File((String)field.get(null));
    // Only the reviewed main-user store: discovery must never select a similarly named cache.
    if(!root.getPath().equals("/data/bbkcore/theme")||!root.getCanonicalFile().equals(root))throw new IOException("Unrecognized native theme store");
    return root;
  }
  static String active() throws Exception {
    Object factory=Class.forName("vivo.app.VivoFrameworkFactory").getMethod("getFrameworkFactoryImpl").invoke(null);
    Object manager=factory.getClass().getMethod("getThemeIconManager").invoke(factory);
    String path=(String)manager.getClass().getMethod("getThemePath",int.class,int.class).invoke(manager,0,0);
    if(path==null)throw new IOException("Cannot identify the active icon theme");return new File(path).getPath();
  }
  private static File stock() throws Exception {
    String path=active();
    if(path.equals("/data/bbkcore/theme/style/1"))return new File("/system/etc/theme/style/1");
    if(path.startsWith("/data/bbkcore/theme"))throw new IOException("Restore or switch back to a stock icon style before using separate icon-theme recovery");
    if(!path.startsWith("/system/")&&!path.startsWith("/oem/")&&!path.startsWith("/product/")&&!path.startsWith("/vendor/")&&!path.startsWith("/system_ext/")&&!path.startsWith("/my_")&&!path.startsWith("/cust/"))
      throw new IOException("The active icon style needs review ("+path+")");
    return new File(path);
  }
  private static IconThemeStore open(IconThemeStore.Step step) throws Exception {return new IconThemeStore(root(),stock(),step);}
  static boolean pending() throws Exception {
    File saved=new File("/data/bbkcore/theme/1999901990");
    if(!Files.exists(new File(saved,"state.json").toPath(),LinkOption.NOFOLLOW_LINKS)
        &&!Files.exists(new File(saved,"refresh.json").toPath(),LinkOption.NOFOLLOW_LINKS)){
      for(String key:new String[]{"original-style","original-renderer","previous-style","previous-renderer"})
        if(Files.exists(new File(saved,key).toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("Theme recovery journal is missing; saved resources require review");
      return false;
    }
    return open(null).present();
  }
  private static String folderBlur(Context context,boolean write,String value) throws Exception {
    String authority="settings",key="launcher_folder_blur_style";
    Object manager=Class.forName("android.app.ActivityManagerNative").getMethod("getDefault").invoke(null);
    android.os.IBinder token=new android.os.Binder();
    Object holder=manager.getClass().getMethod("getContentProviderExternal",String.class,int.class,android.os.IBinder.class,String.class)
        .invoke(manager,authority,0,token,"VFontThemeFolderBlur");
    if(holder==null)throw new IOException("Settings provider is unavailable");
    try{
      var ctor=android.content.ContentProviderClient.class.getDeclaredConstructor(android.content.ContentResolver.class,
          Class.forName("android.content.IContentProvider"),String.class,boolean.class);ctor.setAccessible(true);
      // app_process has no registered ApplicationThread; acquire the fixed main-user provider externally.
      try(var client=(android.content.ContentProviderClient)ctor.newInstance(context.getContentResolver(),holder.getClass().getField("provider").get(holder),authority,false)){
        android.os.Bundle user=new android.os.Bundle();user.putInt("_user",0);
        if(write){
          if(value==null)client.delete(android.net.Uri.parse("content://settings/secure"),"name=?",new String[]{key});
          else {android.os.Bundle update=new android.os.Bundle(user);update.putString("value",value);client.call("PUT_secure",key,update);}
        }
        return client.call("GET_secure",key,user).getString("value");
      }
    }finally{manager.getClass().getMethod("removeContentProviderExternalAsUser",String.class,android.os.IBinder.class,int.class).invoke(manager,authority,token,0);}
  }
  private static void refresh(Context context,IconThemeStore store) throws Exception {
    if(!store.refreshNeeded())return;
    String blur=store.preserveFolderBlur(folderBlur(context,false,null));
    ResolveInfo home=context.getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),PackageManager.MATCH_DEFAULT_ONLY);
    String launcher=home!=null&&home.activityInfo!=null?home.activityInfo.packageName:"com.bbk.launcher2";
    String themePkg=FullThemeState.themePackage(context);
    java.lang.Process broadcast=new ProcessBuilder("/system/bin/am","broadcast","--user","0","-a","intent.action.theme.changed",
        "-p",launcher,"--es","pkg",themePkg,"--es","reason","icons").redirectErrorStream(true).start();
    try{
      if(!broadcast.waitFor(15,java.util.concurrent.TimeUnit.SECONDS))throw new IOException("Theme refresh is incomplete; reopen the icon manager to recover");
      String receipt=new String(FullThemeStore.readBounded(broadcast.getInputStream(),4097),java.nio.charset.StandardCharsets.UTF_8);
      if(broadcast.exitValue()!=0||receipt.length()>4096||!receipt.contains("Broadcast completed: result="))throw new IOException("Native theme refresh was rejected; saved originals were kept");
    }finally{if(broadcast.isAlive())broadcast.destroyForcibly();}
    PackageManager pm=context.getPackageManager();Set<String> keys=store.refreshKeys();
    for(ResolveInfo entry:pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0)) {
      ComponentName component=new ComponentName(entry.activityInfo.packageName,entry.activityInfo.name);
      if((keys.contains(component.getPackageName())||keys.contains(component.getClassName()))&&IconRefresh.supported(pm,component))IconRefresh.refresh(pm,component);
    }
    String now=folderBlur(context,false,null);
    if(!Objects.equals(now,blur)){
      if(!Objects.equals(blur,folderBlur(context,true,blur)))throw new IOException("Folder blur could not be restored; reopen the icon manager");
    }
    IconRefresh.refreshSidebar(context);store.refreshed();
  }
  private static String profile(JSONObject fast,File base) throws Exception {
    return IconSystemTask.hash(("theme-icons-v2\n"+fast.optString("profile")+"\n"
        +IconThemeStore.hash(new File(base,"description.xml"))+"\n"+IconThemeStore.hash(new File(base,"launcher/iconsize.xml")))
        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }
  private static JSONObject report(Context context,JSONObject fast,IconThemeStore store) throws Exception {
    JSONArray failures=new JSONArray();String profile="",path="";boolean approved=false;
    JSONObject state=store.state();
    try {
      if(Build.VERSION.SDK_INT<31||!Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a"))throw new IOException("Android 12+ on arm64 is required");
      Object manager=Class.forName("android.app.ActivityManagerNative").getMethod("getDefault").invoke(null);
      Object user=manager.getClass().getMethod("getCurrentUser").invoke(manager);
      if(user.getClass().getField("id").getInt(user)!=0)throw new IOException("Switch to the main Android user");
      ResolveInfo home=context.getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),PackageManager.MATCH_DEFAULT_ONLY);
      if(home==null||home.activityInfo==null||!home.activityInfo.packageName.equals("com.bbk.launcher2"))throw new IOException("Vivo's stock Home app must be the default launcher");
      if(!root().canWrite())throw new IOException("Native theme directory is not writable");
      folderBlur(context,false,null);
      path=active();File base=store.base();IconThemeArchive.validateBase(base);
      if(state==null&&!path.equals(base.getPath())&&!path.equals(new File(root(),"style/1").getPath()))throw new IOException("The selected icon style changed");
      if(state!=null){store.verifyCurrent();if(!path.equals(new File(root(),"style/1").getPath()))throw new IOException("Icon style changed outside VFont; restore requires review");}
      profile=profile(fast,base);approved=store.approved(profile);
      if(state!=null&&!profile.equals(state.optString("profile")))throw new IOException("Theme software or style changed; restore the original before applying again");
    }catch(Exception error){failures.put(error.getMessage()==null?error.toString():error.getMessage());}
    boolean trial=state!=null&&state.optBoolean("trial");
    return new JSONObject(fast.toString()).put("backend","theme").put("corner_adjustment",false).put("profile",profile)
        .put("capable",failures.length()==0).put("can_apply",failures.length()==0&&approved&&!trial)
        .put("pending_trial",trial).put("managed_count",state==null?0:state.getJSONObject("choices").length())
        .put("tested_profile",false).put("theme_path",path).put("failures",failures)
        .put("scope","Experimental native icon-theme fallback. Original resources are retained until Restore.");
  }
  static JSONObject unavailable(JSONObject fast,Exception error) throws Exception {
    JSONObject report=new JSONObject(fast.toString()).put("backend","theme").put("corner_adjustment",false)
        .put("capable",false).put("can_apply",false).put("tested_profile",false).put("managed_count",0).put("pending_trial",false)
        .put("failures",new JSONArray().put(error.getMessage()==null?error.toString():error.getMessage()));
    try{JSONObject state=open(null).state();if(state!=null)report.put("managed_count",state.getJSONObject("choices").length())
        .put("pending_trial",state.optBoolean("trial")||state.optBoolean("next_trial")).put("profile",state.optString("profile"));}catch(Exception ignored){}
    return report;
  }
  private static ComponentName component(JSONObject app) throws Exception {
    String pkg=app.getString("package"),cls=app.getString("class");
    if(!IconSystemTask.name(pkg)||!IconSystemTask.name(cls))throw new IOException("Invalid app component");return new ComponentName(pkg,cls);
  }
  private static String key(PackageManager pm,ComponentName component) throws Exception {
    List<ResolveInfo> entries=pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(component.getPackageName()),0);
    boolean found=false;for(ResolveInfo entry:entries)if(entry.activityInfo.name.equals(component.getClassName()))found=true;
    if(!found)throw new IOException("App no longer has this launcher icon");
    return entries.size()==1?component.getPackageName():component.getClassName();
  }
  static JSONObject run(Context context,JSONObject job,File folder,JSONObject fast,JSONObject fastState) throws Exception {
    IconThemeStore.Step step=null;
    if(BuildConfig.DEBUG&&job.has("interrupt_after")){
      int cut=job.getInt("interrupt_after");if(cut<0||cut>20000)throw new IOException("Invalid test checkpoint");int[] count={0};
      step=name->{if(count[0]++==cut)throw new IOException("Verification interruption: "+name);};
    }
    if(BuildConfig.DEBUG&&job.has("interrupt_at")){
      String cut=job.getString("interrupt_at");
      if(!List.of("move:theme.xml:theme.xml","move:1:1","move:1:previous-style").contains(cut))throw new IOException("Invalid test checkpoint");
      step=name->{if(name.equals(cut))throw new IOException("Verification interruption: "+name);};
    }
    IconThemeStore store=open(step);String operation=job.getString("operation");
    if(operation.startsWith("theme-")){if(!BuildConfig.DEBUG)throw new IOException("Verification request unavailable");operation=operation.substring(6);}
    if(operation.startsWith("calendar-"))throw new IOException("Restore the theme icon choices before using the separate Calendar tool");
    if(!List.of("check","trial","confirm","apply","reset").contains(operation))throw new IOException("Invalid theme icon request");
    if(operation.equals("check")&&!store.present())return new JSONObject().put("ok",true).put("compatibility",report(context,fast,store))
        .put("message","Theme icon checks complete. A VFont-icon trial and restore are required.");
    File lock=store.lock();if(Files.isSymbolicLink(lock.toPath()))throw new IOException("Unexpected theme transaction lock");
    try(FileChannel channel=FileChannel.open(lock.toPath(),StandardOpenOption.CREATE,StandardOpenOption.WRITE);FileLock held=channel.tryLock()) {
      if(held==null)throw new IOException("Another theme icon transaction is still running");
      boolean recovered=store.recover();refresh(context,store);
      JSONObject current=store.state(),report=report(context,fast,store);JSONArray apps=job.getJSONArray("apps");
      if(operation.equals("check"))return new JSONObject().put("ok",true).put("compatibility",report)
          .put("message",recovered?"Interrupted theme operation recovered. Saved originals are available.":"Theme icon checks complete.");
      File receipt=new File(lock.getParentFile(),"tx-"+job.getString("tx"));
      if(!receipt.createNewFile())throw new IOException("Theme transaction already handled; check its recovery state");
      if(operation.equals("confirm")) {
        if(current==null||!current.optBoolean("trial"))throw new IOException("No pending theme icon trial");
        String tested=current.getString("profile");boolean approve=job.optBoolean("visible")&&report.optBoolean("capable")&&tested.equals(report.optString("profile"));
        store.restore();refresh(context,store);if(approve)store.approve(tested);
        return new JSONObject().put("ok",true).put("changed",1).put("compatibility",report(context,fast,store))
            .put("message",approve?"VFont icon restored. Experimental theme icons enabled for this software and style.":"Original theme restored. Theme icons remain unverified.");
      }
      if(operation.equals("apply")&&!report.optBoolean("can_apply"))throw new IOException("Complete the VFont theme-icon trial and restore first");
      if(operation.equals("trial")&&(!report.optBoolean("capable")||current!=null||apps.length()!=1
          ||!component(apps.getJSONObject(0)).equals(new ComponentName("dev.fontlab","dev.fontlab.MainActivity"))))
        throw new IOException("Theme trial requires supported features and VFont's original icon");
      Map<String,File> choices=store.choices();Set<String> selected=new HashSet<>();int changed=0;
      for(int i=0;i<apps.length();i++) {
        JSONObject app=apps.getJSONObject(i);ComponentName component=component(app);
        String key;
        if(operation.equals("reset")){
          String pkg=component.getPackageName(),cls=component.getClassName();key=choices.containsKey(cls)?cls:pkg;
        }else{
          key=key(context.getPackageManager(),component);
          if(IconSystemTask.savedKey(fastState,component)!=null)throw new IOException("Restore this app's fast icon override before using theme icons");
          for(String suffix:new String[]{"_origin.png","_origin.jpg","_1x1_0.png","_1x1_1.png","_1x2_0.png","_1x2_1.png","_2x1_0.png","_2x1_1.png","_2x2_0.png","_2x2_1.png"})
            if(Files.exists(new File(IconCompatibility.ICONS,key+suffix).toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("Restore this app's existing native custom icon before using theme icons");
          if(new File(store.base(),"icons/dynamic_icon/"+component.getPackageName()).exists())throw new IOException("Live icons are preserved by the theme fallback; choose a different app");
        }
        if(!selected.add(key))throw new IOException("Duplicate app icon");
        if(operation.equals("reset")){if(choices.remove(key)!=null)changed++;continue;}
        File image=new File(folder,i+".png");if(!IconSystemTask.hash(image).equals(app.getString("sha256")))throw new IOException("Prepared icon changed");
        BitmapFactory.Options size=new BitmapFactory.Options();size.inJustDecodeBounds=true;BitmapFactory.decodeFile(image.getPath(),size);
        if(!"image/png".equals(size.outMimeType)||size.outWidth<1||size.outWidth>1024||size.outHeight<1||size.outHeight>1024)throw new IOException("Unsupported icon image");
        choices.put(key,image);changed++;
      }
      if(operation.equals("reset")&&job.optBoolean("all")){changed=choices.size()+changed;choices.clear();}
      if(choices.isEmpty())store.restore();
      else if(changed>0){
        if(!report.optBoolean("capable"))throw new IOException("Theme changed outside VFont; keep the saved originals and request review");
        if(root().getUsableSpace()<1200L*1024*1024)throw new IOException("Free at least 1.2 GB before applying theme icons");
        store.apply(choices,report.getString("profile"),operation.equals("trial"),current!=null?current.getBoolean("base_data"):active().equals(new File(root(),"style/1").getPath()),job.getString("tx"));
      }
      refresh(context,store);
      return new JSONObject().put("ok",true).put("changed",changed).put("compatibility",report(context,fast,store))
          .put("message",operation.equals("trial")?"Theme trial applied. Check VFont's blue V and an existing folder, then return to restore it.":operation.equals("reset")?"Restored "+changed+(changed==1?" theme icon.":" theme icons."):"Applied "+changed+(changed==1?" theme icon.":" theme icons.")+" Previous choices were kept.");
    }
  }
}
