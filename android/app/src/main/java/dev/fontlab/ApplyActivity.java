package dev.fontlab;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;

/** Keeps the operation visible while its worker completes. */
public final class ApplyActivity extends Activity {
  private static volatile ApplyActivity current;
  private static volatile boolean closing;

  static void clearPendingClose() {
    closing = false;
  }

  static void finishOperation() {
    closing = true;
    ApplyActivity activity = current;
    if (activity != null) activity.finish();
  }

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    if (closing || !MainActivity.operationActive()) {
      finish();
      return;
    }
    current = this;
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    if (Build.VERSION.SDK_INT >= 33) {
      getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
          android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
          () -> {
            if (!MainActivity.operationActive()) finish();
          });
    }
    JSONObject colors = MainActivity.palette(this);
    MainActivity.styleSystemBars(getWindow(), colors);
    LinearLayout content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setGravity(Gravity.CENTER);
    int pad = ToolUi.dp(this, 24);
    content.setPadding(pad, pad, pad, pad);
    content.setBackgroundColor((int) colors.optLong("background"));
    content.setOnApplyWindowInsetsListener((view, insets) -> {
      if (Build.VERSION.SDK_INT >= 30) {
        android.graphics.Insets safe = insets.getInsets(
            WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        view.setPadding(safe.left + pad, safe.top + pad, safe.right + pad, safe.bottom + pad);
      }
      return insets;
    });
    TextView mark = new TextView(this);
    boolean icons=getIntent().getBooleanExtra("icons",false);
    mark.setText(icons ? "▦" : "Aa");
    mark.setTextColor((int) colors.optLong("primary"));
    mark.setTextSize(40);
    mark.setGravity(Gravity.CENTER);
    int size = (int) (48 * getResources().getDisplayMetrics().density);
    content.addView(mark, new LinearLayout.LayoutParams(size * 2, size * 2));
    TextView message = new TextView(this);
    String operation=getIntent().getStringExtra("operation");
    String label="check".equals(operation)||"calendar-check".equals(operation) ? "Checking compatibility…" :
        "reset".equals(operation)||"confirm".equals(operation)||"calendar-reset".equals(operation) ? "Restoring icons…" : "Applying icons…";
    message.setText(icons ? label+"\n\nKeep VFont open until this finishes." :
        ("probe".equals(operation) ? "Checking native access..." : "restore".equals(operation) ? "Restoring system default font..." : "Applying font...")+"\n\nKeep VFont open until this finishes.");
    message.setTextColor((int) colors.optLong("text"));
    message.setTextSize(18);
    message.setGravity(Gravity.CENTER);
    message.setPadding(0, size / 2, 0, 0);
    content.addView(message);
    setContentView(content);
    content.requestApplyInsets();
  }

  @Override protected void onResume() {
    super.onResume();
    if (closing || !MainActivity.operationActive()) {
      finish();
    }
  }

  @Override public void onBackPressed() {
    if (!MainActivity.operationActive()) super.onBackPressed();
  }

  @Override protected void onDestroy() {
    if (current == this) current = null;
    super.onDestroy();
  }
}
