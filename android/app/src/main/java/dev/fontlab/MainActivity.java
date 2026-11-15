package dev.fontlab;

import android.app.AlertDialog;
import android.app.NativeActivity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.Context;
import android.content.res.Configuration;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.WindowManager;
import android.view.Window;
import android.view.WindowInsetsController;
import android.view.WindowInsets;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import rikka.shizuku.Shizuku;

public final class MainActivity extends NativeActivity {
  static {
    System.loadLibrary("fontlab");
  }

  private static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private volatile IFontService service;
  private volatile File selected;
  private volatile String selectedHash;
  private final AtomicInteger runningJobs = new AtomicInteger();
  private volatile boolean binding;
  private volatile Runnable bindingTimeoutRunnable;
  private AlertDialog collectionDialog;
  private File pendingCollectionInput;
  private String importSupportId;
  private long importSupportStarted;
  private static volatile String lastOperationMessage;
  private static volatile boolean lastOperationError;
  private volatile String lastUiMessage;
  private volatile boolean lastUiError;
  private volatile Boolean lastUiReady;
  private volatile JSONObject lastUiResult;
  private Shizuku.UserServiceArgs serviceArgs;
  volatile Toolbox toolbox;
  private String toolboxPage="home",toolboxQuery="";
  private boolean fontPage;
  private boolean toolboxLaunching;
  private static final int PICK = 30;
  private static volatile java.lang.ref.WeakReference<MainActivity> current = new java.lang.ref.WeakReference<>(null);
  private static final java.util.concurrent.atomic.AtomicBoolean deviceOperation = new java.util.concurrent.atomic.AtomicBoolean();
  private final Runnable resumeReconnectRunnable =
      () -> {
        if (!isRunning() && !isFinishing() && !isDestroyed()) reconnectIfAuthorized();
      };

  private boolean isRunning() {return runningJobs.get() > 0;}
  static boolean operationActive() {return deviceOperation.get();}
  static boolean beginOperation() {
    if(!deviceOperation.compareAndSet(false,true))return false;
    ApplyActivity.clearPendingClose();
    nativeEvent("{\"busy\":true}");return true;
  }
  static void endOperation() {
    deviceOperation.set(false);
    ApplyActivity.finishOperation();
    MainActivity activity=current.get();
    if(activity!=null)activity.runOnUiThread(()->{if(!activity.isDestroyed()){
      nativeEvent("{\"busy\":"+activity.uiBusy()+"}");
      if(activity.service!=null||!lastOperationError)activity.refreshStatus();
      else if(activity.toolbox!=null)activity.toolbox.refreshStatus();
    }});
  }

  static IFontService iconsService() {
    MainActivity activity=current.get();
    return activity==null ? null : activity.service;
  }

  static void connectForSupport() {
    MainActivity activity=current.get();if(activity!=null)activity.dispatch("connect");
  }
  boolean uiBusy(){return isRunning()||operationActive();}
  boolean wantsToolbox(){return !fontPage;}
  void cancelToolboxLaunch(){toolboxLaunching=false;}
  static MainActivity owner(){return current.get();}
  String toolboxPage(){return toolboxPage;}
  String toolboxQuery(){return toolboxQuery;}
  void rememberToolbox(String page,String query){toolboxPage=page;toolboxQuery=query;}
  void attachToolbox(Toolbox shell){
    toolbox=shell;toolboxLaunching=false;
    if(lastUiMessage!=null||lastUiResult!=null){
      shell.notice(lastUiMessage,lastUiError,lastUiReady,uiBusy(),lastUiResult);
    }
  }
  void detachToolbox(Toolbox shell){if(toolbox==shell){rememberToolbox(shell.page(),shell.query());toolbox=null;}}
  void refreshToolboxConnection(){if(!isRunning())reconnectIfAuthorized();}
  void openTool(String id){
    if(uiBusy()){android.widget.Toast.makeText(this,"Wait for the current operation to finish.",android.widget.Toast.LENGTH_SHORT).show();return;}
    ToolCatalog.tool(id);
    switch(id){
      case "fonts":fontPage=true;if(toolbox!=null){rememberToolbox(toolbox.page(),toolbox.query());toolbox.owner.finish();}publishTheme();break;
      case "themes":ToolUi.open(toolbox==null?this:toolbox.owner,new Intent(this,ThemesActivity.class));break;
      case "components":ToolUi.open(toolbox==null?this:toolbox.owner,new Intent(this,ThemesActivity.class).putExtra("components_only",true));break;
      case "icons":ToolUi.open(toolbox==null?this:toolbox.owner,new Intent(this,IconsActivity.class));break;
      case "icon_packs":ToolUi.open(toolbox==null?this:toolbox.owner,new Intent(this,IconsActivity.class).putExtra("batch",true));break;
      case "support":ToolUi.open(toolbox==null?this:toolbox.owner,new Intent(this,SupportActivity.class));break;
      default:fontPage=false;if(toolbox!=null)toolbox.navigate(id,true);else{toolboxPage=id;showToolbox();}break;
    }
  }
  private void showToolbox(){fontPage=false;if(toolbox==null&&!toolboxLaunching&&!isFinishing()&&!isDestroyed()){toolboxLaunching=true;startActivity(new Intent(this,ToolboxActivity.class));}}

  public static native String nativeValidate(byte[] data);

  private static native String nativeValidateFd(int fd);

  static native String nativePrepare(int inputFd, int outputFd, int faceIndex);

  public static native void nativeInitialize(MainActivity activity);

  public static native void nativeSelected(
      String metadata, String name, int[] preview, int width, int height);

  public static native void nativeEvent(String event);

  static native int nativeUiGeneration();

  /** Android's wallpaper palette, shared by the Rust UI and the operation screen. */
  static JSONObject palette(Context context) {
    boolean dark = (context.getResources().getConfiguration().uiMode
        & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    JSONObject colors = new JSONObject();
    try {
      int accent =
          Build.VERSION.SDK_INT >= 31
              ? context.getColor(
                  dark
                      ? android.R.color.system_accent1_200
                      : android.R.color.system_accent1_600)
              : (dark ? 0xffa8c7fa : 0xff0b57d0);
      int background=dark?0xff101217:0xfff0f2f7,surface=dark?0xff1b1f28:0xffffffff,elevated=dark?0xff272d39:0xffe2e6ef;
      int text=dark?0xfff4f6fb:0xff171b26,muted=dark?0xffbbc4d4:0xff454d60,outline=dark?0xff929db2:0xff6c7587;
      int onPrimary=dark?0xff101217:0xffffffff;
      int primary=readable(accent,onPrimary,7);
      colors.put("dark", dark).put("motion", android.animation.ValueAnimator.areAnimatorsEnabled());
      colors.put("background",background&0xffffffffL).put("surface",surface&0xffffffffL).put("elevated",elevated&0xffffffffL)
          .put("text",text&0xffffffffL).put("muted",muted&0xffffffffL).put("outline",outline&0xffffffffL)
          .put("primary",primary&0xffffffffL).put("on_primary",onPrimary&0xffffffffL)
          .put("secondary",surface&0xffffffffL).put("on_secondary",text&0xffffffffL);
      colors.put("error", (dark ? Color.rgb(255, 180, 171) : Color.rgb(186, 26, 26)) & 0xffffffffL);
    } catch (org.json.JSONException impossible) { throw new AssertionError(impossible); }
    return colors;
  }

  static double contrast(int a,int b){double x=luminance(a),y=luminance(b);return (Math.max(x,y)+.05)/(Math.min(x,y)+.05);}
  private static double luminance(int color){double sum=0;int[] values={Color.red(color),Color.green(color),Color.blue(color)};double[] weights={.2126,.7152,.0722};for(int i=0;i<3;i++){double v=values[i]/255.;sum+=weights[i]*(v<=.04045?v/12.92:Math.pow((v+.055)/1.055,2.4));}return sum;}
  static int readable(int color,int background,double minimum){if(contrast(color,background)>=minimum)return color;int target=contrast(Color.BLACK,background)>contrast(Color.WHITE,background)?Color.BLACK:Color.WHITE;for(int i=1;i<=20;i++){float t=i/20f;int next=Color.rgb(Math.round(Color.red(color)*(1-t)+Color.red(target)*t),Math.round(Color.green(color)*(1-t)+Color.green(target)*t),Math.round(Color.blue(color)*(1-t)+Color.blue(target)*t));if(contrast(next,background)>=minimum)return next;}return target;}

  static void styleSystemBars(Window window, JSONObject colors) {
    int background = (int) colors.optLong("background");
    window.setStatusBarColor(background);
    window.setNavigationBarColor(background);
    window.setStatusBarContrastEnforced(false);
    window.setNavigationBarContrastEnforced(false);
    window.getDecorView().post(() -> {
      if (Build.VERSION.SDK_INT >= 30) {
        WindowInsetsController controller = window.getDecorView().getWindowInsetsController();
        if (controller != null) {
          int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
              | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
          controller.setSystemBarsAppearance(colors.optBoolean("dark") ? 0 : light, light);
        }
      }
    });
  }

  private void publishTheme() {
    JSONObject colors = palette(this);
    styleSystemBars(getWindow(), colors);
    nativeEvent("{\"theme\":" + colors + "}");
  }

  private void event(String message, boolean error, Boolean ready, Boolean busy, String result) {
    if (isDestroyed() || isFinishing()) return;
    try {
      JSONObject json = new JSONObject().put("message", message).put("error", error);
      if (ready != null) json.put("ready", ready);
      if (busy != null) {
        boolean operationBusy=busy || operationActive();
        json.put("busy", operationBusy);
        runOnUiThread(() -> {
          if (isDestroyed()) return;
          if (operationBusy) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
          else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
      }
      JSONObject outcome = result != null ? new JSONObject(result) : null;
      if (outcome != null) json.put("result", outcome);
      lastUiMessage = message;
      lastUiError = error;
      if (ready != null) lastUiReady = ready;
      if (outcome != null) lastUiResult = outcome;
      else if (Boolean.FALSE.equals(ready)) lastUiResult = null;
      Toolbox shell = toolbox;
      if (shell != null) {
        runOnUiThread(
            () -> {
              if (!isDestroyed() && toolbox != null) {
                toolbox.notice(message, error, ready, uiBusy(), outcome);
              }
            });
      }
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
                if (isDestroyed() || isFinishing()) return;
                event("Shizuku is running. Connect to authorize VFont.", false, false, null, null);
                reconnectIfAuthorized();
              });
  private final Shizuku.OnBinderDeadListener dead =
      () ->
          runOnUiThread(
              () -> {
                cancelBindingTimeout();
                String log=SupportLog.begin(this,"connection");SupportLog.finish(this,log,false,"Shizuku disconnected","",0);
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
                if (grant == PackageManager.PERMISSION_GRANTED) {
                  connect();
                } else {
                  boolean blocked = false;
                  try {
                    blocked = !Shizuku.shouldShowRequestPermissionRationale();
                  } catch (Throwable ignored) {
                  }
                  event(
                      blocked
                          ? "Shizuku access was denied permanently. Allow VFont inside the Shizuku app, then tap Connect."
                          : "Shizuku access was not granted.",
                      true,
                      false,
                      false,
                      null);
                }
              });
  private final ServiceConnection connection =
      new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
          cancelBindingTimeout();
          service = IFontService.Stub.asInterface(binder);
          binding = false;
          String log=SupportLog.begin(MainActivity.this,"connection");SupportLog.finish(MainActivity.this,log,true,"","complete",0);
          refreshStatus();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
          cancelBindingTimeout();
          service = null;
          binding = false;
          event("Shizuku connection ended. Connect again.", true, false, false, null);
        }
      };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    current = new java.lang.ref.WeakReference<>(this);
    SupportLog.initialize(this);
    cleanImportCache();
    getExternalFilesDir(null); // Create the bounded receipt directory as the app owner.
    nativeInitialize(this);
    if (Build.VERSION.SDK_INT >= 33) {
      getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
          android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
          () -> nativeEvent("{\"back\":true}"));
    }
    publishTheme();
    getWindow().getDecorView().setOnApplyWindowInsetsListener((view, insets) -> {
      if (Build.VERSION.SDK_INT >= 30) {
        android.graphics.Insets safe = insets.getInsets(
            WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        nativeEvent("{\"insets\":[" + safe.left + "," + safe.top + ","
            + safe.right + "," + safe.bottom + "]}");
      } else {
        nativeEvent("{\"insets\":[" + insets.getSystemWindowInsetLeft() + ","
            + insets.getSystemWindowInsetTop() + ","
            + insets.getSystemWindowInsetRight() + ","
            + insets.getSystemWindowInsetBottom() + "]}");
      }
      return view.onApplyWindowInsets(insets);
    });
    getWindow().getDecorView().requestApplyInsets();
    if (state != null) {
      toolboxPage = state.getString("toolbox_page", "home");
      toolboxQuery = state.getString("toolbox_query", "");
      fontPage = state.getBoolean("font_page", false);
    } else if (getIntent() != null) {
      String page = getIntent().getStringExtra("toolbox_page");
      String query = getIntent().getStringExtra("toolbox_query");
      if (page != null && !page.isEmpty()) toolboxPage = page;
      if (query != null) toolboxQuery = query;
    }
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
    File cached = selectionFile();
    if (cached.isFile()) {
      runningJobs.incrementAndGet();
      worker.execute(() -> restoreSelection(cached));
    } else {
      nativeEvent("{\"cleared\":true}");
    }
  }

  /** Called by the Rust UI; there is no exported command receiver or arbitrary command input. */
  public void dispatch(String action) {
    runOnUiThread(() -> handle(action));
  }

  @Override
  protected void onResume() {
    super.onResume();
    publishTheme();
    if(!fontPage)showToolbox();
    mainHandler.removeCallbacks(resumeReconnectRunnable);
    mainHandler.postDelayed(resumeReconnectRunnable, 2500);
  }

  @Override
  protected void onPause() {
    mainHandler.removeCallbacks(resumeReconnectRunnable);
    super.onPause();
  }

  @SuppressWarnings("deprecation")
  @Override
  public void onBackPressed() {
    nativeEvent("{\"back\":true}");
  }

  @Override
  public void onConfigurationChanged(Configuration configuration) {
    super.onConfigurationChanged(configuration);
    publishTheme();
    File file = selected;
    if (!isRunning() && !operationActive() && file != null && !worker.isShutdown()) {
      runningJobs.incrementAndGet();
      worker.execute(() -> restoreSelection(file));
    }
  }

  private void handle(String action) {
    if (isRunning() || isDestroyed() || isFinishing()) return;
    if(operationActive()) {
      event("An operation is still running. Wait for it to finish.",false,null,true,null);
      return;
    }
    switch (action) {
      case "support":
        ToolUi.open(this, new Intent(this, SupportActivity.class));
        break;
      case "back":
        if(fontPage)showToolbox();else finish();
        break;
      case "home":
        showToolbox();
        break;
      case "font_help":
        ToolUi.explain(this,"fonts");
        break;
      case "connection":
        openTool("connection");
        break;
      case "connect":
        connect();
        break;
      case "status":
        lastOperationMessage = null;
        lastOperationError = false;
        refreshStatus();
        break;
      case "pick":
        try {
          Intent intent =
              new Intent(Intent.ACTION_OPEN_DOCUMENT)
                  .setType("*/*")
                  .addCategory(Intent.CATEGORY_OPENABLE);
          startActivityForResult(intent, PICK);
        } catch (ActivityNotFoundException e) {
          event("No system file picker was found on this device.", true, null, false, null);
        }
        break;
      case "icons":
      case "icons_pack":
        ToolUi.open(
            this,
            new Intent(this, IconsActivity.class).putExtra("batch", action.equals("icons_pack")));
        break;
      case "themes":
        ToolUi.open(this, new Intent(this, ThemesActivity.class));
        break;
      case "clear":
        clearSelection();
        break;
      case "apply":
        runJob("apply");
        break;
      case "restore":
        runJob("restore");
        break;
      case "probe":
        runJob("probe");
        break;
    }
  }

  @Override protected void onNewIntent(Intent intent){
    super.onNewIntent(intent);setIntent(intent);toolboxLaunching=false;
    if(Intent.ACTION_MAIN.equals(intent.getAction())&&intent.hasCategory(Intent.CATEGORY_LAUNCHER)&&!uiBusy()){
      Toolbox shell = toolbox;
      toolbox = null;
      toolboxPage = "home";
      toolboxQuery = "";
      if (shell != null && shell.owner != null && !shell.owner.isFinishing() && !shell.owner.isDestroyed()) {
        shell.owner.finish();
      }
      showToolbox();
    }
  }

  private void cancelBindingTimeout() {
    Runnable timeout = bindingTimeoutRunnable;
    bindingTimeoutRunnable = null;
    if (timeout != null) {
      mainHandler.removeCallbacks(timeout);
    }
  }

  boolean isShizukuInstalled() {
    try {
      getPackageManager().getPackageInfo(SHIZUKU_PACKAGE, 0);
      return true;
    } catch (Throwable ignored) {
      return false;
    }
  }

  String shizukuSummary() {
    if (!isShizukuInstalled()) {
      return "Shizuku is not installed. Install Shizuku, start it with Wireless debugging or ADB, then tap Connect.";
    }
    try {
      if (!Shizuku.pingBinder()) {
        return "Shizuku is installed, but not running. Start Shizuku with Wireless debugging or ADB, then tap Connect.";
      }
      int version = Shizuku.getVersion();
      int uid = Shizuku.getUid();
      if (version < 13 || uid != 2000) {
        if (uid == 0) {
          return "Shizuku is running as root (UID 0). Start Shizuku with Wireless debugging or ADB (UID 2000), then reconnect.";
        }
        return "Use Shizuku 13+ started with Wireless debugging or ADB (UID 2000).";
      }
      if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
        return "Shizuku is running. Tap Connect and allow VFont when prompted.";
      }
      if (binding) return "Connecting to Shizuku…";
      if (service != null && service.asBinder().isBinderAlive()) return "Shizuku connected (UID 2000).";
      return "Shizuku is authorized. Tap Connect to check this phone.";
    } catch (Throwable ignored) {
      return "Connect Shizuku to check this phone.";
    }
  }

  void openShizukuApp() {
    if (isDestroyed() || isFinishing()) return;
    Intent launch = getPackageManager().getLaunchIntentForPackage(SHIZUKU_PACKAGE);
    if (launch != null) {
      try {
        startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return;
      } catch (ActivityNotFoundException ignored) {
      }
    }
    try {
      startActivity(
          new Intent(
                  Intent.ACTION_VIEW,
                  Uri.parse("market://details?id=" + SHIZUKU_PACKAGE))
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    } catch (ActivityNotFoundException ignored) {
      try {
        startActivity(
            new Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://shizuku.rikka.app/download/"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      } catch (ActivityNotFoundException unavailable) {
        android.widget.Toast.makeText(
                this,
                "Install Shizuku first, then tap Connect.",
                android.widget.Toast.LENGTH_SHORT)
            .show();
      }
    }
  }

  void openDeveloperSettings() {
    if (isDestroyed() || isFinishing()) return;
    try {
      startActivity(
          new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    } catch (ActivityNotFoundException ignored) {
      android.widget.Toast.makeText(
              this,
              "Enable Developer options in About phone > Software information (tap Build number 7 times).",
              android.widget.Toast.LENGTH_LONG)
          .show();
      try {
        startActivity(
            new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      } catch (ActivityNotFoundException fallbackIgnored) {
        try {
          startActivity(
              new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException ignoredAll) {
        }
      }
    }
  }

  private void connect() {
    if (isDestroyed() || isFinishing()) return;
    try {
      if (!isShizukuInstalled()) {
        event(
            "Shizuku is not installed. Install Shizuku, start it with Wireless debugging or ADB, then tap Connect.",
            true,
            false,
            false,
            null);
        return;
      }
      if (!Shizuku.pingBinder()) {
        event(
            "Start Shizuku via Wireless debugging or ADB first, then tap Connect.",
            true,
            false,
            false,
            null);
        return;
      }
      if (Shizuku.getVersion() < 13 || Shizuku.getUid() != 2000) {
        event(
            "Use Shizuku 13+ started with Wireless debugging or ADB (UID 2000).",
            true,
            false,
            false,
            null);
        return;
      }
      if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
        if (Shizuku.shouldShowRequestPermissionRationale()) {
          event("Allow VFont when Shizuku prompts for access.", false, false, null, null);
        }
        Shizuku.requestPermission(1);
        return;
      }
      if (service != null && service.asBinder().isBinderAlive()) {
        refreshStatus();
        return;
      }
      service = null;
      if (!binding) {
        binding = true;
        cancelBindingTimeout();
        bindingTimeoutRunnable =
            () -> {
              bindingTimeoutRunnable = null;
              if (isDestroyed() || isFinishing() || !binding) return;
              if (service != null && service.asBinder().isBinderAlive()) {
                binding = false;
                return;
              }
              binding = false;
              try {
                Shizuku.unbindUserService(serviceArgs, connection, true);
              } catch (Throwable ignored) {
              }
              event(
                  "Shizuku did not respond in time. Restart Shizuku and tap Connect again.",
                  true,
                  false,
                  false,
                  null);
            };
        mainHandler.postDelayed(bindingTimeoutRunnable, 8000);
        event("Connecting to Shizuku…", false, false, null, null);
        try {
          Shizuku.bindUserService(serviceArgs, connection);
        } catch (Throwable e) {
          cancelBindingTimeout();
          binding = false;
          throw e;
        }
      }
    } catch (Throwable e) {
      cancelBindingTimeout();
      binding = false;
      event("Could not connect: " + e.getMessage(), true, false, false, null);
    }
  }

  private void reconnectIfAuthorized() {
    if (isDestroyed() || isFinishing()) return;
    try {
      if (Shizuku.pingBinder()
          && Shizuku.getVersion() >= 13
          && Shizuku.getUid() == 2000
          && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
        connect();
      }
    } catch (Throwable ignored) {
      // A later resume or the Connect button can retry when Shizuku is available.
    }
  }

  private void refreshStatus() {
    if (isDestroyed() || worker.isShutdown()) return;
    IFontService connected = service;
    if (connected == null) {
      boolean canAutoConnect = false;
      try {
        canAutoConnect =
            !binding
                && !lastOperationError
                && Shizuku.pingBinder()
                && Shizuku.getVersion() >= 13
                && Shizuku.getUid() == 2000
                && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
      } catch (Throwable ignored) {
      }
      if (canAutoConnect) {
        connect();
        return;
      }
      if (lastOperationMessage != null) {
        event(lastOperationMessage, lastOperationError, false, isRunning() ? null : false, null);
      } else {
        event(shizukuSummary(), false, false, isRunning() ? null : false, null);
      }
      return;
    }
    worker.execute(
        () -> {
          try {
            String result = connected.status();
            JSONObject json = new JSONObject(result);
            boolean supported = json.optBoolean("ok");
            event(
                !supported || lastOperationMessage == null ? json.optString("message") : lastOperationMessage,
                !supported || (lastOperationMessage != null && lastOperationError),
                true,
                isRunning() ? null : false,
                result);
          } catch (android.os.RemoteException e) {
            if (isDestroyed()) return;
            service = null;
            binding = false;
            cancelBindingTimeout();
            String message =
                lastOperationMessage != null
                    ? lastOperationMessage
                    : "Shizuku connection interrupted. Reconnecting...";
            boolean error = lastOperationMessage == null || lastOperationError;
            event(message, error, false, isRunning() ? null : false, null);
            runOnUiThread(this::reconnectIfAuthorized);
          } catch (Throwable e) {
            event(
                lastOperationMessage != null
                    ? lastOperationMessage
                    : "Device check failed: " + e.getMessage(),
                true,
                true,
                isRunning() ? null : false,
                null);
          }
        });
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request != PICK || result != RESULT_OK || data == null || data.getData() == null) return;
    if (isRunning() || operationActive()) {
      event("Wait for the current operation to finish.", false, null, null, null);
      return;
    }
    importSupportId=SupportLog.begin(this,"font_import");
    importSupportStarted=android.os.SystemClock.uptimeMillis();
    Uri uri = data.getData();
    lastOperationMessage = null;
    lastOperationError = false;
    runningJobs.incrementAndGet();
    event("Preparing font…", false, null, true, null);
    worker.execute(() -> importFont(uri));
  }

  private void importFont(Uri uri) {
    File temp = new File(getCacheDir(), "font-import-"+java.util.UUID.randomUUID()+".tmp");
    String path = uri.getPath();
    String name =
        "file".equals(uri.getScheme()) && path != null && !path.isEmpty()
            ? new File(path).getName()
            : "Selected font.ttf";
    try {
      if (!"file".equals(uri.getScheme())) {
        try (Cursor cursor =
            getContentResolver()
                .query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
          if (cursor != null && cursor.moveToFirst()) {
            String displayName = cursor.getString(0);
            if (displayName != null && !displayName.trim().isEmpty()) name = displayName;
          }
        } catch (Throwable ignored) {
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
    } catch (Throwable e) {
      temp.delete();
      finishImportLog(e);
      runningJobs.decrementAndGet();
      event("Font was not imported: " + e.getMessage(), true, null, false, null);
      return;
    }
    event("Checking font format...", false, null, true, null);
    prepareAndPreview(temp, name, 0, true);
  }

  private void prepareAndPreview(File input, String name, int faceIndex, boolean chooseFace) {
    File prepared = new File(getCacheDir(), "prepared-font-"+java.util.UUID.randomUUID()+".tmp");
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
        prepared.delete();
        String[] labels = new String[faces.length()];
        for (int i = 0; i < labels.length; i++) labels[i] = faces.getString(i);
        runOnUiThread(
            () -> {
                if(isDestroyed()||isFinishing()||current.get()!=this){
                  finishImportLog(new java.util.concurrent.CancellationException());
                  input.delete();
                  runningJobs.decrementAndGet();
                  return;
                }
                pendingCollectionInput = input;
                final boolean[] handedOff = new boolean[1];
                final boolean[] canceled = new boolean[1];
                final boolean[] released = new boolean[1];
                AlertDialog dialog =
                    ToolUi.dialog(this)
                        .setTitle("Choose a collection face")
                        .setItems(
                            labels,
                            (d, index) -> {
                              handedOff[0] = true;
                              if (pendingCollectionInput == input) pendingCollectionInput = null;
                              if (!worker.isShutdown()) {
                                try {
                                  worker.execute(() -> prepareAndPreview(input, name, index, false));
                                } catch (RuntimeException rejected) {
                                  if (!released[0]) {
                                    released[0] = true;
                                    runningJobs.decrementAndGet();
                                  }
                                  input.delete();
                                  finishImportLog(rejected);
                                  event("Font was not imported: " + rejected.getMessage(), true, null, false, null);
                                }
                              } else {
                                if (!released[0]) {
                                  released[0] = true;
                                  runningJobs.decrementAndGet();
                                }
                                input.delete();
                              }
                            })
                        .setOnCancelListener(d -> canceled[0] = true)
                        .setOnDismissListener(
                            d -> {
                              if (collectionDialog == d) collectionDialog = null;
                              if (!handedOff[0] && !released[0]) {
                                released[0] = true;
                                if (pendingCollectionInput == input) pendingCollectionInput = null;
                                input.delete();
                                runningJobs.decrementAndGet();
                                finishImportLog(new java.util.concurrent.CancellationException());
                                if (canceled[0] && !isDestroyed() && !isFinishing()) {
                                  event("Collection selection canceled.", false, null, false, null);
                                }
                              }
                            })
                        .create();
                if (collectionDialog != null && collectionDialog.isShowing()) {
                  collectionDialog.dismiss();
                }
                collectionDialog = dialog;
                dialog.show();
            });
        return;
      }
      event("Drawing native preview...", false, null, true, null);
      showPreview(prepared, name, result.getJSONObject("font"));
      finishImportLog(isDestroyed()||isFinishing()||current.get()!=this?new java.util.concurrent.CancellationException():null);
      prepared.delete();
      input.delete();
      runningJobs.decrementAndGet();
      emitPreviewReady();
    } catch (Throwable e) {
      prepared.delete();
      input.delete();
      finishImportLog(e);
      runningJobs.decrementAndGet();
      event("Font was not imported: " + e.getMessage(), true, null, false, null);
    }
  }

  private void emitPreviewReady() {
    event(
        lastOperationMessage == null ? "Preview ready" : lastOperationMessage,
        lastOperationMessage != null && lastOperationError,
        null,
        false,
        null);
  }

  private void finishImportLog(Throwable error) {
    String id=importSupportId;if(id==null)return;importSupportId=null;
    long elapsed=android.os.SystemClock.uptimeMillis()-importSupportStarted;
    if(error==null)SupportLog.finish(this,id,true,"","complete",elapsed);
    else SupportLog.failure(this,id,error,"input",elapsed);
  }

  private File selectionFile() {
    return new File(getFilesDir(), "selected-font.ttf");
  }

  private void restoreSelection(File file) {
    boolean decremented = false;
    try {
      String validated;
      try (ParcelFileDescriptor fd =
          ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) {
        validated = nativeValidateFd(fd.detachFd());
      }
      JSONObject result = new JSONObject(validated);
      if (!result.optBoolean("ok")) throw new IllegalArgumentException(result.optString("message"));
      showPreview(file, getPreferences(MODE_PRIVATE).getString("selected_name", "Selected font"),
          result.getJSONObject("font"));
      runningJobs.decrementAndGet();
      decremented = true;
      emitPreviewReady();
    } catch (Throwable error) {
      runningJobs.decrementAndGet();
      decremented = true;
      if(isDestroyed()||current.get()!=this)return;
      selected = null;
      selectedHash = null;
      nativeEvent("{\"cleared\":true}");
      event("Choose the font again: " + error.getMessage(), true, null, false, null);
    } finally {
      if (!decremented) runningJobs.decrementAndGet();
    }
  }

  private void clearSelection() {
    File file = selectionFile();
    if (file.exists() && !file.delete()) {
      event("Could not remove the app's selected copy. Try again.", true, null, false, null);
      return;
    }
    selected = null;
    selectedHash = null;
    lastOperationMessage = null;
    lastOperationError = false;
    getPreferences(MODE_PRIVATE).edit().remove("selected_name").apply();
    nativeEvent("{\"cleared\":true}");
    event("Selection removed", false, null, false, null);
  }

  static Typeface previewTypeface(File prepared) throws Exception {
    android.graphics.fonts.Font font = new android.graphics.fonts.Font.Builder(prepared).build();
    return new Typeface.CustomFallbackBuilder(
        new android.graphics.fonts.FontFamily.Builder(font).build())
        .setStyle(font.getStyle()).setSystemFallback("sans-serif").build();
  }

  private void showPreview(File prepared, String name, JSONObject fontInfo) throws Exception {
      if(isDestroyed()||current.get()!=this)return;
      Typeface face = previewTypeface(prepared);
      if (face == null) throw new IllegalArgumentException("Android could not load this font");
      TextPaint paint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
      paint.setColor((int) palette(this).optLong("text"));
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
      int previewHeight = Math.max(286, Math.min(512, preview.getHeight() + 40));
      Bitmap bitmap = Bitmap.createBitmap(720, previewHeight, Bitmap.Config.ARGB_8888);
      int[] pixels = new int[720 * previewHeight];
      try {
        Canvas canvas = new Canvas(bitmap);
        canvas.save();
        canvas.translate(28, 20);
        preview.draw(canvas);
        canvas.restore();
        bitmap.getPixels(pixels, 0, 720, 0, 0, 720, previewHeight);
      } finally {
        bitmap.recycle();
      }
      if(isDestroyed()||current.get()!=this)return;
      File destination = selectionFile();
      if (!prepared.equals(destination)) {
        java.nio.file.Files.move(prepared.toPath(), destination.toPath(),
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
      selected = destination;
      selectedHash = fontInfo.getString("sha256");
      getPreferences(MODE_PRIVATE).edit().putString("selected_name", name).apply();
      nativeSelected(
          fontInfo.toString(),
          name == null ? "Selected font" : name,
          pixels,
          720,
          previewHeight);
  }

  private void writeLastOperation(String response) {
    try (FileOutputStream log =
        new FileOutputStream(new File(getFilesDir(), "last-operation.json"))) {
      log.write(response.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    } catch (Throwable ignored) {
    }
  }

  private void cleanImportCache() {
    File cache = getCacheDir();
    if (cache == null) return;
    File[] files = cache.listFiles();
    if (files == null) return;
    for (File file : files) {
      String name = file.getName();
      if ((name.startsWith("font-import-") || name.startsWith("prepared-font-"))
          && name.endsWith(".tmp")) {
        file.delete();
      }
    }
  }

  private void runJob(String action) {
    boolean restore = "restore".equals(action);
    boolean probe = "probe".equals(action);
    IFontService connected = service;
    if (connected == null) {
      event("Connect Shizuku first.", true, false, false, null);
      return;
    }
    if (!restore && !probe && (selected == null || !selected.isFile())) {
      event("Choose a font first.", true, null, false, null);
      return;
    }
    if(!beginOperation())return;
    runningJobs.incrementAndGet();
    lastOperationMessage = null;
    lastOperationError = false;
    event(
        probe
            ? "Checking native access..."
            : restore ? "Restoring system default font..." : "Applying font...",
        false,
        null,
        true,
        null);
    try {
      startActivity(new Intent(this, ApplyActivity.class).putExtra("operation",action));
      worker.execute(
          () -> {
            String supportId = SupportLog.begin(this, "font_" + action);
            long supportStarted = android.os.SystemClock.uptimeMillis();
            String outcomeMessage = "Operation interrupted.";
            boolean outcomeError = true;
            String outcomeResponse = null;
            boolean remoteLost = false;
            try {
              String response;
              if (probe) response = connected.probe();
              else if (restore) response = connected.restore();
              else {
                String hash = selectedHash;
                if (hash == null) throw new IllegalStateException("Choose a font first");
                try (ParcelFileDescriptor fd =
                    ParcelFileDescriptor.open(selected, ParcelFileDescriptor.MODE_READ_ONLY)) {
                  response = connected.apply(fd, hash);
                }
              }
              JSONObject result = new JSONObject(response);
              SupportLog.result(
                  this,
                  supportId,
                  result,
                  android.os.SystemClock.uptimeMillis() - supportStarted);
              outcomeMessage = result.optString("message");
              outcomeError = !result.optBoolean("ok");
              outcomeResponse = response;
              lastOperationMessage = outcomeMessage;
              lastOperationError = outcomeError;
              writeLastOperation(response);
            } catch (Throwable e) {
              SupportLog.failure(
                  this,
                  supportId,
                  e,
                  "dispatch",
                  android.os.SystemClock.uptimeMillis() - supportStarted);
              if (e instanceof android.os.RemoteException) {
                remoteLost = true;
                service = null;
                binding = false;
                cancelBindingTimeout();
              }
              outcomeMessage =
                  "Operation interrupted: "
                      + e.getMessage()
                      + ". Reconnect and check the current font before retrying.";
              outcomeError = true;
              lastOperationMessage = outcomeMessage;
              lastOperationError = true;
              try {
                outcomeResponse =
                    new JSONObject()
                        .put("ok", false)
                        .put("message", outcomeMessage)
                        .toString();
                writeLastOperation(outcomeResponse);
              } catch (Throwable ignored) {
              }
            } finally {
              runningJobs.decrementAndGet();
              deviceOperation.set(false);
              ApplyActivity.finishOperation();
            }
            event(
                outcomeMessage,
                outcomeError,
                remoteLost ? Boolean.FALSE : null,
                false,
                outcomeResponse);
            endOperation();
          });
    } catch(RuntimeException error) {
      runningJobs.decrementAndGet();
      deviceOperation.set(false);
      ApplyActivity.finishOperation();
      lastOperationMessage = "Could not start the operation: " + error.getMessage();
      lastOperationError = true;
      event(lastOperationMessage, true, null, false, null);
      endOperation();
    }
  }

  @Override
  protected void onSaveInstanceState(Bundle state){
    if(toolbox!=null){state.putString("toolbox_page",toolbox.page());state.putString("toolbox_query",toolbox.query());}state.putBoolean("font_page",fontPage);super.onSaveInstanceState(state);
  }

  @Override
  protected void onDestroy() {
    mainHandler.removeCallbacks(resumeReconnectRunnable);
    cancelBindingTimeout();
    AlertDialog dialog = collectionDialog;
    collectionDialog = null;
    if (dialog != null) {
      try {
        dialog.dismiss();
      } catch (Throwable ignored) {
      }
    }
    File pending = pendingCollectionInput;
    pendingCollectionInput = null;
    if (pending != null) pending.delete();
    ToolUi.closeHelp(this);
    if(current.get()==this) current.clear();
    Shizuku.removeBinderReceivedListener(received);
    Shizuku.removeBinderDeadListener(dead);
    Shizuku.removeRequestPermissionResultListener(permission);
    if (service != null || binding) {
      try {
        Shizuku.unbindUserService(
            serviceArgs,
            connection,
            !isChangingConfigurations() && !isRunning() && !operationActive());
      } catch (Throwable ignored) {
      }
    }
    worker.shutdown();
    super.onDestroy();
  }
}
