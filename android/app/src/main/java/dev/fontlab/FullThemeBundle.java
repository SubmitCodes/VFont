package dev.fontlab;

import android.system.Os;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.json.*;
import org.w3c.dom.Element;

/** Stages native resources and optional adjustable icons before journaled publication. */
final class FullThemeBundle {
  final Map<String,File> resources=new TreeMap<>();
  final JSONObject metadata=new JSONObject();
  private static String text(Element root,String tag) throws IOException {
    var nodes=root.getElementsByTagName(tag);if(nodes.getLength()!=1)throw new IOException("Missing or ambiguous theme "+tag);
    String value=nodes.item(0).getTextContent().trim();if(value.length()>256)throw new IOException("Theme metadata is too long");return value;
  }
  private static void write(File file,byte[] bytes) throws Exception {
    Files.createDirectories(file.getParentFile().toPath());
    try(FileOutputStream out=new FileOutputStream(file)){out.write(bytes);out.getFD().sync();}Os.chmod(file.getPath(),0644);
  }
  private static byte[] read(ZipFile zip,ZipEntry entry,int max) throws Exception {
    if(entry==null||entry.isDirectory()||entry.getSize()<0||entry.getSize()>max)throw new IOException("Unsupported lock-screen archive");
    try(var in=zip.getInputStream(entry)){byte[] bytes=FullThemeStore.readBounded(in,max+1);CRC32 crc=new CRC32();crc.update(bytes);
      if(bytes.length>max||bytes.length!=entry.getSize()||crc.getValue()!=entry.getCrc())throw new IOException("Damaged lock-screen archive");return bytes;}
  }
  private static void validateZip(File file) throws Exception {
    try(ZipFile zip=new ZipFile(file)){
      Set<String> names=new HashSet<>();long total=0;int count=0;
      for(var entries=zip.entries();entries.hasMoreElements();){ZipEntry e=entries.nextElement();ThemeArchive.validPath(e.getName(),e.isDirectory());
        if(++count>20000||!names.add(e.getName().toLowerCase(Locale.ROOT))||e.getSize()<0||e.getSize()>64L*1024*1024||(total+=e.getSize())>512L*1024*1024)throw new IOException("Unsupported nested theme archive");
      }
    }
  }
  FullThemeBundle(File archive,File work,String expectedHash) throws Exception {
    this(archive,work,expectedHash,null,new File("/data/bbkcore/theme"));
  }
  FullThemeBundle(File archive,File work,String expectedHash,Set<String> parts,File current) throws Exception {
    this(archive,work,expectedHash,parts,current,false,ThemeIconControls.STOCK);
  }
  FullThemeBundle(File archive,File work,String expectedHash,Set<String> parts,File current,boolean adjustable,File stock) throws Exception {
    if(!expectedHash.matches("[a-f0-9]{64}")||!IconThemeStore.hash(archive).equals(expectedHash))throw new IOException("The selected theme changed");
    File theme=new File(work,"theme");if(!theme.mkdir())throw new IOException("Cannot prepare native theme resources");
    try(ThemeArchive input=new ThemeArchive(archive,"en_US")){
      boolean adjust=adjustable&&(parts==null||parts.contains("icons"))&&!input.icons.isEmpty();
      // Every member is read below with its size and CRC checked while staging.
      Element xml=ThemeArchive.xml(input.read("description.xml",65536));String id=text(xml,"id");
      if(!id.matches("[0-9]{1,30}"))throw new IOException("The native theme needs a numeric identity");
      String configId=id.length()>=10?id.substring(id.length()-9):id;
      if(Long.parseLong(configId)==0)throw new IOException("The native theme identity is invalid");
      if(parts==null&&input.components.contains("Fonts"))throw new IOException("This theme includes a font. Its font needs a separate verified recovery path before full Apply");
      if(parts!=null){
        Set<String> available=new HashSet<>();JSONArray found=ThemeParts.available(archive);for(int i=0;i<found.length();i++)available.add(found.getString(i));
        if(!available.containsAll(parts))throw new IOException("The selected components are not included in this theme");
        if(parts.contains("icons")||parts.contains("launcher"))ThemeParts.launcher(null,new File(current,"launcher"),new File(theme,"launcher"),parts);
        if(parts.contains("home")||parts.contains("lockpaper")){
          copy(new File(current,"wallpaper"),new File(theme,"wallpaper"));
          for(String side:List.of("home","lockpaper"))if(parts.contains(side))for(String extension:List.of("png","jpg"))Files.deleteIfExists(new File(theme,"wallpaper/"+(side.equals("home")?"default_wallpaper.":"default_lock_wallpaper.")+extension).toPath());
        }
      }
      for(String path:input.members.keySet()){
        byte[] data=input.read(path,(int)IconThemeStore.MAX_FILE);
        if(parts!=null&&!parts.contains(ThemeParts.group(path)))continue;
        if(adjust&&(path.equals("theme.xml")||path.startsWith("style/1/")&&!path.startsWith("style/1/icons/")))continue;
        String name=path.split("/",2)[0];
        if(!name.matches("[A-Za-z_][A-Za-z0-9_.-]{0,199}")||name.startsWith(".vfont-")||name.equals("disassembleapply")||path.matches("(?i).*\\.(apk|dex|so|ttf|otf)$"))throw new IOException("Unsupported full-theme component: "+name);
        File file=new File(theme,path);write(file,data);
      }
      if(adjust)ThemeIconControls.prepare(input,theme,stock,metadata);
      if(parts!=null&&parts.contains("status")){
        File source=new File(work,"status-source");write(source,input.read("com.android.systemui",(int)IconThemeStore.MAX_FILE));
        ThemeParts.status(source,new File(current,"com.android.systemui"),new File(theme,"com.android.systemui"));
        Os.chmod(new File(theme,"com.android.systemui").getPath(),0644);
      }
      boolean nativeResources=parts==null||parts.contains("status")||parts.contains("system")||parts.contains("apps")||parts.contains("launcher");
      // Vivo selects built-in shared resources when the native compatMix marker is absent.
      if(nativeResources&&!new File(theme,"compatMix").exists())write(new File(theme,"compatMix"),new byte[0]);
      // Native resources must be traversable by the launcher, System UI and widget processes.
      try(var paths=Files.walk(theme.toPath())){for(Path path:paths.collect(java.util.stream.Collectors.toList()))if(Files.isDirectory(path))Os.chmod(path.toString(),0755);}
      for(File file:theme.listFiles())resources.put("theme/"+file.getName(),file);
      metadata.put("sha256",expectedHash).put("id",id).put("config_id",Integer.parseInt(configId)).put("title",input.title);
      metadata.put("native_resources",nativeResources);
      if(parts!=null)metadata.put("partial",true).put("components",new JSONArray(parts));
      for(String side:List.of("home","lock")){
        String prefix=side.equals("home")?"wallpaper/default_wallpaper":"wallpaper/default_lock_wallpaper";
        File paper=new File(theme,prefix+".png");if(!paper.isFile())paper=new File(theme,prefix+".jpg");
        if(paper.isFile()&&(parts==null||parts.contains(side.equals("home")?"home":"lockpaper")))metadata.put(side+"_wallpaper",paper.getName()).put(side+"_sha256",IconThemeStore.hash(paper));
      }
      File lockscreen=new File(theme,"lockscreen");
      if(lockscreen.isFile()){
        validateZip(lockscreen);File inner=new File(work,"lockscreen.itz");
        try(ZipFile outer=new ZipFile(lockscreen)){
          ZipEntry selected=null;for(var entries=outer.entries();entries.hasMoreElements();){ZipEntry e=entries.nextElement();if(e.getName().endsWith(".itz")){if(selected!=null)throw new IOException("Multiple lock-screen bundles need review");selected=e;}}
          if(selected==null)throw new IOException("This lock-screen format needs review");write(inner,read(outer,selected,(int)IconThemeStore.MAX_FILE));
        }
        validateZip(inner);
        try(ZipFile zip=new ZipFile(inner)){
          Element lock=ThemeArchive.xml(read(zip,zip.getEntry("description.xml"),65536));String lockId=text(lock,"id"),style=text(lock,"designer"),title=lockTitle(lock,zip);
          if(!lockId.matches("[0-9]{1,30}"))throw new IOException("Unsupported lock-screen theme identity");
          if(!style.equals("-12"))throw new IOException("Unsupported lock-screen engine version ("+style+"; expected -12)");
          File output=new File(work,"vfont-"+expectedHash+".zip");write(output,read(zip,zip.getEntry("lockscreen/"+title+".zip"),(int)IconThemeStore.MAX_FILE));validateZip(output);
          resources.put("unlock/"+output.getName(),output);metadata.put("lock_id",lockId).put("lock_file",output.getName());
        }
      }
      if(parts==null||parts.contains("icons")){File custom=new File(work,"custom");if(!custom.mkdir())throw new IOException("Cannot prepare native theme icons");Os.chmod(custom.getPath(),0777);resources.put("custom",custom);}
      if(!IconThemeStore.hash(archive).equals(expectedHash))throw new IOException("The theme source changed while preparing it");
    }
  }
  private static String lockTitle(Element lock,ZipFile zip) throws IOException {
    var nodes=lock.getElementsByTagName("title");if(nodes.getLength()==0)throw new IOException("Missing lock-screen title");
    Set<String> matched=new LinkedHashSet<>();
    for(int i=0;i<nodes.getLength();i++){
      String candidate=nodes.item(i).getTextContent().trim();
      if(candidate.length()<=256&&candidate.matches("[A-Za-z0-9_-]{1,120}")&&zip.getEntry("lockscreen/"+candidate+".zip")!=null)matched.add(candidate);
    }
    if(matched.size()==1)return matched.iterator().next();
    if(matched.size()>1)throw new IOException("Multiple lock-screen packages in theme metadata");
    throw new IOException("Unsupported lock-screen archive");
  }
  static void copy(File source,File output) throws Exception {
    if(!source.exists())return;if(Files.isSymbolicLink(source.toPath())||!source.isDirectory())throw new IOException("Unsupported current wallpaper resources");
    long total=0;try(var paths=Files.walk(source.toPath())){List<Path> files=paths.collect(java.util.stream.Collectors.toList());if(files.size()>20000)throw new IOException("Too many current wallpaper resources");
      for(Path path:files){if(Files.isSymbolicLink(path))throw new IOException("Linked current wallpaper resource");File target=new File(output,source.toPath().relativize(path).toString());
        if(Files.isDirectory(path)){Files.createDirectories(target.toPath());Os.chmod(target.getPath(),0755);}else {if(!Files.isRegularFile(path)||(total+=Files.size(path))>256L*1024*1024)throw new IOException("Unsupported current wallpaper resources");write(target,Files.readAllBytes(path));}}
    }
  }
}
