package dev.fontlab;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

/** Refresh OriginOS caches without disabling an app or changing its chosen alias. */
final class IconRefresh {
  static boolean missingApp(PackageManager pm,ComponentName component) {
    try {pm.getApplicationInfo(component.getPackageName(),0);return false;}
    catch(PackageManager.NameNotFoundException missing){return true;}
  }
  static void refreshSidebar(Context context) {
    // UpSlide keeps its own bitmaps; its views use the refreshed cache on next open.
    context.sendBroadcast(new Intent("intent.action.theme.changed").setPackage("com.vivo.upslide")
        .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY));
  }
  static boolean supported(PackageManager pm,ComponentName component) {
    try {
      ActivityInfo info=pm.getActivityInfo(component,PackageManager.MATCH_DISABLED_COMPONENTS);
      int state=pm.getComponentEnabledSetting(component);
      if(!info.applicationInfo.enabled)return false;
      if(state==PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)return info.enabled;
      if(state!=PackageManager.COMPONENT_ENABLED_STATE_ENABLED)return false;
      int appState=pm.getApplicationEnabledSetting(component.getPackageName());
      if(appState==PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)return true;
      if(appState!=PackageManager.COMPONENT_ENABLED_STATE_ENABLED)return false;
      // DEFAULT must keep the package enabled when its current setting is explicit.
      PackageInfo declared=pm.getPackageArchiveInfo(info.applicationInfo.sourceDir,0);
      return declared!=null&&declared.applicationInfo!=null&&declared.applicationInfo.enabled;
    }catch(Exception error){return false;}
  }
  static void check(PackageManager pm,ComponentName component) {
    if(!supported(pm,component))throw new IllegalStateException("Cannot safely refresh this app's launcher entry: "+component.getPackageName());
  }
  static void refresh(PackageManager pm,ComponentName component) throws Exception {
    check(pm,component);
    Object binder=Class.forName("android.app.AppGlobals").getMethod("getPackageManager").invoke(null);
    int componentState=pm.getComponentEnabledSetting(component);
    boolean packageLevel=componentState==PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    String setting=packageLevel?"Application":"Component";Class<?> targetType=packageLevel?String.class:ComponentName.class;
    Object target=packageLevel?component.getPackageName():component;
    java.lang.reflect.Method get=binder.getClass().getMethod("get"+setting+"EnabledSetting",targetType,int.class);
    java.lang.reflect.Method set=binder.getClass().getMethod("set"+setting+"EnabledSetting",targetType,int.class,int.class,int.class,String.class);
    int before=(Integer)get.invoke(binder,target,0);
    if(before!=0&&before!=1)throw new IllegalStateException("App setting changed during icon apply");
    try {
      // Both settings enable the app. Package refresh leaves every launcher alias untouched.
      set.invoke(binder,target,before==0?1:0,PackageManager.DONT_KILL_APP,0,"com.vivo.permissionmanager");
    }finally{set.invoke(binder,target,before,PackageManager.DONT_KILL_APP,0,"com.vivo.permissionmanager");}
    if((Integer)get.invoke(binder,target,0)!=before||pm.getComponentEnabledSetting(component)!=componentState)
      throw new IllegalStateException("Could not preserve app launcher settings");
  }
}
