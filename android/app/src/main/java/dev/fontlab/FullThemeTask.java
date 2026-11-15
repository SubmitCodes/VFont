package dev.fontlab;

import android.content.Context;
import android.content.ComponentName;
import java.util.*;
import android.os.SystemClock;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import org.json.*;

/** The reviewed main-user, native full-theme transaction. No arbitrary paths or commands. */
final class FullThemeTask {
  private static final File ROOT=new File("/data/bbkcore/theme"),CUSTOM=new File("/data/bbkcore/icon_custom/0"),UNLOCK=new File("/data/bbkcore/unlock");
  private static void progress(File folder,String message){try{IconSystemTask.writeJson(new File(folder,"progress.json"),new JSONObject().put("message",message));}catch(Exception ignored){}}
  static boolean pending(){return Files.exists(new File(ROOT,FullThemeStore.NAME+"/state.json").toPath(),LinkOption.NOFOLLOW_LINKS);}
  static void configurationApi(Class<?> type) throws Exception {
    var method=type.getMethod("updateThemeConfiguration",int.class);
    if(method.getReturnType()!=boolean.class||!java.lang.reflect.Modifier.isStatic(method.getModifiers()))throw new NoSuchMethodException("Native theme configuration API is incompatible");
  }
  private static void platform(Context context) throws Exception {
    Object manager=Class.forName("android.app.ActivityManagerNative").getMethod("getDefault").invoke(null);
    Object user=manager.getClass().getMethod("getCurrentUser").invoke(manager);
    if(user.getClass().getField("id").getInt(user)!=0)throw new IOException("Switch to the main Android user before changing themes");
    manager.getClass().getMethod("forceStopPackage",String.class,int.class);
    Object configuration=manager.getClass().getMethod("getConfiguration").invoke(manager);
    if(configuration.getClass().getMethod("getThemeId").getReturnType()!=int.class)throw new IOException("Native theme resource refresh is unavailable");
    if(configuration.getClass().getField("assetsSeq").getType()!=int.class)throw new IOException("Native drawable refresh is unavailable");
    configuration.getClass().getMethod("setThemeId",int.class);manager.getClass().getMethod("updateConfiguration",configuration.getClass());
    try{configurationApi(Class.forName("vivo.content.res.VivoGlobalThemeManager"));}
    catch(ReflectiveOperationException|LinkageError error){throw new IOException("This phone does not provide the required Vivo theme configuration API",error);}
    for(File directory:new File[]{ROOT,CUSTOM.getParentFile(),UNLOCK})
      if(Files.isSymbolicLink(directory.toPath())||!directory.isDirectory()||!directory.getCanonicalFile().equals(directory)||!directory.canRead()||!directory.canWrite())throw new IOException("Native theme storage is unavailable: "+directory.getName());
    Object pm=Class.forName("android.app.AppGlobals").getMethod("getPackageManager").invoke(null);
    pm.getClass().getMethod("getComponentEnabledSetting",ComponentName.class,int.class);
    pm.getClass().getMethod("setComponentEnabledSetting",ComponentName.class,int.class,int.class,int.class,String.class);
    pm.getClass().getMethod("getApplicationEnabledSetting",String.class,int.class);
    pm.getClass().getMethod("setApplicationEnabledSetting",String.class,int.class,int.class,int.class,String.class);
    context.getPackageManager().getPackageInfo("com.vivo.permissionmanager",0);
  }
  private static String restore(Context context,FullThemeStore store) throws Exception {
    store.recoverIconEdit();JSONObject state=store.state();if(state==null)return null;
    if(state.getString("phase").equals("active")){
      FullThemeState.verifyManaged(context,state.getJSONObject("config"),state.getJSONObject("managed_config"));
      store.nativeMetadata(FullThemeState.themeStoreUids(context));
      state=store.state();
      state.put("restore_config",FullThemeState.recoveryConfig(context,state.getJSONObject("config"),state.getJSONObject("managed_config")));store.save(state);
    }
    else if(state.getString("phase").equals("activating"))FullThemeState.verifyInterrupted(context,state.getJSONObject("config"),state.getJSONObject("metadata"));
    store.restoreFiles();state=store.state();
    if(state!=null&&state.getString("phase").equals("active")){store.restoreFiles();state=store.state();}
    if(state==null)return null;
    if(!state.getString("phase").equals("restoring-config"))throw new IOException("Theme recovery needs review; saved originals were kept");
    String warning=FullThemeState.restore(context,state.has("restore_config")?state.getJSONObject("restore_config"):state.getJSONObject("config"));store.restored();
    return warning;
  }
  private static JSONObject restoreParts(Context context,FullThemeStore store,Set<String> parts)throws Exception {
    store.recoverIconEdit();JSONObject state=store.state();if(state==null)throw new IOException("There is no saved setup to restore");
    if(List.of("staging","staging-ready").contains(state.getString("phase")))store.restoreFiles();
    store.recoverFiles();state=store.state();
    if(state==null)throw new IOException("There is no saved setup to restore");
    Set<Integer> themeUids=FullThemeState.themeStoreUids(context);
    if(state.getString("phase").equals("active")){
      FullThemeState.verifyManaged(context,state.getJSONObject("config"),state.getJSONObject("managed_config"));store.nativeMetadata(themeUids);
      String paperError=FullThemeState.staticWallpaperError(state.getJSONObject("config"),parts);
      if(paperError!=null)throw new IOException(paperError);
      JSONObject before=FullThemeState.capture(context,false);File work=store.begin(before);
      ThemeRestore selected=new ThemeRestore(store,work,ROOT,parts);selected.metadata.put("restore_before",before);
      for(String side:List.of("home","lock")){
        JSONObject paper=(parts.contains(side.equals("home")?"home":"lockpaper")?state.getJSONObject("config"):before).getJSONObject("papers").getJSONObject(side);
        if(paper.optBoolean("static",true)&&paper.has("sha256"))selected.metadata.put(side+"_sha256",paper.getString("sha256"));
      }
      store.install(selected.resources,selected.metadata);state=store.state();
    }
    JSONObject metadata=state.getJSONObject("metadata");
    if(!state.getString("phase").equals("activating")||!parts.equals(ThemeParts.selection(metadata.optJSONArray("restore_components"))))throw new IOException("Finish the pending theme recovery before restoring a different selection");
    String warning=FullThemeState.restoreSelected(context,state.getJSONObject("config"),metadata.getJSONObject("restore_before"),parts);
    store.nativeMetadata(themeUids);store.activated(FullThemeState.capture(context,false));
    String message="Selected components were restored. Unselected parts and the remaining backup were kept."+(warning!=null?" "+warning:"");
    return new JSONObject().put("ok",true).put("active",true).put("theme",metadata).put("message",message);
  }
  private static File iconLock() throws Exception {
    File saved=new File(ROOT,FullThemeStore.NAME+"/base/custom/.vfont-v1/lock");
    File lock=pending()&&saved.isFile()?saved:new File(CUSTOM,".vfont-v1/lock");
    if(!pending())return IconSystemTask.sharedLock();
    if(Files.isSymbolicLink(lock.toPath())||!lock.isFile())throw new IOException("The saved native operation lock is unavailable; recovery data was kept");
    return lock;
  }
  private static void finishIconRefresh(Context context,FullThemeStore store) throws Exception {
    JSONObject value=store.state();JSONArray targets=value.optJSONArray("icon_refresh");if(targets==null)return;
    var pm=context.getPackageManager();
    for(int i=0;i<targets.length();i++){
      ComponentName component=ComponentName.unflattenFromString(targets.getString(i));
      if(component==null||!IconSystemTask.name(component.getPackageName())||!IconSystemTask.name(component.getClassName()))throw new IOException("Invalid saved icon refresh");
      if(IconRefresh.supported(pm,component))IconRefresh.refresh(pm,component);
    }
    IconRefresh.refreshSidebar(context);value.remove("icon_refresh");store.save(value);
  }
  static void icons(Context context,JSONObject job,File folder,JSONObject result) throws Exception {
    platform(context);
    String operation=job.getString("operation");
    if(!List.of("check","apply","reset","trial","confirm").contains(operation))throw new IOException("Restore the full theme before using the separate Calendar or icon-theme tool");
    try(FileChannel channel=FileChannel.open(iconLock().toPath(),StandardOpenOption.WRITE);FileLock held=channel.tryLock();
        FullThemeStore store=new FullThemeStore(ROOT,CUSTOM,UNLOCK,null)){
      if(held==null)throw new IOException("An appearance operation is running; wait for it to finish");
      store.recoverIconEdit();JSONObject value=store.state();
      if(value==null||!value.getString("phase").equals("active"))throw new IOException("Finish restoring the interrupted full theme before editing individual icons");
      FullThemeState.verifyManaged(context,value.getJSONObject("config"),value.getJSONObject("managed_config"));
      store.nativeMetadata(FullThemeState.themeStoreUids(context));store.verifyActive();finishIconRefresh(context,store);
      if(operation.equals("check")){IconSystemTask.runIcons(context,job,folder,result,true,null);result.getJSONObject("compatibility").put("full_theme_icons",value.optBoolean("theme_icons",true));return;}
      store.claim(job.getString("tx"));List<ComponentName> refreshes=new ArrayList<>();
      try{
        IconSystemTask.roots(store.beginIconEdit());
        try{IconSystemTask.runIcons(context,job,folder,result,true,refreshes);}finally{IconSystemTask.roots(CUSTOM);}
        FullThemeState.verifyManaged(context,value.getJSONObject("config"),value.getJSONObject("managed_config"));
        JSONArray targets=new JSONArray();for(ComponentName component:refreshes)targets.put(component.flattenToString());
        store.commitIconEdit(targets);finishIconRefresh(context,store);
        result.getJSONObject("compatibility").put("full_theme_icons",value.optBoolean("theme_icons",true));
        if(value.optBoolean("theme_icons",true)&&operation.equals("reset")&&result.optBoolean("ok"))result.put("message",result.optInt("changed")==0?"No icon overrides to restore for this theme.":"Restored "+result.getInt("changed")+(result.getInt("changed")==1?" app icon":" app icons")+" to the current theme.");
      }catch(Exception failure){
        IconSystemTask.roots(CUSTOM);try{store.abortIconEdit();}catch(Exception recovery){failure.addSuppressed(recovery);}throw failure;
      }
    }
  }
  static JSONObject run(Context context,JSONObject job,File folder) throws Exception {
    platform(context);String operation=job.getString("operation");
    if(!operation.matches("full-theme-(check|apply|restore)")&&!(BuildConfig.DEBUG&&operation.matches("full-theme-(recovery-check|metadata-restore)")))throw new IllegalArgumentException("Unknown full-theme operation");
    if(BuildConfig.DEBUG&&operation.equals("full-theme-metadata-restore")){
      if(pending())throw new IOException("Theme recovery is still active");FullThemeState.repairMetadata(context,job.getJSONObject("before"),job.getJSONObject("expected"));
      return new JSONObject().put("ok",true).put("message","Original wallpaper metadata restored after expanding the recovery footprint");
    }
    if(operation.equals("full-theme-check")){
      String adjustableReason=ThemeIconControls.supportReason(ThemeIconControls.STOCK);
      JSONObject result=new JSONObject().put("ok",true).put("active",pending()).put("can_apply",true)
          .put("adjustable_icons_supported",adjustableReason==null)
          .put("message","Full-theme recovery is available.");
      if(adjustableReason!=null)result.put("adjustable_icons_reason",adjustableReason);
      JSONObject captured=null;
      if(pending())try(FullThemeStore store=new FullThemeStore(ROOT,CUSTOM,UNLOCK,null)){
        store.recoverIconEdit();JSONObject state=store.state();
        result.put("phase",state.getString("phase")).put("can_apply",state.getString("phase").equals("active")).put("theme",state.optJSONObject("metadata"));
        JSONObject metadata=state.optJSONObject("metadata");
        if(metadata!=null&&metadata.has("restore_components")&&!state.getString("phase").equals("active"))result.put("pending_restore",metadata.getJSONArray("restore_components"));
        try{captured=FullThemeState.capture(context,false);if(BuildConfig.DEBUG)result.put("observed",captured);}catch(Exception unavailable){result.put("observation_error",unavailable.getClass().getSimpleName());}
      }else{
        try{captured=FullThemeState.capture(context,false);if(BuildConfig.DEBUG)result.put("observed",captured);}catch(Exception unavailable){result.put("observation_error",unavailable.getClass().getSimpleName());}
      }
      if(captured!=null){
        String paperReason=FullThemeState.staticWallpaperError(captured,null);
        result.put("wallpaper_can_backup",paperReason==null);
        if(paperReason!=null)result.put("wallpaper_reason",paperReason);
        JSONObject papers=captured.optJSONObject("papers");
        if(papers!=null){
          result.put("home_wallpaper_static",papers.optJSONObject("home")!=null&&papers.getJSONObject("home").optBoolean("static",false));
          result.put("lock_wallpaper_static",papers.optJSONObject("lock")!=null&&papers.getJSONObject("lock").optBoolean("static",false));
        }
      }
      return result;
    }
    File iconLock=iconLock();
    try(FileChannel channel=FileChannel.open(iconLock.toPath(),StandardOpenOption.WRITE);FileLock held=channel.tryLock();
        FullThemeStore store=new FullThemeStore(ROOT,CUSTOM,UNLOCK,null)){
      if(held==null)throw new IOException("An icon operation is running; wait for it to finish");
      store.claim(job.getString("tx"));
      if(operation.equals("full-theme-restore")){progress(folder,"Restoring your saved theme and appearance…");Set<String> parts=ThemeParts.selection(job.optJSONArray("components"));if(parts!=null)return restoreParts(context,store,parts);long started=SystemClock.uptimeMillis();String warning=restore(context,store);String message="Your previous theme, wallpapers, lock screen and app icons were restored."+(warning!=null?" "+warning:"");return new JSONObject().put("ok",true).put("active",false).put("duration_ms",SystemClock.uptimeMillis()-started).put("message",message);}
      if(operation.equals("full-theme-recovery-check")){
        if(store.state()!=null)throw new IOException("Restore the pending full theme before its recovery check");
        store.begin(FullThemeState.capture(context,true));JSONObject value=store.state();value.put("phase","restoring-config");store.save(value);
        FullThemeState.restore(context,value.getJSONObject("config"));store.restored();
        return new JSONObject().put("ok",true).put("active",false).put("message","PASS: original wallpaper bytes, crop, lock-screen layout and theme settings survived native recovery");
      }
      if(IconThemeTask.pending()||CalendarIcons.managed())throw new IOException("Restore the separate native icon or Calendar skin before applying a full theme");
      store.recoverIconEdit();JSONObject current=store.state();
      Set<Integer> themeUids=FullThemeState.themeStoreUids(context);
      if(themeUids.isEmpty())throw new IOException("No compatible Vivo Theme Store package was found on this phone");
      if(current!=null){if(!current.getString("phase").equals("active"))throw new IOException("Restore the interrupted full theme before applying another");FullThemeState.verifyManaged(context,current.getJSONObject("config"),current.getJSONObject("managed_config"));store.nativeMetadata(themeUids);}
      if(!ThemeImports.id(job.optString("import_id")))throw new IOException("Invalid selected theme identity");
      File archive=new File(folder,"theme.itz");String hash=job.getString("archive_sha256");
      if(Files.isSymbolicLink(archive.toPath())||archive.length()>ThemeArchive.MAX_ARCHIVE||!hash.equals(IconThemeStore.hash(archive)))throw new IOException("The prepared theme changed");
      if(ROOT.getUsableSpace()<2L*1024*1024*1024)throw new IOException("Full-theme recovery needs 2 GiB of free storage");
      Set<String> parts=ThemeParts.selection(job.optJSONArray("components"));
      boolean adjustable=job.optBoolean("adjustable_icons",false)&&ThemeIconControls.supportReason(ThemeIconControls.STOCK)==null;
      long started=SystemClock.uptimeMillis();progress(folder,"Saving your current setup…");
      JSONObject baselineCapture=FullThemeState.capture(context,current==null);
      String paperError=FullThemeState.staticWallpaperError(baselineCapture,parts);
      if(paperError!=null)throw new IOException(paperError);
      File work=store.begin(baselineCapture);long backedUp=SystemClock.uptimeMillis();
      try{
        progress(folder,"Checking and preparing theme components…");
        FullThemeBundle bundle=new FullThemeBundle(archive,work,hash,parts,ROOT,adjustable,ThemeIconControls.STOCK);bundle.metadata.put("import_id",job.getString("import_id"));
        if(bundle.metadata.optBoolean("partial")){JSONObject papers=FullThemeState.capture(context,false).getJSONObject("papers");for(String side:List.of("home","lock"))if(!bundle.metadata.has(side+"_sha256")&&papers.getJSONObject(side).optBoolean("static",true)&&papers.getJSONObject(side).has("sha256"))bundle.metadata.put(side+"_sha256",papers.getJSONObject(side).getString("sha256"));}
        if(bundle.metadata.optBoolean("partial")&&new File(ROOT,"disassembleapply").isDirectory()){
          // Stock Themes rewrites this history even for a partial Apply; move its first original into recovery.
          File history=new File(work,"native-history");FullThemeBundle.copy(new File(ROOT,"disassembleapply"),history);bundle.resources.put("theme/disassembleapply",history);
        }
        long prepared=SystemClock.uptimeMillis();progress(folder,"Installing theme resources…");store.install(bundle.resources,bundle.metadata);
        long installed=SystemClock.uptimeMillis();progress(folder,"Refreshing your home screen and system styling…");FullThemeState.activate(context,bundle.metadata);
        long activated=SystemClock.uptimeMillis();progress(folder,"Verifying the theme and recovery backup…");store.nativeMetadata(themeUids);store.activated(FullThemeState.capture(context,false));
        JSONObject timing=new JSONObject().put("backup",backedUp-started).put("prepare",prepared-backedUp).put("install",installed-prepared).put("activate",activated-installed).put("verify",SystemClock.uptimeMillis()-activated);
        return new JSONObject().put("ok",true).put("active",true).put("theme",bundle.metadata).put("timings_ms",timing).put("message",bundle.metadata.optBoolean("partial")?"Selected components applied. Everything else was kept. Restore will bring back your previous setup.":"Full theme applied. Restore will bring back your previous setup.");
      }catch(Exception failure){
        if(store.cancelPreparation())throw new IOException("The theme was not applied. Your active setup and recovery backup were kept. "+failure.getMessage(),failure);
        try{restore(context,store);}catch(Exception recovery){throw new IOException("Full-theme Apply stopped. Saved originals were kept; use Restore to finish recovery. "+failure.getMessage()+"; recovery: "+recovery.getMessage(),failure);}
        throw new IOException("Full-theme Apply stopped and your previous setup was restored: "+failure.getMessage(),failure);
      }
    }
  }
}
