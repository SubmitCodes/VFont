package dev.fontlab;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/** Reads a local native theme as data. No archive member becomes an output path. */
final class ThemeArchive implements AutoCloseable {
  static final long MAX_ARCHIVE=256L*1024*1024;
  final ZipFile zip;
  final Map<String,ZipEntry> members=new LinkedHashMap<>();
  final Map<String,String> icons=new TreeMap<>();
  final List<String> previews=new ArrayList<>();
  final Set<String> components=new LinkedHashSet<>();
  final String title,author,version,description;

  ThemeArchive(File file,String locale) throws Exception {
    if(!file.isFile()||file.length()>MAX_ARCHIVE)throw new IOException("Choose a theme smaller than 256 MiB");
    zip=new ZipFile(file);
    try {
      long total=0;Set<String> names=new java.util.HashSet<>();int count=0;
      for(var entries=zip.entries();entries.hasMoreElements();) {
        ZipEntry entry=entries.nextElement();String name=entry.getName();
        if(++count>20000)throw new IOException("The theme has too many files");
        validPath(name,entry.isDirectory());
        if(!names.add(name.toLowerCase(Locale.ROOT)))throw new IOException("The theme has duplicate file names");
        if(entry.isDirectory())continue;
        if(entry.getSize()<0||entry.getSize()>64L*1024*1024||(total+=entry.getSize())>1024L*1024*1024)
          throw new IOException("The unpacked theme is too large");
        members.put(name,entry);
      }
      Element root=xml(read("description.xml",65536));
      title=localized(root,"title",locale);author=localized(root,"author",locale);
      version=localized(root,"version",locale);description=localized(root,"description",locale);
      for(String name:members.keySet()) {
        String lower=name.toLowerCase(Locale.ROOT);
        if(lower.startsWith("preview/")&&(lower.endsWith(".png")||lower.endsWith(".jpg")||lower.endsWith(".jpeg")))previews.add(name);
        String content=lower.startsWith("style/1/")?lower.substring(8):lower;
        String component=content.split("/",2)[0];
        if(component.equals("wallpaper")||component.equals("wallpapers"))components.add("Wallpaper");
        if(component.equals("lockscreen")||component.equals("lock_screen")||component.equals("com.android.keyguard"))components.add("Lock screen");
        if(component.equals("com.android.systemui"))components.add("System UI");
        if(component.equals("fonts")||component.equals("font"))components.add("Fonts");
      }
      previews.sort(String::compareTo);if(previews.size()>4)previews.subList(4,previews.size()).clear();
      // Prefer the complete native style over a root icon directory, then ordinary over 1x1 artwork.
      for(String prefix:new String[]{"style/1/icons/","icons/"})for(String suffix:new String[]{".png","_single_1x1.png","_1x1.png","_single_2x2.png"})
        for(String name:members.keySet())if(name.startsWith(prefix)&&name.endsWith(suffix)) {
          String pkg=name.substring(prefix.length(),name.length()-suffix.length());
          if(pkg.matches(".*_[0-9]x[0-9]")||pkg.endsWith("_bg")||pkg.endsWith("_single"))continue;
          if(packageName(pkg))icons.putIfAbsent(pkg,name);
        }
      if(icons.size()>2000)throw new IOException("The theme has too many static icons");
      if(!icons.isEmpty())components.add("Static icons");
      if(components.isEmpty()&&previews.isEmpty())throw new IOException("No supported theme artwork was found");
    }catch(Throwable error){zip.close();throw error;}
  }
  static Element xml(byte[] metadata) throws Exception {
      return xml(metadata,"theme");
  }
  static Element xml(byte[] metadata,String tag) throws Exception {
      if(metadata.length>65536)throw new IOException("Theme metadata is too large");
      String xml=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(metadata)).toString();
      if(xml.indexOf('\0')>=0||xml.contains("<!DOCTYPE")||xml.contains("<!ENTITY"))throw new IOException("Unsupported theme metadata");
      if(xml.startsWith("\uFEFF"))xml=xml.substring(1);
      var factory=DocumentBuilderFactory.newInstance();factory.setExpandEntityReferences(false);
      var builder=factory.newDocumentBuilder();
      builder.setEntityResolver((publicId,systemId)->{throw new org.xml.sax.SAXException("External theme metadata is unsupported");});
      builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler(){
        @Override public void error(org.xml.sax.SAXParseException error) throws org.xml.sax.SAXException {throw error;}
        @Override public void fatalError(org.xml.sax.SAXParseException error) throws org.xml.sax.SAXException {throw error;}
      });
      Element root;
      try{root=builder.parse(new org.xml.sax.InputSource(new StringReader(xml))).getDocumentElement();}
      catch(org.xml.sax.SAXException error){throw new IOException("Invalid theme metadata",error);}
      if(!root.getTagName().equals(tag))throw new IOException("This is not a supported Vivo theme");
      return root;
  }
  static boolean packageName(String name) {
    return name.length()<=220&&name.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+");
  }
  static void validPath(String name,boolean directory) throws IOException {
    if(name.isEmpty()||name.length()>512||name.startsWith("/")||name.contains("\\")||name.contains(":"))throw new IOException("Unsafe theme file path");
    for(int i=0;i<name.length();i++)if(Character.isISOControl(name.charAt(i)))throw new IOException("Unsafe theme file path");
    String path=directory?name.substring(0,name.length()-1):name;
    for(String part:path.split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals(".."))throw new IOException("Unsafe theme file path");
  }
  private static String localized(Element root,String tag,String locale) throws IOException {
    var nodes=root.getElementsByTagName(tag);String first="",english="";
    for(int i=0;i<nodes.getLength();i++) {
      Element item=(Element)nodes.item(i);String value=item.getTextContent().trim();
      if(value.length()>2048)throw new IOException("Theme metadata is too long");
      if(first.isEmpty())first=value;
      String language=item.getAttribute("locale").replace('-','_');
      if(language.equalsIgnoreCase(locale.replace('-','_')))return value;
      if(language.equalsIgnoreCase("en_US")||language.equalsIgnoreCase("en"))english=value;
    }
    return english.isEmpty()?first:english;
  }
  private static byte[] readBounded(java.io.InputStream in,int limit) throws IOException {
    java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
    byte[] buffer=new byte[8192];int total=0,n;
    while(total<limit&&(n=in.read(buffer,0,Math.min(buffer.length,limit-total)))!=-1)if(n>0){out.write(buffer,0,n);total+=n;}
    return out.toByteArray();
  }
  byte[] read(String name,int maximum) throws IOException {
    ZipEntry entry=members.get(name);
    if(entry==null)throw new IOException("Missing theme file: "+name);
    if(entry.getSize()>maximum)throw new IOException("Theme artwork is too large: "+name);
    try(var input=zip.getInputStream(entry)) {
      byte[] bytes=readBounded(input,maximum+1);CRC32 crc=new CRC32();crc.update(bytes);
      if(bytes.length>maximum||bytes.length!=entry.getSize()||crc.getValue()!=entry.getCrc())throw new IOException("Damaged or oversized theme file: "+name);
      return bytes;
    }
  }
  byte[] icon(String pkg) throws IOException {
    byte[] bytes=read(icons.get(pkg),4*1024*1024);
    byte[] signature={(byte)137,80,78,71,13,10,26,10};
    if(bytes.length<33||!Arrays.equals(signature,Arrays.copyOf(bytes,8))||ByteBuffer.wrap(bytes).getInt(12)!=0x49484452)
      throw new IOException("Unsupported static icon: "+pkg);
    int w=ByteBuffer.wrap(bytes).getInt(16),h=ByteBuffer.wrap(bytes).getInt(20);
    if(w<1||h<1||w>4096||h>4096||(long)w*h>16_000_000)throw new IOException("Theme icon dimensions are too large");
    return bytes;
  }
  void verifyContents() throws IOException {
    byte[] buffer=new byte[65536];
    for(ZipEntry entry:members.values())try(var input=zip.getInputStream(entry)){
      CRC32 crc=new CRC32();long total=0;int n;
      while((n=input.read(buffer))!=-1){total+=n;if(total>entry.getSize())throw new IOException("Oversized theme file: "+entry.getName());crc.update(buffer,0,n);}
      if(total!=entry.getSize()||crc.getValue()!=entry.getCrc())throw new IOException("Damaged theme file: "+entry.getName());
    }
  }
  @Override public void close() throws IOException {zip.close();}
}
