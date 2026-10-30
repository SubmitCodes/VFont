package dev.fontlab;

import android.app.NativeActivity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.WindowManager;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;
import rikka.shizuku.Shizuku;

public final class MainActivity extends NativeActivity {
  static {
    System.loadLibrary("fontlab");
  }

  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private volatile IFontService service;
  private File selected;
  private volatile boolean running;
  private boolean binding;
  private static volatile String lastOperationMessage;
  private static volatile boolean lastOperationError;
  private Shizuku.UserServiceArgs serviceArgs;
  private static final int PICK = 30;

  public static native String nativeValidate(byte[] data);

  static native String nativePrepare(int inputFd, int outputFd, int faceIndex);

  public static native void nativeInitialize(MainActivity activity);

  public static native void nativeSelected(
      String metadata, String name, int[] preview, int width, int height);

  public static native void nativeEvent(String event);

  static native int nativeUiGeneration();

  private void event(String message, boolean error, Boolean ready, Boolean busy, String result) {
    try {
      JSONObject json = new JSONObject().put("message", message).put("error", error);
      if (ready != null) json.put("ready", ready);
      if (busy != null) json.put("busy", busy);
      if (result != null) json.put("result", new JSONObject(result));
      if (BuildConfig.DEBUG) android.util.Log.i("VFont", "UI event: " + message + " busy=" + busy);
      nativeEvent(json.toString());
    } catch (Exception ignored) {
      nativeEvent(
          "{\"message\":\"Could not read the operation result.\",\"error\":true,\"busy\":false}");
    }
  }

  private final Shizuku.OnBinderReceivedListener received =
      () ->
          runOnUiThread(
              () -> {
                event("Shizuku is running. Connect to authorize VFont.", false, false, null, null);
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) connect();
              });
  private final Shizuku.OnBinderDeadListener dead =
      () ->
          runOnUiThread(
              () -> {
                service = null;
                binding = false;
                event(
                    "Shizuku stopped. Start it again to apply or restore fonts.",
                    true,
                    false,
                    false,
                    null);
              });
  private final Shizuku.OnRequestPermissionResultListener permission =
      (code, grant) ->
          runOnUiThread(
              () -> {
                if (grant == PackageManager.PERMISSION_GRANTED) connect();
                else event("Shizuku access was not granted.", true, false, false, null);
              });
  private final ServiceConnection connection =
      new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
          service = IFontService.Stub.asInterface(binder);
          binding = false;
          refreshStatus();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
          service = null;
          binding = false;
          event("Shizuku connection ended. Connect again.", true, false, false, null);
        }
      };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    nativeInitialize(this);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    getWindow().setStatusBarColor(Color.rgb(15, 18, 22));
    getWindow().setNavigationBarColor(Color.rgb(15, 18, 22));
    serviceArgs =
        new Shizuku.UserServiceArgs(new ComponentName(this, FontUserService.class))
            .daemon(false)
            .processNameSuffix("font_access")
            .version(BuildConfig.VERSION_CODE)
            .tag("fontlab-v1");
    try {
      File last = new File(getFilesDir(), "last-operation.json");
      if (lastOperationMessage == null && last.isFile()) {
        JSONObject operation =
            new JSONObject(
                new String(
                    java.nio.file.Files.readAllBytes(last.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8));
        lastOperationMessage = operation.optString("message");
        lastOperationError = !operation.optBoolean("ok");
      }
    } catch (Exception ignored) {
    }
    Shizuku.addBinderReceivedListenerSticky(received);
    Shizuku.addBinderDeadListener(dead);
    Shizuku.addRequestPermissionResultListener(permission);
    File cached = new File(getCacheDir(), "selected-font.ttf");
    if (cached.isFile()) {
      running = true;
      worker.execute(() -> importFont(Uri.fromFile(cached)));
    }
  }

  /** Called by the Rust UI; there is no exported command receiver or arbitrary command input. */
  public void dispatch(String action) {
    runOnUiThread(() -> handle(action));
  }

  @Override
  protected void onResume() {
    super.onResume();
    new android.os.Handler(android.os.Looper.getMainLooper())
        .postDelayed(
            () -> {
              if (!running && service != null && !isFinishing() && !isDestroyed()) refreshStatus();
            },
            900);
  }

  private void handle(String action) {
    if (running || isDestroyed() || isFinishing()) return;
    switch (action) {
      case "connect":
        connect();
        break;
      case "status":
        lastOperationMessage = null;
        refreshStatus();
        break;
      case "game":
        try {
          startActivity(
              new Intent()
                  .setComponent(
                      new ComponentName(
                          "com.vivo.gamecube",
                          "com.vivo.gamecube.GameCubeSettings$AddGameActivity")));
        } catch (Throwable e) {
          try {
            startActivity(
                new Intent()
                    .setComponent(
                        new ComponentName(
                            "com.vivo.gamecube", "com.vivo.gamecube.GameCubeMainActivity")));
          } catch (Throwable ignored) {
            event("Open Settings → Ultra Game Mode and add VFont.", true, null, false, null);
          }
        }
        break;
      case "pick":
        Intent intent =
            new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .setType("*/*")
                .addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, PICK);
        break;
      case "apply":
        runJob(false);
        break;
      case "restore":
        runJob(true);
        break;
    }
  }

  private void connect() {
    try {
      if (!Shizuku.pingBinder()) {
        event("Start Shizuku first, then tap Connect.", true, false, false, null);
        return;
      }
      if (Shizuku.getVersion() < 13 || Shizuku.getUid() != 2000) {
        event("Use Shizuku 13+ started with ADB or wireless debugging.", true, false, false, null);
        return;
      }
      if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
        Shizuku.requestPermission(1);
        return;
      }
      if (service != null) {
        refreshStatus();
        return;
      }
      if (!binding) {
        binding = true;
        event("Connecting to Shizuku…", false, false, null, null);
        Shizuku.bindUserService(serviceArgs, connection);
      }
    } catch (Throwable e) {
      binding = false;
      event("Could not connect: " + e.getMessage(), true, false, false, null);
    }
  }

  private void refreshStatus() {
    if (isDestroyed() || worker.isShutdown()) return;
    IFontService connected = service;
    if (connected == null) {
      event("Connect Shizuku to check this phone.", false, false, null, null);
      return;
    }
    worker.execute(
        () -> {
          try {
            String result = connected.status();
            JSONObject json = new JSONObject(result);
            event(
                lastOperationMessage == null ? json.optString("message") : lastOperationMessage,
                lastOperationMessage == null ? !json.optBoolean("ok") : lastOperationError,
                json.optBoolean("ok"),
                false,
                result);
          } catch (Throwable e) {
            event("Device check failed: " + e.getMessage(), true, false, false, null);
          }
        });
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request != PICK || result != RESULT_OK || data == null || data.getData() == null) return;
    Uri uri = data.getData();
    lastOperationMessage = null;
    running = true;
    event("Checking font and creating a native preview…", false, null, true, null);
    worker.execute(() -> importFont(uri));
  }

  private void importFont(Uri uri) {
    File temp = new File(getCacheDir(), "font-import.tmp");
    try {
      String name =
          "file".equals(uri.getScheme())
              ? getPreferences(MODE_PRIVATE).getString("selected_name", "Selected font.ttf")
              : "Selected font.ttf";
      if (!"file".equals(uri.getScheme())) {
        try (Cursor cursor =
            getContentResolver()
                .query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
          if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
        }
      }
      try (InputStream input = getContentResolver().openInputStream(uri);
          FileOutputStream output = new FileOutputStream(temp)) {
        if (input == null) throw new IllegalArgumentException("Could not open the chosen file");
        byte[] buffer = new byte[16384];
        int count, total = 0;
        while ((count = input.read(buffer)) != -1) {
          total += count;
          if (total > 64 * 1024 * 1024)
            throw new IllegalArgumentException("Choose a font smaller than 64 MiB");
          output.write(buffer, 0, count);
        }
      }
      prepareAndPreview(temp, name, 0, true);
    } catch (Throwable e) {
      temp.delete();
      event("Font was not imported: " + e.getMessage(), true, null, false, null);
      running = false;
    }
  }

  private void prepareAndPreview(File input, String name, int faceIndex, boolean chooseFace) {
    File prepared = new File(getCacheDir(), "prepared-font.tmp");
    try {
      String metadata;
      try (ParcelFileDescriptor source =
              ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY);
          ParcelFileDescriptor destination =
              ParcelFileDescriptor.open(
                  prepared,
                  ParcelFileDescriptor.MODE_CREATE
                      | ParcelFileDescriptor.MODE_TRUNCATE
                      | ParcelFileDescriptor.MODE_WRITE_ONLY)) {
        metadata = nativePrepare(source.detachFd(), destination.detachFd(), faceIndex);
      }
      JSONObject result = new JSONObject(metadata);
      if (!result.optBoolean("ok")) throw new IllegalArgumentException(result.optString("message"));
      org.json.JSONArray faces = result.getJSONArray("faces");
      if (chooseFace && faces.length() > 1) {
        String[] labels = new String[faces.length()];
        for (int i = 0; i < labels.length; i++) labels[i] = faces.getString(i);
        runOnUiThread(
            () ->
                new android.app.AlertDialog.Builder(this)
                    .setTitle("Choose a collection face")
                    .setItems(
                        labels,
                        (dialog, index) ->
                            worker.execute(() -> prepareAndPreview(input, name, index, false)))
                    .setOnCancelListener(
                        dialog -> {
                          running = false;
                          event("Collection selection canceled.", false, null, false, null);
                        })
                    .show());
        return;
      }
      Typeface face = new Typeface.Builder(prepared).build();
      if (face == null) throw new IllegalArgumentException("Android could not load this font");
      Bitmap bitmap = Bitmap.createBitmap(720, 460, Bitmap.Config.ARGB_8888);
      Canvas canvas = new Canvas(bitmap);
      canvas.drawColor(Color.rgb(24, 29, 35));
      TextPaint paint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
      paint.setColor(Color.rgb(248, 247, 243));
      paint.setTypeface(face);
      paint.setTextSize(43);
      String sample =
          "Make it yours.\n"
              + "The quick brown fox 0123456789\n"
              + "العربية — أبجد هوز\n"
              + "😀 😎 ❤️ 👍🏽 👩‍💻 🇹🇷";
      StaticLayout preview =
          StaticLayout.Builder.obtain(sample, 0, sample.length(), paint, 664)
              .setAlignment(Layout.Alignment.ALIGN_NORMAL)
              .setLineSpacing(14, 1)
              .setIncludePad(true)
              .build();
      canvas.save();
      canvas.translate(28, 20);
      preview.draw(canvas);
      canvas.restore();
      paint.setTypeface(Typeface.DEFAULT);
      paint.setTextSize(24);
      canvas.drawText("Current system emoji (for comparison)", 28, 355, paint);
      paint.setTextSize(43);
      canvas.drawText("😀 😎 ❤️ 👍🏽 👩‍💻 🇹🇷", 28, 420, paint);
      int[] pixels = new int[720 * 460];
      bitmap.getPixels(pixels, 0, 720, 0, 0, 720, 460);
      bitmap.recycle();
      File destination = new File(getCacheDir(), "selected-font.ttf");
      java.nio.file.Files.move(
          prepared.toPath(),
          destination.toPath(),
          java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      selected = destination;
      getPreferences(MODE_PRIVATE).edit().putString("selected_name", name).apply();
      nativeSelected(
          result.getJSONObject("font").toString(),
          name == null ? "Selected font" : name,
          pixels,
          720,
          460);
      event(
          lastOperationMessage == null ? "Font checked. Ready to apply." : lastOperationMessage,
          lastOperationMessage != null && lastOperationError,
          null,
          false,
          null);
    } catch (Throwable e) {
      prepared.delete();
      event("Font was not imported: " + e.getMessage(), true, null, false, null);
    }
    running = false;
  }

  private void runJob(boolean restore) {
    IFontService connected = service;
    if (connected == null) {
      event("Connect Shizuku first.", true, false, false, null);
      return;
    }
    if (!restore && (selected == null || !selected.isFile())) {
      event("Choose a font first.", true, null, false, null);
      return;
    }
    running = true;
    lastOperationMessage = null;
    event(
        "VFont will briefly reopen. Swipe in Game Cube’s performance panel and leave CPU/GPU/FPS"
            + " visible.",
        false,
        null,
        true,
        null);
    worker.execute(
        () -> {
          try {
            String response;
            if (restore) response = connected.restore();
            else {
              String metadata;
              try (FileInputStream input = new FileInputStream(selected)) {
                metadata = nativeValidate(input.readAllBytes());
              }
              String hash = new JSONObject(metadata).getJSONObject("font").getString("sha256");
              try (ParcelFileDescriptor fd =
                  ParcelFileDescriptor.open(selected, ParcelFileDescriptor.MODE_READ_ONLY)) {
                response = connected.apply(fd, hash);
              }
            }
            JSONObject result = new JSONObject(response);
            lastOperationMessage = result.optString("message");
            lastOperationError = !result.optBoolean("ok");
            try (FileOutputStream log =
                new FileOutputStream(new File(getFilesDir(), "last-operation.json"))) {
              log.write(response.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            event(result.optString("message"), !result.optBoolean("ok"), null, false, response);
          } catch (Throwable e) {
            lastOperationMessage =
                "Operation interrupted: "
                    + e.getMessage()
                    + ". Reconnect and check the current font before retrying.";
            lastOperationError = true;
            event(lastOperationMessage, true, null, false, null);
          } finally {
            running = false;
          }
        });
  }

  @Override
  protected void onDestroy() {
    Shizuku.removeBinderReceivedListener(received);
    Shizuku.removeBinderDeadListener(dead);
    Shizuku.removeRequestPermissionResultListener(permission);
    if (service != null || binding) {
      try {
        Shizuku.unbindUserService(serviceArgs, connection, !isChangingConfigurations() && !running);
      } catch (Throwable ignored) {
      }
    }
    worker.shutdown();
    super.onDestroy();
  }
}
