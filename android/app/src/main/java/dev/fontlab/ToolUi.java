package dev.fontlab;

import android.animation.ValueAnimator;
import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.*;
import android.os.Build;
import android.view.*;
import android.view.animation.Interpolator;
import android.widget.*;
import org.json.JSONObject;

/** Native port of Essentials' grouped cards/help geometry. See assets/licenses/essentials-MIT.txt. */
final class ToolUi {
  private static java.lang.ref.WeakReference<Dialog> help=new java.lang.ref.WeakReference<>(null);
  private static java.lang.ref.WeakReference<Activity> helpOwner=new java.lang.ref.WeakReference<>(null);
  static int dp(Context c,float value){return Math.round(value*c.getResources().getDisplayMetrics().density);}
  static int color(JSONObject p,String key){return (int)p.optLong(key);}
  static int blend(int a,int b,float t){return Color.rgb(Math.round(Color.red(a)*(1-t)+Color.red(b)*t),Math.round(Color.green(a)*(1-t)+Color.green(b)*t),Math.round(Color.blue(a)*(1-t)+Color.blue(b)*t));}
  static int background(JSONObject p){return color(p,"background");}
  static int pastel(JSONObject p,int hue){return Color.HSVToColor(new float[]{hue,p.optBoolean("dark")?.30f:.22f,p.optBoolean("dark")?.29f:.97f});}
  static int ink(JSONObject p,int hue){return MainActivity.readable(Color.HSVToColor(new float[]{hue,p.optBoolean("dark")?.30f:.62f,p.optBoolean("dark")?.95f:.58f}),pastel(p,hue),4.5);}
  static GradientDrawable shape(Context c,int color,float radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(c,radius));return d;}
  static GradientDrawable grouped(Context c,int color,boolean first,boolean last){GradientDrawable d=new GradientDrawable();d.setColor(color);float top=dp(c,first?24:3),bottom=dp(c,last?24:3);d.setCornerRadii(new float[]{top,top,top,top,bottom,bottom,bottom,bottom});return d;}
  static void ripple(View view,GradientDrawable shape,JSONObject p){view.setBackground(new RippleDrawable(ColorStateList.valueOf((color(p,"primary")&0xffffff)|0x24000000),shape,null));}
  static void buttonStyle(TextView view,JSONObject p,boolean primary){
    view.setStateListAnimator(null);view.setElevation(0);view.setTranslationZ(0);view.setBackgroundTintList(null);view.setAlpha(1);
    GradientDrawable enabled=shape(view.getContext(),color(p,primary?"primary":"surface"),26);if(!primary)enabled.setStroke(dp(view.getContext(),1),color(p,"outline"));
    GradientDrawable disabled=shape(view.getContext(),color(p,"elevated"),26);disabled.setStroke(dp(view.getContext(),1),color(p,"outline"));StateListDrawable states=new StateListDrawable();states.addState(new int[]{-android.R.attr.state_enabled},disabled);states.addState(new int[]{},new RippleDrawable(ColorStateList.valueOf((color(p,"primary")&0xffffff)|0x24000000),enabled,null));
    view.setBackground(states);states.setState(view.getDrawableState());view.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{color(p,"muted"),color(p,primary?"on_primary":"text")}));
  }
  static TextView text(Context c,JSONObject p,String value,int size){TextView v=new TextView(c);v.setText(value);v.setTextSize(size);v.setTextColor(color(p,"text"));v.setIncludeFontPadding(false);return v;}
  static LinearLayout column(Context c){LinearLayout v=new LinearLayout(c);v.setOrientation(LinearLayout.VERTICAL);return v;}
  static AlertDialog.Builder dialog(Activity a){
    JSONObject p=MainActivity.palette(a);return new AlertDialog.Builder(a,p.optBoolean("dark")?android.R.style.Theme_Material_Dialog_Alert:android.R.style.Theme_Material_Light_Dialog_Alert){
      @Override public AlertDialog create(){AlertDialog dialog=super.create();dialog.setOnShowListener(which->{dialog.getWindow().setBackgroundDrawable(shape(a,color(p,"elevated"),28));for(int button:new int[]{-1,-2,-3}){android.widget.Button control=dialog.getButton(button);if(control!=null){buttonStyle(control,p,button==-1);control.setAllCaps(false);control.setMinHeight(dp(a,48));control.setPadding(dp(a,12),dp(a,8),dp(a,12),dp(a,8));}}});return dialog;}
    };
  }
  static ImageView glyph(Context c,int icon,int tint){ImageView v=new ImageView(c);v.setImageResource(icon);v.setImageTintList(ColorStateList.valueOf(tint));v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);v.setScaleType(ImageView.ScaleType.CENTER_INSIDE);return v;}
  static ImageButton iconButton(Activity a,JSONObject p,int icon,String description,Runnable action){
    ImageButton v=new ImageButton(a);v.setImageResource(icon);v.setImageTintList(ColorStateList.valueOf(color(p,"primary")));v.setContentDescription(description);v.setPadding(dp(a,12),dp(a,12),dp(a,12),dp(a,12));GradientDrawable background=shape(a,color(p,"surface"),24);background.setStroke(dp(a,1),color(p,"outline"));ripple(v,background,p);v.setStateListAnimator(null);v.setElevation(0);v.setOnClickListener(w->action.run());press(v);return v;
  }
  // Essentials uses damping .9 / stiffness 500 for entry; normalize the same response to a View animation.
  static final Interpolator SPRING=t->{double seconds=t*.45,omega=Math.sqrt(500),z=.9,wd=omega*Math.sqrt(1-z*z);return t==1?1:(float)(1-Math.exp(-z*omega*seconds)*(Math.cos(wd*seconds)+z/Math.sqrt(1-z*z)*Math.sin(wd*seconds)));};
  static void press(View view){view.setOnTouchListener((v,e)->{if(ValueAnimator.areAnimatorsEnabled()){
    if(e.getActionMasked()==android.view.MotionEvent.ACTION_DOWN)v.animate().scaleX(.985f).scaleY(.985f).setDuration(90).start();
    else if(e.getActionMasked()==android.view.MotionEvent.ACTION_UP||e.getActionMasked()==android.view.MotionEvent.ACTION_CANCEL)v.animate().scaleX(1).scaleY(1).setInterpolator(SPRING).setDuration(240).start();
  }return false;});}
  static void bind(View view,Activity a,String tool){view.setOnLongClickListener(v->{v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);explain(a,tool);return true;});}
  static void enter(View view,boolean forward){
    if(!ValueAnimator.areAnimatorsEnabled())return;view.setAlpha(0);view.post(()->{view.setTranslationX((forward?1:-.12f)*view.getWidth()/3f);view.animate().translationX(0).alpha(1).setDuration(400).setInterpolator(SPRING).start();});
  }
  static void open(Activity a,Intent intent){a.startActivity(intent);if(ValueAnimator.areAnimatorsEnabled())a.overridePendingTransition(dev.fontlab.R.anim.vf_page_enter,dev.fontlab.R.anim.vf_page_exit);}
  static LinearLayout navigation(Activity a,JSONObject p,String title,Runnable back,String helpId){
    LinearLayout outer=new LinearLayout(a);outer.setGravity(Gravity.CENTER);outer.setPadding(dp(a,20),dp(a,12),dp(a,20),dp(a,4));
    LinearLayout pill=new LinearLayout(a);pill.setGravity(Gravity.CENTER_VERTICAL);pill.setPadding(dp(a,8),dp(a,8),dp(a,18),dp(a,8));pill.setBackground(shape(a,color(p,"primary"),40));pill.setElevation(0);
    ImageButton previous=iconButton(a,p,R.drawable.vf_rounded_arrow_back_24,"Back",back);pill.addView(previous,new LinearLayout.LayoutParams(dp(a,48),dp(a,48)));
    TextView label=text(a,p,title,15);label.setTextColor(color(p,"on_primary"));label.setGravity(Gravity.CENTER);label.setSingleLine(true);label.setEllipsize(android.text.TextUtils.TruncateAt.END);label.setTypeface(null,android.graphics.Typeface.BOLD);pill.addView(label,new LinearLayout.LayoutParams(0,-2,1));
    outer.addView(pill,new LinearLayout.LayoutParams(0,-2,1));ImageButton more=iconButton(a,p,R.drawable.vf_rounded_help_24,"About "+title,()->explain(a,helpId));more.setTag("help:"+helpId);bind(pill,a,helpId);
    LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(dp(a,56),dp(a,56));mp.setMarginStart(dp(a,8));outer.addView(more,mp);return outer;
  }
  static boolean closeHelp(Activity owner){Dialog dialog=help.get();if(dialog!=null&&dialog.isShowing()&&helpOwner.get()==owner){dialog.dismiss();return true;}return false;}
  static void explain(Activity a,String id){
    ToolCatalog.Tool tool=ToolCatalog.tool(id);Dialog old=help.get();if(old!=null)old.dismiss();JSONObject p=MainActivity.palette(a);Dialog dialog=new Dialog(a);help=new java.lang.ref.WeakReference<>(dialog);helpOwner=new java.lang.ref.WeakReference<>(a);
    final int baseH=dp(a,16),baseBottom=dp(a,24);
    LinearLayout body=new LinearLayout(a){
      private final android.graphics.Rect frame=new android.graphics.Rect();
      @Override protected void onMeasure(int width,int height){
        a.getWindow().getDecorView().getWindowVisibleDisplayFrame(frame);
        int maxW=dp(a,520);
        if(MeasureSpec.getMode(width)!=MeasureSpec.UNSPECIFIED&&MeasureSpec.getSize(width)>maxW){
          width=MeasureSpec.makeMeasureSpec(maxW,MeasureSpec.getMode(width));
        }
        int frameH=frame.height()>0?frame.height():a.getResources().getDisplayMetrics().heightPixels;
        int specH=MeasureSpec.getSize(height);
        int available=specH>0?Math.min(specH,Math.max(dp(a,180),frameH-dp(a,16))):Math.max(dp(a,180),frameH-dp(a,16));
        int fixed=getPaddingTop()+getPaddingBottom();View scroll=null;
        for(int i=0;i<getChildCount();i++){View child=getChildAt(i);if("help-scroll".equals(child.getTag())){scroll=child;continue;}measureChildWithMargins(child,width,0,MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED),0);LinearLayout.LayoutParams cp=(LinearLayout.LayoutParams)child.getLayoutParams();fixed+=child.getMeasuredHeight()+cp.topMargin+cp.bottomMargin;}
        if(scroll!=null){
          int remaining=Math.max(0,available-fixed);
          int scrollCap=Math.min(Math.round(frameH*.62f),remaining);
          scroll.getLayoutParams().height=-2;
          measureChildWithMargins(scroll,width,0,MeasureSpec.makeMeasureSpec(scrollCap,MeasureSpec.AT_MOST),0);
          scroll.getLayoutParams().height=Math.min(scroll.getMeasuredHeight(),scrollCap);
        }
        super.onMeasure(width,MeasureSpec.makeMeasureSpec(available,MeasureSpec.AT_MOST));
      }
    };body.setOrientation(LinearLayout.VERTICAL);body.setPadding(baseH,baseH,baseH,baseBottom);body.setBackground(shape(a,background(p),28));
    body.setOnApplyWindowInsetsListener((v,insets)->{
      if(Build.VERSION.SDK_INT>=30){
        android.graphics.Insets safe=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
        v.setPadding(baseH+safe.left,baseH,baseH+safe.right,Math.max(baseBottom,safe.bottom+dp(a,16)));
      }
      return insets;
    });
    View handle=new View(a);handle.setBackground(shape(a,color(p,"muted"),4));LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(dp(a,32),dp(a,4));hp.gravity=Gravity.CENTER;hp.bottomMargin=dp(a,22);body.addView(handle,hp);
    LinearLayout heading=new LinearLayout(a);heading.setGravity(Gravity.CENTER_VERTICAL);heading.setPadding(dp(a,8),0,dp(a,8),dp(a,20));
    ImageView icon=glyph(a,tool.icon(),ink(p,tool.hue()));icon.setPadding(dp(a,10),dp(a,10),dp(a,10),dp(a,10));icon.setBackground(shape(a,pastel(p,tool.hue()),30));heading.addView(icon,new LinearLayout.LayoutParams(dp(a,40),dp(a,40)));
    TextView title=text(a,p,tool.title(),20);title.setTypeface(null,android.graphics.Typeface.BOLD);title.setPadding(dp(a,14),0,0,0);heading.addView(title,new LinearLayout.LayoutParams(0,-2,1));body.addView(heading);
    ScrollView scroll=new ScrollView(a);scroll.setTag("help-scroll");scroll.setFillViewport(false);scroll.setVerticalScrollBarEnabled(false);LinearLayout sections=column(a);sections.setPadding(dp(a,16),dp(a,18),dp(a,16),dp(a,18));sections.setBackground(shape(a,color(p,"surface"),24));
    for(String[] section:new String[][]{{"What it does",tool.what()},{"How to use it",tool.how()},{"What it needs",tool.needs()},{"Restore",tool.restore()}}){TextView label=text(a,p,section[0],14);label.setTextColor(color(p,"primary"));label.setTypeface(null,android.graphics.Typeface.BOLD);sections.addView(label);TextView content=text(a,p,section[1],16);content.setLineSpacing(dp(a,3),1);LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.topMargin=dp(a,8);cp.bottomMargin=dp(a,20);sections.addView(content,cp);}
    scroll.addView(sections);body.addView(scroll,new LinearLayout.LayoutParams(-1,-2));
    TextView done=text(a,p,"Done",16);done.setGravity(Gravity.CENTER);done.setMinHeight(dp(a,48));done.setTypeface(null,android.graphics.Typeface.BOLD);done.setTextColor(color(p,"on_primary"));ripple(done,shape(a,color(p,"primary"),26),p);done.setOnClickListener(v->dialog.dismiss());LinearLayout.LayoutParams dp=new LinearLayout.LayoutParams(-1,-2);dp.topMargin=dp(a,16);body.addView(done,dp);
    dialog.setContentView(body);Window window=dialog.getWindow();if(window==null)return;window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);int targetW=a.getResources().getDisplayMetrics().widthPixels>dp(a,520)?dp(a,520):-1;WindowManager.LayoutParams wp=window.getAttributes();wp.width=targetW;wp.height=-2;wp.gravity=Gravity.BOTTOM;wp.dimAmount=.4f;window.setAttributes(wp);dialog.setCanceledOnTouchOutside(true);dialog.show();window.setLayout(targetW,-2);body.requestApplyInsets();
    if(ValueAnimator.areAnimatorsEnabled()){body.setTranslationY(dp(a,100));body.setAlpha(0);body.animate().translationY(0).alpha(1).setDuration(400).setInterpolator(SPRING).start();}
  }
}
