package dev.fontlab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;
import rikka.shizuku.Shizuku;

/** Manual support exports, available before the native transport's firmware gate. */
public final class SupportActivity extends Activity {
  private static final int SAVE_REPORT=51,SAVE_SYSTEM=52;
  private final ExecutorService worker=Executors.newSingleThreadExecutor();
  private final List<Button> controls=new ArrayList<>();
  private JSONObject colors,report;
  private TextView summary,notice;
  private Button save,review;
  private boolean busy;

  private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
  private int color(String key){return (int)colors.optLong(key);}
  private TextView text(String value,int size){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(color("text"));return view;}
  private void add(LinearLayout root,android.view.View view){LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=dp(12);root.addView(view,params);}
  private Button button(LinearLayout root,String label,Runnable action){
    Button button=new Button(this);button.setText(label);button.setAllCaps(false);button.setTextSize(16);
    button.setMinHeight(dp(52));button.setPadding(dp(16),dp(12),dp(16),dp(12));button.setTextColor(color("on_secondary"));
    GradientDrawable shape=new GradientDrawable();shape.setColor(color("secondary"));shape.setCornerRadius(dp(26));button.setBackground(shape);
    ToolUi.buttonStyle(button,colors,false);button.setOnClickListener(v->action.run());ToolUi.bind(button,this,"support");ToolUi.press(button);controls.add(button);add(root,button);return button;
  }
  @Override public void onCreate(Bundle state){
    super.onCreate(state);SupportLog.initialize(this);colors=MainActivity.palette(this);try{colors.put("background",ToolUi.background(colors)&0xffffffffL);}catch(Exception impossible){throw new AssertionError(impossible);}MainActivity.styleSystemBars(getWindow(),colors);
    ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(color("background"));
    LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);scroll.addView(root);
    root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets safe=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout());root.setPadding(dp(24)+safe.left,dp(12)+safe.top,dp(24)+safe.right,dp(24)+safe.bottom);return insets;});
    TextView title=text("Device support",28);title.setTypeface(null,Typeface.BOLD);add(root,title);
    add(root,text("Check your phone and save a report for help.",15));
    summary=text("Basic checks work without Shizuku. Connect it for deeper read-only checks.",16);add(root,summary);
    button(root,"Check device",this::scan);
    if(MainActivity.iconsService()==null)button(root,"Connect Shizuku",()->{if(!busy){MainActivity.connectForSupport();finish();}});
    review=button(root,"Review report",this::review);
    save=button(root,"Save support report",()->choose(SAVE_REPORT));
    ToolUi.buttonStyle(save,colors,true);
    LinearLayout advanced=new LinearLayout(this);advanced.setOrientation(LinearLayout.VERTICAL);advanced.setVisibility(android.view.View.GONE);
    Button more=button(root,"Report details and advanced options",()->advanced.setVisibility(advanced.getVisibility()==android.view.View.VISIBLE?android.view.View.GONE:android.view.View.VISIBLE));
    button(advanced,"What's in the report?",()->ToolUi.dialog(this).setTitle("Report privacy").setMessage("Software/build information, fixed component versions and hashes, check results and up to 200 sanitized VFont events. No serial, IMEI, accounts, app list, filenames or input contents. Nothing is uploaded automatically.").setPositiveButton("Done",null).show());
    button(advanced,"Export system files (optional)",()->{
      if(busy)return;
      ToolUi.dialog(this).setTitle("Export stock system files?")
          .setMessage("This separate ZIP contains only fixed Android/Vivo code components and an inventory. It can contain up to 512 MiB before compression. Unreadable or oversized files are listed as skipped.\n\nNo app data, custom fonts, custom icons or personal files are included. Uses Shizuku when connected. Share it privately when requested for investigation.")
          .setPositiveButton("Choose ZIP location",(dialog,which)->choose(SAVE_SYSTEM)).setNegativeButton("Cancel",null).show();
    });
    button(advanced,"Clear recent history",()->{if(!busy&&!MainActivity.operationActive())ToolUi.dialog(this).setTitle("Clear VFont history?")
        .setMessage("Removes VFont's local diagnostic events and cached report. Your selections and appearance stay as they are.")
        .setPositiveButton("Clear",(dialog,which)->{try{SupportLog.clear(this);java.nio.file.Files.deleteIfExists(cache().toPath());report=null;state(false);summary.setText("History cleared. Check device to create a new report.");notice.setText("");}catch(Exception error){notice.setText("Could not clear history.");}})
        .setNegativeButton("Cancel",null).show();});

    add(root,advanced);
    notice=text("",14);notice.setTextColor(color("muted"));add(root,notice);LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setBackgroundColor(color("background"));page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));page.addView(ToolUi.navigation(this,colors,"Device support",()->{if(!busy)finish();},"support"));page.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets safe=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout());page.setPadding(safe.left,safe.top,safe.right,safe.bottom);return insets;});root.setOnApplyWindowInsetsListener(null);root.setPadding(dp(20),dp(16),dp(20),dp(12));setContentView(page);
    if(android.os.Build.VERSION.SDK_INT>=33) {
      getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,()->{if(!busy)finish();});
    }
    load();state(false);
    boolean staleShell=report!=null&&MainActivity.iconsService()!=null
        &&"not_tested".equals(report.optJSONObject("shell")==null?"not_tested":report.optJSONObject("shell").optString("status"));
    if(report==null||staleShell)scan();else showSummary();
  }
  @SuppressWarnings("deprecation")
  @Override public void onBackPressed(){if(!busy)super.onBackPressed();}
  private File cache(){return new File(getFilesDir(),"support-report.json");}
  private void load(){
    try(FileInputStream input=new FileInputStream(cache())) {
      byte[] bytes=SupportDiagnostics.readBounded(input,SupportDiagnostics.MAX_REPORT+1);if(bytes.length>SupportDiagnostics.MAX_REPORT)return;
      JSONObject value=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
    if(BuildConfig.VERSION_NAME.equals(value.optString("app_version"))&&BuildConfig.VERSION_CODE==value.optInt("app_version_code")&&android.os.Build.FINGERPRINT.equals(value.getJSONObject("app").getJSONObject("software").optString("fingerprint")))report=value;
    }catch(Exception ignored){}
  }
  private void state(boolean value){busy=value;for(Button button:controls)button.setEnabled(!value);save.setEnabled(!value&&report!=null);review.setEnabled(!value&&report!=null);}
  static JSONObject connection(){
    JSONObject out=new JSONObject();
    try{
      boolean running=Shizuku.pingBinder();out.put("running",running).put("service_connected",MainActivity.iconsService()!=null);
      if(running){
        try{int uid=Shizuku.getUid();out.put("uid",uid).put("uid_supported",uid==2000).put("version",Shizuku.getVersion());}catch(Exception ignored){}
      }
      boolean granted=running&&Shizuku.checkSelfPermission()==android.content.pm.PackageManager.PERMISSION_GRANTED;out.put("authorized",granted);
    }
    catch(Exception error){try{out.put("status","inaccessible");}catch(Exception ignored){}}
    return out;
  }
  private void scan(){
    if(busy||!MainActivity.beginOperation())return;state(true);notice.setText("Checking device. System files are read for identification; no native apply or backup restore is run.");
    worker.execute(()->{
      String event=SupportLog.begin(this,"diagnostics");long started=SystemClock.uptimeMillis();
      try {
        JSONObject app=SupportDiagnostics.collect(this),shell=null;IFontService service=MainActivity.iconsService();
        if(service!=null)try{shell=new JSONObject(service.diagnostics());}
        catch(SecurityException error){shell=new JSONObject().put("status","unauthorized");}
        catch(Exception error){shell=new JSONObject().put("status","connection_failed");}
        SupportLog.finish(this,event,true,"","complete",SystemClock.uptimeMillis()-started);
        JSONObject value=SupportDiagnostics.report(this,app,shell,connection());
        IconSystemTask.writeJson(cache(),value);
        runOnUiThread(()->{if(!isDestroyed()){report=value;showSummary();notice.setText("Report ready. Review it before saving. Check again after a firmware update.");}});
      }catch(Exception error){SupportLog.finish(this,event,false,error.getClass().getSimpleName(),"",SystemClock.uptimeMillis()-started);runOnUiThread(()->{if(!isDestroyed())notice.setText("Could not complete the scan. The previous report is kept.");});}
      finally{runOnUiThread(()->{MainActivity.endOperation();if(!isDestroyed())state(false);});}
    });
  }
  private void showSummary(){
    try {
      JSONObject software=report.getJSONObject("app").getJSONObject("software"),shell=report.getJSONObject("shell"),shizuku=report.optJSONObject("shizuku");
      String transport=shell.optString("native_transport",report.getJSONObject("app").optString("native_transport","not_tested"));
      String statusNote;
      if(shizuku!=null&&shizuku.optBoolean("running")&&shizuku.has("uid")&&!shizuku.optBoolean("uid_supported",true))
        statusNote="Shizuku is running as UID "+shizuku.optInt("uid")+" (root). Restart Shizuku with ADB or Wireless Debugging (UID 2000) to run shell checks.";
      else statusNote=(transport.equals("reviewed")?"The tested native transport profile was found. Normal font/icon checks still apply.":transport.equals("experimental")?"Native customization is available for testing on this software. Your tools show any check that is still needed.":transport.equals("unavailable")?"A required native transport feature is unavailable. Save this report to help investigate support.":"Basic report available. Connect Shizuku and check again for deeper evidence.");
      summary.setText(software.optString("model")+"\n"+software.optString("build")+"\n\n"+statusNote);
    }catch(Exception error){summary.setText("Report collected. Review the check results for details.");}
  }
  private void review(){
    if(report==null||busy)return;try{TextView contents=text(new String(SupportDiagnostics.encode(report),StandardCharsets.UTF_8),12);contents.setTypeface(Typeface.MONOSPACE);contents.setTextIsSelectable(true);contents.setPadding(dp(16),dp(16),dp(16),dp(16));ScrollView scroll=new ScrollView(this);scroll.addView(contents);ToolUi.dialog(this).setTitle("Report contents").setView(scroll).setPositiveButton("Close",null).show();}catch(Exception error){notice.setText("Could not display this report.");}
  }
  private void choose(int request){
    if(busy||MainActivity.operationActive()||request==SAVE_REPORT&&report==null)return;
    try {
      startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
          .setType(request==SAVE_REPORT?"application/json":"application/zip")
          .putExtra(Intent.EXTRA_TITLE,request==SAVE_REPORT?"VFont-support.json":"VFont-system-components.zip"),request);
    } catch (android.content.ActivityNotFoundException error) {
      notice.setText("No file manager is available to choose a save location.");
    }
  }
  @Override protected void onActivityResult(int request,int result,Intent data){
    super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;
    if(request!=SAVE_REPORT&&request!=SAVE_SYSTEM)return;
    if(busy||!MainActivity.beginOperation())return;state(true);notice.setText(request==SAVE_REPORT?"Saving report...":"Exporting readable stock components. Keep VFont open...");
    worker.execute(()->{
      String event=request==SAVE_SYSTEM?SupportLog.begin(this,"system_bundle"):null;long started=SystemClock.uptimeMillis();
      try {
        String message;
        if(request==SAVE_REPORT) {
          byte[] bytes=SupportDiagnostics.encode(report);
          try(OutputStream out=getContentResolver().openOutputStream(data.getData(),"wt")){if(out==null)throw new java.io.IOException();out.write(bytes);}
          message="Support report saved. Nothing was uploaded.";
        }else {
          JSONObject receipt;IFontService service=MainActivity.iconsService();
          if(service!=null)try(ParcelFileDescriptor out=getContentResolver().openFileDescriptor(data.getData(),"wt")){if(out==null)throw new java.io.IOException();receipt=new JSONObject(service.exportSystemComponents(out));}
          else try(OutputStream out=getContentResolver().openOutputStream(data.getData(),"wt")){if(out==null)throw new java.io.IOException();receipt=SupportDiagnostics.bundle(this,out);}
          if(!receipt.optBoolean("ok"))throw new java.io.IOException();
          message="System bundle saved: "+receipt.optInt("files")+" files. Its manifest lists included and skipped components. Nothing was uploaded.";
          SupportLog.result(this,event,receipt,SystemClock.uptimeMillis()-started);
        }
        String saved=message;runOnUiThread(()->{if(!isDestroyed())notice.setText(saved);});
      }catch(Exception error){if(event!=null)SupportLog.finish(this,event,false,error.getClass().getSimpleName(),"",SystemClock.uptimeMillis()-started);runOnUiThread(()->{if(!isDestroyed())notice.setText(request==SAVE_SYSTEM?"System bundle failed. Discard the incomplete ZIP and try another destination.":"Could not save the report. Try another destination.");});}
      finally{runOnUiThread(()->{MainActivity.endOperation();if(!isDestroyed())state(false);});}
    });
  }
  @Override protected void onDestroy(){ToolUi.closeHelp(this);worker.shutdown();super.onDestroy();}
}
