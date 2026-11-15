package dev.fontlab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** Local native themes with a persistent full-theme backup and Restore. */
public final class ThemesActivity extends Activity {
  private static final int PICK=51;
  private final ExecutorService worker=Executors.newSingleThreadExecutor();
  private JSONObject colors,theme,fullState;
  private boolean busy,themeChanged,componentsOnly;
  private TextView status,recoveryStatus;
  private Button applyButton,restoreButton;
  private JSONArray availableParts=new JSONArray();
  private final java.util.Set<String> sourceParts=new java.util.LinkedHashSet<>();
  private final java.util.Set<String> selectedParts=new java.util.LinkedHashSet<>();
  private final java.util.Map<String,CheckBox> partChecks=new java.util.LinkedHashMap<>();
  private SquigglyProgress progress;
  private AlertDialog activeDialog;
  private LinearLayout root,content;
  private final List<View> controls=new ArrayList<>();
  private final android.os.Handler connectionHandler=new android.os.Handler(android.os.Looper.getMainLooper());
  private IFontService observedService;
  private final Runnable connectionCheck=new Runnable(){public void run(){if(!busy&&!MainActivity.operationActive()&&observedService!=MainActivity.iconsService())load();connectionHandler.postDelayed(this,750);}};
  private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
  private int color(String key){return (int)colors.optLong(key);}
  private GradientDrawable shape(String key,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color(key));d.setCornerRadius(dp(radius));return d;}
  private LinearLayout column(){LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);return layout;}
  private TextView text(String value,int size){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(color("text"));return view;}
  private void add(LinearLayout parent,View child,int margin){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(margin);parent.addView(child,p);}
  private Button button(String title,boolean primary,Runnable action){
    Button view=new Button(this);view.setText(title);view.setAllCaps(false);view.setTextSize(15);view.setTypeface(null,Typeface.BOLD);view.setMinHeight(dp(52));view.setPadding(dp(16),dp(12),dp(16),dp(12));view.setStateListAnimator(null);
    view.setTextColor(color(primary?"on_primary":"on_secondary"));view.setBackground(new RippleDrawable(ColorStateList.valueOf((color("primary")&0xffffff)|0x20000000),shape(primary?"primary":"secondary",26),null));
    ToolUi.buttonStyle(view,colors,primary);view.setOnClickListener(v->action.run());ToolUi.bind(view,this,componentsOnly?"components":"themes");ToolUi.press(view);controls.add(view);return view;
  }
  @Override public void onCreate(Bundle state){
    super.onCreate(state);componentsOnly=getIntent().getBooleanExtra("components_only",false);if(state!=null)selectedParts.addAll(state.getStringArrayList("parts")==null?List.of():state.getStringArrayList("parts"));colors=MainActivity.palette(this);try{colors.put("background",ToolUi.background(colors)&0xffffffffL);}catch(Exception impossible){throw new AssertionError(impossible);}MainActivity.styleSystemBars(getWindow(),colors);
    root=column();root.setBackgroundColor(color("background"));
    root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets safe=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout());root.setPadding(dp(20)+safe.left,dp(8)+safe.top,dp(20)+safe.right,dp(12)+safe.bottom);return insets;});
    status=text("Loading your theme…",14);status.setTextColor(color("muted"));add(root,status,12);
    status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    progress=new SquigglyProgress(this,color("primary"));root.addView(progress,new LinearLayout.LayoutParams(-1,dp(20)));progress.setVisibility(View.GONE);
    ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);content=column();scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
    recoveryStatus=text("Checking recovery backup…",13);recoveryStatus.setTextColor(color("muted"));add(root,recoveryStatus,8);
    LinearLayout actionRow=new LinearLayout(this);applyButton=button("Choose theme",true,()->{if(theme==null)pick();else if(componentsOnly)confirmParts();else confirmFull();});applyButton.setTag("theme-primary");
    restoreButton=button(componentsOnly?"Restore selected":"Restore",false,this::confirmRestore);restoreButton.setContentDescription(componentsOnly?"Restore selected components":"Restore previous setup");restoreButton.setTag("theme-restore");
    LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(0,-2,1);ap.setMarginEnd(dp(8));actionRow.addView(applyButton,ap);actionRow.addView(restoreButton,new LinearLayout.LayoutParams(0,-2,1));add(root,actionRow,8);
    root.addView(ToolUi.navigation(this,colors,componentsOnly?"Theme components":"Full themes",()->{if(!busy)finish();},componentsOnly?"components":"themes"));
    setContentView(root);root.requestApplyInsets();
    if(android.os.Build.VERSION.SDK_INT>=33)getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,()->{if(!busy)finish();});
    load();
  }
  @Override public void onBackPressed(){if(!busy)super.onBackPressed();}
  private void submit(Runnable task,Runnable onRejected){
    try{worker.execute(task);}catch(java.util.concurrent.RejectedExecutionException rejected){if(onRejected!=null)onRejected.run();}
  }
  private void updatePrimary(){
    if(applyButton==null)return;boolean archive=false;try{archive=theme!=null&&ThemeImports.archive(this,theme.getString("id")).isFile();}catch(Exception ignored){}
    applyButton.setText(theme==null?"Choose theme":componentsOnly?(selectedParts.isEmpty()?"Select components":"Review "+selectedParts.size()+" selected"):"Apply full theme");
    boolean canApply=MainActivity.iconsService()!=null&&fullState!=null&&fullState.optBoolean("ok")&&fullState.optBoolean("can_apply");
    applyButton.setEnabled(!busy&&(theme==null||archive&&canApply&&(!componentsOnly||!selectedParts.isEmpty()&&sourceParts.containsAll(selectedParts))));applyButton.setAlpha(1);
    if(restoreButton!=null){
      boolean backup=fullState!=null&&fullState.optBoolean("active");
      restoreButton.setEnabled(!busy&&MainActivity.iconsService()!=null&&backup&&(!componentsOnly||!selectedParts.isEmpty()));
      String paperHint="";
      if(fullState!=null&&fullState.optBoolean("ok")&&!componentsOnly&&theme!=null&&!fullState.optBoolean("wallpaper_can_backup",true)){
        paperHint=fullState.optString("wallpaper_reason","");
      }else if(fullState!=null&&fullState.optBoolean("ok")&&componentsOnly){
        if(selectedParts.contains("home")&&!fullState.optBoolean("home_wallpaper_static",true))paperHint="Home wallpaper must be static before changing it here.";
        else if(selectedParts.contains("lockpaper")&&!fullState.optBoolean("lock_wallpaper_static",true))paperHint="Lock screen wallpaper must be static before changing it here.";
      }
      recoveryStatus.setText(busy?"Keep VFont open until this operation finishes.":fullState==null?"Connect Shizuku to check the saved backup.":!paperHint.isEmpty()?paperHint:backup?componentsOnly?"Restore returns only the checked parts to your saved setup.":"Your previous setup is backed up.":"No saved setup yet. Apply creates a backup.");
    }
  }
  private void setBusy(boolean value,String message){busy=value;status.setText(message);status.setVisibility(message.isEmpty()?View.GONE:View.VISIBLE);progress.progress(-1);progress.setVisibility(value?View.VISIBLE:View.GONE);for(View view:controls){view.setEnabled(!value);view.setAlpha(1);}updatePrimary();}
  private void reportProgress(String message,Float fraction){runOnUiThread(()->{if(!isDestroyed()&&busy){status.setText(message);status.setVisibility(View.VISIBLE);progress.progress(fraction);}});}
  private void load(){
    if(isDestroyed()||worker.isShutdown())return;
    observedService=MainActivity.iconsService();fullState=null;
    setBusy(true,"Loading your theme…");
    submit(()->{try{ThemeImports.cleanup(this);JSONObject current=ThemeImports.current(this);JSONArray parts=componentsOnly&&current!=null?ThemeParts.available(ThemeImports.archive(this,current.getString("id"))):new JSONArray();
      String warning="";
      if(MainActivity.iconsService()!=null&&MainActivity.beginOperation())try{fullState=FullThemeJobs.run(this,"check",null);if(!fullState.optBoolean("ok"))warning=fullState.optString("message");}catch(Exception error){fullState=new JSONObject();warning=error.getMessage();}finally{MainActivity.endOperation();}
      String outcome=warning;runOnUiThread(()->{if(!isDestroyed()){theme=current;availableParts=parts;refreshParts();showTheme();setBusy(false,outcome);}});
    }catch(Exception error){runOnUiThread(()->{if(!isDestroyed()){theme=null;showTheme();setBusy(false,error.getMessage());}});}},()->setBusy(false,""));
  }
  private void showTheme(){
    content.removeAllViews();controls.clear();controls.add(applyButton);controls.add(restoreButton);partChecks.clear();
    LinearLayout heading=new LinearLayout(this);heading.setGravity(Gravity.CENTER_VERTICAL);TextView name=text(componentsOnly?"Theme components":"Full themes",26);name.setTypeface(null,Typeface.BOLD);heading.addView(name,new LinearLayout.LayoutParams(0,-2,1));
    android.widget.ImageButton more=ToolUi.iconButton(this,colors,R.drawable.vf_rounded_settings_24,"Theme options",this::themeOptions);controls.add(more);heading.addView(more,new LinearLayout.LayoutParams(dp(48),dp(48)));add(content,heading,8);
    if(theme==null){add(content,text(componentsOnly?"Import a theme to apply new components. A saved setup can be restored without an import.":"Import a Vivo .itz file to preview its look and apply the full theme.",16),24);if(componentsOnly)showComponents();updatePrimary();return;}
    try {
      String id=theme.getString("id");LinearLayout summary=new LinearLayout(this);summary.setGravity(Gravity.CENTER_VERTICAL);summary.setPadding(dp(16),dp(16),dp(16),dp(16));summary.setBackground(shape("surface",24));
      if(theme.optInt("previews")>0){ImageView thumbnail=previewImage(0);thumbnail.setContentDescription("Open theme previews");thumbnail.setOnClickListener(v->showPreviews());thumbnail.setFocusable(true);summary.addView(thumbnail,new LinearLayout.LayoutParams(dp(72),dp(100)));}
      LinearLayout labels=column();labels.setPadding(dp(16),0,0,0);TextView title=text(theme.getString("title"),21);title.setTypeface(null,Typeface.BOLD);labels.addView(title);TextView author=text(theme.optString("author"),13);author.setTextColor(color("muted"));labels.addView(author);
      Button change=button("Change theme",false,this::pick);LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.topMargin=dp(12);labels.addView(change,cp);summary.addView(labels,new LinearLayout.LayoutParams(0,-2,1));add(content,summary,16);
      if(theme.optInt("previews")>0)add(content,button("View "+theme.getInt("previews")+" previews",false,this::showPreviews),12);
      if(componentsOnly){showComponents();}else {
        TextView label=text("Included in this theme",18);label.setTypeface(null,Typeface.BOLD);add(content,label,24);JSONArray parts=theme.getJSONArray("components");List<String> names=new ArrayList<>();for(int i=0;i<parts.length();i++)names.add(parts.getString(i));add(content,text(String.join(" / ",names),15),8);
        TextView recovery=text("Your current setup is saved before the first Apply. Use Restore previous setup to return to it.",14);recovery.setTextColor(color("muted"));add(content,recovery,18);
        TextView fontScope=text(theme.getJSONArray("components").toString().contains("\"Fonts\"")?"Bundled font: requires a supported font/recovery path before Apply.":"Font: not included. Your current font stays applied.",14);fontScope.setTextColor(color("muted"));add(content,fontScope,8);
      }
      if(theme.getJSONArray("icons").length()>0){
        boolean supported=fullState==null||fullState.optBoolean("adjustable_icons_supported",true);
        CheckBox adjustable=new CheckBox(this);adjustable.setText("Use native icon size and shape controls");adjustable.setTextSize(16);adjustable.setTextColor(color("text"));adjustable.setButtonTintList(ColorStateList.valueOf(color("primary")));adjustable.setMinHeight(dp(56));
        adjustable.setChecked(supported&&getSharedPreferences("themes",0).getBoolean("adjustable_icons",true));
        if(supported){
          adjustable.setOnCheckedChangeListener((button,on)->getSharedPreferences("themes",0).edit().putBoolean("adjustable_icons",on).apply());
          controls.add(adjustable);
        }else{
          adjustable.setEnabled(false);adjustable.setAlpha(0.55f);
        }
        add(content,adjustable,18);
        String scopeText=supported?"Adapts this theme’s icons to your phone’s controls. Legacy animated icons use static artwork; native layered icons keep their variants. Sidebar icon size is controlled by the system.":fullState.optString("adjustable_icons_reason","This phone does not support adjustable icon controls.");
        TextView scope=text(scopeText,14);scope.setTextColor(color("muted"));add(content,scope,4);
      }
      if(MainActivity.iconsService()==null)add(content,button("Connect Shizuku",false,()->{MainActivity owner=MainActivity.owner();if(owner!=null)owner.openTool("connection");finish();}),16);
      updatePrimary();
    }catch(Exception error){status.setText(error.getMessage());}
  }
  private void refreshParts(){
    sourceParts.clear();for(int i=0;i<availableParts.length();i++)sourceParts.add(availableParts.optString(i));
  }
  private void showComponents(){
    TextView hint=text("Select what changes",18);hint.setTypeface(null,Typeface.BOLD);add(content,hint,24);add(content,text("Apply or restore the checked parts. Unselected parts stay as they are.",14),6);
    java.util.Set<String> choices=new java.util.LinkedHashSet<>();if(fullState!=null&&fullState.optBoolean("active"))choices.addAll(ThemeParts.LABELS.keySet());else choices.addAll(sourceParts);
    selectedParts.retainAll(choices);
    for(String key:choices){
      CheckBox option=new CheckBox(this);option.setText(ThemeParts.LABELS.get(key)+(sourceParts.contains(key)?"":" · Restore only"));option.setTextSize(16);option.setTextColor(color("text"));option.setPadding(dp(12),dp(12),dp(12),dp(12));option.setMinHeight(dp(56));option.setBackground(shape("surface",18));option.setButtonTintList(ColorStateList.valueOf(color("primary")));option.setChecked(selectedParts.contains(key));option.setTag("part:"+key);partChecks.put(key,option);controls.add(option);add(content,option,4);
      option.setOnCheckedChangeListener((v,on)->{if(on){selectedParts.add(key);String other=key.equals("status")?"system":key.equals("system")?"status":"";CheckBox conflict=partChecks.get(other);if(conflict!=null)conflict.setChecked(false);}else selectedParts.remove(key);updatePrimary();});
    }
    if(choices.isEmpty())add(content,text("Import a theme or apply one to create a recovery backup.",15),16);
  }
  private ImageView previewImage(int index)throws Exception {ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setImageDrawable(android.graphics.drawable.Drawable.createFromPath(new File(ThemeImports.folder(this,theme.getString("id")),"preview-"+index+".png").getPath()));return image;}
  private void showPreviews(){
    if(busy||theme==null)return;try{HorizontalScrollView gallery=new HorizontalScrollView(this);gallery.setHorizontalScrollBarEnabled(false);LinearLayout images=new LinearLayout(this);images.setPadding(dp(12),dp(8),dp(12),dp(8));int height=Math.min(dp(420),Math.round(getResources().getDisplayMetrics().heightPixels*.55f));
      for(int i=0;i<theme.optInt("previews");i++){ImageView image=previewImage(i);image.setContentDescription("Theme preview "+(i+1));LinearLayout.LayoutParams cell=new LinearLayout.LayoutParams(Math.round(height*.55f),height);cell.setMarginEnd(dp(12));images.addView(image,cell);}gallery.addView(images);
      activeDialog=ToolUi.dialog(this).setTitle("Theme previews - swipe across").setView(gallery).setPositiveButton("Done",null).show();
    }catch(Exception error){status.setText("Could not open previews.");}
  }
  private void themeOptions(){if(busy||theme==null)return;activeDialog=ToolUi.dialog(this).setTitle("Theme options").setItems(new String[]{"Theme details","Remove imported theme"},(d,i)->{if(i==1)confirmRemove();else{ScrollView scroll=new ScrollView(this);TextView details=text(theme.optString("description","No extra details included."),15);details.setPadding(dp(20),dp(12),dp(20),dp(20));scroll.addView(details);activeDialog=ToolUi.dialog(this).setTitle("Theme details").setView(scroll).setPositiveButton("Done",null).show();}}).show();}
  private void confirmRestore(){
    if(busy||fullState==null||!fullState.optBoolean("active")||componentsOnly&&selectedParts.isEmpty())return;
    JSONArray chosen=componentsOnly?new JSONArray(selectedParts):null;List<String> names=new ArrayList<>();for(String key:selectedParts)names.add(ThemeParts.LABELS.get(key));
    activeDialog=ToolUi.dialog(this).setTitle(componentsOnly?"Restore selected components?":"Restore previous setup?").setMessage(componentsOnly?String.join("\n",names)+"\n\nReturn only these parts to the setup saved before your first Apply. Unselected parts stay applied. The remaining backup stays available.":"Return to the theme, wallpapers and icon setup saved before your first Apply.").setNegativeButton("Cancel",null).setPositiveButton("Restore",(d,w)->runFull("restore",chosen)).show();
  }
  private void confirmParts(){
    if(busy||theme==null||selectedParts.isEmpty())return;JSONArray chosen=new JSONArray();List<String> names=new ArrayList<>();for(String key:selectedParts){chosen.put(key);names.add(ThemeParts.LABELS.get(key));}
    activeDialog=ToolUi.dialog(this).setTitle("Apply selected components?").setMessage(String.join("\n",names)+"\n\nEverything else stays as it is. Restore selected returns only the checked parts to your saved setup.").setNegativeButton("Keep editing",null).setPositiveButton("Apply",(d,w)->runFull("apply",chosen)).show();
  }
  private void confirmFull(){
    if(busy||MainActivity.operationActive()||theme==null)return;
    activeDialog=ToolUi.dialog(this).setTitle("Apply full theme?").setMessage("The theme can change your wallpaper, lock screen, icons and included system styling. VFont keeps your previous setup for Restore. You can customize individual icons in App icons afterward. Applying another full theme starts with that theme's icons.")
        .setNegativeButton("Cancel",null).setPositiveButton("Apply",(d,w)->runFull("apply")).show();
  }
  private void runFull(String operation){
    runFull(operation,null);
  }
  private void runFull(String operation,JSONArray parts){
    if(busy||isDestroyed()||worker.isShutdown()||!MainActivity.beginOperation())return;
    String id=theme==null?null:theme.optString("id");setBusy(true,operation.equals("restore")?parts==null?"Restoring your previous setup…":"Restoring selected components…":"Backing up your setup and applying the theme…");getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    submit(()->{
      String message;boolean ok=false;String log=SupportLog.begin(this,"full_theme_"+operation);long started=android.os.SystemClock.uptimeMillis();
      try{
        JSONObject result=FullThemeJobs.run(this,operation,id,this::reportProgress,parts);SupportLog.result(this,log,result,android.os.SystemClock.uptimeMillis()-started);ok=result.optBoolean("ok");message=result.optString("message","Finished.");
        fullState=FullThemeJobs.run(this,"check",null);
      }catch(Exception error){ok=false;SupportLog.failure(this,log,error,"full-theme",android.os.SystemClock.uptimeMillis()-started);message=error.getMessage();try{fullState=FullThemeJobs.run(this,"check",null);}catch(Exception ignored){}
      }finally{MainActivity.endOperation();}
      String outcome=message;boolean success=ok;
      runOnUiThread(()->{if(!isDestroyed()){getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);showTheme();setBusy(false,outcome);activeDialog=ToolUi.dialog(this).setTitle(success?(operation.equals("restore")?parts==null?"Previous setup restored":"Selected components restored":"Theme applied"):"Theme needs attention").setMessage(outcome).setPositiveButton("Done",(d,w)->{if(themeChanged){themeChanged=false;recreate();}}).show();}});
    },()->{MainActivity.endOperation();getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);setBusy(false,"");});
  }
  private void pick(){if(busy||MainActivity.operationActive())return;startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),PICK);}
  private void confirmRemove(){
    if(busy||MainActivity.operationActive())return;
    activeDialog=ToolUi.dialog(this).setTitle("Remove imported theme?").setMessage("The original file and applied icons stay as they are. You can restore original icons from App icons. Saved individual theme-icon choices are kept.")
        .setNegativeButton("Cancel",null).setPositiveButton("Remove",(d,w)->{
          if(isDestroyed()||worker.isShutdown()||!MainActivity.beginOperation())return;
          setBusy(true,"Removing imported theme…");
          submit(()->{try{ThemeImports.clear(this);runOnUiThread(()->{if(!isDestroyed()){theme=null;showTheme();setBusy(false,"");}});}catch(Exception error){runOnUiThread(()->{if(!isDestroyed())setBusy(false,error.getMessage());});}finally{MainActivity.endOperation();}},()->{MainActivity.endOperation();setBusy(false,"");});
        }).show();
  }
  @Override protected void onActivityResult(int request,int result,Intent data){
    super.onActivityResult(request,result,data);if(request!=PICK||result!=RESULT_OK||data==null||data.getData()==null)return;
    if(busy||isDestroyed()||worker.isShutdown()||!MainActivity.beginOperation()){status.setText("An operation is still running. Try again when it finishes.");return;}
    setBusy(true,"Reading theme and preparing previews…");getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    submit(()->{String message="";try{JSONObject imported=ThemeImports.importTheme(this,data.getData(),this::reportProgress);theme=imported;selectedParts.clear();availableParts=componentsOnly?ThemeParts.available(ThemeImports.archive(this,imported.getString("id"))):new JSONArray();refreshParts();}
      catch(Throwable error){message="Theme was not imported: "+(error.getMessage()==null?"Unsupported or damaged file":error.getMessage());}
      finally{MainActivity.endOperation();String outcome=message;runOnUiThread(()->{if(!isDestroyed()){getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);showTheme();setBusy(false,outcome);if(themeChanged)recreate();}});}},()->{MainActivity.endOperation();getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);setBusy(false,"");});
  }
  @Override protected void onResume(){super.onResume();connectionHandler.post(connectionCheck);}
  @Override protected void onPause(){connectionHandler.removeCallbacks(connectionCheck);super.onPause();}
  @Override protected void onSaveInstanceState(Bundle state){state.putStringArrayList("parts",new ArrayList<>(selectedParts));super.onSaveInstanceState(state);}
  @Override public void onConfigurationChanged(Configuration configuration){super.onConfigurationChanged(configuration);if(busy)themeChanged=true;else recreate();}
  @Override protected void onDestroy(){connectionHandler.removeCallbacks(connectionCheck);ToolUi.closeHelp(this);if(activeDialog!=null&&activeDialog.isShowing())activeDialog.dismiss();worker.shutdown();super.onDestroy();}
}
