package dev.fontlab;

import android.content.Context;
import java.io.*;
import java.nio.file.*;
import java.util.UUID;
import org.json.*;

/** App-side preparation for the same private, bounded system bridge used by icons. */
final class FullThemeJobs {
  static JSONObject run(Context context,String operation,String id) throws Exception {return run(context,operation,id,null);}
  static JSONObject run(Context context,String operation,String id,java.util.function.BiConsumer<String,Float> progress) throws Exception {
    return run(context,operation,id,progress,null);
  }
  static JSONObject run(Context context,String operation,String id,java.util.function.BiConsumer<String,Float> progress,JSONArray parts) throws Exception {
    if(!operation.matches("check|apply|restore"))throw new IllegalArgumentException("Unknown theme operation");
    IFontService service=MainActivity.iconsService();if(service==null)throw new IllegalStateException("Connect Shizuku from VFont's main screen first");
    String tx=UUID.randomUUID().toString().replace("-","");File folder=new File(new File(context.getExternalFilesDir(null),"icons"),"job-"+tx);boolean dispatched=false;
    if(!folder.mkdirs())throw new IOException("Could not prepare the theme operation");
    java.util.concurrent.ScheduledExecutorService poller=null;
    try{
      JSONObject request=new JSONObject().put("tx",tx).put("operation","full-theme-"+operation).put("apps",new JSONArray());
      if(operation.equals("apply")){
        if(progress!=null)progress.accept("Preparing selected theme…",-1f);
        File source=ThemeImports.archive(context,id),copy=new File(folder,"theme.itz");
        try(InputStream in=Files.newInputStream(source.toPath());FileOutputStream out=new FileOutputStream(copy)){FullThemeStore.copyStream(in,out);out.getFD().sync();}
        String hash=ThemeImports.info(context,id).getString("archive_sha256");if(!IconThemeStore.hash(copy).equals(hash))throw new IOException("The selected theme changed while preparing it");
        request.put("import_id",id).put("archive_sha256",hash).put("adjustable_icons",context.getSharedPreferences("themes",0).getBoolean("adjustable_icons",true));
      }
      if(parts!=null){if(operation.equals("check"))throw new IllegalArgumentException("Check has no component selection");ThemeParts.selection(parts);request.put("components",parts);}
      File input=new File(folder,"request.json");IconSystemTask.writeJson(input,request);dispatched=true;
      if(progress!=null){
        poller=java.util.concurrent.Executors.newSingleThreadScheduledExecutor();String[] last={""};
        poller.scheduleWithFixedDelay(()->{try{
          File file=new File(folder,"progress.json");if(Files.isSymbolicLink(file.toPath())||!file.isFile()||file.length()>2048)return;
          String message=new JSONObject(new String(Files.readAllBytes(file.toPath()),java.nio.charset.StandardCharsets.UTF_8)).getString("message");
          if(!message.equals(last[0])){last[0]=message;progress.accept(message,-1f);}
        }catch(Exception ignored){}},0,200,java.util.concurrent.TimeUnit.MILLISECONDS);
      }
      JSONObject result=new JSONObject(service.icons(tx,IconSystemTask.hash(input)));
      if(!operation.equals("check"))IconSystemTask.writeJson(new File(context.getFilesDir(),"last-full-theme.json"),result);
      if(result.optBoolean("ok")){
        var editor=context.getSharedPreferences("themes",0).edit();
        if(result.optBoolean("active")){
          JSONObject active=result.optJSONObject("theme");if(active!=null&&ThemeImports.id(active.optString("import_id")))editor.putString("active",active.getString("import_id"));
        }else editor.remove("active");
        if(!editor.commit())throw new IOException("Theme result is saved; reopen Themes to refresh its status");
      }
      return result;
    }finally{
      if(poller!=null)poller.shutdownNow();
      if(!dispatched||new File(folder,"result.json").isFile()){
        for(String name:new String[]{"theme.itz","request.json","result.json","progress.json"}){File file=new File(folder,name);if(Files.isSymbolicLink(file.toPath()))throw new IOException("Unexpected theme job link");if(file.isFile())file.delete();}
        folder.delete();
      }
    }
  }
}
