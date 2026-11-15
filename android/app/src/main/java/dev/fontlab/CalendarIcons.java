package dev.fontlab;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.*;
import android.system.Os;
import android.system.OsConstants;
import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** Recovery for the withdrawn live Calendar prototype. */
final class CalendarIcons {
  static final String APPLY_BLOCKED="Live Calendar is temporarily unavailable because this method breaks launcher folders and icon shapes. Restore the original Calendar skin if one is applied.";
  static final File SOURCE=new File("/system/etc/theme/style/1");
  static final String CALENDAR="icons/dynamic_icon/com.bbk.calendar";
  private static final File STYLE=new File("/data/bbkcore/theme/style"),ACTIVE=new File(STYLE,"1");
  private static final File STORE=new File("/data/bbkcore/theme/.vfont-calendar-v1"),STAGE=new File(STORE,"assets"),STATE=new File(STORE,"state.json");
  private static final ComponentName APP=new ComponentName("com.bbk.calendar","com.bbk.calendar.MainActivity");

  private static Object manager() throws Exception {
    Object factory=Class.forName("vivo.app.VivoFrameworkFactory").getMethod("getFrameworkFactoryImpl").invoke(null);
    return factory.getClass().getMethod("getThemeIconManager").invoke(factory);
  }
  private static String activePath() throws Exception {
    Object manager=manager();String path=(String)manager.getClass().getMethod("getThemePath",int.class,int.class).invoke(manager,0,0);
    if(path==null)throw new IllegalStateException("Cannot identify the active icon style");return new File(path).getAbsolutePath();
  }
  private static void reloadLauncher(boolean execute) throws Exception {
    Object manager=Class.forName("android.app.ActivityManagerNative").getMethod("getDefault").invoke(null);
    java.lang.reflect.Method restart=manager.getClass().getMethod("forceStopPackage",String.class,int.class);
    // Vivo caches the theme path in the launcher process. Its data and layout stay intact.
    if(execute)restart.invoke(manager,"com.bbk.launcher2",0);
  }
  private static void directory(File file) throws Exception {
    if(Files.isSymbolicLink(file.toPath())||!file.isDirectory()||!file.getAbsolutePath().equals(file.getCanonicalPath()))throw new IllegalStateException("Unexpected calendar directory");
  }
  private static String digest(File file) throws Exception {
    if(Files.isSymbolicLink(file.toPath())||!file.isFile()||file.length()>32*1024*1024)throw new IllegalStateException("Unexpected calendar asset");
    MessageDigest hash=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(file.toPath())){byte[] buffer=new byte[65536];int read;while((read=in.read(buffer))!=-1)hash.update(buffer,0,read);}
    StringBuilder out=new StringBuilder();for(byte value:hash.digest())out.append(String.format(Locale.ROOT,"%02x",value&255));return out.toString();
  }
  private static void sync(File directory) throws Exception {
    directory(directory);java.io.FileDescriptor fd=Os.open(directory.getPath(),OsConstants.O_RDONLY,0);try{Os.fsync(fd);}finally{Os.close(fd);}
  }
  private static boolean background(String path){return path.matches("icons/dynamic_icon/com\\.bbk\\.calendar/(dark/)?calendar_bg_1x1_[01]\\.png");}
  static byte[] artwork(File source,boolean dark) throws Exception {
    Bitmap input=BitmapFactory.decodeFile(source.getPath());if(input==null)throw new IllegalStateException("Native calendar background is unavailable");
    Bitmap image=input.copy(Bitmap.Config.ARGB_8888,true);input.recycle();
    try {
      Canvas canvas=new Canvas(image);canvas.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(Color.parseColor(dark?"#25415F":"#D5E9FF"));
      canvas.drawRoundRect(0,0,image.getWidth(),image.getHeight(),image.getWidth()*.20f,image.getHeight()*.20f,paint);paint.setColor(Color.parseColor(dark?"#80B9FF":"#71A7E8"));
      canvas.drawRoundRect(image.getWidth()*.30f,image.getHeight()*.83f,image.getWidth()*.70f,image.getHeight()*.86f,image.getHeight()*.015f,image.getHeight()*.015f,paint);
      ByteArrayOutputStream out=new ByteArrayOutputStream();if(!image.compress(Bitmap.CompressFormat.PNG,100,out))throw new IllegalStateException("Could not prepare calendar artwork");return out.toByteArray();
    }finally{image.recycle();}
  }
  private static File child(File root,String path) {
    if(path.startsWith("/")||path.contains("\\")||(!path.isEmpty()&&Arrays.stream(path.split("/",-1)).anyMatch(p->p.isEmpty()||p.equals(".")||p.equals(".."))))throw new IllegalArgumentException("Invalid calendar asset path");
    return path.isEmpty()?root:new File(root,path);
  }
  private static void walk(File root,File file,Map<String,JSONObject> expected,List<File> seen,int depth,boolean complete) throws Exception {
    if(depth>10||seen.size()>4096||Files.isSymbolicLink(file.toPath()))throw new IllegalStateException("Calendar assets changed outside VFont");
    String path=root.toPath().relativize(file.toPath()).toString();JSONObject item=expected.get(path);
    if(item==null&&!complete&&path.endsWith(".vfont-partial")) {
      JSONObject pending=expected.get(path.substring(0,path.length()-14));
      if(pending!=null&&!pending.optBoolean("directory")&&file.isFile()&&file.length()<=32*1024*1024){seen.add(file);return;}
    }
    if(item==null)throw new IllegalStateException("Calendar assets changed outside VFont");seen.add(file);
    if(item.optBoolean("directory")) {
      directory(file);File[] children=file.listFiles();if(children==null)throw new IllegalStateException("Cannot verify calendar assets");for(File child:children)walk(root,child,expected,seen,depth+1,complete);
    }else if(!item.getString("sha256").equals(digest(file)))throw new IllegalStateException("Calendar assets changed outside VFont; the changed files were kept");
  }
  private static List<File> verify(File root,JSONArray entries,boolean complete) throws Exception {
    if(entries.length()==0||entries.length()>4096)throw new IllegalStateException("Invalid calendar journal");Map<String,JSONObject> expected=new HashMap<>();
    for(int i=0;i<entries.length();i++){JSONObject entry=entries.getJSONObject(i);String path=entry.getString("path");child(root,path);if(expected.put(path,entry)!=null||(!entry.optBoolean("directory")&&!IconSystemTask.token(entry.optString("sha256"),64)))throw new IllegalStateException("Invalid calendar journal");}
    if(!expected.getOrDefault("",new JSONObject()).optBoolean("directory"))throw new IllegalStateException("Invalid calendar root");
    List<File> seen=new ArrayList<>();if(Files.isSymbolicLink(root.toPath()))throw new IllegalStateException("Unexpected calendar root link");if(root.exists())walk(root,root,expected,seen,0,complete);
    if(complete&&seen.size()!=entries.length())throw new IllegalStateException("Calendar assets are missing");return seen;
  }
  private static JSONObject state() throws Exception {
    if(!STATE.exists()){if(Files.isSymbolicLink(STATE.toPath()))throw new IllegalStateException("Unexpected calendar journal link");return null;}
    directory(STORE);if(Files.isSymbolicLink(STATE.toPath())||!STATE.isFile()||STATE.length()>2*1024*1024)throw new IllegalStateException("Invalid calendar journal");
    JSONObject state=new JSONObject(new String(Files.readAllBytes(STATE.toPath()),StandardCharsets.UTF_8));
    if((state.optInt("schema")!=1&&state.optInt("schema")!=2)||!SOURCE.getPath().equals(state.optString("source")))throw new IllegalStateException("Invalid calendar journal");return state;
  }
  static boolean managed() throws Exception {return state()!=null;}
  static JSONObject status(Context context,JSONObject report) throws Exception {
    JSONObject saved=state();String path="";try{path=activePath();}catch(Exception ignored){}
    long bytes=saved==null?0:saved.optLong("bytes",180L*1024*1024);
    return new JSONObject().put("available",false).put("managed",saved!=null).put("active",saved!=null&&ACTIVE.isDirectory()).put("theme_path",path)
        .put("reason",APPLY_BLOCKED).put("storage_mb",(bytes+1024*1024-1)/(1024*1024));
  }
  static JSONObject change(Context context,String operation,JSONObject report) throws Exception {
    if(operation.equals("calendar-apply"))throw new IllegalStateException(APPLY_BLOCKED);
    if(!operation.equals("calendar-reset"))throw new IllegalArgumentException("Invalid Calendar operation");
    directory(new File("/data/bbkcore/theme"));if(Files.isSymbolicLink(STORE.toPath()))throw new IllegalStateException("Unexpected calendar store link");
    if(!STORE.exists()&&!STORE.mkdir())throw new IllegalStateException("Cannot create calendar recovery store");directory(STORE);Os.chmod(STORE.getPath(),0700);
    File lock=new File(STORE,"lock");if(Files.isSymbolicLink(lock.toPath()))throw new IllegalStateException("Unexpected calendar lock");
    try(FileChannel channel=FileChannel.open(lock.toPath(),StandardOpenOption.CREATE,StandardOpenOption.WRITE);FileLock held=channel.tryLock()) {
      if(held==null)throw new IllegalStateException("Another calendar operation is still running");JSONObject saved=state();
        if(saved==null)return new JSONObject().put("changed",false).put("managed",false).put("active",false);
        JSONArray entries=saved.getJSONArray("entries");
        if(ACTIVE.exists()||Files.isSymbolicLink(ACTIVE.toPath())) {
          directory(STYLE);if(STAGE.exists()||Files.isSymbolicLink(STAGE.toPath()))throw new IllegalStateException("Calendar recovery needs review");verify(ACTIVE,entries,true);
          Files.move(ACTIVE.toPath(),STAGE.toPath(),StandardCopyOption.ATOMIC_MOVE);sync(STYLE);sync(STORE);
        }
        List<File> files=verify(STAGE,entries,false);for(int i=files.size()-1;i>=0;i--)Files.delete(files.get(i).toPath());
        sync(STORE);if(saved.optBoolean("parent_created")&&STYLE.exists()){directory(STYLE);STYLE.delete();}
      IconRefresh.refresh(context.getPackageManager(),APP);
      reloadLauncher(true);
      Files.delete(STATE.toPath());sync(STORE);
      return new JSONObject().put("changed",true).put("managed",false).put("active",false);
    }
  }

}
