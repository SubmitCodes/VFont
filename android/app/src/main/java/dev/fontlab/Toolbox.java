package dev.fontlab;

import android.app.*;
import android.content.*;
import android.graphics.Typeface;
import android.os.Build;
import android.text.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import org.json.JSONObject;

/** Category-based shell. NativeActivity and its authenticated service remain the same owner. */
final class Toolbox {
  final Activity owner;
  final MainActivity main;
  final FrameLayout view,pageHost;
  private final JSONObject palette;
  private String page="home",returnPage="home",query="";
  private LinearLayout results;
  private EditText search;
  private TextView connectionStatus,accessStatus,connectAction,openShizukuAction;
  private String message="Connect Shizuku to check native access.";
  private boolean verified,ready,busy,lastError;
  Toolbox(Activity owner,MainActivity main,String route,String savedQuery){
    this.owner=owner;this.main=main;palette=MainActivity.palette(owner);view=new FrameLayout(owner);view.setBackgroundColor(ToolUi.background(palette));view.setTag("toolbox");pageHost=new FrameLayout(owner);view.addView(pageHost,new FrameLayout.LayoutParams(-1,-1));
    view.setOnApplyWindowInsetsListener((v,insets)->{if(Build.VERSION.SDK_INT>=30){android.graphics.Insets safe=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());view.setPadding(safe.left,safe.top,safe.right,safe.bottom);}return insets;});
    query=savedQuery==null?"":savedQuery;navigate(validPage(route)?route:"home",false);view.requestApplyInsets();
  }
  private static boolean validPage(String route){return route!=null&&(List.of("home","appearance","settings","connection","recovery","about","search").contains(route)||ToolCatalog.CATEGORIES.stream().anyMatch(c->route.equals("category:"+c.id())));}
  String page(){return page;}
  String query(){return query;}
  private int dp(float value){return ToolUi.dp(owner,value);}
  private TextView text(String value,int size){return ToolUi.text(owner,palette,value,size);}
  private LinearLayout column(){return ToolUi.column(owner);}
  void notice(String message,boolean error,Boolean ready,boolean busy,JSONObject result){
    this.message=message;this.lastError=error;this.busy=busy;if(ready!=null)this.ready=ready;
    if(result!=null&&result.has("command_verified"))verified=result.optBoolean("command_verified");
    refreshStatus();
  }
  void refreshStatus(){
    boolean connected=MainActivity.iconsService()!=null;
    boolean installed=main.isShizukuInstalled();
    if(connectionStatus!=null)connectionStatus.setText(connected?"Shizuku is connected":main.shizukuSummary());
    if(connectAction!=null)connectAction.setVisibility(connected?View.GONE:View.VISIBLE);
    if(openShizukuAction!=null)openShizukuAction.setVisibility(!connected&&installed?View.VISIBLE:View.GONE);
    if(accessStatus!=null){
      String statusText;
      if(lastError&&message!=null&&!message.isBlank())statusText=message;
      else if(verified)statusText="Your successful native-access check is remembered for this software.";
      else if(connected)statusText=message==null||message.isBlank()?"A native-access check is available for this software.":message;
      else if(message!=null&&!message.isBlank()&&!"Connect Shizuku to check native access.".equals(message))statusText=message;
      else statusText="Connect to check native access. Importing and previews still work.";
      accessStatus.setText(statusText);
      accessStatus.setTextColor(ToolUi.color(palette,lastError?"error":"text"));
    }
  }
  private boolean blocked(){if(main.uiBusy()){Toast.makeText(owner,"Wait for the current operation to finish.",Toast.LENGTH_SHORT).show();return true;}return false;}
  boolean back(){
    if(ToolUi.closeHelp(owner))return true;
    if(blocked())return true;
    if(page.equals("search")){
      if(search!=null&&Build.VERSION.SDK_INT>=30&&search.getRootWindowInsets()!=null&&search.getRootWindowInsets().isVisible(WindowInsets.Type.ime())){hideKeyboard();search.clearFocus();return true;}
      navigate(returnPage,false);return true;
    }
    if(!page.equals("home")){navigate("home",true);return true;}
    return false;
  }
  void navigate(String route,boolean animate){
    if(!validPage(route))throw new IllegalArgumentException("Unknown toolbox page");
    if(route.equals("search")&&!page.equals("search"))returnPage=page;
    hideKeyboard();page=route;connectionStatus=null;accessStatus=null;connectAction=null;openShizukuAction=null;search=null;
    LinearLayout content=column();content.setPadding(dp(16),dp(page.equals("home")?0:20),dp(16),dp(110));
    ScrollView scroll=new ScrollView(owner);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);scroll.setClipToPadding(false);scroll.addView(content);scroll.setTag("page:"+page);
    FrameLayout stage=new FrameLayout(owner);stage.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
    if(page.equals("home"))home(content);
    else if(page.equals("appearance"))tools(content,ToolCatalog.TOOLS.stream().filter(t->List.of("fonts","themes","icons").contains(t.category())).collect(Collectors.toList()));
    else if(page.startsWith("category:")){String id=page.substring(9);TextView heading=text(ToolCatalog.category(id).title(),25);heading.setTypeface(null,Typeface.BOLD);heading.setPadding(dp(4),dp(4),dp(4),dp(24));content.addView(heading);tools(content,ToolCatalog.TOOLS.stream().filter(t->t.category().equals(id)).collect(Collectors.toList()));}
    else if(page.equals("search"))search(content);
    else if(page.equals("connection"))connection(content);
    else if(page.equals("recovery"))recovery(content);
    else if(page.equals("settings"))settings(content);
    else if(page.equals("about"))about(content);
    if(page.equals("home"))stage.addView(homeBar(),bottomParams());
    else {String title=title();String help=page.equals("connection")?"connection":page.equals("recovery")?"recovery":page.startsWith("category:")?ToolCatalog.TOOLS.stream().filter(t->t.category().equals(page.substring(9))).findFirst().orElseThrow().id():"about";
      stage.addView(ToolUi.navigation(owner,palette,title,()->back(),help),bottomParams());}
    View previous=pageHost.getChildCount()==0?null:pageHost.getChildAt(pageHost.getChildCount()-1);
    pageHost.addView(stage,new FrameLayout.LayoutParams(-1,-1));
    if(animate&&palette.optBoolean("motion")&&previous!=null){previous.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);previous.animate().scaleX(.92f).scaleY(.92f).alpha(0).setDuration(200).withEndAction(()->pageHost.removeView(previous)).start();ToolUi.enter(stage,!route.equals("home"));}
    else if(previous!=null)pageHost.removeView(previous);
    stage.setAccessibilityPaneTitle(page.equals("home")?"VFont":title());
    refreshStatus();
  }
  private FrameLayout.LayoutParams bottomParams(){FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);p.bottomMargin=dp(8);return p;}
  private String title(){if(page.startsWith("category:"))return ToolCatalog.category(page.substring(9)).title();return switch(page){case "appearance"->"Appearance";case "settings"->"Settings";case "connection"->"Connection and checks";case "recovery"->"Restore your look";case "search"->"Search tools";default->"About VFont";};}
  private void home(LinearLayout content){
    TextView hero=text("VFont",34);hero.setGravity(Gravity.CENTER);hero.setTextColor(ToolUi.color(palette,"primary"));hero.setTypeface(null,Typeface.BOLD);content.addView(hero,new LinearLayout.LayoutParams(-1,dp(120)));
    for(int i=0;i<ToolCatalog.CATEGORIES.size();i++){
      ToolCatalog.Category c=ToolCatalog.CATEGORIES.get(i);boolean first=i==0||i==3,last=i==2||i==ToolCatalog.CATEGORIES.size()-1;
      LinearLayout row=entry(c.title(),c.summary(),c.icon(),c.hue(),first,last);row.setTag("category:"+c.id());row.setOnClickListener(v->{if(!blocked())navigate("category:"+c.id(),true);});
      row.setOnLongClickListener(v->{String id=ToolCatalog.TOOLS.stream().filter(t->t.category().equals(c.id())).findFirst().orElseThrow().id();ToolUi.explain(owner,id);return true;});
      LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(first&&i>0?12:2);content.addView(row,p);
    }
    TextView hint=text("Long-press a tool to find out what it does.",13);hint.setTextColor(ToolUi.color(palette,"muted"));hint.setPadding(dp(16),dp(20),dp(16),dp(12));content.addView(hint);
  }
  private LinearLayout entry(String title,String summary,int icon,int hue,boolean first,boolean last){
    LinearLayout row=new LinearLayout(owner);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(16),dp(16),dp(12),dp(16));row.setMinimumHeight(dp(72));row.setFocusable(true);ToolUi.ripple(row,ToolUi.grouped(owner,ToolUi.color(palette,"surface"),first,last),palette);ToolUi.press(row);
    ImageView glyph=ToolUi.glyph(owner,icon,ToolUi.ink(palette,hue));glyph.setPadding(dp(10),dp(10),dp(10),dp(10));glyph.setBackground(ToolUi.shape(owner,ToolUi.pastel(palette,hue),40));row.addView(glyph,new LinearLayout.LayoutParams(dp(40),dp(40)));
    LinearLayout labels=column();labels.setPadding(dp(14),0,dp(8),0);TextView name=text(title,16);labels.addView(name);TextView subtitle=text(summary,14);subtitle.setTextColor(ToolUi.color(palette,"muted"));LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,-2);sp.topMargin=dp(4);labels.addView(subtitle,sp);row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));return row;
  }
  private void tools(LinearLayout content,List<ToolCatalog.Tool> tools){
    for(int i=0;i<tools.size();i++){
      ToolCatalog.Tool t=tools.get(i);LinearLayout row=entry(t.title(),t.summary(),t.icon(),t.hue(),i==0,i==tools.size()-1);row.setTag("tool:"+t.id());row.setOnClickListener(v->{if(!blocked())main.openTool(t.id());});ToolUi.bind(row,owner,t.id());
      ImageButton info=ToolUi.iconButton(owner,palette,R.drawable.vf_rounded_info_24,"About "+t.title(),()->ToolUi.explain(owner,t.id()));info.setTag("help:"+t.id());row.addView(info,new LinearLayout.LayoutParams(dp(48),dp(48)));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(2);content.addView(row,p);
    }
  }
  private LinearLayout homeBar(){
    LinearLayout outer=new LinearLayout(owner);outer.setGravity(Gravity.CENTER);outer.setPadding(dp(24),dp(12),dp(24),dp(4));TextView find=text("Search tools",17);find.setGravity(Gravity.CENTER);find.setMinHeight(dp(60));find.setPadding(dp(20),dp(14),dp(20),dp(14));find.setTypeface(null,Typeface.BOLD);find.setTextColor(ToolUi.color(palette,"on_primary"));ToolUi.ripple(find,ToolUi.shape(owner,ToolUi.color(palette,"primary"),40),palette);find.setContentDescription("Search tools");find.setTag("nav:search");find.setOnClickListener(v->{if(!blocked())navigate("search",true);});ToolUi.press(find);outer.addView(find,new LinearLayout.LayoutParams(0,-2,1));ImageButton help=ToolUi.iconButton(owner,palette,R.drawable.vf_rounded_help_24,"About VFont",()->navigate("about",true));LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(dp(56),dp(56));hp.setMarginStart(dp(8));outer.addView(help,hp);return outer;
  }
  private void search(LinearLayout content){
    search=new EditText(owner);search.setSingleLine(true);search.setMinHeight(dp(56));search.setHint("Search tools");search.setTextSize(17);search.setTextColor(ToolUi.color(palette,"text"));search.setHintTextColor(ToolUi.color(palette,"muted"));search.setPadding(dp(20),dp(12),dp(20),dp(12));search.setBackground(ToolUi.shape(owner,ToolUi.color(palette,"surface"),30));search.setTag("search-tools");search.setText(query);content.addView(search,new LinearLayout.LayoutParams(-1,-2));
    results=column();LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2);rp.topMargin=dp(20);content.addView(results,rp);showResults();search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){query=s.toString();showResults();}public void afterTextChanged(Editable e){}});
  }
  private void showResults(){if(results==null)return;results.removeAllViews();List<ToolCatalog.Tool> found=ToolCatalog.search(query);if(found.isEmpty()){TextView empty=text("No tools found. Try font, theme, icons or Restore.",16);empty.setPadding(dp(16),dp(20),dp(16),dp(20));results.addView(empty);}else tools(results,found);}
  private void card(LinearLayout root,String title,String body){LinearLayout card=column();card.setPadding(dp(20),dp(20),dp(20),dp(20));card.setBackground(ToolUi.shape(owner,ToolUi.color(palette,"surface"),24));TextView heading=text(title,18);heading.setTypeface(null,Typeface.BOLD);card.addView(heading);TextView detail=text(body,15);detail.setTextColor(ToolUi.color(palette,"muted"));detail.setLineSpacing(dp(3),1);LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(-1,-2);tp.topMargin=dp(10);card.addView(detail,tp);LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.bottomMargin=dp(12);root.addView(card,cp);}
  private TextView action(LinearLayout root,String label,String explanation,Runnable run){TextView b=text(label,16);b.setGravity(Gravity.CENTER);b.setPadding(dp(16),dp(14),dp(16),dp(14));b.setMinHeight(dp(52));b.setTextColor(ToolUi.color(palette,"on_secondary"));ToolUi.ripple(b,ToolUi.shape(owner,ToolUi.color(palette,"secondary"),28),palette);ToolUi.buttonStyle(b,palette,false);b.setOnClickListener(v->{if(!blocked())run.run();});b.setOnLongClickListener(v->{ToolUi.dialog(owner).setTitle(label).setMessage(explanation).setPositiveButton("Done",null).show();return true;});ToolUi.press(b);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(12);root.addView(b,p);return b;}
  private void connection(LinearLayout content){
    TextView heading=text("Connection",26);heading.setTypeface(null,Typeface.BOLD);content.addView(heading);connectionStatus=text("",18);connectionStatus.setPadding(dp(4),dp(20),dp(4),dp(12));content.addView(connectionStatus);
    connectAction=action(content,"Connect Shizuku","Connects the authorized Shizuku service.",()->main.dispatch("connect"));
    openShizukuAction=action(content,"Open Shizuku","Opens the Shizuku app so you can start or authorize it.",()->main.openShizukuApp());
    accessStatus=text("",14);accessStatus.setPadding(dp(4),dp(8),dp(4),dp(20));content.addView(accessStatus);
    action(content,"Refresh connection","Reads the current native service and font.",()->main.dispatch("status"));
    card(content,"Vivo & iQOO setup tips",
        "OriginOS: Settings -> System management / About phone -> Version info -> tap Software version 7 times.\n"
            +"Funtouch OS (Global): Settings -> About phone -> Software information -> tap Build number 7 times.\n\n"
            +"In Developer options, turn on Wireless debugging (and USB debugging (Security settings) / Disable permission monitoring if shown), then start Shizuku via Wireless debugging. Root-mode Shizuku (UID 0) is not supported.\n\n"
            +"Allow unrestricted / high background power usage for both Shizuku and VFont so Vivo does not stop Shizuku mid-apply.");
    LinearLayout advanced=column();advanced.setVisibility(View.GONE);action(content,"Troubleshooting","Shows manual checks, developer settings and support without repeating a trial.",()->advanced.setVisibility(advanced.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE));content.addView(advanced);
    action(advanced,"Test native access","Read-only access test. Successful checks are remembered for this software.",()->main.dispatch("probe"));
    action(advanced,"Developer options","Opens Developer options so you can check Wireless debugging.",()->main.openDeveloperSettings());
    action(advanced,"Device support","Opens read-only device checks and manual report export.",()->main.openTool("support"));
  }
  private void recovery(LinearLayout content){
    card(content,"Choose the Restore that matches your change","A full-theme Restore returns the setup saved before its first Apply. Font Restore returns the system default font. Individual icon Restore returns saved originals, or removes a theme override.");
    action(content,"Font · restore system default font","Opens the font page; use Restore there to return to the system default font.",()->main.openTool("fonts"));action(content,"Theme · restore previous setup","Opens Themes; Restore previous setup returns the saved theme and appearance.",()->main.openTool("themes"));action(content,"Icons · restore selected apps","Opens App icons. Use the app's Restore action or Icon options.",()->main.openTool("icons"));
    card(content,"If VFont cannot restore",
        "If Shizuku is unavailable or Restore fails on your ROM, revert manually:\n"
            +"• Font: Vivo Settings -> Display & brightness -> Font style (or i Theme -> Local -> Fonts)\n"
            +"• Theme / Icons: i Theme -> Me -> Local -> Themes / Icons");
  }
  private void settings(LinearLayout content){
    card(content,"Follow your phone","VFont uses your phone's light or dark appearance, wallpaper colours, text size and reduced-motion preference. The app does not change those system preferences.");
    tools(content,ToolCatalog.TOOLS.stream().filter(t->List.of("connection","support","about").contains(t.id())).collect(Collectors.toList()));
  }
  private void about(LinearLayout content){
    TextView name=text("VFont",34);name.setTextColor(ToolUi.color(palette,"primary"));name.setGravity(Gravity.CENTER);name.setTypeface(null,Typeface.BOLD);content.addView(name,new LinearLayout.LayoutParams(-1,dp(120)));
    card(content,"Make your phone yours","Version "+BuildConfig.VERSION_NAME+"\nLocal fonts, themes and icons for compatible Vivo and iQOO phones. No root required.");
    card(content,"A place for every tool","Tools are grouped into categories, with their own pages. Search by what you want to change. Long-press a tool or tap its Help icon to learn what it does, what it needs and how to restore it.");
    card(content,"Design credits","The grouped cards, pastel icon wells, floating navigation and help presentation adapt Essentials by Sameera Sandakelum / sameerasw.com, under the MIT licence. VFont keeps its own customization and recovery tools.");
    action(content,"Essentials licence","View the MIT copyright and permission notice included with the adapted interface and icons.",()->{try(InputStream in=owner.getAssets().open("licenses/essentials-MIT.txt")){ToolUi.dialog(owner).setTitle("Essentials · MIT licence").setMessage(readUtf8(in)).setPositiveButton("Done",null).show();}catch(Exception e){Toast.makeText(owner,"Could not open the licence.",Toast.LENGTH_SHORT).show();}});
  }
  private static String readUtf8(InputStream in)throws IOException{
    ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[4096];int n;
    while((n=in.read(buf))!=-1)out.write(buf,0,n);
    return out.toString(StandardCharsets.UTF_8.name());
  }
  private void hideKeyboard(){InputMethodManager keyboard=(InputMethodManager)owner.getSystemService(Context.INPUT_METHOD_SERVICE);if(keyboard!=null)keyboard.hideSoftInputFromWindow(view.getWindowToken(),0);}
}
