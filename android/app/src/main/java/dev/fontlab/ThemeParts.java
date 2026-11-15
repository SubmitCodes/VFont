package dev.fontlab;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.json.*;
import org.w3c.dom.*;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

/** Explicit component boundaries; unrecognized artwork is never guessed to be a status icon. */
final class ThemeParts {
  static final Map<String,String> LABELS=new LinkedHashMap<>();
  static {
    LABELS.put("status","Status bar: battery & network icons");LABELS.put("system","System UI, including Quick Settings");
    LABELS.put("home","Home wallpaper");LABELS.put("lockpaper","Lock wallpaper");LABELS.put("lock","Lock screen");
    LABELS.put("icons","App icons");LABELS.put("launcher","Home screen style");LABELS.put("apps","App & shared styling");
  }
  static Set<String> selection(JSONArray array) throws Exception {
    if(array==null)return null;Set<String> out=new LinkedHashSet<>();
    for(int i=0;i<array.length();i++)if(!LABELS.containsKey(array.getString(i))||!out.add(array.getString(i)))throw new IOException("Invalid theme component selection");
    if(out.isEmpty()||out.contains("status")&&out.contains("system"))throw new IOException("Choose components, using either status icons or the complete System UI");return out;
  }
  static String group(String path){
    String root=path.split("/",2)[0];
    if(root.equals("style")||root.equals("theme.xml"))return "icons";
    if(root.equals("icons"))return "icons";
    if(root.equals("launcher")){String name=new File(path).getName();return name.startsWith("icon_")||name.equals("iconsize.xml")||name.startsWith("clone_flag_")?"icons":"launcher";}
    if(root.equals("com.bbk.launcher2"))return "launcher";
    if(root.equals("lockscreen"))return "lock";
    if(root.equals("wallpaper")){String name=new File(path).getName();return name.startsWith("default_lock_wallpaper.")?"lockpaper":name.startsWith("default_wallpaper.")?"home":"";}
    if(root.equals("com.android.systemui")||root.equals("com.vivo.systemuiplugin"))return "system";
    if(root.contains(".")&&!root.endsWith(".xml")||root.equals("vivo")||root.equals("dialer_contacts"))return "apps";
    return "";
  }
  static JSONArray available(File archive) throws Exception {
    Set<String> found=new HashSet<>();try(ThemeArchive input=new ThemeArchive(archive,"en_US")){
      for(String path:input.members.keySet()){String group=group(path);if(!group.isEmpty())found.add(group);if(path.equals("com.android.systemui"))found.add("status");}
    }
    JSONArray out=new JSONArray();for(String key:LABELS.keySet())if(found.contains(key))out.put(key);return out;
  }
  static void launcher(File source,File current,File output,Set<String> parts)throws Exception {
    FullThemeBundle.copy(current,output);if(!output.exists()&&!output.mkdir())throw new IOException("Cannot prepare launcher resources");
    try(var paths=Files.walk(output.toPath())){
      for(Path path:paths.filter(Files::isRegularFile).collect(java.util.stream.Collectors.toList()))if(parts.contains(group("launcher/"+output.toPath().relativize(path).toString().replace(File.separatorChar,'/'))))Files.delete(path);
    }
    if(source!=null&&source.exists()){
      if(Files.isSymbolicLink(source.toPath())||!source.isDirectory())throw new IOException("Unsupported original launcher resources");
      long total=0;try(var paths=Files.walk(source.toPath())){
        List<Path> files=paths.collect(java.util.stream.Collectors.toList());if(files.size()>20000)throw new IOException("Too many launcher resources");
        for(Path path:files){
          if(Files.isSymbolicLink(path)||!Files.isDirectory(path)&&!Files.isRegularFile(path))throw new IOException("Unsupported launcher resource");
          String relative=source.toPath().relativize(path).toString().replace(File.separatorChar,'/');
          if(Files.isRegularFile(path)&&parts.contains(group("launcher/"+relative))){
            if(Files.size(path)>64L*1024*1024||(total+=Files.size(path))>256L*1024*1024)throw new IOException("Launcher resources exceed the supported size");
            File target=new File(output,relative);Files.createDirectories(target.getParentFile().toPath());Files.copy(path,target.toPath());
            android.system.Os.chmod(target.getPath(),0644);try(FileOutputStream sync=new FileOutputStream(target,true)){sync.getFD().sync();}
          }
        }
      }
    }
    try(var paths=Files.walk(output.toPath())){for(Path path:paths.filter(Files::isDirectory).collect(java.util.stream.Collectors.toList()))android.system.Os.chmod(path.toString(),0755);}
  }
  static boolean statusImage(String path){
    String name=new File(path).getName();
    return name.endsWith(".png")&&(name.startsWith("vivo_battery_")||name.startsWith("vivo_save_mode_battery_")||name.startsWith("vivo_superx_battery_")
        ||name.startsWith("vivo_wifi_")||name.startsWith("vivo_double_wifi_")||name.startsWith("vivo_deputy_wifi_")||name.startsWith("vivo_deputy_merge_wifi_")||name.startsWith("vivo_signal_")||name.startsWith("stat_sys_")
        ||name.startsWith("vivo_stat_sys_")||name.startsWith("ic_")&&name.contains("mobiledata"));
  }
  private static boolean statusValue(String name){return name.startsWith("vivo_battery_")||name.startsWith("vivo_statusbar_battery_")||name.startsWith("vivo_status_bar_icon_size");}
  private static Map<String,byte[]> zip(File file) throws Exception {
    Map<String,byte[]> out=new TreeMap<>();if(file==null||!file.exists())return out;
    if(Files.isSymbolicLink(file.toPath())||!file.isFile()||file.length()>64L*1024*1024)throw new IOException("Unsupported System UI archive");
    long total=0;Set<String> seen=new HashSet<>();try(ZipFile zip=new ZipFile(file)){
      for(var entries=zip.entries();entries.hasMoreElements();){ZipEntry e=entries.nextElement();ThemeArchive.validPath(e.getName(),e.isDirectory());
        if(!seen.add(e.getName().toLowerCase(Locale.ROOT))||seen.size()>20000||e.getSize()<0||e.getSize()>64L*1024*1024||(total+=e.getSize())>128L*1024*1024)throw new IOException("Unsupported System UI member");
        if(e.isDirectory())continue;try(InputStream in=zip.getInputStream(e)){byte[] data=FullThemeStore.readBounded(in,(int)e.getSize()+1);CRC32 crc=new CRC32();crc.update(data);if(data.length!=e.getSize()||crc.getValue()!=e.getCrc())throw new IOException("Damaged System UI member");out.put(e.getName(),data);}
      }
    }return out;
  }
  static void status(File source,File current,File output) throws Exception {
    status(source,current,output,true);
  }
  static void status(File source,File current,File output,boolean requireArtwork) throws Exception {
    Map<String,byte[]> incoming=zip(source),merged=zip(current);int count=0;
    merged.keySet().removeIf(ThemeParts::statusImage);
    for(var entry:incoming.entrySet())if(statusImage(entry.getKey())){merged.put(entry.getKey(),entry.getValue());count++;}
    if(count==0&&requireArtwork)throw new IOException("This theme has no recognized battery/network artwork to separate");
    Document doc=merged.containsKey("theme_values.xml")?ThemeArchive.xml(merged.get("theme_values.xml"),"vivo_theme_values").getOwnerDocument():javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
    if(doc.getDocumentElement()==null)doc.appendChild(doc.createElement("vivo_theme_values"));Element root=doc.getDocumentElement();
    for(Node n=root.getFirstChild();n!=null;){Node next=n.getNextSibling();if(n instanceof Element e&&statusValue(e.getAttribute("name")))root.removeChild(n);n=next;}
    if(incoming.containsKey("theme_values.xml")){Element values=ThemeArchive.xml(incoming.get("theme_values.xml"),"vivo_theme_values");
      for(Node n=values.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element e&&statusValue(e.getAttribute("name")))root.appendChild(doc.importNode(n,true));}
    TransformerFactory factory=TransformerFactory.newInstance();factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING,true);
    ByteArrayOutputStream xml=new ByteArrayOutputStream();factory.newTransformer().transform(new DOMSource(doc),new StreamResult(xml));merged.put("theme_values.xml",xml.toByteArray());
    Files.createDirectories(output.getParentFile().toPath());try(FileOutputStream file=new FileOutputStream(output);ZipOutputStream zip=new ZipOutputStream(file)){
      for(var entry:merged.entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue());zip.closeEntry();}zip.finish();file.getFD().sync();
    }
  }
}
