package dev.fontlab;

import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Exercises real NativeActivity destruction/recreation; never changes a font or setting. */
public final class LifecycleCheck extends Instrumentation {
  @Override
  public void onCreate(Bundle arguments) {
    super.onCreate(arguments);
    start();
  }

  private void awaitUi(int previous) {
    long deadline = SystemClock.uptimeMillis() + 10000;
    while (MainActivity.nativeUiGeneration() <= previous) {
      if (SystemClock.uptimeMillis() > deadline)
        throw new AssertionError("Rust UI did not restart");
      SystemClock.sleep(50);
    }
    waitForIdleSync();
  }

  private int verifyImports() throws Exception {
    int checked = 0;
    File input = new File(getTargetContext().getCacheDir(), "format-check-input.bin");
    File output = new File(getTargetContext().getCacheDir(), "format-check-output.bin");
    try {
      // AssetManager also exposes framework assets; select our fixtures explicitly.
      for (String name :
          new String[] {
            "alternate.ttf",
            "cff.otf",
            "collection.otc",
            "collection.ttc",
            "color-v0.ttf",
            "color-v1.ttf",
            "regular.ttf",
            "regular.woff",
            "regular.woff2",
            "variable.ttf"
          }) {
        try (InputStream source = getContext().getAssets().open("generated/" + name);
            FileOutputStream target = new FileOutputStream(input)) {
          byte[] buffer = new byte[8192];
          int count;
          while ((count = source.read(buffer)) != -1) target.write(buffer, 0, count);
        }
        int index = name.endsWith(".ttc") || name.endsWith(".otc") ? 1 : 0;
        String response;
        try (ParcelFileDescriptor source =
                ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY);
            ParcelFileDescriptor target =
                ParcelFileDescriptor.open(
                    output,
                    ParcelFileDescriptor.MODE_WRITE_ONLY
                        | ParcelFileDescriptor.MODE_CREATE
                        | ParcelFileDescriptor.MODE_TRUNCATE)) {
          response = MainActivity.nativePrepare(source.detachFd(), target.detachFd(), index);
        }
        JSONObject result = new JSONObject(response);
        if (!result.optBoolean("ok")) throw new AssertionError(name + ": " + response);
        Typeface face = new Typeface.Builder(output).build();
        if (face == null) throw new AssertionError(name + ": Android could not load prepared data");
        if (name.startsWith("color-")) {
          Bitmap image = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
          Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
          paint.setTypeface(face);
          paint.setTextSize(90);
          new Canvas(image).drawText("😀", 10, 100, paint);
          boolean green = false;
          for (int y = 0; y < 128 && !green; y++)
            for (int x = 0; x < 128; x++) {
              int pixel = image.getPixel(x, y);
              if ((pixel & 0x00ffffff) == 0x0000ff00 && (pixel >>> 24) > 200) {
                green = true;
                break;
              }
            }
          image.recycle();
          if (!green) throw new AssertionError(name + ": custom color glyph did not render");
        }
        checked++;
      }
    } finally {
      input.delete();
      output.delete();
    }
    if (checked != 10) throw new AssertionError("Expected 10 format fixtures, got " + checked);
    return checked;
  }

  @Override
  public void onStart() {
    Bundle result = new Bundle();
    try {
      int generation = MainActivity.nativeUiGeneration();
      ActivityMonitor initial = addMonitor(MainActivity.class.getName(), null, false);
      // Vivo can defer a background instrumentation launch. Start only our activity
      // with the instrumentation's normal shell identity.
      try (ParcelFileDescriptor launch =
          getUiAutomation().executeShellCommand("am start --user 0 -n dev.fontlab/.MainActivity")) {
        try (ParcelFileDescriptor.AutoCloseInputStream input =
            new ParcelFileDescriptor.AutoCloseInputStream(launch)) {
          input.readAllBytes();
        }
      }
      Activity first = initial.waitForActivityWithTimeout(10000);
      removeMonitor(initial);
      if (first == null) throw new AssertionError("Activity did not launch");
      awaitUi(generation);
      int formats = verifyImports();
      AtomicReference<Activity> current = new AtomicReference<>(first);
      for (int i = 0; i < 2; i++) {
        ActivityMonitor monitor = addMonitor(MainActivity.class.getName(), null, false);
        generation = MainActivity.nativeUiGeneration();
        runOnMainSync(() -> current.get().recreate());
        Activity next = monitor.waitForActivityWithTimeout(10000);
        if (next == null || next == current.get())
          throw new AssertionError("Activity was not recreated");
        current.set(next);
        awaitUi(generation);
        removeMonitor(monitor);
      }
      result.putString(
          "result",
          "PASS: "
              + formats
              + " native font imports, COLRv0/COLRv1 emoji rendering, two Rust UI recreations; no"
              + " system font/settings changed");
      finish(Activity.RESULT_OK, result);
    } catch (Throwable error) {
      result.putString("result", "FAIL: " + error);
      finish(Activity.RESULT_CANCELED, result);
    }
  }
}
