package dev.fontlab;

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipOutputStream;
import org.json.JSONObject;

/** Shell-side bridge: fixed task, validated identifiers, no caller-supplied commands. */
final class IconJobs {
  static final String FINGERPRINT="vivo/PD2454/PD2454:17/CP2A.260605.016_V000L1/261030013318:user/release-keys";
  static final String PACKAGE="com.vivo.upslide";
  static final String APK_HASH="413c6c9d96e756ddd715ce16a77734e956fe34790b3e13b6387f919698228e66";
  // Reference identities label the physically tested profile; they do not block other firmware.
  static JSONObject check(Context context) throws Exception { return NativeTransport.inspect(context).profile; }

  /** Called only by the Rust font policy through the private service JNI helper. */
  static void font(Context context,String tx,String script) throws Exception {
    if(!IconSystemTask.token(tx,32) || script==null || script.length()>3400)
      throw new IllegalArgumentException("Invalid font transaction");
    File root=new File(IconSystemTask.JOBS).getParentFile();
    if(!root.isDirectory() || Files.isSymbolicLink(root.toPath())
        || !root.getCanonicalPath().equals("/storage/emulated/0/Android/data/dev.fontlab/files"))
      throw new IllegalStateException("Font receipt directory is unavailable");
    File receipt=new File(root,"font-"+tx+".result");
    if(receipt.exists() || Files.isSymbolicLink(receipt.toPath()))
      throw new IllegalStateException("Font transaction already exists");
    String wrapped="uid=$(id -u); [ \"$uid\" = 1000 ] || exit 1; ("+script+"); r=$?; "
        +"(set -C; printf '%s|%s|%s\\n' "+tx+" \"$uid\" \"$r\" > '"+receipt.getPath()+"')";
    String command="[ $(id -u) = 1000 ] && /system/bin/timeout 45 /system/bin/sh -c '"
        +wrapped.replace("'","'\\''")+"' </dev/null >/dev/null 2>&1 &";
    dispatch(NativeTransport.inspect(context),new File("font-"+tx),command);
    long deadline=SystemClock.uptimeMillis()+50_000;
    while(SystemClock.uptimeMillis()<deadline) {
      if(receipt.isFile()) {
        if(Files.isSymbolicLink(receipt.toPath()) || receipt.length()>128)
          throw new IllegalStateException("Invalid font receipt");
        String text;
        try(java.io.FileInputStream in=new java.io.FileInputStream(receipt)) {
          byte[] bytes=FullThemeStore.readBounded(in,129);
          if(bytes.length>128)throw new IllegalStateException("Invalid font receipt");
          text=new String(bytes,java.nio.charset.StandardCharsets.US_ASCII);
        }
        if(text.endsWith("\n")) {
          if(!text.matches(tx+"\\|1000\\|[0-9]{1,3}\\n"))
            throw new IllegalStateException("Font receipt mismatch");
          Files.delete(receipt.toPath());
          if(!text.equals(tx+"|1000|0\n"))
            throw new IllegalStateException("Native font transaction was rejected. Check the current font; Restore is available");
          return;
        }
      }
      Thread.sleep(100);
    }
    throw new IllegalStateException("Native font transaction timed out. Check the current font before retrying; Restore is available");
  }

  private static int query(NativeTransport transport,boolean feature) throws Exception {
    Parcel data=Parcel.obtain(),reply=Parcel.obtain();
    try {
      data.writeInterfaceToken(NativeTransport.DESCRIPTOR);
      if(feature)data.writeInt(3);else data.writeString(PACKAGE);
      if(!transport.binder.transact(feature?transport.protocol.feature:transport.protocol.fd,data,reply,0))throw new IllegalStateException("Native icon protocol unavailable");
      reply.readException();return reply.readInt();
    }finally{data.recycle();reply.recycle();}
  }

  private static final class Observer extends Binder {
    final NativeTransport.Protocol protocol;
    final CountDownLatch ended=new CountDownLatch(1);
    volatile boolean failed;
    Observer(NativeTransport.Protocol protocol){this.protocol=protocol;attachInterface(null,NativeTransport.OBSERVER);}
    @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags) throws RemoteException {
      if(code==INTERFACE_TRANSACTION){if(reply!=null)reply.writeString(NativeTransport.OBSERVER);return true;}
      if(code!=protocol.start&&code!=protocol.end&&code!=protocol.progress&&code!=protocol.error)return super.onTransact(code,data,reply,flags);
      data.enforceInterface(NativeTransport.OBSERVER);
      if(Binder.getCallingUid()!=1000 || !PACKAGE.equals(data.readString()) || data.readInt()!=0)
        throw new SecurityException("Unexpected native icon observer");
      if(code==protocol.progress){data.readLong();data.readLong();}
      if(code==protocol.error){data.readInt();failed=true;}
      if(android.os.Build.VERSION.SDK_INT>=33)data.enforceNoDataAvail();
      else if(data.dataAvail()>0)throw new android.os.BadParcelableException("Unexpected observer payload");
      if(code==protocol.end)ended.countDown();
      return true;
    }
  }

  private static void dispatch(NativeTransport transport,File folder,String command) throws Exception {
    if(query(transport,false)!=-1)throw new IllegalStateException("A native backup is already running; try again when it finishes");
    if(query(transport,true)!=1)throw new IllegalStateException("Native icon transport is unsupported");
    // The system service rejects a descriptor opened through another app's emulated-storage mount.
    File archive=new File("/data/local/tmp","vfont-icons-"+folder.getName()+".zip");
    if(!archive.createNewFile())throw new IllegalStateException("Icon dispatch already exists");
    try {
      // UID1000 has no external-root mapping. An empty ZIP reaches the fixed command without extracting files.
      try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(archive))){}
      Observer observer=new Observer(transport.protocol);
      Parcel data=Parcel.obtain(),reply=Parcel.obtain();
      try(ParcelFileDescriptor input=ParcelFileDescriptor.open(archive,ParcelFileDescriptor.MODE_READ_ONLY)) {
        data.writeInterfaceToken(NativeTransport.DESCRIPTOR);data.writeString(PACKAGE);data.writeTypedObject(input,0);
        data.writeBoolean(false);data.writeStringArray(new String[]{command});data.writeStrongBinder(observer);
        data.writeStringList(Collections.emptyList());data.writeStringList(Collections.emptyList());data.writeInt(32768);
        if(!transport.binder.transact(transport.protocol.restore,data,reply,0))throw new IllegalStateException("Native icon dispatch unavailable");
        reply.readException();
        if(!reply.readBoolean())throw new IllegalStateException("Native icon dispatch failed; reopen the manager to check recovery");
      }finally{data.recycle();reply.recycle();}
      if(!observer.ended.await(2,TimeUnit.SECONDS) || observer.failed || query(transport,false)!=-1)
        throw new IllegalStateException("Native icon dispatch did not close; reopen the manager to check recovery");
    }finally{archive.delete();}
  }

  static String run(Context context,String apk,String tx,String sha256) throws Exception {
    if (!IconSystemTask.token(tx,32) || !IconSystemTask.token(sha256,64)
        || !apk.matches("/data/app/[A-Za-z0-9_~+=/.-]+/base\\.apk"))
      throw new IllegalArgumentException("Invalid icon job");
    File folder=IconSystemTask.job(tx), request=new File(folder,"request.json"), result=new File(folder,"result.json");
    if(Files.isSymbolicLink(folder.toPath()) || !folder.getAbsolutePath().equals(folder.getCanonicalPath())
        || !sha256.equals(IconSystemTask.hash(request)) || result.exists() || Files.isSymbolicLink(result.toPath()))
      throw new IllegalStateException("Icon request is missing, changed, or already finished");
    String command="[ $(id -u) = 1000 ] && [ ! -e '"+result.getPath()+"' ] && "
        + "CLASSPATH='"+apk+"' /system/bin/timeout 180 /system/bin/app_process /system/bin dev.fontlab.IconSystemTask "
        +tx+" "+sha256+" </dev/null >/dev/null 2>&1 &";
    // Dispatch only the bounded worker; Vivo's command runner has a shorter lifetime than an icon batch.
    dispatch(NativeTransport.inspect(context),folder,command);
    long deadline=SystemClock.uptimeMillis()+190_000;
    while(SystemClock.uptimeMillis()<deadline) {
      if(result.isFile()) {
        if(Files.isSymbolicLink(result.toPath()) || result.length()>256*1024)
          throw new IllegalStateException("Invalid icon receipt");
        String text=new String(Files.readAllBytes(result.toPath()),java.nio.charset.StandardCharsets.UTF_8);
        if(!tx.equals(new JSONObject(text).optString("tx"))) throw new IllegalStateException("Icon receipt mismatch");
        return text;
      }
      Thread.sleep(200);
    }
    throw new IllegalStateException("No icon receipt. Reopen the manager and use Restore if icons changed");
  }
}
