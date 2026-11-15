package dev.fontlab;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Looper;
import android.os.Process;
import android.system.Os;
import java.io.File;
import java.io.IOException;
import java.io.FileOutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** One bounded icon transaction. Invoked from this installed APK by the system-UID bridge. */
public final class IconSystemTask {
  static final String JOBS = "/storage/emulated/0/Android/data/dev.fontlab/files/icons";
  private static File ICONS = IconCompatibility.ICONS;
  private static File STORE = new File(ICONS, ".vfont-v1");
  private static final String[] SUFFIXES = {"_origin.png", "_origin.jpg", "_1x1_0.png", "_1x1_1.png",
      "_1x2_0.png", "_1x2_1.png", "_2x1_0.png", "_2x1_1.png", "_2x2_0.png", "_2x2_1.png"};
  private static final String[] OUTPUTS = {"_origin.png", "_1x1_1.png", "_1x2_1.png", "_2x1_1.png", "_2x2_1.png"};

  static File sharedLock() throws Exception {
    if(!ICONS.exists()&&!ICONS.mkdir())throw new IOException("Could not prepare native icon storage");directory(ICONS);
    if(!STORE.exists()&&!STORE.mkdir())throw new IOException("Could not prepare icon backups");directory(STORE);Os.chmod(STORE.getPath(),0700);
    File lock=new File(STORE,"lock");
    if(Files.isSymbolicLink(lock.toPath())||(lock.exists()&&!lock.isFile()))throw new IOException("Unexpected icon lock");
    if(lock.createNewFile())Os.chmod(lock.getPath(),0600);return lock;
  }

  static boolean token(String value, int length) {
    return value != null && value.length() == length && value.matches("[a-f0-9]+");
  }
  static boolean name(String value) {
    return value != null && value.length() <= 200 && value.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+");
  }
  static File job(String tx) {
    if (!token(tx, 32)) throw new IllegalArgumentException("Invalid icon transaction");
    return new File(JOBS, "job-" + tx);
  }
  static String hash(byte[] bytes) throws Exception {
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
    StringBuilder result = new StringBuilder(64);
    for (byte b : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
    return result.toString();
  }
  static String hash(File file) throws Exception {
    regular(file, 4 * 1024 * 1024);
    return hash(Files.readAllBytes(file.toPath()));
  }
  private static void regular(File file, long maximum) throws Exception {
    if (Files.isSymbolicLink(file.toPath()) || !file.isFile() || file.length() > maximum)
      throw new IllegalStateException("Unexpected icon file: " + file.getName());
  }
  private static void directory(File file) throws Exception {
    if (Files.isSymbolicLink(file.toPath()) || !file.isDirectory()
        || !file.getAbsolutePath().equals(file.getCanonicalPath()))
      throw new IllegalStateException("Unexpected icon directory");
  }
  static void writeJson(File file, JSONObject value) throws Exception {
    File temp = new File(file.getParentFile(), file.getName() + ".tmp");
    if (Files.isSymbolicLink(temp.toPath()) || Files.isSymbolicLink(file.toPath()))
      throw new IllegalStateException("Unexpected transaction file");
    try (FileOutputStream out = new FileOutputStream(temp)) {
      out.write(value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
      out.getFD().sync();
    }
    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
  }
  private static JSONObject state() throws Exception {
    if(ICONS.exists()) directory(ICONS);
    if (Files.isSymbolicLink(STORE.toPath())) throw new IllegalStateException("Unexpected icon store link");
    if (STORE.exists()) directory(STORE);
    File file = new File(STORE, "state.json");
    if (!file.exists()) return new JSONObject();
    regular(file, 2 * 1024 * 1024);
    return new JSONObject(new String(Files.readAllBytes(file.toPath()),java.nio.charset.StandardCharsets.UTF_8));
  }
  private static List<String> keys(JSONObject object) {
    List<String> keys=new ArrayList<>();java.util.Iterator<String> iterator=object.keys();
    while(iterator.hasNext())keys.add(iterator.next());return keys;
  }
  private static void save(JSONObject state) throws Exception {
    writeJson(new File(STORE, "state.json"), state);
  }
  private static File image(String key, String suffix) {
    if (!name(key) || !List.of(SUFFIXES).contains(suffix)) throw new IllegalArgumentException("Invalid icon name");
    return new File(ICONS, key + suffix);
  }
  private static String optionalHash(JSONObject values, String key) {
    String value = values.optString(key, "");
    if (value.equals("null")) value = "";
    if (!value.isEmpty() && !token(value, 64)) throw new IllegalArgumentException("Invalid saved icon checksum");
    return value;
  }
  private static void verify(File file, String expected) throws Exception {
    if (expected.isEmpty()) {
      if (file.exists() || Files.isSymbolicLink(file.toPath())) throw new IllegalStateException("Icon changed outside VFont");
    } else if (!expected.equals(hash(file))) throw new IllegalStateException("Icon changed outside VFont");
  }
  private static void reset(JSONObject state, String key) throws Exception {
    JSONObject record = state.getJSONObject(key);
    String backupId = record.getString("backup");
    if (!backupId.matches("[a-f0-9]{32}-[0-9]{1,3}")) throw new IllegalArgumentException("Invalid icon backup");
    File backup = new File(STORE, backupId);
    directory(backup);
    JSONObject old = record.getJSONObject("original"), applied = record.getJSONObject("applied");
    // Validate every slot before deleting or moving any file. A pending journal can be reset after interruption.
    for (String suffix : SUFFIXES) {
      File original = new File(backup, key + suffix), target = image(key, suffix);
      String oldHash = optionalHash(old, suffix), newHash = optionalHash(applied, suffix);
      if (original.exists()) {
        if (oldHash.isEmpty()) throw new IllegalStateException("Unexpected backup image");
        verify(original, oldHash);
        if (target.exists()) verify(target, newHash);
        else if (Files.isSymbolicLink(target.toPath())) throw new IllegalStateException("Unexpected icon link");
      } else if (!oldHash.isEmpty()) verify(target, oldHash);
      else if (target.exists()) verify(target, newHash);
      else if (Files.isSymbolicLink(target.toPath())) throw new IllegalStateException("Unexpected icon link");
    }
    for (String suffix : SUFFIXES) {
      File original = new File(backup, key + suffix), target = image(key, suffix);
      if (original.exists()) {
        if (target.exists()) Files.delete(target.toPath());
        Files.move(original.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
      } else if (optionalHash(old, suffix).isEmpty() && target.exists()) Files.delete(target.toPath());
    }
    state.remove(key);
    save(state);
    // Only the now-empty, exact backup directory is eligible for removal.
    backup.delete();
  }

  private static ComponentName component(JSONObject app) throws Exception {
    String pkg = app.getString("package"), cls = app.getString("class");
    if (!name(pkg) || !name(cls)) throw new IllegalArgumentException("Invalid app component");
    return new ComponentName(pkg, cls);
  }
  static String savedKey(JSONObject state, ComponentName component) throws Exception {
    for(String key:keys(state)) {
      if(!name(key))throw new IllegalStateException("Invalid saved icon key");
      if(component.equals(component(state.getJSONObject(key).getJSONObject("app"))))return key;
    }
    return null;
  }
  private static void checkRefresh(PackageManager pm, ComponentName component) throws Exception {
    IconRefresh.check(pm,component);
  }
  static String restoreMessage(int changed,int skipped,int failed) {
    String message=changed>0?"Restored "+changed+(changed==1?" icon.":" icons.")
        :failed>0?"Restored 0 icons.":skipped==0?"No saved icons to restore.":"";
    if(skipped>0)message+=(message.isEmpty()?"":" ")+"Skipped "+skipped
        +(skipped==1?" uninstalled app":" uninstalled apps")+"; saved originals were kept.";
    if(failed>0)message+=" Could not restore "+failed+"; saved originals were kept. Check the app's native icon editor before retrying.";
    return message;
  }
  private static String key(PackageManager pm, ComponentName component) throws Exception {
    List<ResolveInfo> matches = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER).setPackage(component.getPackageName()), 0);
    boolean found = false;
    for (ResolveInfo match : matches) if (match.activityInfo.name.equals(component.getClassName())) found = true;
    if (!found) throw new IllegalStateException("App no longer has this launcher icon");
    return matches.size() == 1 ? component.getPackageName() : component.getClassName();
  }
  private static byte[] png(Bitmap source, int width, int height, int corners,boolean backing) throws Exception {
    Bitmap image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    Bitmap filled=backing?IconRaster.filledTile(source):null,artwork=filled==null?source:filled;
    try {
      float scale = Math.min((float) width / artwork.getWidth(), (float) height / artwork.getHeight());
      float w = artwork.getWidth() * scale, h = artwork.getHeight() * scale;
      new Canvas(image).drawBitmap(artwork, null, new RectF((width-w)/2, (height-h)/2, (width+w)/2, (height+h)/2), new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
      if(corners>0) {
        // This ROM classifies rounded pack images as freeform and puts a white tile behind them.
        // Its shape classifier runs before its round mask. Register the raster corners outside
        // that mask so the artwork follows the normal icon path without a second background.
        IconRaster.registerCorners(image,corners);
      }
      java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
      if (!image.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IllegalStateException("Could not prepare icon");
      return out.toByteArray();
    } finally { image.recycle();if(filled!=null)filled.recycle(); }
  }
  private static void apply(JSONObject state, String key, JSONObject app, Bitmap source, String tx, int index,
      int corners,boolean backing, String trialProfile) throws Exception {
    if (state.has(key)) reset(state, key);
    String backupId = tx + "-" + index;
    File backup = new File(STORE, backupId);
    if (!backup.mkdir()) throw new IllegalStateException("Icon backup already exists");
    Os.chmod(backup.getPath(), 0700);
    JSONObject old = new JSONObject(), applied = new JSONObject();
    for (String suffix : SUFFIXES) {
      File target = image(key, suffix);
      if (Files.isSymbolicLink(target.toPath())) throw new IllegalStateException("Unexpected icon link");
      old.put(suffix, target.exists() ? hash(target) : JSONObject.NULL);
      applied.put(suffix, JSONObject.NULL);
    }
    int[][] sizes = {{source.getWidth(), source.getHeight()}, {256,256}, {256,512}, {512,256}, {512,512}};
    List<File> candidates = new ArrayList<>();
    for (int i=0;i<OUTPUTS.length;i++) {
      byte[] bytes = png(source, sizes[i][0], sizes[i][1], i==1?corners:0,i==1&&backing);
      File candidate = new File(backup, "new" + i + ".png");
      try (FileOutputStream out = new FileOutputStream(candidate)) {out.write(bytes);out.getFD().sync();}
      Os.chmod(candidate.getPath(), 0666); // OriginOS's own editor must be able to replace imported image bytes.
      applied.put(OUTPUTS[i], hash(bytes));
      candidates.add(candidate);
    }
    JSONObject record = new JSONObject().put("backup", backupId).put("app", app)
        .put("original", old).put("applied", applied);
    if (trialProfile!=null) record.put("trial",trialProfile);
    state.put(key, record);
    save(state); // Journal precedes every original-file move.
    try {
      for (String suffix : SUFFIXES) {
        File target = image(key, suffix);
        verify(target, optionalHash(old, suffix));
        if (target.exists()) Files.move(target.toPath(), new File(backup, key + suffix).toPath(), StandardCopyOption.ATOMIC_MOVE);
      }
      for (int i=0;i<OUTPUTS.length;i++) {
        File target = image(key, OUTPUTS[i]);
        if (target.exists() || Files.isSymbolicLink(target.toPath())) throw new IllegalStateException("Icon appeared during apply");
        Files.move(candidates.get(i).toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
        verify(target, applied.getString(OUTPUTS[i]));
      }
    } catch (Throwable error) {
      reset(state, key);
      throw error;
    }
  }
  private static Context context() throws Exception {
    Looper.prepareMainLooper();
    Class<?> type = Class.forName("android.app.ActivityThread");
    Object thread = type.getMethod("systemMain").invoke(null);
    Context context=(Context)type.getMethod("getSystemContext").invoke(thread);
    try {return context.createPackageContext("com.vivo.permissionmanager",0);}
    catch(PackageManager.NameNotFoundException missing) {return context;}
  }
  private static String approvedProfile() throws Exception {
    File approved=new File(STORE,"verified.json");
    if (!approved.exists()) return "";
    regular(approved,4096);
    return new JSONObject(new String(Files.readAllBytes(approved.toPath()),java.nio.charset.StandardCharsets.UTF_8)).optString("profile");
  }
  private static JSONObject report(Context context, JSONObject state) throws Exception {
    return withState(IconCompatibility.inspect(context),state);
  }
  private static JSONObject withState(JSONObject report, JSONObject state) throws Exception {
    JSONObject trial=state.optJSONObject("dev.fontlab");
    boolean pending=trial!=null && trial.has("trial");
    String profile=report.optString("profile");
    if(profile.equals(approvedProfile())&&!VerificationStore.recorded(VerificationStore.icons(),"fast",profile))VerificationStore.save(VerificationStore.icons(),"fast",profile,true);
    return report.put("pending_trial",pending).put("managed_count",state.length())
        .put("backend","fast").put("full_theme_active",FullThemeTask.pending())
        .put("can_apply",!pending && IconCompatibility.allowed(report,VerificationStore.has(VerificationStore.icons(),"fast",profile)?profile:""));
  }
  private static final class Replayed extends Exception {}
  static void roots(File directory){ICONS=directory;STORE=new File(directory,".vfont-v1");}
  private static void refresh(PackageManager pm,ComponentName component,List<ComponentName> deferred)throws Exception {
    if(deferred==null)IconRefresh.refresh(pm,component);else if(!deferred.contains(component))deferred.add(component);
  }
  static void runIcons(Context context,JSONObject job,File folder,JSONObject result,boolean layer,List<ComponentName> refreshes)throws Exception {
    PackageManager pm=context.getPackageManager();String operation=job.getString("operation");JSONArray apps=job.getJSONArray("apps");
    if(!layer&&FullThemeTask.pending()){FullThemeTask.icons(context,job,folder,result);return;}
    boolean themeTest=BuildConfig.DEBUG&&operation.matches("theme-(check|trial|confirm|apply|reset)");
    if(apps.length()>500 || (!themeTest&&!List.of("check","calendar-check","calendar-apply","calendar-reset","trial","confirm","apply","reset").contains(operation)))
      throw new IllegalArgumentException("Invalid icon request");
    JSONObject savedIcons=state(),initial=report(context,savedIcons);boolean themePending=!layer&&IconThemeTask.pending();
    if(!layer&&(themeTest||IconThemeTask.preferTheme(initial.optBoolean("capable"),themePending)&&!operation.startsWith("calendar-"))){
      JSONObject theme;
      try{theme=IconThemeTask.run(context,job,folder,initial,savedIcons);}
      catch(Exception error){result.put("compatibility",IconThemeTask.unavailable(initial,error));throw error;}
      for(String name:keys(theme))result.put(name,theme.get(name));return;
    }
    if(themePending&&operation.startsWith("calendar-"))throw new IllegalStateException("Restore your theme icon choices before using the separate Calendar tool");
    if(operation.equals("calendar-check")) {
      JSONObject saved=state(),report=report(context,saved);JSONArray managed=new JSONArray();for(String key:keys(saved))managed.put(saved.getJSONObject(key).getJSONObject("app"));
      result.put("compatibility",report).put("managed",managed).put("ok",true)
          .put("calendar",CalendarIcons.status(context,report)).put("message","Read-only calendar check.");return;
    }
    if(operation.equals("check")) {
      JSONObject report=report(context,state());result.put("compatibility",report).put("ok",true)
          .put("message",report.optBoolean("can_apply") ? "Native icon checks passed." :
              report.optBoolean("capable") ? "Native features found. A VFont-icon trial is required." : "This software needs further review.");
      return;
    }
    File lock=sharedLock();
    try(FileChannel channel=FileChannel.open(lock.toPath(),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        FileLock held=channel.tryLock()) {
      if(held==null) throw new IllegalStateException("Another icon transaction is running");
      // A repeated dispatch can mutate icons only once.
      if(!new File(STORE,"tx-"+job.getString("tx")).createNewFile()) throw new Replayed();
      JSONObject state=state(), report=report(context,state);
      result.put("compatibility",report);
      if(operation.equals("calendar-apply")||operation.equals("calendar-reset")) {
        JSONObject changed=CalendarIcons.change(context,operation,report);
        if(refreshes==null)IconRefresh.refreshSidebar(context);
        result.put("ok",true).put("calendar",CalendarIcons.status(context,report)).put("changed",changed.optBoolean("changed")?1:0)
            .put("message",operation.equals("calendar-apply")?"Live Calendar skin applied. Vivo keeps the date updated.":"Original Calendar skin restored.");return;
      }
      if(operation.equals("apply") && !report.optBoolean("can_apply"))
        throw new IllegalStateException("Run compatibility checks and complete the VFont-icon trial first");
      if(operation.equals("trial")) {
        if(!report.optBoolean("capable") || state.has("dev.fontlab") || apps.length()!=1
            || !component(apps.getJSONObject(0)).equals(new ComponentName("dev.fontlab","dev.fontlab.MainActivity")))
          throw new IllegalStateException("Trial requires supported features and VFont's original icon; restore its managed icon first");
      }
      if(operation.equals("confirm")) {
        JSONObject trial=state.optJSONObject("dev.fontlab");
        if(trial==null || !trial.has("trial")) throw new IllegalStateException("No pending icon trial");
        ComponentName self=new ComponentName("dev.fontlab","dev.fontlab.MainActivity");checkRefresh(pm,self);
        boolean approve=job.optBoolean("visible") && trial.optBoolean("trial_ready")
            && report.optBoolean("capable") && report.optString("profile").equals(trial.optString("trial"));
        reset(state,"dev.fontlab");refresh(pm,self,refreshes);
        if(refreshes==null)IconRefresh.refreshSidebar(context);
        VerificationStore.save(VerificationStore.icons(),"fast",report.getString("profile"),approve);
        if(approve) writeJson(new File(STORE,"verified.json"),new JSONObject().put("profile",report.getString("profile")));
        result.put("ok",true).put("changed",1).put("compatibility",withState(report,state))
            .put("message",approve ? "VFont icon restored. Experimental icon support enabled for this software and style." : "VFont icon restored. Experimental support remains unverified.");
        return;
      }
      List<ComponentName> components=new ArrayList<>();List<String> keys=new ArrayList<>();
      for(int i=0;i<apps.length();i++) {
        ComponentName c=component(apps.getJSONObject(i));
        if(operation.equals("apply")&&c.getPackageName().equals("com.bbk.calendar")&&CalendarIcons.managed())
          throw new IllegalStateException("Restore the live Calendar skin before applying a static Calendar icon");
        String key=operation.equals("reset") ? savedKey(state,c) : key(pm,c);
        if(key==null)continue;
        if(!operation.equals("reset"))checkRefresh(pm,c);
        if(keys.contains(key)) throw new IllegalArgumentException("Duplicate app icon");
        keys.add(key);components.add(c);
        if(operation.equals("apply") || operation.equals("trial")) {
          File source=new File(folder,i+".png");
          if(!hash(source).equals(apps.getJSONObject(i).getString("sha256"))) throw new IllegalStateException("Prepared icon changed");
          BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(source.getPath(),bounds);
          if(!"image/png".equals(bounds.outMimeType) || bounds.outWidth<1 || bounds.outHeight<1 || bounds.outWidth>1024 || bounds.outHeight>1024) throw new IllegalArgumentException("Unsupported icon image");
        }
      }
      if(operation.equals("reset") && job.optBoolean("all")) {
        keys.clear();components.clear();
        for(String key:keys(state)) {
          if(!name(key)) throw new IllegalStateException("Invalid saved icon key");
          ComponentName c=component(state.getJSONObject(key).getJSONObject("app"));
          keys.add(key);components.add(c);
        }
      }
      int changed=0;JSONArray failed=new JSONArray(),skipped=new JSONArray();
      if(operation.equals("reset")&&(job.optBoolean("all")||containsCalendar(apps))&&CalendarIcons.managed()) {
        try {CalendarIcons.change(context,"calendar-reset",report);changed++;}
        catch(Exception error){failed.put(new JSONObject().put("package","com.bbk.calendar").put("message",error.toString()));}
      }
      for(int i=0;i<keys.size();i++) {
        String key=keys.get(i);
        try {
        if(operation.equals("reset")&&IconRefresh.missingApp(pm,components.get(i))) {
          skipped.put(new JSONObject().put("package",components.get(i).getPackageName()).put("reason","not_installed"));
          continue;
        }
        if(operation.equals("reset"))checkRefresh(pm,components.get(i));
        if(operation.equals("reset")) {if(state.has(key)) {reset(state,key);changed++;}}
        else {
          Bitmap image=BitmapFactory.decodeFile(new File(folder,i+".png").getPath());
          if(image==null) throw new IllegalArgumentException("Could not decode icon");
          try {apply(state,key,apps.getJSONObject(i),image,job.getString("tx"),i,report.optBoolean("corner_adjustment")?report.optInt("corner_marker_size",4):0,report.optBoolean("backing_normalization"),
              operation.equals("trial") ? report.getString("profile") : null);changed++;} finally {image.recycle();}
        }
        refresh(pm,components.get(i),refreshes);
        if(operation.equals("trial")) {state.getJSONObject(key).put("trial_ready",true);save(state);}
        } catch(Exception error) {
          if(!operation.equals("reset"))throw error;
          failed.put(new JSONObject().put("package",components.get(i).getPackageName()).put("message",error.toString()));
        }
      }
      if(refreshes==null)IconRefresh.refreshSidebar(context);
      JSONArray managed=new JSONArray();for(String key:keys(state)) managed.put(state.getJSONObject(key).getJSONObject("app"));
      result.put("ok",failed.length()==0).put("changed",changed).put("managed",managed).put("failed",failed).put("skipped",skipped)
          .put("compatibility",withState(report,state))
          .put("message",operation.equals("trial") ? "Trial applied. Check VFont's blue V icon on the home screen, then return to restore it."
              : operation.equals("apply")?"Applied "+changed+" icons.":restoreMessage(changed,skipped.length(),failed.length()));
    }
  }
  public static void main(String[] args) {
    File folder = null;
    String operation="";
    JSONObject result = new JSONObject();
    if(BuildConfig.DEBUG)System.err.println("VFont native worker started: uid="+Process.myUid()+", arguments="+args.length);
    try {
      if (Process.myUid()!=1000 || args.length!=2 || !token(args[0],32) || !token(args[1],64)) throw new SecurityException("Invalid icon transaction caller");
      folder=job(args[0]);directory(folder);
      result.put("tx",args[0]);
      File request=new File(folder,"request.json");regular(request,256*1024);
      byte[] bytes=Files.readAllBytes(request.toPath());
      if (!hash(bytes).equals(args[1])) throw new IllegalStateException("Icon request changed");
      JSONObject job=new JSONObject(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
      if (!args[0].equals(job.getString("tx"))) throw new IllegalArgumentException("Transaction mismatch");
      Context context=context();PackageManager pm=context.getPackageManager();
      operation=job.getString("operation");JSONArray apps=job.getJSONArray("apps");
      if(operation.startsWith("full-theme-")){
        JSONObject full=FullThemeTask.run(context,job,folder);for(String key:FullThemeStore.keys(full))result.put(key,full.get(key));return;
      }
      runIcons(context,job,folder,result,false,null);
    } catch(Replayed ignored) {folder=null;
    } catch(Throwable e) {
      if(BuildConfig.DEBUG&&operation.startsWith("full-theme-"))e.printStackTrace(System.err);
      if(BuildConfig.DEBUG&&operation.startsWith("theme-"))android.util.Log.w("VFontTheme", "Theme worker failed",e);
      if(BuildConfig.DEBUG&&operation.startsWith("calendar-"))try{result.put("diagnostic",android.util.Log.getStackTraceString(e));}catch(Exception ignored){}
      try {if(result.has("compatibility")&&!result.getJSONObject("compatibility").optString("backend").equals("theme"))withState(result.getJSONObject("compatibility"),state());}catch(Exception ignored){}
      try {result.put("ok",false).put("message",(operation.startsWith("calendar-")?e.toString():(e.getMessage()==null?e.toString():e.getMessage()))
          + (operation.startsWith("full-theme-") ? "" : FullThemeTask.pending()?" Your full-theme backup was kept.":operation.equals("calendar-apply") ? " No icons were changed." : operation.equals("check")||operation.equals("calendar-check") ? ". Check did not change active icons." :
              ". Some icons may already have changed; Restore can recover saved originals."));} catch(Exception ignored) {}
    } finally {
      if(folder!=null) try {writeJson(new File(folder,"result.json"),result);} catch(Exception error) {if(BuildConfig.DEBUG)error.printStackTrace(System.err);}
      System.exit(0);
    }
  }
  private static boolean containsCalendar(JSONArray apps) throws Exception {
    for(int i=0;i<apps.length();i++)if(component(apps.getJSONObject(i)).getPackageName().equals("com.bbk.calendar"))return true;return false;
  }
}
