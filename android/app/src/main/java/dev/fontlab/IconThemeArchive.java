package dev.fontlab;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.xml.parsers.DocumentBuilderFactory;

/** Native style-1 icon composer. Renderer metadata stays separate from the import identity. */
final class IconThemeArchive {
  private static final long MAX_FILE=16L*1024*1024, MAX_TOTAL=512L*1024*1024;
  private static final Pattern ORIGIN=Pattern.compile("(<originostheme>\\s*)([01])(\\s*</originostheme>)");
  private static final byte[] PNG={(byte)137,80,78,71,13,10,26,10};

  private static String digest(byte[] bytes) throws Exception {
    StringBuilder result=new StringBuilder();
    for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))result.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
    return result.toString();
  }
  private static void path(String name) throws IOException {
    if(name.isEmpty()||name.startsWith("/")||name.contains("\\")||name.indexOf('\0')>=0
        ||name.indexOf('\n')>=0||name.indexOf('\r')>=0||name.length()>512)
      throw new IOException("Invalid icon theme path");
    for(String part:name.split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals(".."))throw new IOException("Invalid icon theme path");
  }
  private static byte[] readBounded(InputStream in,int limit) throws IOException {
    java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
    byte[] buffer=new byte[8192];int total=0,n;
    while(total<limit&&(n=in.read(buffer,0,Math.min(buffer.length,limit-total)))!=-1)if(n>0){out.write(buffer,0,n);total+=n;}
    return out.toByteArray();
  }
  private static byte[] read(Path file,long maximum) throws IOException {
    if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>maximum)
      throw new IOException("Unexpected icon theme asset");
    try(InputStream input=Files.newInputStream(file)) {
      byte[] bytes=readBounded(input,(int)maximum+1);
      if(bytes.length>maximum)throw new IOException("Icon theme asset is too large");
      return bytes;
    }
  }
  private record Replacement(Path file,String sha256) {
    byte[] bytes() throws Exception {
      byte[] bytes=read(file,4*1024*1024);
      if(!sha256.equals(digest(bytes)))throw new IOException("The selected icon changed during composition");
      return bytes;
    }
  }
  private static void collect(Path root,Path directory,Map<String,String> files,long[] total,int depth) throws Exception {
    if(depth>20||!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))throw new IOException("Unexpected icon theme directory");
    try(var children=Files.newDirectoryStream(directory)) {
      for(Path child:children) {
        if(Files.isSymbolicLink(child))throw new IOException("Icon theme links are unsupported");
        String name=root.relativize(child).toString().replace(File.separatorChar,'/');path(name);
        if(Files.isDirectory(child,LinkOption.NOFOLLOW_LINKS))collect(root,child,files,total,depth+1);
        else {
          byte[] bytes=read(child,MAX_FILE);total[0]+=bytes.length;
          if(files.size()>=8192||total[0]>MAX_TOTAL)throw new IOException("Icon theme exceeds the supported size");
          files.put(name,digest(bytes));
        }
      }
    }
  }
  private static Map<String,String> snapshot(Path root) throws Exception {
    Map<String,String> result=new TreeMap<>();long[] total={0};
    collect(root,root.resolve("icons"),result,total,0);
    collect(root,root.resolve("launcher"),result,total,0);
    for(String name:new String[]{"description.xml","com.bbk.launcher2"}) {
      byte[] bytes=read(root.resolve(name),MAX_FILE);total[0]+=bytes.length;
      if(total[0]>MAX_TOTAL)throw new IOException("Icon theme exceeds the supported size");
      result.put(name,digest(bytes));
    }
    if(!result.containsKey("launcher/iconsize.xml")||!result.containsKey("launcher/icon_mask.png"))
      throw new IOException("The selected icon style has no supported launcher configuration");
    return result;
  }
  private static byte[] configuration(byte[] bytes) throws IOException {
    String text=new String(bytes,StandardCharsets.UTF_8);
    if(text.contains("<!DOCTYPE")||text.contains("<!ENTITY"))throw new IOException("Unsupported icon configuration");
    Matcher match=ORIGIN.matcher(text);
    if(!match.find())throw new IOException("Unsupported icon configuration");
    if(!match.group(2).equals("1"))
      throw new IOException("The selected style does not use the native renderer");
    if(match.find())throw new IOException("Ambiguous icon configuration");
    return bytes;
  }
  private static byte[] rendererIdentity(byte[] bytes) throws IOException {
    if(bytes.length>65536)throw new IOException("Unsupported renderer identity");
    try {
      String text=StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
          .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
      if(text.indexOf('\0')>=0||text.contains("<!DOCTYPE")||text.contains("<!ENTITY"))
        throw new IOException("Unsupported renderer identity");
      if(text.startsWith("\uFEFF"))text=text.substring(1);
      var factory=DocumentBuilderFactory.newInstance();factory.setExpandEntityReferences(false);
      var root=factory.newDocumentBuilder().parse(new org.xml.sax.InputSource(new StringReader(text))).getDocumentElement();
      if(!root.getTagName().equals("theme"))throw new IOException("Invalid renderer identity");
      var ids=root.getElementsByTagName("id");var editions=root.getElementsByTagName("edition");
      var inners=root.getElementsByTagName("innertype");
      if(ids.getLength()!=1||editions.getLength()>1||inners.getLength()!=1)
        throw new IOException("Ambiguous renderer identity");
      if(ids.item(0).getParentNode()!=root||inners.item(0).getParentNode()!=root
          ||editions.getLength()==1&&editions.item(0).getParentNode()!=root)
        throw new IOException("Invalid renderer identity fields");
      String id=ids.item(0).getTextContent().trim();
      String edition=editions.getLength()==0?"1":editions.item(0).getTextContent().trim();
      String inner=inners.item(0).getTextContent().trim();
      if(!id.matches("[1-9][0-9]?")||Integer.parseInt(id)>50||!edition.equals("1")||!inner.matches("[01]"))
        throw new IOException("The selected renderer identity needs review");
      return bytes;
    }catch(IOException error){throw error;}
    catch(Exception error){throw new IOException("Invalid renderer identity",error);}
  }
  private static void png(byte[] bytes) throws IOException {
    if(bytes.length<33||bytes.length>4*1024*1024||!Arrays.equals(PNG,Arrays.copyOf(bytes,8))
        ||bytes[12]!='I'||bytes[13]!='H'||bytes[14]!='D'||bytes[15]!='R')throw new IOException("Invalid replacement icon");
    java.nio.ByteBuffer header=java.nio.ByteBuffer.wrap(bytes);int width=header.getInt(16),height=header.getInt(20);
    if(width<1||height<1||width>4096||height>4096)throw new IOException("Replacement icon dimensions are unsupported");
  }
  private static void entry(ZipOutputStream zip,String name,byte[] bytes,Map<String,String> output,long[] total) throws Exception {
    total[0]+=bytes.length;
    if(output.size()>=8192||total[0]>MAX_TOTAL)throw new IOException("Icon theme exceeds the supported size");
    path(name);if(output.put(name,digest(bytes))!=null)throw new IOException("Duplicate icon theme member");
    ZipEntry entry=new ZipEntry(name);entry.setTime(0);zip.putNextEntry(entry);zip.write(bytes);zip.closeEntry();
  }

  static void validateBase(File base) throws Exception {
    Path root=base.toPath();
    if(!base.getCanonicalFile().equals(base.getAbsoluteFile())||Files.isSymbolicLink(root))throw new IOException("Unexpected icon theme base");
    configuration(read(root.resolve("launcher/iconsize.xml"),65536));
    rendererIdentity(read(root.resolve("description.xml"),65536));
    if(!Files.isDirectory(root.resolve("icons"),LinkOption.NOFOLLOW_LINKS))throw new IOException("Theme icons are unavailable");
    read(root.resolve("launcher/icon_mask.png"),MAX_FILE);read(root.resolve("com.bbk.launcher2"),MAX_FILE);
  }

  /** Rebuild from the original base and the complete current choice map, never the last output. */
  static Map<String,String> write(File base,Map<String,File> choices,String id,File destination) throws Exception {
    if(!id.matches("[0-9]{7,10}")||choices.size()>500)throw new IllegalArgumentException("Invalid icon theme selection");
    Path root=base.toPath().toAbsolutePath().normalize(), target=destination.toPath().toAbsolutePath().normalize();
    if(!root.toFile().getCanonicalFile().toPath().equals(root)||target.startsWith(root))throw new IOException("Invalid icon theme snapshot");
    Map<String,String> original=snapshot(root), output=new TreeMap<>();Map<String,Replacement> overrides=new TreeMap<>();
    Replacement preview=null;
    for(Map.Entry<String,File> choice:new TreeMap<>(choices).entrySet()) {
      String pkg=choice.getKey();
      if(pkg.length()>200||!pkg.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+"))throw new IllegalArgumentException("Invalid icon package");
      byte[] bytes=read(choice.getValue().toPath(),4*1024*1024);png(bytes);
      Replacement icon=new Replacement(choice.getValue().toPath(),digest(bytes));
      if(preview==null)preview=icon;
      for(String prefix:new String[]{"icons/","icons/dark/"})for(String suffix:new String[]{".png","_1x1.png"})overrides.put(prefix+pkg+suffix,icon);
    }
    boolean created=false;long[] total={0};
    try {
      OutputStream file=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);created=true;
      try(ZipOutputStream zip=new ZipOutputStream(file)) {
        for(Map.Entry<String,String> source:original.entrySet()) {
          String name=source.getKey();byte[] bytes=read(root.resolve(name),MAX_FILE);
          if(!source.getValue().equals(digest(bytes)))throw new IOException("The base icon style changed during composition");
          if(name.startsWith("launcher/")&&name.endsWith("/iconsize.xml"))bytes=configuration(bytes);
          if(name.equals("description.xml")) {
            rendererIdentity(bytes);
            // The launcher reads theme.xml first; a custom import ID selects its incompatible folder branch.
            entry(zip,"theme.xml",bytes,output,total);
          }
          entry(zip,"style/1/"+name,overrides.containsKey(name)?overrides.remove(name).bytes():bytes,output,total);
        }
        for(Map.Entry<String,Replacement> override:overrides.entrySet())entry(zip,"style/1/"+override.getKey(),override.getValue().bytes(),output,total);
        String description="<?xml version=\"1.0\" encoding=\"UTF-8\"?><theme><id>"+id+"</id><id3>"+id+"</id3><uid>"+id
            +"</uid><version>1.0.0</version><edition>1</edition><titles><title locale=\"en_US\">VFont icon choices</title>"
            +"<title locale=\"zh_CN\">VFont icon choices</title></titles><authors><author locale=\"en_US\">VFont</author></authors>"
            +"<descriptions><description locale=\"en_US\">Icons selected on this device.</description></descriptions><innertype>0</innertype></theme>";
        entry(zip,"description.xml",description.getBytes(StandardCharsets.UTF_8),output,total);
        // Use the selected artwork for local previews; these images never change the wallpaper.
        byte[] previewBytes=preview==null?read(root.resolve("launcher/icon_mask.png"),MAX_FILE):preview.bytes();
        for(String name:new String[]{"preview/preview_icons_0.png","preview/preview_icons_1.png","thumb/thumbnail_0.png"})entry(zip,name,previewBytes,output,total);
      }
      if(!original.equals(snapshot(root)))throw new IOException("The base icon style changed during composition");
      return output;
    }catch(Exception error){if(created)Files.deleteIfExists(target);throw error;}
  }
}
