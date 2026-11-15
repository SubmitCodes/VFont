package dev.fontlab;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Immutable source archive and normalized artwork; selection is published after a complete import. */
final class ThemeImports {
  static boolean id(String id){return id!=null&&id.matches("[a-f0-9]{32}");}
  static boolean pack(String key){return key!=null&&key.startsWith("theme:")&&id(key.substring(6));}
  private static File root(Context context){return new File(context.getFilesDir(),"theme-imports");}
  static File folder(Context context,String id){if(!id(id))throw new IllegalArgumentException("Invalid imported theme");return new File(root(context),id);}
  static JSONObject info(Context context,String id) throws Exception {
    File file=new File(folder(context,id),"info.json");
    if(!file.isFile()||file.length()>256*1024)throw new IllegalStateException("Import this theme again");
    JSONObject info=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
    if(!id.equals(info.getString("id"))||info.getJSONArray("icons").length()>2000)throw new IllegalStateException("Invalid saved theme");
    return info;
  }
  static JSONObject current(Context context) throws Exception {
    String id=context.getSharedPreferences("themes",0).getString("selected","");return id.isEmpty()?null:info(context,id);
  }
  static File archive(Context context,String id) throws Exception {
    JSONObject saved=info(context,id);File file=new File(folder(context,id),"theme.itz");
    if(Files.isSymbolicLink(file.toPath())||!file.isFile()||file.length()>ThemeArchive.MAX_ARCHIVE
        ||file.length()!=saved.optLong("archive_bytes",-1)||!saved.getString("archive_sha256").equals(IconThemeStore.hash(file)))
      throw new IllegalStateException("Import this theme again to apply all its components");
    return file;
  }
  static IconPacks.Pack selectedPack(Context context) throws Exception {
    JSONObject info=current(context);return info==null?null:new IconPacks.Pack("Theme: "+info.getString("title"),"theme:"+info.getString("id"));
  }
  static List<String> catalog(Context context,String id) throws Exception {
    JSONArray icons=info(context,id).getJSONArray("icons");List<String> names=new ArrayList<>();
    for(int i=0;i<icons.length();i++){String name=icons.getString(i);if(!ThemeArchive.packageName(name))throw new IllegalStateException("Invalid saved theme icon");names.add(name);}return names;
  }
  static Drawable drawable(Context context,String id,String icon) throws Exception {
    if(!ThemeArchive.packageName(icon))throw new IllegalArgumentException("Invalid theme icon");
    File file=new File(new File(folder(context,id),"icons"),icon+".png");
    if(!file.isFile()||file.length()>4*1024*1024)throw new IllegalStateException("Import this theme again");
    Drawable image=Drawable.createFromPath(file.getPath());if(image==null)throw new IllegalStateException("Theme icon is unavailable");return image;
  }
  private static String artworkHash(Context context,JSONObject info) throws Exception {
    File folder=folder(context,info.getString("id"));
    if(Files.isSymbolicLink(folder.toPath())||!folder.getCanonicalFile().equals(new File(root(context).getCanonicalFile(),info.getString("id"))))throw new IllegalStateException("Theme artwork storage changed");
    List<String> paths=new ArrayList<>();JSONArray icons=info.getJSONArray("icons");
    if(icons.length()>2000||info.getInt("previews")<0||info.getInt("previews")>4)throw new IllegalStateException("Invalid saved theme artwork");
    for(int i=0;i<icons.length();i++){String name=icons.getString(i);if(!ThemeArchive.packageName(name))throw new IllegalStateException("Invalid saved icon");paths.add("icons/"+name+".png");}
    for(int i=0;i<info.getInt("previews");i++)paths.add("preview-"+i+".png");
    java.security.MessageDigest hash=java.security.MessageDigest.getInstance("SHA-256");long total=0;
    for(String path:paths){
      File file=new File(folder,path);if(Files.isSymbolicLink(file.toPath())||!file.getCanonicalFile().equals(new File(folder.getCanonicalFile(),path))||!file.isFile()||file.length()>4*1024*1024)throw new IllegalStateException("Saved theme artwork is missing");
      byte[] bytes;try(var input=Files.newInputStream(file.toPath())){bytes=FullThemeStore.readBounded(input,4*1024*1024+1);}
      if(bytes.length>4*1024*1024||(total+=bytes.length)>80L*1024*1024)throw new IllegalStateException("Saved theme artwork exceeds its limit");
      hash.update(path.getBytes(StandardCharsets.UTF_8));hash.update((byte)0);hash.update(ByteBuffer.allocate(4).putInt(bytes.length).array());hash.update(bytes);
    }
    StringBuilder out=new StringBuilder(64);for(byte value:hash.digest())out.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return out.toString();
  }
  private static void image(Context context,byte[] bytes,File file,boolean icon) throws Exception {
    Bitmap decoded=ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes)),(decoder,info,source)->{
      int w=info.getSize().getWidth(),h=info.getSize().getHeight(),limit=icon?256:720;
      if(w<1||h<1||w>8192||h>8192||(long)w*h>32_000_000)throw new IllegalArgumentException("Theme artwork dimensions are too large");
      float scale=Math.min(1f,(float)limit/Math.max(w,h));decoder.setTargetSize(Math.max(1,Math.round(w*scale)),Math.max(1,Math.round(h*scale)));
      decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
    });
    Bitmap output=decoded;
    try {
      if(icon){output=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);float scale=Math.min(256f/decoded.getWidth(),256f/decoded.getHeight()),w=decoded.getWidth()*scale,h=decoded.getHeight()*scale;
        new Canvas(output).drawBitmap(decoded,null,new RectF((256-w)/2,(256-h)/2,(256+w)/2,(256+h)/2),new Paint(Paint.FILTER_BITMAP_FLAG));}
      try(FileOutputStream stream=new FileOutputStream(file)){if(!output.compress(Bitmap.CompressFormat.PNG,100,stream))throw new IllegalStateException("Could not save theme artwork");stream.getFD().sync();}
    }finally{if(output!=decoded)output.recycle();decoded.recycle();}
  }
  static JSONObject importTheme(Context context,Uri uri) throws Exception {return importTheme(context,uri,null);}
  static synchronized JSONObject importTheme(Context context,Uri uri,java.util.function.BiConsumer<String,Float> progress) throws Exception {
    String id=UUID.randomUUID().toString().replace("-","");File input=new File(context.getCacheDir(),"theme-import-"+id+".tmp"),destination=folder(context,id);boolean published=false;
    try {
      String name="Selected theme";
      if("file".equals(uri.getScheme()))name=new File(uri.getPath()).getName();
      else try(var cursor=context.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(cursor!=null&&cursor.moveToFirst())name=cursor.getString(0);}
      if(name==null)name="Selected theme";if(name.length()>256)name=name.substring(0,256);
      if(progress!=null)progress.accept("Reading theme file…",-1f);
      long started=android.os.SystemClock.uptimeMillis();
      java.security.MessageDigest archiveHash=java.security.MessageDigest.getInstance("SHA-256");
      try(var from=context.getContentResolver().openInputStream(uri);var to=new FileOutputStream(input)) {
        if(from==null)throw new IllegalArgumentException("Could not open this theme");byte[] buffer=new byte[32768];long total=0;int n;
        while((n=from.read(buffer))!=-1){if((total+=n)>ThemeArchive.MAX_ARCHIVE)throw new IllegalArgumentException("Choose a theme smaller than 256 MiB");archiveHash.update(buffer,0,n);to.write(buffer,0,n);}to.getFD().sync();
      }
      StringBuilder digest=new StringBuilder(64);for(byte value:archiveHash.digest())digest.append(String.format(java.util.Locale.ROOT,"%02x",value&255));
      try{
        JSONObject cached=current(context);
        if(cached!=null&&cached.optLong("archive_bytes")==input.length()&&digest.toString().equals(cached.optString("archive_sha256"))
            &&cached.optString("artwork_sha256").matches("[a-f0-9]{64}")&&cached.getString("artwork_sha256").equals(artworkHash(context,cached))){
          archive(context,cached.getString("id"));
          if(progress!=null)progress.accept("Reusing verified theme previews…",1f);
          return new JSONObject(cached.toString()).put("reused",true).put("import_ms",android.os.SystemClock.uptimeMillis()-started);
        }
      }catch(Exception ignored){} // Missing or changed cached artwork is rebuilt from the supplied archive.
      JSONObject result;
      try(ThemeArchive archive=new ThemeArchive(input,java.util.Locale.getDefault().toString())) {
        if(progress!=null)progress.accept("Checking theme contents…",-1f);
        archive.verifyContents();
        File icons=new File(destination,"icons");if(!icons.mkdirs())throw new IllegalStateException("Could not prepare theme storage");
        JSONArray keys=new JSONArray();long total=0;int done=0;
        for(String pkg:archive.icons.keySet()){
          File file=new File(icons,pkg+".png");image(context,archive.icon(pkg),file,true);total+=file.length();
          if(total>64L*1024*1024)throw new IllegalArgumentException("The imported icons need too much storage");keys.put(pkg);done++;
          if(progress!=null&&(done%8==0||done==archive.icons.size()))progress.accept("Preparing icons · "+done+" / "+archive.icons.size(),(float)done/archive.icons.size());
        }
        int previews=0;
        for(String path:archive.previews){if(progress!=null)progress.accept("Preparing previews…",-1f);image(context,archive.read(path,16*1024*1024),new File(destination,"preview-"+previews+".png"),false);previews++;}
        result=new JSONObject().put("id",id).put("title",archive.title.isEmpty()?name:archive.title).put("author",archive.author)
            .put("version",archive.version).put("description",archive.description).put("filename",name).put("icons",keys)
            .put("previews",previews).put("components",new JSONArray(archive.components)).put("archive_sha256",digest.toString()).put("archive_bytes",input.length()).put("import_ms",android.os.SystemClock.uptimeMillis()-started);
        result.put("artwork_sha256",artworkHash(context,result));result.put("import_ms",android.os.SystemClock.uptimeMillis()-started);
      }
      Files.move(input.toPath(),new File(destination,"theme.itz").toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE);
      IconSystemTask.writeJson(new File(destination,"info.json"),result);
      if(!context.getSharedPreferences("themes",0).edit().putString("selected",id).commit())throw new IllegalStateException("Could not save the theme selection");
      published=true;cleanup(context);return result;
    }finally{input.delete();if(!published)removeOwned(destination);}
  }
  static synchronized void clear(Context context) throws Exception {
    if(!context.getSharedPreferences("themes",0).edit().remove("selected").commit())throw new IllegalStateException("Could not remove the theme selection");cleanup(context);
  }
  static synchronized void cleanup(Context context) {
    Set<String> keep=new HashSet<>();keep.add(context.getSharedPreferences("themes",0).getString("selected",""));
    keep.add(context.getSharedPreferences("themes",0).getString("active",""));
    for(Object value:context.getSharedPreferences("icon-choices",0).getAll().values())try{keep.add(new JSONObject(String.valueOf(value)).optString("theme"));}catch(Exception ignored){}
    File[] folders=root(context).listFiles();if(folders!=null)for(File folder:folders)if(id(folder.getName())&&!keep.contains(folder.getName()))removeOwned(folder);
    File[] temporary=context.getCacheDir().listFiles();if(temporary!=null)for(File file:temporary)
      if(file.getName().matches("theme-import-[a-f0-9]{32}\\.tmp")&&file.isFile()&&!Files.isSymbolicLink(file.toPath()))file.delete();
  }
  private static void removeOwned(File folder) {
    // Unknown contents are retained. Only our flat normalized artwork layout can be removed.
    try {
      if(Files.isSymbolicLink(folder.toPath())||!folder.isDirectory())return;
      File[] children=folder.listFiles();if(children==null)return;
      for(File child:children){
        if(Files.isSymbolicLink(child.toPath()))return;
        if(child.getName().equals("icons")&&child.isDirectory()){
          File[] icons=child.listFiles();if(icons==null)return;
          for(File icon:icons)if(!icon.isFile()||Files.isSymbolicLink(icon.toPath())||!icon.getName().endsWith(".png")||!ThemeArchive.packageName(icon.getName().substring(0,icon.getName().length()-4)))return;
        }else if(!child.isFile()||!(child.getName().equals("info.json")||child.getName().equals("theme.itz")||child.getName().matches("preview-[0-3]\\.png")))return;
      }
      for(File child:children){if(child.isDirectory())for(File icon:child.listFiles())if(!icon.delete())return;if(!child.delete())return;}folder.delete();
    }catch(Exception ignored){}
  }
}
