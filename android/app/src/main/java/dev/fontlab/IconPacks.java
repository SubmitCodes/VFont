package dev.fontlab;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

/** Standard appfilter packs, resolved through Android so split APKs work too. */
final class IconPacks {
  static final class Pack {
    final String name, pkg;
    Pack(String name,String pkg) {this.name=name;this.pkg=pkg;}
    @Override public String toString() {return name;}
  }
  static final class Entry {
    final String label;
    final ComponentName component;
    final int drawable;
    final String drawableName;
    final String themeId;
    final boolean supported;
    boolean selected;
    Entry(String label,ComponentName component,int drawable,String drawableName,boolean supported) {
      this(label,component,drawable,drawableName,supported,"");
    }
    Entry(String label,ComponentName component,int drawable,String drawableName,boolean supported,String themeId) {
      this.label=label;this.component=component;this.drawable=drawable;this.drawableName=drawableName;this.supported=supported;this.themeId=themeId;
    }
    boolean hasArtwork(){return drawable!=0||!themeId.isEmpty();}
  }
  static Intent launcher() {return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);}
  static List<Pack> installed(Context context) {
    PackageManager pm=context.getPackageManager();
    Map<String,Pack> candidates=new LinkedHashMap<>();
    List<ResolveInfo> activities=new ArrayList<>(pm.queryIntentActivities(launcher(),0));
    for(String action:new String[]{"org.adw.launcher.THEMES","com.gau.go.launcherex.theme","com.novalauncher.THEME"})
      activities.addAll(pm.queryIntentActivities(new Intent(action),0));
    for(ResolveInfo item:activities) {
      String pkg=item.activityInfo.packageName;
      if(candidates.containsKey(pkg)) continue;
      try {
        Resources res=pm.getResourcesForApplication(pkg);
        boolean filter=res.getIdentifier("appfilter","xml",pkg)!=0;
        if(!filter) try(InputStream ignored=res.getAssets().open("appfilter.xml")) {filter=true;}
        if(filter) candidates.put(pkg,new Pack(item.activityInfo.applicationInfo.loadLabel(pm).toString(),pkg));
      } catch(Exception ignored) {}
    }
    List<Pack> packs=new ArrayList<>(candidates.values());
    packs.sort(Comparator.comparing(p->p.name,String.CASE_INSENSITIVE_ORDER));
    try{Pack theme=ThemeImports.selectedPack(context);if(theme!=null)packs.add(0,theme);}catch(Exception ignored){}
    return packs;
  }
  static Map<String,String> parse(XmlPullParser xml) throws Exception {
    Map<String,String> mappings=new HashMap<>();int events=0;
    for(int event=xml.getEventType();event!=XmlPullParser.END_DOCUMENT;event=xml.nextToken()) {
      if(++events>1_000_000 || event==XmlPullParser.DOCDECL) throw new IllegalArgumentException("Unsupported icon pack XML");
      if(event!=XmlPullParser.START_TAG || !"item".equals(xml.getName())) continue;
      String component=xml.getAttributeValue(null,"component"), drawable=xml.getAttributeValue(null,"drawable");
      if(component==null || drawable==null || !drawable.matches("[a-z0-9_]+")) continue;
      if(component.startsWith("ComponentInfo{") && component.endsWith("}")) component=component.substring(14,component.length()-1);
      ComponentName name=ComponentName.unflattenFromString(component);
      if(name!=null && IconSystemTask.name(name.getPackageName()) && IconSystemTask.name(name.getClassName()))
        mappings.putIfAbsent(name.flattenToString(),drawable);
    }
    return mappings;
  }
  private static Map<String,String> mappings(Resources res,String pkg) throws Exception {
    try(InputStream input=res.getAssets().open("appfilter.xml")) {
      XmlPullParser parser=XmlPullParserFactory.newInstance().newPullParser();parser.setInput(input,"UTF-8");return parse(parser);
    } catch(java.io.FileNotFoundException missing) {
      int id=res.getIdentifier("appfilter","xml",pkg);
      if(id==0) throw new IllegalArgumentException("This pack has no appfilter");
      try(android.content.res.XmlResourceParser parser=res.getXml(id)) {return parse(parser);}
    }
  }
  static Map<String,String> activityMappings(Map<String,String> mappings) {
    // Some Vivo releases move the same fully qualified activity to another package.
    return fallbackMappings(mappings,false);
  }
  static Map<String,String> packageMappings(Map<String,String> mappings) {
    return fallbackMappings(mappings,true);
  }
  private static Map<String,String> fallbackMappings(Map<String,String> mappings,boolean byPackage) {
    Map<String,String> result=new HashMap<>();
    for(Map.Entry<String,String> entry:mappings.entrySet()) {
      ComponentName candidate=ComponentName.unflattenFromString(entry.getKey());
      if(candidate!=null)result.merge(byPackage?candidate.getPackageName():candidate.getClassName(),entry.getValue(),(first,next)->first.equals(next)?first:"");
    }
    return result;
  }
  static String match(Map<String,String> mappings,Map<String,String> activities,ComponentName component) {
    return match(mappings,activities,Map.of(),component);
  }
  static String match(Map<String,String> mappings,Map<String,String> activities,Map<String,String> packages,ComponentName component) {
    String exact=mappings.get(component.flattenToString());if(exact!=null)return exact;
    String fallback=activities.get(component.getClassName());if(fallback!=null&&!fallback.isEmpty())return fallback;
    String byPackage=packages.get(component.getPackageName());return byPackage==null||byPackage.isEmpty()?null:byPackage;
  }
  static List<String> catalog(Context context,Pack pack) throws Exception {
    if(ThemeImports.pack(pack.pkg))return ThemeImports.catalog(context,pack.pkg.substring(6));
    Resources res=context.getPackageManager().getResourcesForApplication(pack.pkg);
    TreeSet<String> names=new TreeSet<>(mappings(res,pack.pkg).values());
    try(InputStream input=res.getAssets().open("drawable.xml")) {
      XmlPullParser parser=XmlPullParserFactory.newInstance().newPullParser();parser.setInput(input,"UTF-8");catalogNames(parser,names);
    } catch(java.io.FileNotFoundException missing) {
      int id=res.getIdentifier("drawable","xml",pack.pkg);
      if(id!=0)try(android.content.res.XmlResourceParser parser=res.getXml(id)){catalogNames(parser,names);}
    }
    for(var it=names.iterator();it.hasNext();){
      if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("Cancelled");
      if(res.getIdentifier(it.next(),"drawable",pack.pkg)==0)it.remove();
    }
    return new ArrayList<>(names);
  }
  private static void catalogNames(XmlPullParser xml,java.util.Set<String> names) throws Exception {
    int events=0;
    for(int event=xml.getEventType();event!=XmlPullParser.END_DOCUMENT;event=xml.nextToken()) {
      if(++events>1_000_000||event==XmlPullParser.DOCDECL)throw new IllegalArgumentException("Unsupported icon catalog XML");
      if(event!=XmlPullParser.START_TAG||!"item".equals(xml.getName()))continue;
      String name=xml.getAttributeValue(null,"drawable");if(name!=null&&name.matches("[a-z0-9_]+"))names.add(name);
    }
  }
  static List<Entry> entries(Context context,Pack pack) throws Exception {
    boolean theme=ThemeImports.pack(pack.pkg);String themeId=theme?pack.pkg.substring(6):"";
    java.util.Set<String> themeIcons=theme?new java.util.HashSet<>(ThemeImports.catalog(context,themeId)):java.util.Set.of();
    PackageManager pm=context.getPackageManager();Resources res=pack.pkg.isEmpty()||theme?null:pm.getResourcesForApplication(pack.pkg);
    Map<String,String> mappings=res==null?Map.of():mappings(res,pack.pkg);
    Map<String,String> activities=activityMappings(mappings);
    Map<String,String> packages=packageMappings(mappings);
    List<ResolveInfo> launchers=pm.queryIntentActivities(launcher(),0);
    Map<String,Integer> counts=new HashMap<>();
    for(ResolveInfo item:launchers)counts.merge(item.activityInfo.packageName,1,Integer::sum);
    Map<String,Entry> entries=new LinkedHashMap<>();
    for(ResolveInfo item:launchers) {
      ActivityInfo info=item.activityInfo;ComponentName c=new ComponentName(info.packageName,info.name);
      String drawable=theme?(themeIcons.contains(info.packageName)?info.packageName:null):match(mappings,activities,counts.getOrDefault(info.packageName,0)==1?packages:Map.of(),c);
      int id=drawable==null||res==null?0:res.getIdentifier(drawable,"drawable",pack.pkg);
      boolean supported=IconRefresh.supported(pm,c);
      entries.put(c.flattenToString(),new Entry(item.loadLabel(pm).toString(),c,id,drawable,supported,theme&&drawable!=null?themeId:""));
    }
    List<Entry> result=new ArrayList<>(entries.values());
    result.sort(Comparator.comparing(e->e.label,String.CASE_INSENSITIVE_ORDER));return result;
  }
  static Bitmap bitmap(Resources resources,int id) {
    return bitmap(resources.getDrawable(id,null));
  }
  static Bitmap bitmap(Drawable drawable) {
    Bitmap image=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);
    int w=Math.max(1,drawable.getIntrinsicWidth()),h=Math.max(1,drawable.getIntrinsicHeight());
    float scale=Math.min(256f/w,256f/h);int width=Math.round(w*scale),height=Math.round(h*scale);
    drawable.setBounds((256-width)/2,(256-height)/2,(256+width)/2,(256+height)/2);
    drawable.draw(new Canvas(image));return image;
  }
}
