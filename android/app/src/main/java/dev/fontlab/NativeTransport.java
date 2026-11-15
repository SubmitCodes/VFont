package dev.fontlab;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.IInterface;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONObject;

/** Capability checks only; a matching interface does not prove native execution or recovery. */
final class NativeTransport {
  static final String DESCRIPTOR="vivo.app.backup.IVivoBackupManager";
  static final String OBSERVER="vivo.app.backup.IPackageBackupRestoreObserver";
  final IBinder binder;
  final Protocol protocol;
  final JSONObject profile;

  static final class Unavailable extends IllegalStateException {
    final String code;
    Unavailable(String code,String message){super(message);this.code=code;}
  }

  static void architecture(String[] abis) {
    if(!List.of(abis).contains("arm64-v8a"))
      throw new Unavailable("device_requirements","Native modifications require an ARM64 Vivo/iQOO phone; previews and Device Support reports remain available");
  }

  static void target(ApplicationInfo app,String maker,int sdk) {
    if(!List.of("vivo","iqoo").contains(maker.toLowerCase(Locale.ROOT)))
      throw new Unavailable("device_requirements","Requires a compatible Vivo/iQOO ROM on Android 14 or later");
    if(sdk<34)
      throw new Unavailable("device_requirements","Native modifications currently require Android 14 or later; you can still export a Device Support report");
    if(app.uid!=1000 || (app.flags&ApplicationInfo.FLAG_SYSTEM)==0
        || (app.flags&ApplicationInfo.FLAG_PERSISTENT)==0 || !app.enabled)
      throw new Unavailable("upslide_identity","Stock enabled, persistent UpSlide running as system UID 1000 is required");
    if(app.backupAgentName!=null && !app.backupAgentName.isEmpty())
      throw new Unavailable("custom_backup_agent","UpSlide declares a different backup agent; send a device support report");
  }

  static final class Protocol {
    final int feature,fd,restore,start,end,progress,error;
    private static void method(Class<?> api,String name,Class<?> result,Class<?>... args) throws Exception {
      java.lang.reflect.Method method=api.getMethod(name,args);
      if(method.getReturnType()!=result || Modifier.isStatic(method.getModifiers()))throw new NoSuchMethodException(name);
    }
    private static int code(Class<?> stub,String name,Set<Integer> used) throws Exception {
      Field field=stub.getDeclaredField("TRANSACTION_"+name);field.setAccessible(true);
      if(field.getType()!=int.class || !Modifier.isStatic(field.getModifiers()))throw new NoSuchFieldException(name);
      int value=field.getInt(null);
      if(value<IBinder.FIRST_CALL_TRANSACTION || value>IBinder.LAST_CALL_TRANSACTION || !used.add(value))
        throw new IllegalStateException("Invalid native backup transaction map");
      return value;
    }
    Protocol(Class<?> api,Class<?> stub,Class<?> observer,Class<?> observerStub) throws Exception {
      if(!api.isInterface() || !observer.isInterface() || !IInterface.class.isAssignableFrom(api)
          || !IInterface.class.isAssignableFrom(observer) || !Binder.class.isAssignableFrom(stub)
          || !api.isAssignableFrom(stub) || !Binder.class.isAssignableFrom(observerStub)
          || !observer.isAssignableFrom(observerStub))throw new IllegalStateException("Invalid native backup interfaces");
      method(api,"checkSupportFeature",boolean.class,int.class);
      method(api,"getFdByPkg",int.class,String.class);
      method(api,"restoreNoClearByZip",boolean.class,String.class,ParcelFileDescriptor.class,boolean.class,
          String[].class,observer,List.class,List.class,int.class);
      method(observer,"onStart",void.class,String.class,int.class);
      method(observer,"onEnd",void.class,String.class,int.class);
      method(observer,"onProgress",void.class,String.class,int.class,long.class,long.class);
      method(observer,"onError",void.class,String.class,int.class,int.class);
      Set<Integer> used=new HashSet<>();
      feature=code(stub,"checkSupportFeature",used);fd=code(stub,"getFdByPkg",used);restore=code(stub,"restoreNoClearByZip",used);
      used.clear();start=code(observerStub,"onStart",used);end=code(observerStub,"onEnd",used);
      progress=code(observerStub,"onProgress",used);error=code(observerStub,"onError",used);
    }
    JSONObject json() throws Exception {
      return new JSONObject().put("checkSupportFeature",feature).put("getFdByPkg",fd).put("restoreNoClearByZip",restore)
          .put("onStart",start).put("onEnd",end).put("onProgress",progress).put("onError",error);
    }
  }

  private NativeTransport(IBinder binder,Protocol protocol,JSONObject profile){this.binder=binder;this.protocol=protocol;this.profile=profile;}

  static boolean verified(JSONObject profile,String proof) {
    String key=profile.optString("transport_profile");
    return profile.optBoolean("tested") || key.matches("[a-f0-9]{64}")&&key.equals(proof);
  }

  static NativeTransport inspect(Context context) throws Exception {
    architecture(Build.SUPPORTED_ABIS);
    ApplicationInfo app;
    try {app=context.getPackageManager().getApplicationInfo(IconJobs.PACKAGE,0);}
    catch(android.content.pm.PackageManager.NameNotFoundException missing) {
      throw new Unavailable("upslide_missing","This ROM has no stock UpSlide package for the native transport");
    }
    target(app,Build.MANUFACTURER,Build.VERSION.SDK_INT);
    Protocol protocol;
    try {protocol=new Protocol(Class.forName(DESCRIPTOR),Class.forName(DESCRIPTOR+"$Stub"),
        Class.forName(OBSERVER),Class.forName(OBSERVER+"$Stub"));}
    catch(ReflectiveOperationException|IllegalStateException|LinkageError error) {
      throw new Unavailable("protocol_mismatch","This ROM's native backup protocol is unavailable or incompatible; save a support report");
    }
    IBinder binder=(IBinder)Class.forName("android.os.ServiceManager")
        .getMethod("checkService",String.class).invoke(null,"vivo_backup_service");
    if(binder==null || !DESCRIPTOR.equals(binder.getInterfaceDescriptor()))
      throw new Unavailable("backup_service_missing","Vivo's native backup service is unavailable");
    String base=IconCompatibility.digest(new File(app.sourceDir));
    StringBuilder identity=new StringBuilder(Build.FINGERPRINT).append('\n').append(base).append('\n').append(protocol.json());
    if(app.splitSourceDirs!=null)for(String split:app.splitSourceDirs)identity.append('\n').append(IconCompatibility.digest(new File(split)));
    boolean tested=IconJobs.FINGERPRINT.equals(Build.FINGERPRINT) && IconJobs.APK_HASH.equals(base)
        && (app.splitSourceDirs==null||app.splitSourceDirs.length==0)
        && protocol.feature==1 && protocol.fd==22 && protocol.restore==25
        && protocol.start==1 && protocol.end==2 && protocol.progress==3 && protocol.error==4;
    JSONObject profile=new JSONObject().put("transport","vivo-backup-upslide-v2").put("tested",tested)
        .put("transport_profile",IconSystemTask.hash(identity.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
        .put("transactions",protocol.json());
    return new NativeTransport(binder,protocol,profile);
  }
}
