package dev.fontlab;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.BitmapDrawable;
import org.json.JSONObject;

/** Extend filled icon tiles beneath the launcher's mask without inventing a backing color. */
final class IconRaster {
  private static Object field(Object object,String name)throws Exception {
    var field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);
  }
  static boolean nativeTileBacking(Context context,JSONObject settings){
    try{
      if(settings.optInt("current_desktop_type")!=1||settings.optInt("theme_icons_style_explore")!=1)return false;
      Context application=new ContextWrapper(context){@Override public Context getApplicationContext(){return this;}};
      Class<?> type=Class.forName("com.vivo.content.IconRedrawManger");Object renderer=type.getMethod("getInstance",Context.class).invoke(null,application);
      if(renderer==null||!Integer.valueOf(1).equals(field(renderer,"mDesktopType"))||!Integer.valueOf(1).equals(field(renderer,"mStyleMode"))||!Boolean.FALSE.equals(field(renderer,"mVivoStyleTheme")))return false;
      var open=type.getDeclaredMethod("isOpenTheme");open.setAccessible(true);if(!Boolean.FALSE.equals(open.invoke(renderer)))return false;
      Object[] templates=(Object[])field(renderer,"mIconTemplates");return roundedMask((Bitmap)field(templates[0],"mMaskBitmap"));
    }catch(Exception|LinkageError unavailable){return false;}
  }
  static boolean nativeCornerAdjustment(Context context,JSONObject settings,JSONObject evidence){
    Bitmap source=null,rendered=null;
    try{
      if(settings.optInt("current_desktop_type")!=1||settings.optInt("theme_icons_style_explore")!=1)return false;
      Context application=new ContextWrapper(context){@Override public Context getApplicationContext(){return this;}};
      Class<?> type=Class.forName("com.vivo.content.IconRedrawManger");Object renderer=type.getMethod("getInstance",Context.class).invoke(null,application);
      var open=type.getDeclaredMethod("isOpenTheme");open.setAccessible(true);
      for(String name:new String[]{"mDesktopType","mStyleMode","mVivoStyleTheme","mStandardShape"})evidence.put(name,String.valueOf(field(renderer,name)));
      evidence.put("open_theme",open.invoke(renderer));
      if(!Integer.valueOf(1).equals(field(renderer,"mDesktopType"))||!Integer.valueOf(1).equals(field(renderer,"mStyleMode"))||!Boolean.FALSE.equals(open.invoke(renderer)))return false;
      boolean launcher=false;Object[] templates=(Object[])field(renderer,"mIconTemplates");Bitmap mask=(Bitmap)field(templates[0],"mMaskBitmap");
      if(!roundedMask(mask)){
        Context home=application.createPackageContext("com.bbk.launcher2",Context.CONTEXT_INCLUDE_CODE|Context.CONTEXT_IGNORE_SECURITY);
        int radius=(Integer)field(renderer,"mVariableIconRadius");
        type=Class.forName("com.example.iconredrawmanager.IconRedrawManager",true,home.getClassLoader());
        renderer=type.getConstructor(Context.class,String.class,int.class,int.class,boolean.class).newInstance(home,IconThemeTask.active()+"/",radius,1,false);launcher=true;
        evidence.put("renderer","launcher").put("mVivoStyleTheme",String.valueOf(field(renderer,"mVivoStyleTheme"))).put("mStandardShape",String.valueOf(field(renderer,"mStandardShape")));
        open=type.getDeclaredMethod("isOpenTheme");open.setAccessible(true);if(!Boolean.FALSE.equals(open.invoke(renderer)))return false;
        templates=(Object[])field(renderer,"mIconTemplates");mask=null;
        for(var bitmapField:templates[0].getClass().getDeclaredFields())if(bitmapField.getType()==Bitmap.class){bitmapField.setAccessible(true);Bitmap candidate=(Bitmap)bitmapField.get(templates[0]);if(roundedMask(candidate)){mask=candidate;break;}}
      }
      evidence.put("rounded_mask",roundedMask(mask));if(!roundedMask(mask))return false;
      source=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(source);Paint paint=new Paint();paint.setColor(0xff00c8c8);canvas.drawRect(48,48,208,208,paint);
      paint.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR));canvas.drawRect(96,96,160,160,paint);
      registerCorners(source,2);
      Drawable drawable=new BitmapDrawable(application.getResources(),source);
      if(Boolean.TRUE.equals(field(renderer,"mVivoStyleTheme"))){
        if(launcher){var method=type.getDeclaredMethod("createVivoStyleThemeIcon",Drawable.class,String.class,String.class,boolean.class,boolean.class,boolean.class);method.setAccessible(true);rendered=(Bitmap)method.invoke(renderer,drawable,"dev.fontlab.rastercheck","",true,false,true);}
        else {var method=type.getDeclaredMethod("createVivoStyleThemeIcon",Drawable.class,String.class,String.class,boolean.class,int.class,boolean.class);method.setAccessible(true);rendered=(Bitmap)method.invoke(renderer,drawable,"dev.fontlab.rastercheck","",true,17,true);}
      }else{
        var method=type.getDeclaredMethod("createThirdThemeIcon",Drawable.class,boolean.class);method.setAccessible(true);rendered=(Bitmap)method.invoke(renderer,drawable,true);
      }
      boolean valid=preservesTransparency(rendered);evidence.put("transparent_output",valid);
      if(rendered!=null){int clear=0,black=0,blackAlpha=0;for(int y=0;y<rendered.getHeight();y++)for(int x=0;x<rendered.getWidth();x++){int pixel=rendered.getPixel(x,y);if((pixel>>>24)==0)clear++;else if((pixel&0xffffff)==0){black++;blackAlpha=Math.max(blackAlpha,pixel>>>24);}}evidence.put("clear",clear).put("black",black).put("black_max_alpha",blackAlpha).put("pixels",rendered.getWidth()*rendered.getHeight()).put("center",Integer.toHexString(rendered.getPixel(rendered.getWidth()/2,rendered.getHeight()/2)));}
      return valid;
    }catch(Exception|LinkageError unavailable){try{Throwable cause=unavailable;while(cause instanceof java.lang.reflect.InvocationTargetException&&cause.getCause()!=null)cause=cause.getCause();evidence.put("error",cause.getClass().getSimpleName()).put("reason",String.valueOf(cause.getMessage())).put("theme_path",IconThemeTask.active());}catch(Exception ignored){}return false;}
    finally{if(rendered!=null&&rendered!=source)rendered.recycle();if(source!=null)source.recycle();}
  }
  static void registerCorners(Bitmap image){
    registerCorners(image,4);
  }
  static void registerCorners(Bitmap image,int size){
    if(size!=2&&size!=4)throw new IllegalArgumentException("Invalid calibrated corner size");
    for(int x:new int[]{0,image.getWidth()-size})for(int y:new int[]{0,image.getHeight()-size})for(int dx=0;dx<size;dx++)for(int dy=0;dy<size;dy++)image.setPixel(x+dx,y+dy,0xff000000);
  }
  static boolean preservesTransparency(Bitmap image){
    if(image==null||image.getWidth()<32||image.getHeight()<32||image.getWidth()>1024||image.getHeight()>1024||(image.getPixel(image.getWidth()/2,image.getHeight()/2)>>>24)!=0)return false;
    int clear=0,color=0,total=image.getWidth()*image.getHeight();
    for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++){
      int pixel=image.getPixel(x,y),alpha=pixel>>>24;if(alpha==0)clear++;
      else {if((pixel&0xffffff)==0&&alpha>8)return false;if((pixel&0xffffff)!=0xffffff)color++;}
    }
    return clear>total*.4&&color>total*.05;
  }
  static boolean roundedMask(Bitmap mask){
    if(mask==null||mask.getWidth()>1024||mask.getHeight()>1024)return false;
    int left=mask.getWidth(),right=-1,top=mask.getHeight(),bottom=-1;
    for(int y=0;y<mask.getHeight();y++)for(int x=0;x<mask.getWidth();x++)if((mask.getPixel(x,y)>>>24)>=248){left=Math.min(left,x);right=Math.max(right,x);top=Math.min(top,y);bottom=Math.max(bottom,y);}
    if(right-left<32||bottom-top<32)return false;
    for(int x:new int[]{left+1,right-1})for(int y:new int[]{top+1,bottom-1})if((mask.getPixel(x,y)>>>24)!=0)return false;
    return true;
  }
  static Bitmap filledTile(Bitmap source){
    return filledTile(source,0.55f);
  }
  static Bitmap filledTile(Bitmap source,float minimumEdge){
    int width=source.getWidth(),height=source.getHeight();if(width>512||height>512||width<16||height<16)return null;
    int[] pixels=new int[width*height];source.getPixels(pixels,0,width,0,0,width,height);
    int left=width,right=-1,top=height,bottom=-1;
    for(int y=0;y<height;y++)for(int x=0;x<width;x++)if((pixels[y*width+x]>>>24)>=248){left=Math.min(left,x);right=Math.max(right,x);top=Math.min(top,y);bottom=Math.max(bottom,y);}
    int w=right-left+1,h=bottom-top+1;
    if(w<width/2||h<height/2||Math.abs(w-h)>Math.max(w,h)/10)return null;
    int[] output=new int[w*h];boolean changed=left!=0||top!=0||w!=width||h!=height;
    for(int y=0;y<h;y++){
      int first=left,last=right,row=(top+y)*width;
      while(first<=last&&(pixels[row+first]>>>24)<248)first++;
      while(last>=first&&(pixels[row+last]>>>24)<248)last--;
      // A filled rounded tile has broad top/bottom edges and no transparent holes.
      if(last-first+1<w*minimumEdge)return null;
      for(int x=first;x<=last;x++)if((pixels[row+x]>>>24)<248)return null;
      for(int x=0;x<w;x++){
        int from=Math.max(first,Math.min(last,left+x));output[y*w+x]=pixels[row+from]|0xff000000;
        changed|=from!=left+x||(pixels[row+from]>>>24)!=255;
      }
    }
    return changed?Bitmap.createBitmap(output,w,h,Bitmap.Config.ARGB_8888):null;
  }
}
