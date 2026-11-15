package dev.fontlab;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.UUID;
import org.json.JSONObject;

/** Per-app choices survive pack changes; imported images stay in private app storage. */
final class IconChoices {
  static JSONObject get(Context context,ComponentName component) {
    try{return new JSONObject(context.getSharedPreferences("icon-choices",0).getString(component.flattenToString(),"{}"));}
    catch(Exception ignored){return new JSONObject();}
  }
  static File photo(Context context,String name) {
    if(!name.matches("[a-f0-9]{32}\\.png"))throw new IllegalArgumentException("Invalid saved image");
    return new File(new File(context.getFilesDir(),"icon-images"),name);
  }
  static void save(Context context,ComponentName component,JSONObject choice) throws Exception {
    saveAll(context,java.util.Map.of(component,choice));
  }
  static void saveAll(Context context,java.util.Map<ComponentName,JSONObject> choices) {
    if(choices.isEmpty())return;
    android.content.SharedPreferences.Editor edit=context.getSharedPreferences("icon-choices",0).edit();
    boolean cleanupTheme=false;java.util.List<File> photos=new java.util.ArrayList<>();
    for(var item:choices.entrySet()) {
      JSONObject old=get(context,item.getKey()),choice=item.getValue();
      String previous=old.optString("photo");
      if(!previous.isEmpty()&&!previous.equals(choice.optString("photo")))photos.add(photo(context,previous));
      cleanupTheme|=old.has("theme");
      edit.putString(item.getKey().flattenToString(),choice.toString());
    }
    if(!edit.commit())throw new IllegalStateException("Could not save icon choices");
    for(File photo:photos)photo.delete();
    if(cleanupTheme)ThemeImports.cleanup(context);
  }
  static Drawable drawable(Context context,IconPacks.Entry entry,IconPacks.Pack pack) throws Exception {
    JSONObject choice=get(context,entry.component);String image=choice.optString("photo");
    if(!image.isEmpty()) {
      Drawable result=Drawable.createFromPath(photo(context,image).getPath());
      if(result==null)throw new IllegalStateException("Choose the image again for "+entry.label);return result;
    }
    if(choice.has("app")) {
      ComponentName app=ComponentName.unflattenFromString(choice.getString("app"));
      if(app==null)throw new IllegalStateException("Choose the source app again");
      return context.getPackageManager().getActivityIcon(app);
    }
    if(choice.has("theme"))return ThemeImports.drawable(context,choice.getString("theme"),choice.getString("icon"));
    if(!choice.has("pack")&&!entry.themeId.isEmpty())return ThemeImports.drawable(context,entry.themeId,entry.drawableName);
    String pkg=choice.optString("pack",pack.pkg),name=choice.optString("drawable",entry.drawableName==null?"":entry.drawableName);
    if(pkg.isEmpty()||name.isEmpty())throw new IllegalStateException("Choose an icon for "+entry.label);
    android.content.res.Resources resources=context.getPackageManager().getResourcesForApplication(pkg);
    int id=resources.getIdentifier(name,"drawable",pkg);
    if(id==0)throw new IllegalStateException("Icon unavailable. Choose another icon for "+entry.label);
    return resources.getDrawable(id,null);
  }
  static Bitmap bitmap(Context context,IconPacks.Entry entry,IconPacks.Pack pack) throws Exception {
    return IconPacks.bitmap(drawable(context,entry,pack));
  }
  static boolean ready(Context context,IconPacks.Entry entry) {
    return entry.supported&&(entry.hasArtwork()||get(context,entry.component).length()!=0);
  }
  static void importPhoto(Context context,ComponentName component,Uri uri) throws Exception {
    File input=File.createTempFile("icon-import-",".image",context.getCacheDir()),output=null;
    try {
      try(InputStream in=context.getContentResolver().openInputStream(uri);FileOutputStream out=new FileOutputStream(input)) {
        if(in==null)throw new IllegalArgumentException("Image unavailable");byte[] buffer=new byte[16384];int size=0,n;
        while((n=in.read(buffer))!=-1){size+=n;if(size>32*1024*1024)throw new IllegalArgumentException("Choose an image smaller than 32 MB");out.write(buffer,0,n);}
      }
      Bitmap decoded=ImageDecoder.decodeBitmap(ImageDecoder.createSource(input),(decoder,info,source)->{
        int width=info.getSize().getWidth(),height=info.getSize().getHeight();
        if(width<1||height<1||width>100000||height>100000)throw new IllegalArgumentException("Unsupported image dimensions");
        float scale=Math.min(1f,256f/Math.max(width,height));decoder.setTargetSize(Math.max(1,Math.round(width*scale)),Math.max(1,Math.round(height*scale)));
        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
      });
      Bitmap image=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);
      try {
        float scale=Math.min(256f/decoded.getWidth(),256f/decoded.getHeight()),w=decoded.getWidth()*scale,h=decoded.getHeight()*scale;
        new Canvas(image).drawBitmap(decoded,null,new RectF((256-w)/2,(256-h)/2,(256+w)/2,(256+h)/2),new Paint(Paint.FILTER_BITMAP_FLAG));
        String name=UUID.randomUUID().toString().replace("-","")+".png";output=photo(context,name);
        File folder=output.getParentFile();if(!folder.isDirectory()&&!folder.mkdirs())throw new IllegalStateException("Could not save image");
        try(FileOutputStream out=new FileOutputStream(output)){if(!image.compress(Bitmap.CompressFormat.PNG,100,out))throw new IllegalStateException("Could not save image");out.getFD().sync();}
        save(context,component,new JSONObject().put("photo",name));output=null;
      }finally{decoded.recycle();image.recycle();}
    }finally{input.delete();if(output!=null)output.delete();}
  }
}
