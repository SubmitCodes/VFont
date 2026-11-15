package dev.fontlab;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;

/** Internal native UI above the EGL font preview; MainActivity remains the service owner. */
public final class ToolboxActivity extends Activity {
  Toolbox toolbox;
  private MainActivity main;
  @Override public void onCreate(Bundle state){
    super.onCreate(state);main=MainActivity.owner();
    if(main==null){
      Intent restart=new Intent(this,MainActivity.class);
      if(state!=null){restart.putExtra("toolbox_page",state.getString("page","home"));restart.putExtra("toolbox_query",state.getString("query",""));}
      startActivity(restart);finish();return;
    }
    if(!main.wantsToolbox()){main.cancelToolboxLaunch();finish();return;}
    MainActivity.styleSystemBars(getWindow(),MainActivity.palette(this));
    toolbox=new Toolbox(this,main,state==null?main.toolboxPage():state.getString("page","home"),state==null?main.toolboxQuery():state.getString("query",""));
    main.attachToolbox(toolbox);setContentView(toolbox.view);
    if(state==null&&IconsActivity.hasPendingTrial(this))startActivity(new Intent(this,IconsActivity.class));
    if(Build.VERSION.SDK_INT>=33){
      getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,this::handleBack);
    }
  }
  private void handleBack(){
    if(toolbox!=null&&toolbox.back())return;
    if(main!=null&&main.uiBusy())return;
    if(main!=null&&toolbox!=null)main.detachToolbox(toolbox);
    if(main!=null)main.finish();
    finish();
  }
  @Override public void onBackPressed(){handleBack();}
  @Override protected void onResume(){super.onResume();if(main!=null){main.refreshToolboxConnection();if(toolbox!=null)toolbox.refreshStatus();}}
  @Override protected void onSaveInstanceState(Bundle state){if(toolbox!=null){state.putString("page",toolbox.page());state.putString("query",toolbox.query());}super.onSaveInstanceState(state);}
  @Override public void onConfigurationChanged(Configuration configuration){
    super.onConfigurationChanged(configuration);ToolUi.closeHelp(this);if(toolbox!=null){String page=toolbox.page(),query=toolbox.query();main.detachToolbox(toolbox);toolbox=new Toolbox(this,main,page,query);main.attachToolbox(toolbox);setContentView(toolbox.view);MainActivity.styleSystemBars(getWindow(),MainActivity.palette(this));}
  }
  @Override protected void onDestroy(){ToolUi.closeHelp(this);if(main!=null&&toolbox!=null)main.detachToolbox(toolbox);super.onDestroy();}
}
