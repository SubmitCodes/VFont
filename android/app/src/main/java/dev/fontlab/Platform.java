package dev.fontlab;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.json.JSONObject;

/** Android-only bindings. All paths, hashes and transaction decisions live in Rust. */
public final class Platform {
  public static Object createSurface(String name) throws Exception {
    Class<?> type = Class.forName("android.view.SurfaceControl$Builder");
    Object builder = type.getConstructor().newInstance();
    type.getMethod("setName", String.class).invoke(builder, name);
    type.getMethod("setHidden", boolean.class).invoke(builder, true);
    Object surface = type.getMethod("build").invoke(builder);
    if (!Boolean.TRUE.equals(surface.getClass().getMethod("isValid").invoke(surface))) {
      surface.getClass().getMethod("release").invoke(surface);
      throw new IllegalStateException("Temporary surface is invalid");
    }
    return surface;
  }

  public static void releaseSurface(Object surface) throws Exception {
    surface.getClass().getMethod("release").invoke(surface);
  }

  public static String refreshFont() throws Exception {
    Object manager =
        Class.forName("android.app.ActivityManagerNative").getMethod("getDefault").invoke(null);
    Method get = manager.getClass().getMethod("getConfiguration");
    get.setAccessible(true);
    Object configuration = get.invoke(manager);
    Field changed = configuration.getClass().getField("typefaceChanged");
    Field scale = configuration.getClass().getField("fontScale");
    float before = changed.getFloat(configuration);
    float size = scale.getFloat(configuration);
    float after = before + 0.01f;
    if (!Float.isFinite(before) || !Float.isFinite(after) || before == after) {
      throw new IllegalStateException("Invalid Vivo font refresh counter");
    }
    changed.setFloat(configuration, after);
    Method update =
        manager.getClass().getMethod("updatePersistentConfiguration", configuration.getClass());
    update.setAccessible(true);
    update.invoke(manager, configuration);
    Object result = get.invoke(manager);
    if (scale.getFloat(result) != size || changed.getFloat(result) != after) {
      throw new IllegalStateException("Font refresh read-back differs");
    }
    return new JSONObject()
        .put("before", before)
        .put("after", after)
        .put("font_scale", size)
        .toString();
  }
}
