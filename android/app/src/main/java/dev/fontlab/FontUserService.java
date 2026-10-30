package dev.fontlab;

import android.content.Context;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.Process;

/** Shizuku creates this Binder directly; it is not an exported Android service. */
public final class FontUserService extends IFontService.Stub {
  private final int owner;

  public FontUserService(Context context) {
    owner = context.getApplicationInfo().uid;
    System.load(context.getApplicationInfo().nativeLibraryDir + "/libfontlab.so");
  }

  private void checkCaller() {
    if (Binder.getCallingUid() != owner || Process.myUid() != 2000) {
      throw new SecurityException(
          "Only the authorized VFont app with Shizuku shell access can call this service");
    }
  }

  private static native String nativeStatus();

  private static native String nativeRun(int fd, String expectedHash, boolean restore);

  @Override
  public synchronized String status() {
    checkCaller();
    return nativeStatus();
  }

  @Override
  public synchronized String apply(ParcelFileDescriptor font, String expectedHash) {
    checkCaller();
    if (font == null) throw new IllegalArgumentException("Choose a font first");
    return nativeRun(font.detachFd(), expectedHash, false);
  }

  @Override
  public synchronized String restore() {
    checkCaller();
    return nativeRun(-1, "", true);
  }

  @Override
  public void destroy() {
    int uid = Binder.getCallingUid();
    if (uid != owner && uid != 2000 && uid != 0) throw new SecurityException();
    System.exit(0);
  }
}
