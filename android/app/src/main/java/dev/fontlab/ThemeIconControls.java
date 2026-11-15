package dev.fontlab;

import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.system.Os;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import org.json.JSONObject;

/** Adapts imported artwork to this phone's native adjustable style, before publication. */
final class ThemeIconControls {
  static final File STOCK=new File("/system/etc/theme/style/1");
  private static void write(File file,byte[] data) throws Exception {
    Files.createDirectories(file.getParentFile().toPath());
    try(FileOutputStream out=new FileOutputStream(file)){out.write(data);out.getFD().sync();}
    Os.chmod(file.getPath(),0644);
  }
  private static byte[] read(File file,int maximum)throws Exception {
    if(Files.isSymbolicLink(file.toPath())||!file.isFile()||file.length()>maximum)throw new IOException("The phone's adjustable icon style is unavailable");
    try(var in=Files.newInputStream(file.toPath())){byte[] data=FullThemeStore.readBounded(in,maximum+1);if(data.length>maximum)throw new IOException("Native icon style exceeds its limit");return data;}
  }
  static String supportReason(File stock) {
    try{
      IconThemeArchive.validateBase(stock);
      File launcher=new File(stock,"com.bbk.launcher2");
      if(Files.isSymbolicLink(launcher.toPath())||!launcher.isFile())return "The phone does not provide a compatible adjustable icon renderer";
      var config=ThemeArchive.xml(read(new File(stock,"launcher/iconsize.xml"),65536),"ICONSIZE");
      for(String flag:List.of("originostheme","supporticonraduischange")){
        var values=config.getElementsByTagName(flag);
        if(values.getLength()!=1||!values.item(0).getTextContent().trim().equals("1"))return "The phone does not provide a compatible adjustable icon renderer";
      }
      return null;
    }catch(Exception error){
      String message=error.getMessage();
      return message!=null&&!message.isEmpty()?message:"The phone does not provide a compatible adjustable icon renderer";
    }
  }
  private static byte[] artwork(byte[] data)throws Exception {
    Bitmap image=ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(data)),(decoder,info,source)->{
      int w=info.getSize().getWidth(),h=info.getSize().getHeight();
      if(w<1||h<1||w>4096||h>4096||(long)w*h>16_000_000)throw new IllegalArgumentException("Theme icon dimensions are too large");
      float scale=Math.min(1f,512f/Math.max(w,h));decoder.setTargetSize(Math.max(1,Math.round(w*scale)),Math.max(1,Math.round(h*scale)));
      decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
    });
    Bitmap tile=null;
    try{
      tile=IconRaster.filledTile(image,0.30f);ByteArrayOutputStream out=new ByteArrayOutputStream();
      if(!(tile==null?image:tile).compress(Bitmap.CompressFormat.PNG,100,out)||out.size()>4*1024*1024)throw new IOException("Cannot prepare adjustable theme artwork");
      return out.toByteArray();
    }finally{if(tile!=null)tile.recycle();image.recycle();}
  }
  static void prepare(ThemeArchive input,File theme,File stock,JSONObject metadata)throws Exception {
    if(input.icons.isEmpty())throw new IOException("This theme has no supported static icons for adjustment");
    String unsupported=supportReason(stock);
    if(unsupported!=null)throw new IOException(unsupported);
    File style=new File(theme,"style/1");
    FullThemeBundle.copy(new File(stock,"launcher"),new File(style,"launcher"));
    byte[] identity=read(new File(stock,"description.xml"),65536);
    write(new File(theme,"theme.xml"),identity);write(new File(style,"description.xml"),identity);
    write(new File(style,"com.bbk.launcher2"),read(new File(stock,"com.bbk.launcher2"),16*1024*1024));
    // Native layered themes retain their colors, dark variants, live frames and size variants.
    boolean rootLayers=input.members.containsKey("icons/icons_color.json");
    if(rootLayers)for(String path:input.members.keySet())if(path.startsWith("icons/")&&!input.members.containsKey("style/1/"+path))
      write(new File(style,path),input.read(path,16*1024*1024));
    long total=0;int normalized=0;
    for(var icon:input.icons.entrySet()){
      String pkg=icon.getKey(),path=icon.getValue();boolean nativeLayer=path.startsWith("style/1/")||rootLayers;
      if(nativeLayer&&!path.endsWith("/"+pkg+".png"))continue;
      byte[] light=artwork(input.icon(pkg));String prefix=path.substring(0,path.lastIndexOf('/')+1);
      String dark=prefix+"dark/"+path.substring(prefix.length());
      byte[] night=input.members.containsKey(dark)?artwork(input.read(dark,4*1024*1024)):light;
      if((total+=light.length+night.length)>128L*1024*1024)throw new IOException("Adjustable theme artwork exceeds its storage limit");
      for(String suffix:List.of(".png","_1x1.png")){
        write(new File(style,"icons/"+pkg+suffix),light);write(new File(style,"icons/dark/"+pkg+suffix),night);
      }
      normalized++;
    }
    metadata.put("adjustable_icons",true).put("normalized_icons",normalized);
  }
}
