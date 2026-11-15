package dev.fontlab;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.json.JSONObject;

/** Android-only bindings. All paths, hashes and transaction decisions live in Rust. */
public final class Platform {
  /** Read-only: verify the Vivo refresh API before any font transaction. */
  public static void checkFontRefresh() throws Exception {
    Object manager = Class.forName("android.app.ActivityManagerNative").getMethod("getDefault").invoke(null);
    Method get = manager.getClass().getMethod("getConfiguration");
    get.setAccessible(true);
    Object configuration = get.invoke(manager);
    float changed = configuration.getClass().getField("typefaceChanged").getFloat(configuration);
    float scale = configuration.getClass().getField("fontScale").getFloat(configuration);
    manager.getClass().getMethod("updatePersistentConfiguration", configuration.getClass());
    if (!Float.isFinite(changed) || !Float.isFinite(scale) || scale <= 0 || changed + .01f == changed)
      throw new IllegalStateException("Unsupported Vivo font configuration");
    try {
      Object pm = Class.forName("android.app.AppGlobals").getMethod("getPackageManager").invoke(null);
      if (pm != null) {
        int status = (Integer) pm.getClass()
            .getMethod("checkUidPermission", String.class, int.class)
            .invoke(pm, "android.permission.CHANGE_CONFIGURATION", android.os.Process.myUid());
        if (status != android.content.pm.PackageManager.PERMISSION_GRANTED) {
          throw new SecurityException("Missing CHANGE_CONFIGURATION permission for font refresh");
        }
      }
    } catch (SecurityException denied) {
      throw denied;
    } catch (ReflectiveOperationException ignored) {
      // Fallback: older/modified AppGlobals; updatePersistentConfiguration itself will enforce permission.
    }
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
    float actualScale = scale.getFloat(result);
    float actualChanged = changed.getFloat(result);
    if (Math.abs(actualScale - size) > 0.001f
        || actualChanged == before
        || Math.abs(actualChanged - after) > 0.005f) {
      throw new IllegalStateException("Font refresh read-back differs");
    }
    return new JSONObject()
        .put("before", before)
        .put("after", after)
        .put("actual", actualChanged)
        .put("font_scale", actualScale)
        .toString();
  }
}
