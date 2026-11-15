package dev.fontlab;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.ComponentName;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.*;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native icon manager using the same wallpaper palette as the Rust UI. */
public final class IconsActivity extends Activity {
  private static final int IMPORT_IMAGE=42;
  private final ExecutorService worker=Executors.newSingleThreadExecutor();
  private final List<View> controls=new ArrayList<>();
  private List<IconPacks.Pack> packs=new ArrayList<>();
  private List<IconPacks.Entry> entries=new ArrayList<>(),visible=new ArrayList<>();
  private IconPacks.Pack pack;
  private TextView status,count,packHint,note,intro;
  private EditText search;
  private ListView list;
  private BaseAdapter adapter;
  private static final int FILTER_MATCHING=0,FILTER_PACK=1,FILTER_SELECTED=2,FILTER_UNMATCHED=3,FILTER_ALL=4;
  private static final String[] FILTER_NAMES={"Matching","Pack","Selected","No match","All"};
  private final Button[] filterButtons=new Button[FILTER_NAMES.length];
  private Button applyButton,sourceButton,selectShownButton,clearShownButton,selectionOptionsButton;
  private int filterMode=FILTER_MATCHING;
  private LinearLayout packCard,selection,actions;
  private View trialNotice,bottomNavigation;
  private AlertDialog activeDialog;
  private boolean busy,loading=true,importing,themeChanged,batchMode,keyboardVisible;
  private boolean initialCheckPending=true;
  private List<IconPacks.Entry> pendingApply;
  private ArrayList<String> pendingComponents;
  private String pendingPack;
  private int loadGeneration;
  private int suggestionGeneration;
  private java.util.concurrent.Future<?> suggestionTask;
  private JSONObject colors,compatibility;
  private String query="",pendingImage,resumeEditor;

  private void setStatus(CharSequence message){status.setText(message);status.setVisibility(message.length()==0?View.GONE:View.VISIBLE);}
  private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
  private int color(String name){return (int)colors.optLong(name);}
  private GradientDrawable shape(String name,int radius){
    GradientDrawable background=new GradientDrawable();background.setColor(color(name));background.setCornerRadius(dp(radius));return background;
  }
  private void surface(View view,String name,int radius){
    view.setBackground(new RippleDrawable(ColorStateList.valueOf((color("primary")&0xffffff)|0x20000000),shape(name,radius),shape(name,radius)));
  }
  private TextView text(String value,int size){
    TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(color("text"));return view;
  }
  private LinearLayout column(){LinearLayout view=new LinearLayout(this);view.setOrientation(LinearLayout.VERTICAL);return view;}
  private LinearLayout row(){LinearLayout view=new LinearLayout(this);view.setGravity(Gravity.CENTER_VERTICAL);return view;}
  private Button button(String label,boolean primary,Runnable action){
    Button b=new Button(this);b.setText(label);b.setTextSize(14);b.setAllCaps(false);b.setTypeface(null,Typeface.BOLD);
    b.setTextColor(color(primary?"on_primary":"on_secondary"));b.setPadding(dp(16),dp(10),dp(16),dp(10));b.setMinHeight(dp(48));b.setStateListAnimator(null);b.setElevation(0);
    b.setMinimumWidth(0);b.setMinWidth(0);surface(b,primary?"primary":"secondary",24);ToolUi.buttonStyle(b,colors,primary);b.setOnClickListener(v->action.run());ToolUi.bind(b,this,batchMode?"icon_packs":"icons");ToolUi.press(b);controls.add(b);return b;
  }
  private Button chip(String label,Runnable action){
    Button b=button(label,false,action);b.setTextSize(13);b.setMinHeight(dp(40));b.setPadding(dp(12),dp(6),dp(12),dp(6));b.setSingleLine(true);b.setEllipsize(android.text.TextUtils.TruncateAt.END);return b;
  }
  private Button dialogButton(String label,boolean primary,Runnable action){Button b=button(label,primary,action);controls.remove(b);return b;}
  private EditText searchField(String hint){
    EditText field=new EditText(this);field.setSingleLine(true);field.setHint(hint);field.setTextColor(color("text"));field.setHintTextColor(color("muted"));
    field.setTextSize(16);field.setPadding(dp(20),dp(12),dp(20),dp(12));field.setMinHeight(dp(52));field.setBackground(shape("surface",26));field.setBackgroundTintList(null);return field;
  }
  private void pair(LinearLayout row,View first,View second){
    LinearLayout.LayoutParams left=new LinearLayout.LayoutParams(0,-2,1);left.setMarginEnd(dp(12));row.addView(first,left);
    row.addView(second,new LinearLayout.LayoutParams(0,-2,1));
  }
  private void add(LinearLayout root,View view,int height,int top){
    if(height>0&&(view instanceof TextView||view instanceof LinearLayout)){view.setMinimumHeight(dp(height));height=-2;}
    LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,height<0?height:dp(height));params.topMargin=dp(top);root.addView(view,params);
  }
  @Override public void onCreate(Bundle saved){
    super.onCreate(saved);colors=MainActivity.palette(this);try{colors.put("background",ToolUi.background(colors)&0xffffffffL);}catch(Exception impossible){throw new AssertionError(impossible);}MainActivity.styleSystemBars(getWindow(),colors);
    batchMode=getIntent().getBooleanExtra("batch",false);
    if(saved!=null){filterMode=saved.getInt("filter_mode",saved.getBoolean("matches_only",true)?FILTER_MATCHING:FILTER_ALL);query=saved.getString("query","");pendingImage=saved.getString("image_app");batchMode=saved.getBoolean("batch");resumeEditor=saved.getString("editor");pendingComponents=saved.getStringArrayList("pending_apply");pendingPack=saved.getString("pending_pack");}
    if(pendingComponents==null&&hasPendingTrial(this)){pendingComponents=new ArrayList<>(getPreferences(MODE_PRIVATE).getStringSet("pending_apply",Set.of()));pendingPack=getPreferences(MODE_PRIVATE).getString("pending_pack","");batchMode=getPreferences(MODE_PRIVATE).getBoolean("pending_batch",false);}
    LinearLayout root=column();root.setBackgroundColor(color("background"));
    LinearLayout header=column();
    // Leave room for matches and actions when the keyboard or large text reduces space.
    ScrollView headerScroll=new ScrollView(this){
      @Override protected void onMeasure(int width,int height){
        int available=View.MeasureSpec.getSize(height);
        if(available>0)height=View.MeasureSpec.makeMeasureSpec(available/2,View.MeasureSpec.AT_MOST);
        super.onMeasure(width,height);
      }
    };
    headerScroll.setVerticalScrollBarEnabled(false);headerScroll.addView(header);
    root.addView(headerScroll,new LinearLayout.LayoutParams(-1,-2));
    root.setOnApplyWindowInsetsListener((v,insets)->{
      android.graphics.Insets safe=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout()|android.view.WindowInsets.Type.ime());
      root.setPadding(dp(20)+safe.left,dp(8)+safe.top,dp(20)+safe.right,dp(12)+safe.bottom);
      boolean shown=insets.isVisible(android.view.WindowInsets.Type.ime());if(keyboardVisible!=shown){keyboardVisible=shown;updateMode();}return insets;
    });
    LinearLayout title=row();TextView heading=text(batchMode?"Icon packs":"App icons",26);heading.setTypeface(null,Typeface.BOLD);title.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
    android.widget.ImageButton more=ToolUi.iconButton(this,colors,R.drawable.vf_rounded_settings_24,"Icon options and help",this::showTools);controls.add(more);title.addView(more,new LinearLayout.LayoutParams(dp(48),dp(48)));add(header,title,-2,0);
    intro=text("",14);intro.setVisibility(View.GONE);
    Button recover=button("Finish your icon test",false,this::showTrialResult);trialNotice=recover;add(header,recover,48,8);
    packCard=column();
    sourceButton=button("Choose icon pack",false,this::chooseSource);sourceButton.setTag("choose-icon-source");add(packCard,sourceButton,52,0);add(header,packCard,-2,8);
    packHint=text("",12);
    status=text("Loading your apps…",13);status.setTextColor(color("muted"));status.setPadding(dp(4),0,dp(4),0);add(header,status,-2,8);
    search=searchField("Find an app");search.setText(query);controls.add(search);add(root,search,52,14);
    search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){} public void onTextChanged(CharSequence s,int st,int b,int c){query=s.toString().toLowerCase(Locale.ROOT);filter();}public void afterTextChanged(Editable e){}});
    count=text("Choose an app below",13);count.setTypeface(null,Typeface.BOLD);add(root,count,-2,12);
    selection=column();
    HorizontalScrollView filterScroll=new HorizontalScrollView(this);filterScroll.setHorizontalScrollBarEnabled(false);
    LinearLayout filterRow=row();
    for(int i=0;i<FILTER_NAMES.length;i++){
      int mode=i;Button b=chip(FILTER_NAMES[i],()->setFilter(mode));
      b.setTag("filter:"+FILTER_NAMES[i].toLowerCase(Locale.ROOT).replace(' ','-'));
      LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);if(i>0)lp.setMarginStart(dp(6));
      filterRow.addView(b,lp);filterButtons[i]=b;
    }
    filterScroll.addView(filterRow,new ViewGroup.LayoutParams(-2,-2));
    selection.addView(filterScroll,new LinearLayout.LayoutParams(-1,-2));
    HorizontalScrollView actionScroll=new HorizontalScrollView(this);actionScroll.setHorizontalScrollBarEnabled(false);actionScroll.setFillViewport(true);
    LinearLayout selectRow=row();
    selectShownButton=chip("Select shown",()->select(true));selectShownButton.setTag("select-shown");
    clearShownButton=chip("Clear shown",()->select(false));clearShownButton.setTag("clear-shown");
    selectionOptionsButton=chip("Select options",this::showSelectionOptions);selectionOptionsButton.setTag("selection-options");
    Button[] rowActions={selectShownButton,clearShownButton,selectionOptionsButton};
    for(int i=0;i<rowActions.length;i++){
      LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);if(i>0)lp.setMarginStart(dp(8));
      selectRow.addView(rowActions[i],lp);
    }
    actionScroll.addView(selectRow,new ViewGroup.LayoutParams(-1,-2));
    LinearLayout.LayoutParams actionParams=new LinearLayout.LayoutParams(-1,-2);actionParams.topMargin=dp(8);
    selection.addView(actionScroll,actionParams);
    add(root,selection,-2,8);
    list=new ListView(this);list.setDivider(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));list.setDividerHeight(dp(8));list.setVerticalScrollBarEnabled(false);list.setSelector(android.R.color.transparent);
    TextView empty=text("No matching apps",15);empty.setGravity(Gravity.CENTER);empty.setTextColor(color("muted"));
    FrameLayout listArea=new FrameLayout(this);listArea.addView(list,new FrameLayout.LayoutParams(-1,-1));listArea.addView(empty,new FrameLayout.LayoutParams(-1,-1));list.setEmptyView(empty);
    adapter=new BaseAdapter(){
      public int getCount(){return visible.size();}public Object getItem(int i){return visible.get(i);}public long getItemId(int i){return i;}
      public View getView(int i,View old,ViewGroup parent){
        LinearLayout row;ImageView icon;TextView label,source;CheckBox check;android.widget.ImageButton edit;
        if(old==null){
          row=row();row.setPadding(dp(12),dp(8),dp(12),dp(8));row.setMinimumHeight(dp(64));
          icon=new ImageView(IconsActivity.this);row.addView(icon,new LinearLayout.LayoutParams(dp(48),dp(48)));
          LinearLayout labels=column();labels.setPadding(dp(14),0,dp(8),0);label=text("",16);label.setMaxLines(2);source=text("",12);source.setTextColor(color("muted"));source.setMaxLines(2);labels.addView(label);labels.addView(source);row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
          check=new CheckBox(IconsActivity.this);check.setMinimumWidth(dp(48));check.setMinimumHeight(dp(48));
          check.setButtonTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{color("primary"),color("muted")}));row.addView(check);edit=ToolUi.iconButton(IconsActivity.this,colors,R.drawable.vf_rounded_brush_24,"Customize icon",()->{});row.addView(edit,new LinearLayout.LayoutParams(dp(48),dp(48)));row.setTag(new Object[]{icon,label,source,check,edit});
        }else{row=(LinearLayout)old;Object[] views=(Object[])row.getTag();icon=(ImageView)views[0];label=(TextView)views[1];source=(TextView)views[2];check=(CheckBox)views[3];edit=(android.widget.ImageButton)views[4];}
        IconPacks.Entry item=visible.get(i);label.setText(item.label);check.setChecked(item.selected);check.setVisibility(batchMode?View.VISIBLE:View.GONE);edit.setVisibility(batchMode?View.VISIBLE:View.GONE);edit.setContentDescription("Edit icon for "+item.label);edit.setOnClickListener(v->showAppEditor(item));surface(row,batchMode&&item.selected?"secondary":"surface",20);
        row.setContentDescription((batchMode?"Select ":"Customize ")+item.label);check.setContentDescription("Select "+item.label);check.setEnabled(!busy&&!loading&&!importing);
        source.setText(!item.supported?"This app can't be changed yet":batchMode?sourceLabel(item):"Tap to choose a new icon");
        try{icon.setImageDrawable(batchMode?IconChoices.drawable(IconsActivity.this,item,pack):getPackageManager().getActivityIcon(item.component));}catch(Exception e){
          if(batchMode&&IconChoices.ready(IconsActivity.this,item))source.setText("Choose this icon again");
          try{icon.setImageDrawable(getPackageManager().getActivityIcon(item.component));}catch(Exception ignored){icon.setImageDrawable(null);}
        }
        check.setOnClickListener(v->{if(!busy&&!loading&&!importing){
          if(check.isChecked()&&!IconChoices.ready(IconsActivity.this,item)){check.setChecked(false);showAppEditor(item);return;}
          item.selected=check.isChecked();rememberSelection();filter();
        }});
        row.setOnClickListener(v->{if(!busy&&!loading&&!importing){if(batchMode){if(!IconChoices.ready(IconsActivity.this,item)){showAppEditor(item);return;}item.selected=!item.selected;rememberSelection();filter();}else showAppEditor(item);}});return row;
      }
    };list.setAdapter(adapter);LinearLayout.LayoutParams listParams=new LinearLayout.LayoutParams(-1,0,1);listParams.topMargin=dp(12);listParams.bottomMargin=dp(4);root.addView(listArea,listParams);
    actions=column();applyButton=button("Review selected apps",true,()->showReview(selectedEntries()));add(actions,applyButton,52,0);add(root,actions,-2,10);
    note=text("",12);note.setVisibility(View.GONE);
    bottomNavigation=ToolUi.navigation(this,colors,batchMode?"Icon packs":"App icons",()->{if(!busy)finish();},batchMode?"icon_packs":"icons");root.addView(bottomNavigation);
    setContentView(root);root.requestApplyInsets();updateMode();updateControls();
    if(android.os.Build.VERSION.SDK_INT>=33)getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,()->{if(!busy)finish();});
    submitTask(()->{try{
      JSONObject previous=null;File receipt=new File(getFilesDir(),"last-icons.json");
      if(receipt.isFile() && receipt.length()<256*1024)try{
        previous=new JSONObject(new String(java.nio.file.Files.readAllBytes(receipt.toPath()),StandardCharsets.UTF_8)).optJSONObject("compatibility");
        if(previous!=null&&!previous.optBoolean("pending_trial")&&!android.os.Build.FINGERPRINT.equals(previous.optString("software_fingerprint")))previous=null;
      }catch(Exception ignored){}
      JSONObject cached=previous;List<IconPacks.Pack> found=IconPacks.installed(this);
      runOnUiThread(()->{if(isDestroyed())return;compatibility=cached;showPacks(found);updateControls();});
    }catch(Exception e){runOnUiThread(()->{if(!isDestroyed()){loading=false;showError(e);updateControls();}});}},()->{loading=false;updateControls();});
  }
  @Override public void onBackPressed(){if(!busy)super.onBackPressed();}
  private boolean submitTask(Runnable task,Runnable onRejected){
    if(isDestroyed()||worker.isShutdown()){if(onRejected!=null)onRejected.run();return false;}
    try{worker.execute(task);return true;}catch(java.util.concurrent.RejectedExecutionException rejected){if(onRejected!=null)onRejected.run();return false;}
  }
  private void showPacks(List<IconPacks.Pack> found){
    if(isDestroyed())return;packs=new ArrayList<>(found);packs.add(new IconPacks.Pack("No automatic pack", ""));
    String last=getIntent().getStringExtra("theme");
    if(last==null)last=getPreferences(MODE_PRIVATE).getString("pack","");else last="theme:"+last;
    getIntent().removeExtra("theme");
    int initial=0;for(int i=0;i<packs.size();i++)if(packs.get(i).pkg.equals(last))initial=i;
    load(packs.get(initial));
  }
  private void load(IconPacks.Pack selected){
    if(busy)return;if(pack!=null&&!pack.pkg.equals(selected.pkg))clearPending();pack=selected;loading=true;setStatus("Loading your apps…");entries=new ArrayList<>();filter();updateControls();
    int generation=++loadGeneration;
    submitTask(()->{try{
      List<IconPacks.Entry> found=IconPacks.entries(this,selected);
      Set<String> saved=getPreferences(MODE_PRIVATE).getStringSet("selected:"+selected.pkg,new HashSet<>());
      for(IconPacks.Entry entry:found)entry.selected=saved.contains(entry.component.flattenToString());
      int matches=0;for(IconPacks.Entry entry:found)if(entry.hasArtwork())matches++;int matched=matches;
      runOnUiThread(()->{if(isDestroyed()||generation!=loadGeneration)return;entries=found;loading=false;if(pendingComponents!=null){if(selected.pkg.equals(pendingPack)){pendingApply=new ArrayList<>();for(IconPacks.Entry entry:found)if(pendingComponents.contains(entry.component.flattenToString()))pendingApply.add(entry);if(pendingApply.isEmpty())clearPending();}else clearPending();pendingComponents=null;}getPreferences(MODE_PRIVATE).edit().putString("pack",selected.pkg).apply();setStatus("");sourceButton.setText(selected.pkg.isEmpty()?"Choose icon pack":selected.name);packHint.setText(selected.pkg.isEmpty()?"Choose artwork from an app preview.":matched+" matching icons. Custom choices are kept.");filter();updateControls();if(themeChanged&&!importing)recreate();else if(resumeEditor!=null&&!importing){IconPacks.Entry edit=findEntry(resumeEditor);resumeEditor=null;if(edit!=null)showAppEditor(edit);}else {checkOnOpen();resumeTrial();}});
    }catch(Exception e){runOnUiThread(()->{if(!isDestroyed()&&generation==loadGeneration){loading=false;setStatus("Could not read this pack: "+e.getMessage());updateControls();if(themeChanged&&!importing)recreate();}});}},()->{loading=false;updateControls();});
  }
  private boolean usesPack(IconPacks.Entry entry){
    if(pack==null||pack.pkg.isEmpty())return false;
    JSONObject choice=IconChoices.get(this,entry.component);
    if(choice.has("photo")||choice.has("app"))return false;
    if(choice.has("theme"))return pack.pkg.equals("theme:"+choice.optString("theme"));
    if(choice.has("pack"))return pack.pkg.equals(choice.optString("pack"));
    return entry.hasArtwork();
  }
  private boolean matchesFilter(IconPacks.Entry entry,int mode){
    if(!batchMode||mode==FILTER_ALL)return true;
    if(mode==FILTER_MATCHING)return IconChoices.ready(this,entry);
    if(mode==FILTER_PACK)return entry.supported&&usesPack(entry);
    if(mode==FILTER_SELECTED)return entry.selected;
    return entry.supported&&!IconChoices.ready(this,entry);
  }
  private void setFilter(int mode){if(busy||loading||importing)return;filterMode=mode;filter();}
  private void filter(){
    visible=new ArrayList<>();for(IconPacks.Entry item:entries)if(matchesFilter(item,filterMode)&&(query.isEmpty()||item.label.toLowerCase(Locale.ROOT).contains(query)||item.component.getPackageName().toLowerCase(Locale.ROOT).contains(query)))visible.add(item);
    if(adapter!=null)adapter.notifyDataSetChanged();updateCount();
  }
  private List<IconPacks.Entry> selectedEntries(){List<IconPacks.Entry> selected=new ArrayList<>();for(IconPacks.Entry e:entries)if(e.selected)selected.add(e);return selected;}
  private IconPacks.Entry findEntry(String component){for(IconPacks.Entry e:entries)if(e.component.flattenToString().equals(component))return e;return null;}
  private String sourceLabel(IconPacks.Entry entry){
    JSONObject choice=IconChoices.get(this,entry.component);
    if(choice.has("photo"))return "Your photo";if(choice.has("app"))return "Another app's icon";
    if(choice.has("theme")||!choice.has("pack")&&!entry.themeId.isEmpty()){
      String key="theme:"+choice.optString("theme",entry.themeId);for(IconPacks.Pack p:packs)if(p.pkg.equals(key))return p.name;return "Saved theme icon";
    }
    String pkg=choice.optString("pack",pack==null?"":pack.pkg);
    if(choice.has("pack")||entry.drawable!=0){for(IconPacks.Pack p:packs)if(p.pkg.equals(pkg))return p.name;return "Saved pack icon";}
    return "No matching icon · Tap to choose";
  }
  private void chooseSource(){
    if(busy||loading||importing||packs.isEmpty())return;
    AlertDialog d=dialog("Choose icon source","");LinearLayout content=dialogContent();
    for(int i=0;i<packs.size();i++){IconPacks.Pack item=packs.get(i);add(content,dialogButton(item.name,item==pack,()->{d.dismiss();if(pack!=item)load(item);}),52,i==0?4:10);}
    ScrollView scroll=new ScrollView(this);scroll.addView(content);d.setView(scroll);d.setButton(-2,"Cancel",(v,w)->{});d.show();
  }
  private void updateMode(){packCard.setVisibility(batchMode&&!keyboardVisible?View.VISIBLE:View.GONE);selection.setVisibility(batchMode&&!keyboardVisible?View.VISIBLE:View.GONE);actions.setVisibility(batchMode?View.VISIBLE:View.GONE);if(bottomNavigation!=null)bottomNavigation.setVisibility(keyboardVisible?View.GONE:View.VISIBLE);}
  private void updateCount(){
    if(count==null)return;
    int[] totals=new int[FILTER_NAMES.length];
    for(IconPacks.Entry e:entries)for(int m=0;m<FILTER_NAMES.length;m++)if(matchesFilter(e,m))totals[m]++;
    for(int m=0;m<FILTER_NAMES.length;m++)if(filterButtons[m]!=null){
      filterButtons[m].setText(FILTER_NAMES[m]+" ("+totals[m]+")");
      ToolUi.buttonStyle(filterButtons[m],colors,filterMode==m);
    }
    int selected=totals[FILTER_SELECTED];String apps=visible.size()+(visible.size()==1?" app":" apps");count.setText(batchMode?selected+" selected · "+apps+" shown":query.isEmpty()?apps:apps+" found");
    boolean readyVisible=false,selectedVisible=false;
    for(IconPacks.Entry e:visible){readyVisible|=IconChoices.ready(this,e);selectedVisible|=e.selected;}
    boolean interactive=!busy&&!loading&&!importing;
    if(selectShownButton!=null)selectShownButton.setEnabled(interactive&&readyVisible);
    if(clearShownButton!=null)clearShownButton.setEnabled(interactive&&selectedVisible);
    if(applyButton!=null){applyButton.setText(selected==0?"Choose apps to continue":"Review "+selected+(selected==1?" app":" apps"));applyButton.setEnabled(interactive&&selected>0);}
  }
  private void rememberSelection(){
    if(pack==null||loading)return;Set<String> selected=new HashSet<>();for(IconPacks.Entry e:entries)if(e.selected)selected.add(e.component.flattenToString());
    getPreferences(MODE_PRIVATE).edit().putStringSet("selected:"+pack.pkg,selected).apply();
  }
  private void applySelection(java.util.function.Predicate<IconPacks.Entry> next){
    if(busy||loading||importing)return;
    for(IconPacks.Entry e:entries)e.selected=next.test(e)&&IconChoices.ready(this,e);
    rememberSelection();filter();setStatus("");
  }
  private void select(boolean value){
    Set<IconPacks.Entry> shown=new HashSet<>(visible);
    applySelection(e->shown.contains(e)?value:e.selected);
  }
  private void usePackForShown(){
    if(busy||loading||importing||pack==null||pack.pkg.isEmpty())return;
    if(visible.isEmpty()){setStatus("No apps are shown. Choose another filter first.");return;}
    IconPacks.Pack source=pack;List<IconPacks.Entry> targets=new ArrayList<>(visible);
    loading=true;updateControls();int generation=loadGeneration,request=++suggestionGeneration;
    AlertDialog progress=dialog("Finding pack icons","Preparing suggestions from "+source.name+".");
    progress.setButton(-2,"Cancel",(v,w)->cancelSuggestions(request));progress.setOnCancelListener(v->cancelSuggestions(request));progress.show();
    if(isDestroyed()||worker.isShutdown()){progress.dismiss();loading=false;updateControls();return;}
    try{
    suggestionTask=worker.submit(()->{
      try{
        Map<IconPacks.Entry,JSONObject> choices=new LinkedHashMap<>();List<IconPacks.Entry> unmatched=new ArrayList<>();
        for(IconPacks.Entry e:targets)if(e.supported){if(e.hasArtwork())choices.put(e,packChoice(source,e.drawableName));else unmatched.add(e);}
        if(!unmatched.isEmpty()){
          IconSuggestions suggestions=new IconSuggestions(IconPacks.catalog(this,source));
          for(IconPacks.Entry e:unmatched){String name=suggestions.suggest(e.label,e.component.getPackageName());if(name!=null)choices.put(e,packChoice(source,name));}
        }
        runOnUiThread(()->{
          if(isDestroyed()||generation!=loadGeneration||request!=suggestionGeneration)return;
          suggestionTask=null;progress.dismiss();loading=false;updateControls();setStatus("");
          if(!unmatched.isEmpty())showPackSuggestions(source,targets,unmatched,choices,new HashSet<>(choices.keySet()));
          else if(!choices.isEmpty())savePackChoices(source,targets,choices);
          else setStatus("No supported apps are shown.");
        });
      }catch(Exception e){
        runOnUiThread(()->{if(!isDestroyed()&&request==suggestionGeneration){suggestionTask=null;progress.dismiss();loading=false;showError(e);updateControls();}});
      }
    });
    }catch(java.util.concurrent.RejectedExecutionException rejected){progress.dismiss();loading=false;updateControls();}
  }
  private void cancelSuggestions(int request){
    if(request!=suggestionGeneration)return;++suggestionGeneration;
    if(suggestionTask!=null){suggestionTask.cancel(true);suggestionTask=null;}
    loading=false;setStatus("");updateControls();
  }
  private JSONObject packChoice(IconPacks.Pack source,String name)throws Exception{
    return ThemeImports.pack(source.pkg)?new JSONObject().put("theme",source.pkg.substring(6)).put("icon",name):new JSONObject().put("pack",source.pkg).put("drawable",name);
  }
  private void showPackSuggestions(IconPacks.Pack source,List<IconPacks.Entry> targets,List<IconPacks.Entry> unmatched,Map<IconPacks.Entry,JSONObject> choices,Set<IconPacks.Entry> accepted){
    AlertDialog d=dialog("Review suggested icons","");LinearLayout content=dialogContent();
    int matched=(int)targets.stream().filter(e->e.supported&&e.hasArtwork()).count();
    TextView summary=text(matched+" matches · "+unmatched.size()+" apps to review\nTap an app to choose a different icon.",14);summary.setTextColor(color("muted"));add(content,summary,-2,0);
    ListView suggestions=new ListView(this);suggestions.setDivider(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));suggestions.setDividerHeight(dp(8));
    suggestions.setAdapter(new BaseAdapter(){
      public int getCount(){return unmatched.size();}public Object getItem(int i){return unmatched.get(i);}public long getItemId(int i){return i;}
      public View getView(int i,View old,ViewGroup parent){
        LinearLayout item;ImageView previous,proposed;TextView name,hint;CheckBox check;
        if(old==null){
          item=row();item.setLayoutParams(new AbsListView.LayoutParams(-1,-2));item.setPadding(dp(12),dp(12),dp(8),dp(12));surface(item,"surface",16);
          previous=new ImageView(IconsActivity.this);item.addView(previous,new LinearLayout.LayoutParams(dp(40),dp(40)));
          LinearLayout labels=column();labels.setPadding(dp(12),0,dp(8),0);name=text("",15);name.setMaxLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);labels.addView(name);
          hint=text("",12);hint.setMaxLines(2);hint.setEllipsize(android.text.TextUtils.TruncateAt.END);hint.setTextColor(color("muted"));labels.addView(hint);item.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
          proposed=new ImageView(IconsActivity.this);item.addView(proposed,new LinearLayout.LayoutParams(dp(40),dp(40)));
          check=new CheckBox(IconsActivity.this);check.setButtonTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{color("primary"),color("muted")}));
          item.addView(check,new LinearLayout.LayoutParams(dp(48),dp(48)));
          labels.setTag(new Object[]{previous,name,hint,proposed,check});
        }else{
          item=(LinearLayout)old;Object[] views=(Object[])item.getChildAt(1).getTag();
          previous=(ImageView)views[0];name=(TextView)views[1];hint=(TextView)views[2];proposed=(ImageView)views[3];check=(CheckBox)views[4];
        }
        IconPacks.Entry entry=unmatched.get(i);JSONObject choice=choices.get(entry);
        item.setTag("suggestion:"+entry.component.flattenToString());
        previous.setContentDescription(entry.label+" previous choice");
        try{previous.setImageDrawable(IconChoices.drawable(IconsActivity.this,entry,source));}catch(Exception e){try{previous.setImageDrawable(getPackageManager().getActivityIcon(entry.component));}catch(Exception ignored){previous.setImageDrawable(null);}}
        name.setText(entry.label);
        hint.setText(choice==null?"Choose an icon":choice.optString("drawable",choice.optString("icon")).replace('_',' '));
        proposed.setContentDescription(entry.label+" suggested icon");
        if(choice!=null)try{if(ThemeImports.pack(source.pkg))proposed.setImageDrawable(ThemeImports.drawable(IconsActivity.this,source.pkg.substring(6),choice.getString("icon")));else{Resources res=getPackageManager().getResourcesForApplication(source.pkg);proposed.setImageDrawable(res.getDrawable(res.getIdentifier(choice.getString("drawable"),"drawable",source.pkg),null));}}catch(Exception ignored){proposed.setImageResource(android.R.drawable.ic_menu_help);}
        else proposed.setImageDrawable(null);
        check.setContentDescription("Use suggested icon for "+entry.label);check.setChecked(accepted.contains(entry));check.setEnabled(choice!=null);
        check.setOnClickListener(v->{if(check.isChecked())accepted.add(entry);else accepted.remove(entry);d.getButton(-1).setText("Use "+accepted.size()+" pack icons");d.getButton(-1).setEnabled(!accepted.isEmpty());});
        item.setOnClickListener(v->{d.dismiss();showIconPicker(entry,source,chosen->{choices.put(entry,chosen);accepted.add(entry);showPackSuggestions(source,targets,unmatched,choices,accepted);},()->showPackSuggestions(source,targets,unmatched,choices,accepted));});return item;
      }
    });
    add(content,suggestions,Math.min(360,Math.max(112,unmatched.size()*84)),12);d.setView(content);
    d.setButton(-1,"Use "+accepted.size()+" pack icons",(v,w)->{Map<IconPacks.Entry,JSONObject> chosen=new LinkedHashMap<>();for(IconPacks.Entry e:accepted)chosen.put(e,choices.get(e));savePackChoices(source,targets,chosen);});
    d.setButton(-2,"Cancel",(v,w)->{});d.show();d.getButton(-1).setEnabled(!accepted.isEmpty());fitListDialog(d,content,suggestions);
  }
  private void savePackChoices(IconPacks.Pack source,List<IconPacks.Entry> targets,Map<IconPacks.Entry,JSONObject> choices){
    loading=true;setStatus("Saving "+source.name+" choices…");updateControls();int generation=loadGeneration;
    submitTask(()->{try{
      Map<ComponentName,JSONObject> saved=new LinkedHashMap<>();for(var item:choices.entrySet())saved.put(item.getKey().component,item.getValue());IconChoices.saveAll(this,saved);
      runOnUiThread(()->{if(isDestroyed()||generation!=loadGeneration)return;loading=false;Set<IconPacks.Entry> shown=new HashSet<>(targets);applySelection(e->shown.contains(e)?choices.containsKey(e):e.selected);updateControls();int skipped=targets.size()-choices.size();setStatus("Chosen "+choices.size()+" "+source.name+" icons."+(skipped>0?" "+skipped+" shown apps left unselected.":"")+" Review before applying.");});
    }catch(Exception e){runOnUiThread(()->{if(!isDestroyed()){loading=false;showError(e);updateControls();}});}},()->{loading=false;updateControls();});
  }
  private void showSelectionOptions(){
    if(busy||loading||importing||entries.isEmpty())return;
    Set<IconPacks.Entry> shown=new HashSet<>(visible);
    List<String> labels=new ArrayList<>();List<Runnable> tasks=new ArrayList<>();
    if(pack!=null&&!pack.pkg.isEmpty()){
      labels.add("Use "+pack.name+" icons for shown");tasks.add(this::usePackForShown);
    }
    labels.add("Select all matching");tasks.add(()->applySelection(e->true));
    if(pack!=null&&!pack.pkg.isEmpty()){labels.add("Select "+pack.name+" matches only");tasks.add(()->applySelection(this::usesPack));}
    labels.add("Select custom overrides");tasks.add(()->applySelection(e->IconChoices.get(this,e.component).length()!=0));
    labels.add("Invert shown apps");tasks.add(()->applySelection(e->shown.contains(e)?!e.selected:e.selected));
    labels.add("Clear hidden selections");tasks.add(()->applySelection(e->shown.contains(e)&&e.selected));
    labels.add("Clear all selections");tasks.add(()->applySelection(e->false));
    options("Selection options",labels,i->tasks.get(i).run());
  }
  private void updateControls(){
    for(View view:controls)view.setEnabled(!busy&&!importing);
    boolean interactive=!busy&&!loading&&!importing;
    sourceButton.setEnabled(interactive&&!packs.isEmpty());search.setEnabled(interactive);
    for(Button b:filterButtons)if(b!=null)b.setEnabled(interactive);
    if(selectionOptionsButton!=null)selectionOptionsButton.setEnabled(interactive&&!entries.isEmpty());
    updateCount();status.setVisibility(status.getText().length()==0?View.GONE:View.VISIBLE);
    trialNotice.setVisibility(compatibility!=null&&compatibility.optBoolean("pending_trial")?View.VISIBLE:View.GONE);
    for(View view:controls)view.setAlpha(1);
    if(adapter!=null)adapter.notifyDataSetChanged();
  }
  private AlertDialog dialog(String title,String message){
    if(activeDialog!=null)activeDialog.dismiss();
    TextView heading=text(title,20);heading.setTypeface(null,Typeface.BOLD);heading.setPadding(dp(20),dp(20),dp(20),dp(12));
    TextView body=text(message,14);body.setPadding(dp(20),0,dp(20),dp(16));
    ScrollView scroll=new ScrollView(this);scroll.addView(body);
    AlertDialog d=ToolUi.dialog(this).setCustomTitle(heading).setView(scroll).create();
    activeDialog=d;d.setOnDismissListener(v->{if(activeDialog==d)activeDialog=null;});
    d.setOnShowListener(v->{
      d.getWindow().setBackgroundDrawable(shape("elevated",28));
      d.getWindow().setLayout(Math.min(dp(560),getResources().getDisplayMetrics().widthPixels-dp(32)),WindowManager.LayoutParams.WRAP_CONTENT);
      List<Button> buttons=new ArrayList<>();for(int which:new int[]{-1,-3,-2}){Button b=d.getButton(which);if(b!=null&&b.getVisibility()==View.VISIBLE)buttons.add(b);}
      if(!buttons.isEmpty()&&buttons.get(0).getParent() instanceof ViewGroup bar){
        bar.removeAllViews();
        LinearLayout footer=column();footer.setBaselineAligned(false);footer.setGravity(Gravity.FILL_HORIZONTAL);footer.setMinimumHeight(0);footer.setPadding(dp(20),dp(12),dp(20),dp(16));footer.setTag("icon-dialog-actions");
        for(int i=0;i<buttons.size();i++){
          Button b=buttons.get(i);ToolUi.buttonStyle(b,colors,b==d.getButton(-1));b.setAllCaps(false);b.setTextSize(14);b.setMinimumWidth(0);b.setMinWidth(0);b.setMinHeight(dp(48));b.setPadding(dp(16),dp(10),dp(16),dp(10));
          LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);if(i>0)p.topMargin=dp(8);footer.addView(b,p);
        }
        if(bar.getParent() instanceof ViewGroup panel){
          panel.setPadding(0,0,0,0);panel.setMinimumHeight(0);if(panel instanceof ScrollView sv)sv.setFillViewport(true);
          int idx=panel.indexOfChild(bar);panel.removeView(bar);panel.addView(footer,idx,new ViewGroup.LayoutParams(-1,-2));
        }else{
          bar.setPadding(0,0,0,0);bar.setMinimumHeight(0);bar.addView(footer,new ViewGroup.LayoutParams(-1,-2));
        }
      }
    });return d;
  }
  private void showTools(){
    options("Icon options",List.of("How to change icons","Selection options","Restore original icons","Device support & report","Live Calendar (unavailable)"),index->{
      if(index==0){AlertDialog d=dialog("Make it yours","One app\nChoose an app, pick an icon from a pack, a photo, or another app. Check the preview, then tap Apply.\n\nIcon pack\nChoose a pack, use the filter chips (Matching, Pack, Selected, No match, All) and Select shown / Clear shown / Select options, then Review before applying.\n\nYou can restore your original icons at any time from an app's preview or Icon options.");d.setButton(-1,"Got it",(v,w)->{});d.show();}
      else if(index==1)showSelectionOptions();else if(index==2)showRestore();else if(index==3)startActivity(new Intent(this,SupportActivity.class));else run("calendar-check",false,false,List.of());
    });
  }
  private void showCalendar(JSONObject calendar) {
    boolean managed=calendar.optBoolean("managed");
    String message=calendar.optString("reason",CalendarIcons.APPLY_BLOCKED);
    if(managed)message+="\n\nThe saved skin uses about "+calendar.optLong("storage_mb")+" MB of phone storage. Restore removes the copy and reloads your home screen to recover the original icon style.";
    AlertDialog d=dialog("Live Vivo Calendar",message);
    if(managed)d.setButton(-1,"Restore Calendar",(v,w)->run("calendar-reset",false,false,List.of()));
    d.setButton(-2,"Close",(v,w)->{});d.show();
  }
  private View preview(IconPacks.Entry entry){
    LinearLayout previews=row();previews.setGravity(Gravity.CENTER);previews.setPadding(0,dp(8),0,dp(12));
    for(boolean original:new boolean[]{true,false}){
      LinearLayout cell=column();cell.setGravity(Gravity.CENTER);ImageView image=new ImageView(this);image.setContentDescription(original?"App original icon":"New icon preview");
      try{image.setImageDrawable(original?getPackageManager().getActivityIcon(entry.component):IconChoices.drawable(this,entry,pack));}catch(Exception ignored){image.setImageResource(android.R.drawable.ic_menu_help);}
      cell.addView(image,new LinearLayout.LayoutParams(dp(76),dp(76)));TextView label=text(original?"App original":"New icon",12);label.setGravity(Gravity.CENTER);label.setTextColor(color("muted"));label.setPadding(0,dp(8),0,0);cell.addView(label);
      previews.addView(cell,new LinearLayout.LayoutParams(0,-2,1));if(original){TextView arrow=text("→",24);arrow.setTextColor(color("muted"));previews.addView(arrow);}
    }
    return previews;
  }
  private LinearLayout dialogContent(){LinearLayout content=column();content.setPadding(dp(20),0,dp(20),0);return content;}
  private boolean usingFullTheme(){return compatibility!=null&&compatibility.optBoolean("full_theme_icons",compatibility.optBoolean("full_theme_active"));}
  private void showAppEditor(IconPacks.Entry entry){
    if(isDestroyed()||isFinishing()||busy||loading||importing)return;
    android.view.WindowInsetsController controller=getWindow().getDecorView().getWindowInsetsController();if(controller!=null)controller.hide(android.view.WindowInsets.Type.ime());search.clearFocus();
    boolean available=IconChoices.ready(this,entry);if(available)try{IconChoices.drawable(this,entry,pack);}catch(Exception e){available=false;}
    AlertDialog d=dialog(entry.label,"");LinearLayout content=dialogContent();content.addView(preview(entry));
    TextView description=text(available?sourceLabel(entry):entry.supported?"Choose artwork for this app.":"This app does not support native icon refresh.",14);description.setTextColor(color("muted"));add(content,description,-2,0);
    if(available)add(content,dialogButton("Change artwork",false,()->{d.dismiss();chooseArtwork(entry);}),48,12);
    if(IconChoices.get(this,entry.component).length()!=0&&entry.hasArtwork())add(content,dialogButton("Use "+pack.name+" match",false,()->saveChoice(entry,new JSONObject())),48,8);
    ScrollView scroll=new ScrollView(this);scroll.addView(content);d.setView(scroll);
    boolean ready=available;d.setButton(-1,ready?"Apply icon":"Choose artwork",(v,w)->{if(ready)run("apply",false,false,List.of(entry));else chooseArtwork(entry);});d.setButton(-2,"Back",(v,w)->{});
    d.setButton(-3,usingFullTheme()?"Use theme icon":"Restore",(v,w)->confirmRestore(List.of(entry),false));d.show();d.getButton(-1).setEnabled(entry.supported);
  }
  private void chooseArtwork(IconPacks.Entry entry){
    AlertDialog d=dialog("Artwork for "+entry.label,"");LinearLayout content=dialogContent();
    String[] labels={"Icon pack or theme","Image from your files","Another app's icon"};
    Runnable[] actions={
      ()->pickPack(entry),
      ()->{pendingImage=entry.component.flattenToString();try{startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"),IMPORT_IMAGE);}catch(Exception error){pendingImage=null;showError(error);}},
      ()->showIconPicker(entry,null)
    };
    for(int i=0;i<labels.length;i++){int index=i;add(content,dialogButton(labels[i],false,()->{d.dismiss();actions[index].run();}),52,i==0?4:10);}
    ScrollView scroll=new ScrollView(this);scroll.addView(content);d.setView(scroll);d.setButton(-2,"Back",(v,w)->showAppEditor(entry));d.setOnCancelListener(v->showAppEditor(entry));d.show();
  }
  private void pickPack(IconPacks.Entry entry){
    List<IconPacks.Pack> available=new ArrayList<>();List<String> names=new ArrayList<>();
    for(IconPacks.Pack p:packs)if(!p.pkg.isEmpty()){available.add(p);names.add(p.name);}
    if(available.isEmpty()){
      AlertDialog d=dialog("Add an icon pack","Install an Android icon pack, then reopen App icons. You can also use a photo or another app's icon right now.");d.setButton(-1,"Back to "+entry.label,(v,w)->showAppEditor(entry));d.show();return;
    }
    if(available.size()==1)showIconPicker(entry,available.get(0));else options("Choose an icon pack",names,i->showIconPicker(entry,available.get(i)));
  }
  private void showReview(List<IconPacks.Entry> selected){
    if(busy||loading||importing||selected.isEmpty())return;
    List<IconPacks.Entry> targets=new ArrayList<>(selected);AlertDialog d=dialog("Review your icons","");
    LinearLayout content=dialogContent();TextView summary=text(targets.size()+(targets.size()==1?" app selected":" apps selected")+" · Original → New",14);summary.setTextColor(color("muted"));add(content,summary,-2,0);
    ListView review=new ListView(this);review.setDivider(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));review.setDividerHeight(dp(8));
    review.setAdapter(new BaseAdapter(){
      public int getCount(){return targets.size();}public Object getItem(int i){return targets.get(i);}public long getItemId(int i){return i;}
      public View getView(int i,View old,ViewGroup parent){
        LinearLayout row;ImageView original,chosen;TextView label;
        if(old==null){row=row();row.setLayoutParams(new AbsListView.LayoutParams(-1,-2));row.setPadding(dp(12),dp(12),dp(12),dp(12));row.setMinimumHeight(dp(72));surface(row,"surface",16);original=new ImageView(IconsActivity.this);row.addView(original,new LinearLayout.LayoutParams(dp(40),dp(40)));label=text("",15);label.setMaxLines(2);label.setEllipsize(android.text.TextUtils.TruncateAt.END);label.setPadding(dp(12),0,dp(8),0);row.addView(label,new LinearLayout.LayoutParams(0,-2,1));TextView arrow=text("→",16);arrow.setTextColor(color("muted"));arrow.setGravity(Gravity.CENTER);row.addView(arrow,new LinearLayout.LayoutParams(dp(24),-2));chosen=new ImageView(IconsActivity.this);row.addView(chosen,new LinearLayout.LayoutParams(dp(40),dp(40)));row.setTag(new Object[]{original,label,chosen});}
        else{row=(LinearLayout)old;Object[] views=(Object[])row.getTag();original=(ImageView)views[0];label=(TextView)views[1];chosen=(ImageView)views[2];}
        IconPacks.Entry entry=targets.get(i);label.setText(entry.label);original.setContentDescription(entry.label+" original icon");chosen.setContentDescription(entry.label+" new icon");
        try{original.setImageDrawable(getPackageManager().getActivityIcon(entry.component));}catch(Exception e){original.setImageDrawable(null);}
        try{chosen.setImageDrawable(IconChoices.drawable(IconsActivity.this,entry,pack));}catch(Exception e){chosen.setImageResource(android.R.drawable.ic_menu_help);label.setText(entry.label+" · Choose an icon first");}
        return row;
      }
    });add(content,review,Math.min(360,Math.max(112,targets.size()*84)),12);d.setView(content);d.setButton(-1,"Apply "+targets.size()+(targets.size()==1?" icon":" icons"),(v,w)->run("apply",false,false,targets));d.setButton(-2,"Keep editing",(v,w)->{});d.show();fitListDialog(d,content,review);
  }
  private void options(String title,List<String> labels,java.util.function.IntConsumer action){
    AlertDialog d=dialog(title,"");LinearLayout content=dialogContent();
    for(int i=0;i<labels.size();i++){int index=i;add(content,dialogButton(labels.get(i),false,()->{d.dismiss();action.accept(index);}),52,i==0?4:10);}
    ScrollView scroll=new ScrollView(this);scroll.addView(content);d.setView(scroll);d.setButton(-2,"Cancel",(v,w)->{});d.show();
  }
  private void choiceChanged(ComponentName component){
    for(IconPacks.Entry entry:entries)if(entry.component.equals(component))entry.selected=IconChoices.ready(this,entry);
    rememberSelection();filter();setStatus("Your icon choice is saved. Apply it from the preview.");updateControls();
  }
  private void saveChoice(IconPacks.Entry entry,JSONObject choice){
    try{IconChoices.save(this,entry.component,choice);choiceChanged(entry.component);showAppEditor(entry);}catch(Exception e){showError(e);}
  }
  private void fitListDialog(AlertDialog d,View content,View area){
    d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);View decor=d.getWindow().getDecorView();int maximum=area.getLayoutParams().height;
    android.view.ViewTreeObserver.OnGlobalLayoutListener listener=()->{
      if(decor.getHeight()<=0||area.getHeight()<=0)return;
      android.graphics.Rect frame=new android.graphics.Rect();decor.getWindowVisibleDisplayFrame(frame);
      if(frame.height()<=0)return;
      int height=Math.min(maximum,Math.max(dp(96),frame.height()-(decor.getHeight()-area.getHeight())-dp(24)));
      if(area.getLayoutParams().height!=height){area.getLayoutParams().height=height;area.requestLayout();}
    };
    content.getViewTreeObserver().addOnGlobalLayoutListener(listener);
    content.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
      public void onViewAttachedToWindow(View v){}
      public void onViewDetachedFromWindow(View v){v.getViewTreeObserver().removeOnGlobalLayoutListener(listener);}
    });
  }
  private void showIconPicker(IconPacks.Entry target,IconPacks.Pack sourcePack){
    showIconPicker(target,sourcePack,choice->saveChoice(target,choice),()->showAppEditor(target));
  }
  private void showIconPicker(IconPacks.Entry target,IconPacks.Pack sourcePack,java.util.function.Consumer<JSONObject> choose,Runnable back){
    AlertDialog d=dialog("Choose an icon for "+target.label,"");
    LinearLayout content=column();content.setPadding(dp(20),0,dp(20),0);
    TextView caption=text(sourcePack==null?"Copy an icon from an installed app.":sourcePack.name,13);caption.setTextColor(color("muted"));add(content,caption,-2,0);
    EditText find=searchField(sourcePack==null?"Search apps":"Search icons");add(content,find,52,12);
    FrameLayout area=new FrameLayout(this);GridView icons=new GridView(this);icons.setNumColumns(GridView.AUTO_FIT);icons.setColumnWidth(dp(96));icons.setHorizontalSpacing(dp(12));icons.setVerticalSpacing(dp(12));icons.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);icons.setSelector(android.R.color.transparent);
    TextView empty=text("Loading icons…",14);empty.setGravity(Gravity.CENTER);area.addView(icons,new FrameLayout.LayoutParams(-1,-1));area.addView(empty,new FrameLayout.LayoutParams(-1,-1));icons.setEmptyView(empty);add(content,area,360,12);
    d.setView(content);d.setButton(-2,"Back",(v,w)->back.run());d.setOnCancelListener(v->back.run());d.show();fitListDialog(d,content,area);
    submitTask(()->{try{
      List<String> keys=new ArrayList<>();java.util.Map<String,String> labels=new java.util.HashMap<>();
      boolean theme=sourcePack!=null&&ThemeImports.pack(sourcePack.pkg);
      Resources resources=sourcePack==null||theme?null:getPackageManager().getResourcesForApplication(sourcePack.pkg);
      if(sourcePack==null)for(IconPacks.Entry entry:IconPacks.entries(this,new IconPacks.Pack("",""))){String key=entry.component.flattenToString();keys.add(key);labels.put(key,entry.label);}
      else for(String name:IconPacks.catalog(this,sourcePack)){keys.add(name);labels.put(name,name.replace('_',' '));}
      runOnUiThread(()->{
        if(isDestroyed()||!d.isShowing())return;
        List<String> shown=new ArrayList<>(keys);
        BaseAdapter choices=new BaseAdapter(){
          public int getCount(){return shown.size();}public Object getItem(int i){return shown.get(i);}public long getItemId(int i){return i;}
          public View getView(int i,View old,ViewGroup parent){
            LinearLayout row;ImageView image;TextView label;
            if(old==null){row=column();row.setGravity(Gravity.CENTER);row.setPadding(dp(8),dp(16),dp(8),dp(12));image=new ImageView(IconsActivity.this);row.addView(image,new LinearLayout.LayoutParams(dp(56),dp(56)));label=text("",12);label.setPadding(0,dp(8),0,0);label.setGravity(Gravity.CENTER);label.setLines(2);label.setEllipsize(android.text.TextUtils.TruncateAt.END);row.addView(label,new LinearLayout.LayoutParams(-1,-2));surface(row,"surface",20);row.setTag(new Object[]{image,label});}
            else{row=(LinearLayout)old;Object[] views=(Object[])row.getTag();image=(ImageView)views[0];label=(TextView)views[1];}
            String key=shown.get(i);label.setText(labels.get(key));row.setContentDescription(labels.get(key));
            try{image.setImageDrawable(sourcePack==null?getPackageManager().getActivityIcon(ComponentName.unflattenFromString(key)):theme?ThemeImports.drawable(IconsActivity.this,sourcePack.pkg.substring(6),key):resources.getDrawable(resources.getIdentifier(key,"drawable",sourcePack.pkg),null));}catch(Exception e){image.setImageDrawable(null);}
            return row;
          }
        };
        Runnable filter=()->{String query=find.getText().toString().toLowerCase(Locale.ROOT);shown.clear();for(String key:keys)if(labels.get(key).toLowerCase(Locale.ROOT).contains(query)||key.toLowerCase(Locale.ROOT).contains(query))shown.add(key);empty.setText("No icons found");choices.notifyDataSetChanged();};
        find.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void afterTextChanged(Editable e){}public void onTextChanged(CharSequence s,int st,int b,int c){filter.run();}});
        icons.setAdapter(choices);filter.run();
        icons.setOnItemClickListener((parent,view,index,id)->{try{
          String key=shown.get(index);JSONObject choice=sourcePack==null?new JSONObject().put("app",key):theme?new JSONObject().put("theme",sourcePack.pkg.substring(6)).put("icon",key):new JSONObject().put("pack",sourcePack.pkg).put("drawable",key);
          d.dismiss();choose.accept(choice);
        }catch(Exception e){showError(e);}});
      });
    }catch(Exception e){runOnUiThread(()->{if(!isDestroyed()&&d.isShowing())empty.setText("Could not load icons: "+e.getMessage());});}},()->{if(!isDestroyed()&&d.isShowing())empty.setText("Icon picker is unavailable.");});
  }
  private void showRestore(){
    if(busy)return;List<IconPacks.Entry> selected=selectedEntries();List<String> options=new ArrayList<>();if(!selected.isEmpty())options.add("Selected apps ("+selected.size()+")");options.add("All icons changed with VFont");
    if(usingFullTheme())options.set(options.size()-1,"All icon overrides for this theme");
    options(usingFullTheme()?"Use theme icons":"Restore original icons",options,index->confirmRestore(selected,selected.isEmpty()||index==1));
  }
  private void confirmRestore(List<IconPacks.Entry> targets,boolean all){
    AlertDialog d=dialog(usingFullTheme()?"Use the current theme's icons?":all?"Restore all original icons?":targets.size()==1?"Restore "+targets.get(0).label+"?":"Restore "+targets.size()+" apps?",usingFullTheme()?"This removes the chosen individual icon overrides. Your full theme stays applied.":all?"This restores the saved originals for every icon changed with VFont.":"This restores the original icons saved by VFont for these apps. Your other apps stay as they are.");
    d.setButton(-1,usingFullTheme()?"Use theme icons":"Restore originals",(v,w)->run("reset",all,false,targets));d.setButton(-2,"Keep current icons",(v,w)->{});d.show();
  }
  private void showCompatibility(){
    StringBuilder checks=new StringBuilder();JSONArray failures=compatibility==null?null:compatibility.optJSONArray("failures");
    if(failures==null||failures.length()==0)checks.append(compatibility==null?"Phone check has not run yet.":"Device checks passed.");
    else for(int i=0;i<failures.length();i++){if(i>0)checks.append('\n');checks.append(failures.optString(i));}
    if(busy)return;String details=compatibility==null?"VFont checks your phone before applying icons. A check can also finish an interrupted icon operation.":
        (compatibility.optBoolean("can_apply")?"Your phone can apply and restore icons.":compatibility.optBoolean("capable")?"Your phone needs a quick VFont icon test before applying other icons.":"VFont cannot apply icons on this phone yet.")+"\n\nTechnical details\n"+
        "Model: "+compatibility.optString("model")+"\nBuild: "+compatibility.optString("build")+"\nMethod: "+(compatibility.optString("backend").equals("theme")?"Icon theme":"Fast native icons")+"\n"+
        "Border adjustment: "+(compatibility.optBoolean("corner_adjustment")?"verified mask only":"off")+"\n"+
        checks+"\n\n"+
        "Save a report to share manually. It contains software versions, checks and hashes. No serial number, account, app list or font contents.";
    AlertDialog d=dialog("Device support",details);d.setButton(-1,"Check phone",(v,w)->run("check",false,false));
    d.setButton(-3,"Support report",(v,w)->startActivity(new Intent(this,SupportActivity.class)));d.setButton(-2,"Close",(v,w)->{});d.show();
  }
  @Override protected void onActivityResult(int request,int result,Intent data){
    super.onActivityResult(request,result,data);
    if(request==IMPORT_IMAGE){
      String target=pendingImage;pendingImage=null;
      if(target==null)return;resumeEditor=target;
      if(result!=RESULT_OK||data==null||data.getData()==null){if(!loading){IconPacks.Entry entry=findEntry(target);resumeEditor=null;if(entry!=null)showAppEditor(entry);}return;}
      ComponentName component=ComponentName.unflattenFromString(target);if(component==null)return;
      importing=true;setStatus("Importing image…");updateControls();
      submitTask(()->{try{IconChoices.importPhoto(this,component,data.getData());runOnUiThread(()->{if(!isDestroyed())choiceChanged(component);});}
        catch(Exception e){showError(e);}finally{runOnUiThread(()->{if(!isDestroyed()){importing=false;updateControls();if(themeChanged&&!loading)recreate();else if(!loading){IconPacks.Entry entry=findEntry(target);resumeEditor=null;if(entry!=null)showAppEditor(entry);}}});}},()->{importing=false;updateControls();});return;
    }

  }
  static boolean hasPendingTrial(android.content.Context context){return context.getSharedPreferences("IconsActivity",0).contains("pending_apply");}
  private void clearPending(){pendingApply=null;pendingComponents=null;pendingPack=null;getPreferences(MODE_PRIVATE).edit().remove("pending_apply").remove("pending_pack").remove("pending_batch").commit();}
  private void rememberPending(List<IconPacks.Entry> targets){
    pendingApply=new ArrayList<>(targets);Set<String> components=new HashSet<>();for(IconPacks.Entry entry:targets)components.add(entry.component.flattenToString());
    if(!getPreferences(MODE_PRIVATE).edit().putStringSet("pending_apply",components).putString("pending_pack",pack.pkg).putBoolean("pending_batch",batchMode).commit()){pendingApply=null;throw new IllegalStateException("Could not retain the requested icon change");}
  }
  private void startTrial(){
    AlertDialog d=dialog("One quick test first","Before changing your apps, VFont needs to check how icons look on this phone. It will give its own icon a blue background and a white V. Check that it appears, your other icons look right and an existing folder still opens. Then return here to restore it.");
    d.setButton(-1,"Test VFont's icon",(v,w)->run("trial",false,false));d.setButton(-2,"Not now",(v,w)->clearPending());d.setOnCancelListener(v->clearPending());d.show();
  }
  private void showTrialResult(){
    if(isDestroyed())return;AlertDialog d=dialog("Restore the trial icon","Did the blue V appear correctly, with other icons and folders unchanged? Both options restore the original. Confirming the result enables experimental support for this software and icon style.");
    d.setButton(-1,pendingApply==null?"Looks right — restore":"Looks right — apply my icons",(v,w)->run("confirm",false,true));d.setButton(-3,"Restore only",(v,w)->{clearPending();run("confirm",false,false);});d.setButton(-2,"Check home screen",(v,w)->startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)));d.show();
  }
  private void resumeTrial(){if(!busy&&!loading&&!importing&&compatibility!=null&&compatibility.optBoolean("pending_trial")&&(activeDialog==null||!activeDialog.isShowing()))showTrialResult();}
  private void showError(Throwable error){runOnUiThread(()->{if(!isDestroyed())setStatus(error.getMessage()==null?error.toString():error.getMessage());});}
  private Bitmap trialBitmap(){
    Bitmap image=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(image);canvas.drawColor(Color.rgb(25,105,210));
    Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(Color.WHITE);paint.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));paint.setTextSize(170);paint.setTextAlign(Paint.Align.CENTER);canvas.drawText("V",128,190,paint);return image;
  }
  private void run(String operation,boolean all,boolean confirmed){
    run(operation,all,confirmed,selectedEntries());
  }
  private void run(String operation,boolean all,boolean confirmed,List<IconPacks.Entry> targets){
    if(busy||loading||importing)return;IFontService service=MainActivity.iconsService();
    if(MainActivity.operationActive()){setStatus("An operation is still running. Wait for it to finish.");return;}
    if(service==null){AlertDialog d=dialog("Connect VFont first","Open Connection and recovery > Connection and checks to connect Shizuku. Your icon choices are saved.");d.setButton(-1,"Back to tools",(v,w)->finish());d.setButton(-2,"Stay here",(v,w)->{});d.show();return;}
    if(operation.equals("apply")&&(compatibility==null||!compatibility.optBoolean("can_apply"))){
      rememberPending(targets);
      if(compatibility==null){run("check",false,false);}
      else if(compatibility.optBoolean("pending_trial"))showTrialResult();else if(compatibility.optBoolean("capable"))startTrial();else {clearPending();showCompatibility();}return;
    }
    if(operation.equals("apply"))clearPending();
    List<IconPacks.Entry> selected=new ArrayList<>(targets);
    if(!all&&(operation.equals("apply")||operation.equals("reset"))&&selected.isEmpty()){setStatus("Select at least one app.");return;}
    if(operation.equals("apply"))for(IconPacks.Entry entry:selected){
      if(!entry.supported){setStatus("VFont can't safely reload "+entry.label+"'s icon yet. Clear its checkbox to continue.");return;}
      if(!IconChoices.ready(this,entry)){setStatus("Choose an icon for "+entry.label+" first.");return;}
    }
    if(!MainActivity.beginOperation())return;
    rememberSelection();busy=true;setStatus(operation.endsWith("check")?"Checking your phone. This only needs a moment…":operation.equals("calendar-apply")?"Preparing the live Calendar skin…":operation.equals("reset")&&usingFullTheme()?"Restoring theme icons…":operation.equals("reset")||operation.equals("calendar-reset")?"Restoring original icons…":"Applying your icons…");updateControls();getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    try {
    worker.execute(()->{
      String supportId=SupportLog.begin(this,operation.startsWith("calendar-")?operation.replace('-','_'):"icons_"+operation);
      long supportStarted=android.os.SystemClock.uptimeMillis();
      File prepared=null;int images=0;boolean dispatched=false;
      boolean checked=false;JSONObject completed=null;
      try{
        String tx=UUID.randomUUID().toString().replace("-","");File folder=new File(new File(getExternalFilesDir(null),"icons"),"job-"+tx);
        if(!folder.mkdirs())throw new IllegalStateException("Could not prepare icon job");prepared=folder;JSONArray apps=new JSONArray();
        int total=operation.equals("trial")?1:(!all&&(operation.equals("apply")||operation.equals("reset"))?selected.size():0);
        for(int i=0;i<total;i++){
          boolean trial=operation.equals("trial");IconPacks.Entry item=trial?null:selected.get(i);
          JSONObject app=new JSONObject().put("package",trial?"dev.fontlab":item.component.getPackageName()).put("class",trial?"dev.fontlab.MainActivity":item.component.getClassName());
          if(operation.equals("apply")||trial){
            Bitmap image=trial?trialBitmap():IconChoices.bitmap(this,item,pack);File file=new File(folder,i+".png");
            images++;
            try(FileOutputStream out=new FileOutputStream(file)){if(!image.compress(Bitmap.CompressFormat.PNG,100,out))throw new IllegalStateException("Could not prepare icon");out.getFD().sync();}finally{image.recycle();}
            app.put("sha256",IconSystemTask.hash(file));
          }apps.put(app);
        }
        JSONObject request=new JSONObject().put("tx",tx).put("operation",operation).put("all",all).put("visible",confirmed).put("apps",apps);
        IconSystemTask.writeJson(new File(folder,"request.json"),request);String hash=IconSystemTask.hash(new File(folder,"request.json"));
        if(!operation.equals("check"))runOnUiThread(()->{if(!isDestroyed())startActivity(new Intent(this,ApplyActivity.class).putExtra("icons",true).putExtra("operation",operation));});
        dispatched=true;String response=service.icons(tx,hash);JSONObject result=new JSONObject(response);completed=result;
        SupportLog.result(this,supportId,result,android.os.SystemClock.uptimeMillis()-supportStarted);
        IconSystemTask.writeJson(new File(getFilesDir(),"last-icons.json"),result);
        JSONObject report=result.optJSONObject("compatibility");
        checked=operation.equals("check")&&result.optBoolean("ok");
        runOnUiThread(()->{if(!isDestroyed()){if(report!=null)compatibility=report;setStatus(operation.equals("check")&&result.optBoolean("ok")?"":result.optString("message","Finished."));updateControls();if(operation.equals("calendar-check")&&result.optBoolean("ok"))showCalendar(result.optJSONObject("calendar"));else if(!operation.equals("check")&&!(result.optBoolean("ok")&&(operation.equals("trial")||operation.equals("confirm")&&confirmed&&pendingApply!=null&&report!=null&&report.optBoolean("can_apply")))){AlertDialog d=dialog(result.optBoolean("ok")?operation.equals("reset")&&result.optInt("changed")==0?"Restore complete":operation.equals("calendar-apply")?"Live Calendar applied":(operation.equals("reset")||operation.equals("confirm"))&&usingFullTheme()?"Theme icons restored":operation.equals("reset")||operation.equals("confirm")||operation.equals("calendar-reset")?"Original icons restored":"Your icons are applied":"Some icons need attention",result.optString("message","Finished."));d.setButton(-1,"Done",(v,w)->{if(themeChanged&&!busy){themeChanged=false;recreate();}});d.setOnCancelListener(v->{if(themeChanged&&!busy){themeChanged=false;recreate();}});d.show();}}});
      }catch(Throwable e){SupportLog.failure(this,supportId,e,dispatched?"dispatch":"staging",android.os.SystemClock.uptimeMillis()-supportStarted);showError(e);}finally{
        if(prepared!=null&&(!dispatched||new File(prepared,"result.json").isFile()))for(int i=0;i<images;i++)new File(prepared,i+".png").delete();
        boolean checkSucceeded=checked;JSONObject finished=completed;
        runOnUiThread(()->{ApplyActivity.finishOperation();busy=false;MainActivity.endOperation();if(!isDestroyed()){
          getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);updateControls();
          if(operation.equals("check")&&pendingApply!=null){List<IconPacks.Entry> retry=pendingApply;if(checkSucceeded)run("apply",false,false,retry);else clearPending();}
          else if(finished!=null&&finished.optBoolean("ok")&&operation.equals("trial"))showTrialResult();
          else if(operation.equals("confirm")){List<IconPacks.Entry> retry=pendingApply;clearPending();if(confirmed&&retry!=null&&finished!=null&&finished.optBoolean("ok")&&compatibility!=null&&compatibility.optBoolean("can_apply"))run("apply",false,false,retry);}
          else if(operation.equals("check")&&checkSucceeded)resumeTrial();
          if(finished==null||!finished.optBoolean("ok"))clearPending();
          if(themeChanged&&!busy&&(activeDialog==null||!activeDialog.isShowing())){themeChanged=false;recreate();}
        }});
      }
    });
    }catch(RuntimeException error){busy=false;MainActivity.endOperation();getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);updateControls();showError(error);}
  }
  @Override public void onConfigurationChanged(Configuration config){super.onConfigurationChanged(config);if(busy||loading||importing)themeChanged=true;else recreate();}
  private void checkOnOpen(){
    if(!initialCheckPending||loading||busy||importing||MainActivity.operationActive()||MainActivity.iconsService()==null)return;
    initialCheckPending=false;
    run("check",false,false);
  }
  private void refreshPacksIfChanged(){
    if(busy||loading||importing||packs.isEmpty())return;
    submitTask(()->{try{
      List<IconPacks.Pack> installed=IconPacks.installed(this);
      runOnUiThread(()->{
        if(isDestroyed()||busy||loading||importing||packs.isEmpty())return;
        List<String> current=new ArrayList<>();for(int i=0;i<packs.size()-1;i++)current.add(packs.get(i).pkg);
        List<String> next=new ArrayList<>();for(IconPacks.Pack p:installed)next.add(p.pkg);
        if(!current.equals(next))showPacks(installed);
      });
    }catch(Exception ignored){}},null);
  }
  @Override protected void onResume(){super.onResume();if(adapter!=null){adapter.notifyDataSetChanged();refreshPacksIfChanged();checkOnOpen();getWindow().getDecorView().post(this::resumeTrial);}}
  @Override protected void onSaveInstanceState(Bundle state){state.putInt("filter_mode",filterMode);state.putString("query",query);state.putString("image_app",pendingImage);state.putBoolean("batch",batchMode);state.putString("editor",resumeEditor);if(pendingApply!=null){ArrayList<String> names=new ArrayList<>();for(IconPacks.Entry entry:pendingApply)names.add(entry.component.flattenToString());state.putStringArrayList("pending_apply",names);state.putString("pending_pack",pack.pkg);}super.onSaveInstanceState(state);}
  @Override protected void onDestroy(){++suggestionGeneration;if(suggestionTask!=null)suggestionTask.cancel(true);ToolUi.closeHelp(this);if(activeDialog!=null&&activeDialog.isShowing())activeDialog.dismiss();worker.shutdown();super.onDestroy();}
}
