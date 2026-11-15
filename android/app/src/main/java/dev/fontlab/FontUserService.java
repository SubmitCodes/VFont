package dev.fontlab;

import android.content.Context;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.Process;

/** Shizuku creates this Binder directly; it is not an exported Android service. */
public final class FontUserService extends IFontService.Stub {
  private final int owner;
  private final String apk;
  private final Context context;
  private final java.io.File verification;
  private final Object exportLock = new Object();

  public FontUserService(Context context) {
    this.context = context;
    owner = context.getApplicationInfo().uid;
    apk = context.getApplicationInfo().sourceDir;
    verification=new java.io.File("/data/local/tmp/vfont-verification-"+owner);
    System.load(context.getApplicationInfo().nativeLibraryDir + "/libfontlab.so");
  }

  private void checkCaller() {
    if (Binder.getCallingUid() != owner || Process.myUid() != 2000) {
      if (Process.myUid() == 0) {
        throw new SecurityException(
            "Shizuku is running as root (UID 0); restart Shizuku with ADB or Wireless Debugging (UID 2000)");
      }
      throw new SecurityException(
          "Only the authorized VFont app with Shizuku shell access can call this service");
    }
  }

  private native String nativeStatus();
  private native String nativeProbe();

  private native String nativeRun(int fd, String expectedHash, boolean restore, String defaultTarget);

  // JNI-only helpers. The Binder accepts a font descriptor/hash or a fixed restore, never a command.
  private String checkTransport() throws Exception {
    org.json.JSONObject profile=IconJobs.check(context);
    String key=profile.getString("transport_profile");
    return profile.put("command_verified",NativeTransport.verified(profile,VerificationStore.has(verification,"font",key)?key:"")).toString();
  }
  private void dispatchFont(String tx, String script) throws Exception { IconJobs.font(context, tx, script); }

  private java.io.File defaultFontFile() {
    return new java.io.File("/data/local/tmp/vfont-default-font-" + owner);
  }

  private String readDefaultFont() {
    try {
      java.io.File file = defaultFontFile();
      if (!java.nio.file.Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
          || java.nio.file.Files.isSymbolicLink(file.toPath())
          || !file.isFile() || file.length() > 256) {
        return "";
      }
      var stat = android.system.Os.lstat(file.getPath());
      if (stat.st_uid != android.system.Os.getuid() || stat.st_nlink != 1) return "";
      String path = new String(
          java.nio.file.Files.readAllBytes(file.toPath()),
          java.nio.charset.StandardCharsets.UTF_8).trim();
      return path.matches("/system/fonts/[A-Za-z0-9_.-]{1,120}\\.ttf")
          && !path.equals("/system/fonts/VivoFont.ttf")
          && !path.contains("..")
          ? path : "";
    } catch (Exception ignored) {
      return "";
    }
  }

  private void recordDefaultFontIfSystem() {
    try {
      java.io.File file = defaultFontFile();
      if (java.nio.file.Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
      org.json.JSONObject status = new org.json.JSONObject(nativeStatus());
      org.json.JSONObject current = status.optJSONObject("current");
      if (current == null) return;
      String target = current.optString("target");
      if (!target.matches("/system/fonts/[A-Za-z0-9_.-]{1,120}\\.ttf")
          || target.equals("/system/fonts/VivoFont.ttf")
          || target.contains("..")) return;
      java.io.File temp = new java.io.File("/data/local/tmp/vfont-default-font-" + owner + ".tmp");
      if (java.nio.file.Files.isSymbolicLink(temp.toPath()) || java.nio.file.Files.isSymbolicLink(file.toPath())) return;
      try (java.io.FileOutputStream out = new java.io.FileOutputStream(temp)) {
        out.write((target + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        out.getFD().sync();
      }
      java.nio.file.Files.move(temp.toPath(), file.toPath(),
          java.nio.file.StandardCopyOption.ATOMIC_MOVE,
          java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      android.system.Os.chmod(file.getPath(), 0600);
    } catch (Exception ignored) {
      // Fall back safely to /system/fonts/Roboto-Regular.ttf on restore.
    }
  }

  @Override
  public synchronized String status() {
    checkCaller();
    long identity=Binder.clearCallingIdentity();
    try {return nativeStatus();} finally {Binder.restoreCallingIdentity(identity);}
  }

  @Override public synchronized String probe() {
    checkCaller();
    long identity=Binder.clearCallingIdentity();
    try {
      String response=nativeProbe();
      org.json.JSONObject result=new org.json.JSONObject(response);
      if(result.optBoolean("ok")) {
        String profile=IconJobs.check(context).getString("transport_profile");
        if(!profile.equals(result.getJSONObject("profile").getString("transport_profile")))
          throw new IllegalStateException("System components changed during the test. Check native access again");
        VerificationStore.save(verification,"font",profile,true);
      }else {
        try{VerificationStore.save(verification,"font",IconJobs.check(context).getString("transport_profile"),false);}catch(Exception ignored){}
      }
      return response;
    }catch(Exception error) {
      try{VerificationStore.save(verification,"font",IconJobs.check(context).getString("transport_profile"),false);}catch(Exception ignored){}
      String detail=error.getMessage();
      String message=(detail!=null&&!detail.isBlank())?detail:"Native access verification failed. Check the connection and save a support report.";
      try{return new org.json.JSONObject().put("ok",false).put("command_verified",false).put("message",message).toString();}
      catch(Exception impossible){return "{\"ok\":false,\"command_verified\":false,\"message\":\"Native access verification failed.\"}";}
    }finally {Binder.restoreCallingIdentity(identity);}
  }

  @Override
  public synchronized String apply(ParcelFileDescriptor font, String expectedHash) {
    checkCaller();
    if (font == null) throw new IllegalArgumentException("Choose a font first");
    long identity=Binder.clearCallingIdentity();
    try {
      recordDefaultFontIfSystem();
      return nativeRun(font.detachFd(), expectedHash, false, readDefaultFont());
    } finally {Binder.restoreCallingIdentity(identity);}
  }

  @Override
  public synchronized String restore() {
    checkCaller();
    long identity=Binder.clearCallingIdentity();
    try {return nativeRun(-1, "", true, readDefaultFont());} finally {Binder.restoreCallingIdentity(identity);}
  }

  @Override
  public synchronized String icons(String tx, String sha256) {
    checkCaller();
    long identity=Binder.clearCallingIdentity();
    try {return IconJobs.run(context,apk,tx,sha256);}
    catch(Exception e) {
      if(BuildConfig.DEBUG) android.util.Log.w("VFont","Icon transport failed",e);
      try {return new org.json.JSONObject().put("ok",false).put("message",e.toString()).toString();}
      catch(Exception impossible) {throw new IllegalStateException(e);}
    }
    finally {Binder.restoreCallingIdentity(identity);}
  }

  @Override
  public String diagnostics() {
    checkCaller();
    synchronized(exportLock) {
      long identity=Binder.clearCallingIdentity();
      try {return SupportDiagnostics.collect(context).toString();}
      catch(Exception error) {return "{\"status\":\"error\"}";}
      finally {Binder.restoreCallingIdentity(identity);}
    }
  }

  @Override
  public String exportSystemComponents(ParcelFileDescriptor destination) {
    checkCaller();if(destination==null)throw new IllegalArgumentException("Choose a destination");
    synchronized(exportLock) {
      long identity=Binder.clearCallingIdentity();
      try(java.io.OutputStream out=new ParcelFileDescriptor.AutoCloseOutputStream(destination)) {
        return SupportDiagnostics.bundle(context,out).toString();
      }catch(Exception error) {return "{\"ok\":false,\"message\":\"System bundle failed; discard the incomplete ZIP\"}";}
      finally {Binder.restoreCallingIdentity(identity);}
    }
  }

  @Override
  public void destroy() {
    int uid = Binder.getCallingUid();
    if (uid != owner && uid != 2000 && uid != 0) throw new SecurityException();
    System.exit(0);
  }
}
