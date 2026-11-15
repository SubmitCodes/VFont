package dev.fontlab;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

/** A small progress indicator that also respects the system animation setting. */
final class SquigglyProgress extends View {
  private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path path=new Path();
  private final int tint;
  private final float density;
  private float progress=-1;
  SquigglyProgress(Context context,int color){
    super(context);tint=color;density=getResources().getDisplayMetrics().density;
    paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(3*density);paint.setStrokeCap(Paint.Cap.ROUND);
    setContentDescription("Theme progress");setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
  }
  void progress(float value){progress=value<0?-1:Math.max(0,Math.min(1,value));setStateDescription(progress<0?"In progress":Math.round(100*progress)+"%");invalidate();}
  @Override protected void onDraw(Canvas canvas){
    super.onDraw(canvas);float start=2*density,end=getWidth()-start,mid=getHeight()/2f;
    paint.setColor((tint&0xffffff)|0x30000000);canvas.drawLine(start,mid,end,mid,paint);
    float filled=progress<0?end:start+(end-start)*progress;
    boolean animate=ValueAnimator.areAnimatorsEnabled();double phase=animate?SystemClock.uptimeMillis()/180.0:0;
    path.reset();
    for(float x=start;x<=filled;x+=density){float y=mid+(float)Math.sin((x-start)/(24*density)*Math.PI*2-phase)*3*density;if(x==start)path.moveTo(x,y);else path.lineTo(x,y);}
    paint.setColor(tint);canvas.drawPath(path,paint);
    if(animate&&isShown())postInvalidateOnAnimation();
  }
  @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){
    super.onInitializeAccessibilityNodeInfo(info);info.setClassName("android.widget.ProgressBar");
    if(progress>=0)info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT,0,1,progress));
  }
}
