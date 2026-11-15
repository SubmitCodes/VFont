package dev.fontlab;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded app-only events. Never store exception messages, input names, URIs or operation payloads. */
final class SupportLog {
  static final int LIMIT=200, MAX_BYTES=128*1024;
  private static final long RETENTION=7L*24*60*60*1000;
  private static final List<String> OPERATIONS=List.of("connection","font_import","font_apply","font_restore",
      "font_probe","icons_check","icons_apply","icons_reset","icons_trial","icons_confirm",
      "calendar_check","calendar_apply","calendar_reset","full_theme_apply","full_theme_restore","diagnostics","system_bundle","app");
  private static final List<String> STAGES=List.of("layout","input","staging","dispatch","verify","refresh","complete");
  private static boolean initialized;

  private static File file(Context context) {return new File(context.getFilesDir(),"support-history.json");}
  private static JSONObject read(Context context) {
    try(FileInputStream in=new FileInputStream(file(context))) {
      byte[] bytes=SupportDiagnostics.readBounded(in,MAX_BYTES+1);
      if(bytes.length>MAX_BYTES)throw new IllegalStateException();
      return new JSONObject(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
    }catch(Exception ignored){return new JSONObject();}
  }
  private static JSONArray events(JSONObject state) {
    JSONArray input=state.optJSONArray("events"),out=new JSONArray();
    if(input!=null)for(int i=Math.max(0,input.length()-LIMIT);i<input.length();i++) {
      JSONObject event=input.optJSONObject(i);
      if(event!=null && System.currentTimeMillis()-event.optLong("time_ms")<RETENTION)out.put(event);
    }
    return out;
  }
  private static void save(Context context,JSONObject state,JSONArray events) throws Exception {
    JSONArray bounded=new JSONArray();
    for(int i=Math.max(0,events.length()-LIMIT);i<events.length();i++)bounded.put(events.get(i));
    state.put("events",bounded);
    if(state.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>MAX_BYTES)
      throw new IllegalStateException("History bound");
    IconSystemTask.writeJson(file(context),state);
  }
  static synchronized void initialize(Context context) {
    if(initialized)return;initialized=true;
    Context app=context.getApplicationContext();
    try {
      JSONObject state=read(app),pending=state.optJSONObject("pending");JSONArray events=events(state);
      if(pending!=null)for(java.util.Iterator<String> it=pending.keys();it.hasNext();) {
        String id=it.next();String operation=pending.getJSONObject(id).optString("operation");
        if(OPERATIONS.contains(operation)&&id.matches("[a-f0-9]{32}"))events.put(new JSONObject()
            .put("time_ms",System.currentTimeMillis()).put("id",id).put("operation",operation)
            .put("event","interrupted").put("code","process_ended")
            .put("app_version_code",pending.getJSONObject(id).optInt("app_version_code"))
            .put("software_profile",pending.getJSONObject(id).optString("software_profile")));
      }
      state.remove("pending");save(app,state,events);
    }catch(Exception ignored){}
    Thread.UncaughtExceptionHandler previous=Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler((thread,error)->{
      try {String id=begin(app,"app");failure(app,id,error,"",0);}
      finally {if(previous!=null)previous.uncaughtException(thread,error);}
    });
  }
  static synchronized String begin(Context context,String operation) {
    if(!OPERATIONS.contains(operation))throw new IllegalArgumentException("Unknown support operation");
    String id=UUID.randomUUID().toString().replace("-","");
    try {
      JSONObject state=read(context),event=new JSONObject().put("time_ms",System.currentTimeMillis())
          .put("id",id).put("operation",operation).put("event","start")
          .put("app_version_code",BuildConfig.VERSION_CODE)
          .put("software_profile",IconSystemTask.hash(android.os.Build.FINGERPRINT.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      JSONArray events=events(state);events.put(event);
      JSONObject pending=state.optJSONObject("pending");if(pending==null)pending=new JSONObject();
      if(pending.length()<8)pending.put(id,event);
      state.put("pending",pending);save(context,state,events);
    }catch(Exception ignored){}
    return id;
  }
  static synchronized void finish(Context context,String id,boolean ok,String message,String stage,long elapsed) {
    try {
      JSONObject state=read(context),pending=state.optJSONObject("pending");
      JSONObject start=pending==null?null:pending.optJSONObject(id);if(start==null)return;
      JSONObject event=new JSONObject().put("time_ms",System.currentTimeMillis()).put("id",id)
          .put("operation",start.getString("operation")).put("event",ok?"ok":"failed")
          .put("app_version_code",start.optInt("app_version_code")).put("software_profile",start.optString("software_profile"))
          .put("code",ok?"ok":code(message)).put("elapsed_ms",Math.max(0,Math.min(elapsed,600_000)));
      if(STAGES.contains(stage))event.put("stage",stage);
      JSONArray events=events(state);events.put(event);pending.remove(id);save(context,state,events);
    }catch(Exception ignored){}
  }
  static void result(Context context,String id,JSONObject result,long elapsed) {
    finish(context,id,result.optBoolean("ok"),result.optString("message"),result.optString("diagnostic_stage"),elapsed);
  }
  static synchronized void failure(Context context,String id,Throwable error,String stage,long elapsed) {
    finish(context,id,false,error.getClass().getSimpleName(),stage,elapsed);
    try {
      JSONObject state=read(context);JSONArray events=events(state);if(events.length()==0)return;
      JSONObject event=events.getJSONObject(events.length()-1);if(!id.equals(event.optString("id")))return;
      if(event.optString("operation").equals("app"))event.put("code","app_crash");
      event.put("failure_type",error.getClass().getSimpleName());JSONArray frames=new JSONArray();
      for(StackTraceElement frame:error.getStackTrace())if(frames.length()<6&&frame.getClassName().startsWith("dev.fontlab."))
        frames.put(frame.getClassName()+"#"+frame.getMethodName()+":"+Math.max(0,frame.getLineNumber()));
      event.put("app_frames",frames);save(context,state,events);
    }catch(Exception ignored){}
  }
  static String code(String value) {
    String text=value==null?"":value.toLowerCase(Locale.ROOT);
    if(text.equals("crash"))return "app_crash";
    if(text.contains("cancel"))return "cancelled";
    if(text.contains("firmware")||text.contains("service has changed"))return "transport_review_required";
    if(text.contains("native font layout")||text.contains("outside the supported system files"))return "unsupported_layout";
    if(text.contains("test native access")||text.contains("before applying"))return "probe_required";
    if(text.contains("refresh"))return "refresh_failed";
    if(text.contains("shizuku")||text.contains("deadobject")||text.contains("binder"))return "connection";
    if(text.contains("not authorized")||text.contains("permission")||text.contains("securityexception")||text.contains("eacces"))return "permission_denied";
    if(text.contains("timed out")||text.contains("timeout"))return "timeout";
    if(text.contains("changed after preview"))return "input_changed";
    if(text.contains("checksum")||text.contains("mismatch")||text.contains("receipt")||text.contains("did not match"))return "verification";
    if(text.contains("not supported")||text.contains("unsupported")||text.contains("missing"))return "unavailable";
    return "operation_failed";
  }
  static synchronized JSONArray export(Context context) throws Exception {
    JSONArray out=new JSONArray(),events=events(read(context));long now=System.currentTimeMillis();
    for(int i=0;i<events.length();i++) {
      JSONObject source=events.getJSONObject(i);String operation=source.optString("operation"),kind=source.optString("event");
      if(!OPERATIONS.contains(operation)||!List.of("start","ok","failed","interrupted").contains(kind))continue;
      JSONObject event=new JSONObject().put("operation",operation).put("event",kind)
          .put("age_seconds",Math.max(0,(now-source.optLong("time_ms",now))/1000));
      String id=source.optString("id");if(id.matches("[a-f0-9]{32}"))event.put("id",id);
      int version=source.optInt("app_version_code");if(version>0)event.put("app_version_code",version);
      String profile=source.optString("software_profile");if(profile.matches("[a-f0-9]{64}"))event.put("software_profile",profile);
      String stage=source.optString("stage");if(STAGES.contains(stage))event.put("stage",stage);
      String code=source.optString("code");
      if(List.of("ok","app_crash","transport_review_required","unsupported_layout","probe_required",
          "refresh_failed","connection","permission_denied","timeout","input_changed","verification",
          "unavailable","operation_failed","process_ended","cancelled").contains(code))event.put("code",code);
      String type=source.optString("failure_type");if(type.matches("[A-Za-z_$][A-Za-z0-9_$]{0,79}"))event.put("failure_type",type);
      JSONArray frames=source.optJSONArray("app_frames"),safe=new JSONArray();
      if(frames!=null)for(int f=0;f<Math.min(6,frames.length());f++) {
        String frame=frames.optString(f);
        if(frame.length()<200&&frame.matches("dev\\.fontlab\\.[A-Za-z0-9_.$]+#[A-Za-z0-9_$<>]+:[0-9]+"))safe.put(frame);
      }
      if(safe.length()>0)event.put("app_frames",safe);
      if(source.has("elapsed_ms"))event.put("elapsed_ms",Math.max(0,Math.min(source.optLong("elapsed_ms"),600_000)));
      out.put(event);
    }
    return out;
  }
  static synchronized void clear(Context context) throws Exception {java.nio.file.Files.deleteIfExists(file(context).toPath());}
}
