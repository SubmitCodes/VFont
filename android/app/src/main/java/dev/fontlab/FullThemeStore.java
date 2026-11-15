package dev.fontlab;

import android.system.Os;
import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Full-theme resources are moved atomically; the first original inodes survive every Apply. */
final class FullThemeStore implements AutoCloseable {
  static final String NAME="1999901991";
  private final File root,custom,unlock,store,journal;
  private final IconThemeStore.Step step;
  private FileChannel channel;
  private FileLock lock;
  FullThemeStore(File root,File custom,File unlock,IconThemeStore.Step step) throws Exception {
    this.root=root;this.custom=custom;this.unlock=unlock;this.step=step;
    directory(root);directory(custom.getParentFile());directory(unlock);
    store=new File(root,NAME);journal=new File(store,"state.json");
    if(!exists(store)){
      for(File file:root.listFiles())if(file.getName().matches("\\.vfont-full-init-[a-f0-9]{32}")){
        directory(file);String[] names=file.list();if(Os.lstat(file.getPath()).st_uid!=Os.getuid()||names==null||names.length>1||(names.length==1&&!names[0].equals("owner")))throw new IOException("Unknown theme initialization files were kept");
        if(names.length==1){File owner=new File(file,"owner");if(Files.isSymbolicLink(owner.toPath())||!owner.isFile()||owner.length()>19||!"VFONT_FULL_THEME_1\n".startsWith(new String(Files.readAllBytes(owner.toPath()),StandardCharsets.US_ASCII)))throw new IOException("Unknown theme initialization marker");Files.delete(owner.toPath());}
        Files.delete(file.toPath());sync(root);
      }
      File initial=new File(root,".vfont-full-init-"+UUID.randomUUID().toString().replace("-",""));
      if(!initial.mkdir())throw new IOException("Cannot prepare full-theme recovery");Os.chmod(initial.getPath(),0700);
      try(FileOutputStream out=new FileOutputStream(new File(initial,"owner"))){out.write("VFONT_FULL_THEME_1\n".getBytes(StandardCharsets.US_ASCII));out.getFD().sync();}
      sync(initial);Files.move(initial.toPath(),store.toPath(),StandardCopyOption.ATOMIC_MOVE);sync(root);
    }
    directory(store);File owner=new File(store,"owner");
    if(Os.lstat(store.getPath()).st_uid!=Os.getuid()
        ||!owner.isFile()||Files.isSymbolicLink(owner.toPath())||owner.length()!=19
        ||Os.lstat(owner.getPath()).st_uid!=Os.getuid()||Os.lstat(owner.getPath()).st_nlink!=1
        ||!new String(Files.readAllBytes(owner.toPath()),StandardCharsets.US_ASCII).equals("VFONT_FULL_THEME_1\n"))throw new IOException("Unknown full-theme recovery storage; existing files were kept");
    int mode=Os.lstat(store.getPath()).st_mode&0777;
    // Stock Themes recursively chmods its theme root, including numeric VFont storage.
    if(mode==0777){Os.chmod(store.getPath(),0700);Os.chmod(owner.getPath(),0600);sync(store);}
    else if(mode!=0700)throw new IOException("Unexpected full-theme recovery permissions; existing files were kept");
    File file=new File(store,"lock");if(Files.isSymbolicLink(file.toPath()))throw new IOException("Unexpected theme recovery lock");
    channel=FileChannel.open(file.toPath(),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
    lock=channel.tryLock();if(lock==null){channel.close();throw new IOException("A full-theme operation is already running");}
  }
  static boolean exists(File file){return Files.exists(file.toPath(),LinkOption.NOFOLLOW_LINKS);}
  private static void directory(File file) throws Exception {
    if(Files.isSymbolicLink(file.toPath())||!file.isDirectory()||!file.getCanonicalFile().equals(file.getAbsoluteFile()))throw new IOException("Unexpected full-theme directory");
  }
  static List<String> keys(JSONObject value){List<String> out=new ArrayList<>();value.keys().forEachRemaining(out::add);Collections.sort(out);return out;}
  static void sync(File dir) throws Exception {var fd=Os.open(dir.getPath(),android.system.OsConstants.O_RDONLY,0);try{Os.fsync(fd);}finally{Os.close(fd);}}
  static byte[] readBounded(InputStream in,int limit) throws IOException {
    ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int total=0,n;
    while(total<limit&&(n=in.read(buffer,0,Math.min(buffer.length,limit-total)))!=-1)if(n>0){out.write(buffer,0,n);total+=n;}
    return out.toByteArray();
  }
  static void copyStream(InputStream in,OutputStream out) throws IOException {
    byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)if(n>0)out.write(buffer,0,n);
  }
  private void point(String name) throws Exception {if(step!=null)step.reached(name);}
  void claim(String tx) throws Exception {
    if(!IconSystemTask.token(tx,32))throw new IOException("Invalid full-theme transaction");
    File marker=new File(store,"tx-"+tx);if(!marker.createNewFile())throw new IOException("This full-theme transaction was already dispatched; check its current state");
    Os.chmod(marker.getPath(),0600);sync(store);
  }
  private static boolean themeName(String name){return name.matches("[A-Za-z_][A-Za-z0-9_.-]{0,199}")&&!name.startsWith(".vfont-")&&!name.equals(".dwd");}
  private File target(String slot) throws Exception {
    if(slot.equals("custom"))return custom;
    if(slot.startsWith("theme/")&&themeName(slot.substring(6)))return new File(root,slot.substring(6));
    if(slot.matches("unlock/vfont-[a-f0-9]{64}\\.zip"))return new File(unlock,slot.substring(7));
    throw new IOException("Invalid full-theme resource");
  }
  private File saved(String group,String slot) throws Exception {target(slot);return new File(new File(store,group),slot);}
  private void move(File from,File to) throws Exception {
    if(exists(to))throw new IOException("Theme recovery destination already exists");
    Files.createDirectories(to.getParentFile().toPath());directory(to.getParentFile());
    Files.move(from.toPath(),to.toPath(),StandardCopyOption.ATOMIC_MOVE);sync(from.getParentFile());sync(to.getParentFile());point("move:"+from.getName());
  }
  private static JSONObject snapshot(File file) throws Exception {return IconThemeStore.snapshot(file);}
  private static void verify(File file,JSONObject expected) throws Exception {IconThemeStore.verify(file,expected,false);}
  private static boolean matches(File file,JSONObject expected) {try{verify(file,expected);return true;}catch(Exception ignored){return false;}}
  File original(String slot) throws Exception {
    JSONObject value=state(),expected=value.getJSONObject("original").optJSONObject(slot);
    if(expected==null)return null;
    File file=exists(saved("base",slot))?saved("base",slot):target(slot);verify(file,expected);return file;
  }
  boolean originalActive(String slot) throws Exception {return matches(target(slot),state().getJSONObject("original").optJSONObject(slot));}
  JSONObject state() throws Exception {
    if(!exists(journal))return null;
    if(Files.isSymbolicLink(journal.toPath())||!journal.isFile()||journal.length()>32L*1024*1024)throw new IOException("Invalid full-theme journal");
    JSONObject value=new JSONObject(new String(Files.readAllBytes(journal.toPath()),StandardCharsets.UTF_8));
    if(value.optInt("schema")!=1||!value.getString("root").equals(root.getPath()))throw new IOException("Invalid full-theme recovery identity");
    for(String key:keys(value.getJSONObject("original")))target(key);
    return value;
  }
  void save(JSONObject state) throws Exception {IconSystemTask.writeJson(journal,state);Os.chmod(journal.getPath(),0600);sync(store);point("journal:"+state.getString("phase"));}
  private Map<String,File> resourceNames() throws Exception {
    Map<String,File> value=new TreeMap<>();File[] files=root.listFiles();if(files==null)throw new IOException("Cannot inspect current theme");
    for(File file:files){String name=file.getName();if(name.equals(".dwd")||name.matches("[0-9]+")||name.startsWith(".vfont-"))continue;
      if(!themeName(name))throw new IOException("Unrecognized current theme resource ("+name+"); originals were kept");value.put("theme/"+name,file);}
    value.put("custom",custom);return value;
  }
  private JSONObject resources() throws Exception {
    JSONObject value=new JSONObject();for(var entry:resourceNames().entrySet())value.put(entry.getKey(),snapshot(entry.getValue()));return value;
  }
  File beginIconEdit() throws Exception {
    recoverIconEdit();JSONObject value=state();
    if(value==null||!value.getString("phase").equals("active"))throw new IOException("Finish the pending full-theme recovery before editing icons");
    verifyCurrent(value);cleanPrevious(value);cleanWork();value.put("phase","icon-staging");save(value);
    File work=new File(store,"work"),staged=new File(work,"custom");
    if(!work.mkdir())throw new IOException("Cannot prepare icon overrides");Os.chmod(work.getPath(),0700);sync(store);
    JSONObject original=value.getJSONObject("current").optJSONObject("custom");if(original==null)throw new IOException("Native icon storage is unavailable");
    for(String path:keys(original)){
      JSONObject row=original.getJSONObject(path);File source=path.isEmpty()?custom:new File(custom,path),target=path.isEmpty()?staged:new File(staged,path);
      if(row.getBoolean("d")){if(!target.mkdir())throw new IOException("Cannot copy icon override storage");}
      else {
        Files.copy(source.toPath(),target.toPath(),LinkOption.NOFOLLOW_LINKS);
        if(Files.isSymbolicLink(target.toPath())||!target.isFile()||!IconThemeStore.hash(target).equals(row.getString("sha")))throw new IOException("Icon override source changed during preparation");
        try(FileOutputStream out=new FileOutputStream(target,true)){out.getFD().sync();}
      }
      Os.chmod(target.getPath(),row.getInt("mode")&0777);point("icon-copy:"+path);
    }
    verify(custom,original);return staged;
  }
  void commitIconEdit(JSONArray refresh) throws Exception {
    JSONObject value=state();if(value==null||!value.getString("phase").equals("icon-staging"))throw new IOException("Missing icon override preparation");
    verifyCurrent(value);File staged=new File(store,"work/custom");JSONObject next=snapshot(staged);if(next==null)throw new IOException("Missing prepared icon overrides");
    for(String path:keys(next))if(next.getJSONObject(path).getBoolean("d"))sync(path.isEmpty()?staged:new File(staged,path));
    boolean baseline=!exists(saved("base","custom"))&&matches(custom,value.getJSONObject("original").optJSONObject("custom"));
    value.put("icon_baseline",baseline).put("icon_old",value.getJSONObject("current").getJSONObject("custom")).put("icon_next",next).put("icon_refresh",refresh).put("phase","icon-installing");save(value);recoverIconEdit();
  }
  void abortIconEdit() throws Exception {
    JSONObject value=state();if(value==null||!value.getString("phase").equals("icon-staging"))return;
    verifyCurrent(value);cleanWork();value.put("phase","active");save(value);
  }
  void recoverIconEdit() throws Exception {
    JSONObject value=state();if(value==null)return;String phase=value.getString("phase");
    if(phase.equals("icon-staging")){abortIconEdit();return;}
    File previous=value.optBoolean("icon_baseline")?saved("base","custom"):new File(store,"icon-previous");
    if(phase.equals("icon-installing")){
      JSONObject old=value.getJSONObject("icon_old"),next=value.getJSONObject("icon_next");
      if(!(matches(custom,next)&&matches(previous,old))){
        if(exists(custom)){verify(custom,old);verify(previous,null);move(custom,previous);}
        verify(custom,null);verify(previous,old);File staged=new File(store,"work/custom");verify(staged,next);move(staged,custom);
      }
      value.getJSONObject("current").put("custom",next);value.put("phase","active");if(!value.optBoolean("icon_baseline"))value.put("icon_cleanup",old);value.remove("icon_old");value.remove("icon_next");save(value);
    }
    if(value.getString("phase").equals("active")&&value.has("icon_cleanup")){
      remove(previous,value.getJSONObject("icon_cleanup"));cleanWork();value.remove("icon_cleanup");save(value);
    }
    if(value.getString("phase").equals("active")&&value.has("icon_baseline")){cleanWork();value.remove("icon_baseline");save(value);}
  }
  File begin(JSONObject config) throws Exception {
    recoverIconEdit();recoverFiles();JSONObject value=state();
    if(value!=null&&!value.getString("phase").equals("active"))throw new IOException("Restore the interrupted full theme first");
    if(value==null){JSONObject original=resources();value=new JSONObject().put("schema",1).put("root",root.getPath()).put("original",original).put("current",new JSONObject(original.toString())).put("config",config);}
    else {cleanPrevious(value);cleanWork();if(value.has("metadata"))value.put("prior_metadata",new JSONObject(value.getJSONObject("metadata").toString()));}
    verifyCurrent(value);value.put("phase","staging");save(value);
    File work=new File(store,"work");if(exists(work))throw new IOException("Full-theme staging needs recovery");
    if(!work.mkdir())throw new IOException("Cannot stage the full theme");Os.chmod(work.getPath(),0700);sync(store);return work;
  }
  private void verifyCurrent(JSONObject value) throws Exception {verifyCurrent(value,false);}
  void verifyActive() throws Exception {
    JSONObject value=state();if(value==null||!value.getString("phase").equals("active"))throw new IOException("Finish the pending full-theme recovery before editing icons");verifyCurrent(value);
  }
  private void verifyCurrent(JSONObject value,boolean nativeMetadata) throws Exception {
    JSONObject expected=value.getJSONObject("current");
    for(String key:resourceNames().keySet())if(!expected.has(key)&&!(nativeMetadata&&key.equals("theme/disassembleapply")))throw new IOException("Theme resources changed outside VFont ("+key+"); originals were kept");
    for(String key:keys(expected))if(!(nativeMetadata&&key.equals("theme/disassembleapply")))verify(target(key),expected.optJSONObject(key));
  }
  void nativeMetadata(int themesUid) throws Exception {nativeMetadata(themesUid<0?Collections.emptySet():Collections.singleton(themesUid));}
  static void verifyNativeMetadata(String slot,JSONObject expected,JSONObject actual,Set<Integer> themeUids) throws Exception {
    if(expected==null||actual==null||!new HashSet<>(keys(expected)).equals(new HashSet<>(keys(actual))))throw new IOException("Theme members changed outside VFont; originals were kept");
    for(String path:keys(expected)){
      JSONObject want=expected.getJSONObject(path),got=actual.getJSONObject(path);
      for(String field:List.of("d","uid","gid","context","size"))if(!want.get(field).toString().equals(got.get(field).toString()))throw new IOException("Theme metadata changed outside VFont; originals were kept");
      boolean directory=want.getBoolean("d");
      if(!directory&&!want.getString("sha").equals(got.getString("sha")))throw new IOException("Theme artwork changed outside VFont; originals were kept");
      int owner=got.getInt("uid"),mode=want.getInt("mode"),now=got.getInt("mode");
      boolean nativeSlot=slot.startsWith("theme/")&&(owner==1000||themeUids.contains(owner));
      if(mode!=now&&!(nativeSlot&&(mode&0777)==(directory?0755:0644)&&now==((mode&~0777)|0777)))throw new IOException("Theme permissions changed outside VFont; originals were kept");
      boolean preview=slot.equals("theme/preview")&&path.matches("preview_lockscreen_[0-3]\\.(jpg|png)")&&themeUids.contains(owner)&&!directory;
      if(!preview&&(!want.get("ino").toString().equals(got.get("ino").toString())||!directory&&!want.get("time").toString().equals(got.get("time").toString())))throw new IOException("Theme identity changed outside VFont; originals were kept");
    }
  }
  void nativeMetadata(Set<Integer> themeUids) throws Exception {
    JSONObject value=state();if(value==null||!List.of("active","activating").contains(value.getString("phase")))return;
    String key="theme/disassembleapply";File folder=target(key);JSONObject current=value.getJSONObject("current");
    boolean reconciled=false;
    for(String slot:keys(current))if(!slot.equals(key)&&current.optJSONObject(slot)!=null){
      JSONObject expected=current.getJSONObject(slot),actual=snapshot(target(slot));
      verifyNativeMetadata(slot,expected,actual,themeUids);
      if(!expected.toString().equals(actual.toString())){
        JSONObject original=value.getJSONObject("original").optJSONObject(slot);
        if(original!=null&&!exists(saved("base",slot))&&original.toString().equals(expected.toString()))value.getJSONObject("original").put(slot,actual);
        current.put(slot,actual);reconciled=true;
      }
    }
    if(matches(folder,current.optJSONObject(key))){if(reconciled)save(value);return;}
    if(themeUids.isEmpty())throw new IOException("Unrecognized native theme history (no theme store package found); saved originals were kept");
    // Stock Themes regenerates its component history after the theme-change broadcast.
    verifyCurrent(value,true);directory(folder);File data=new File(folder,"data.txt");String[] names=folder.list();
    if(names==null||names.length!=1||!names[0].equals("data.txt")||Files.isSymbolicLink(data.toPath())||!data.isFile()||data.length()>65536
        ||!themeUids.contains(Os.lstat(data.getPath()).st_uid))throw new IOException("Unrecognized native theme history; saved originals were kept");
    JSONObject snapshot=snapshot(folder);byte[] bytes=Files.readAllBytes(data.toPath());if(bytes.length>65536)throw new IOException("Native theme history grew beyond its limit");
    if(!themeUids.contains(Os.lstat(folder.getPath()).st_uid)){
      JSONObject recorded=current.optJSONObject(key);
      if(recorded==null)throw new IOException("Unrecognized native theme history owner; saved originals were kept");
      verifyNativeMetadata(key,new JSONObject().put("",recorded.getJSONObject("")),new JSONObject().put("",snapshot.getJSONObject("")),themeUids);
    }
    JSONObject history=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
    if(history.length()<1||history.length()>64)throw new IOException("Unrecognized native theme history");
    for(String type:keys(history)){
      if(!type.matches("[0-9]{1,4}"))throw new IOException("Unrecognized native theme history type");
      JSONObject item=history.getJSONObject(type);
      if(item.optInt("disassembleApplyResType",-1)!=Integer.parseInt(type)||item.length()>64)throw new IOException("Unrecognized native theme history entry");
      for(String field:keys(item)){
        Object v=item.get(field);
        if(!field.matches("[A-Za-z][A-Za-z0-9_]{0,63}")||v instanceof JSONObject||v instanceof JSONArray||v.toString().length()>8192)throw new IOException("Unrecognized native theme history field");
      }
    }
    verify(folder,snapshot);
    if(!value.getJSONObject("original").has(key))value.getJSONObject("original").put(key,JSONObject.NULL);
    JSONObject original=value.getJSONObject("original").optJSONObject(key),previous=current.optJSONObject(key);
    if(original!=null&&!exists(saved("base",key))){
      if(previous==null||!original.toString().equals(previous.toString())||!original.getJSONObject("data.txt").getString("sha").equals(snapshot.getJSONObject("data.txt").getString("sha")))throw new IOException("Original native theme history changed; its recovery record was kept");
      value.getJSONObject("original").put(key,snapshot);
    }
    current.put(key,snapshot);save(value);
  }
  void install(Map<String,File> prepared,JSONObject metadata) throws Exception {
    JSONObject value=state();if(value==null||!value.getString("phase").equals("staging"))throw new IOException("Missing theme preparation");
    JSONObject old=value.getJSONObject("current"),original=value.getJSONObject("original");
    boolean partial=metadata.optBoolean("partial");JSONObject next=partial?new JSONObject(old.toString()):new JSONObject(),backups=new JSONObject();
    value.put("prior_theme_icons",value.optBoolean("theme_icons",value.has("managed_config")));
    if(metadata.has("restore_components")&&metadata.getJSONArray("restore_components").toString().contains("\"icons\""))value.put("theme_icons",false);
    else if(!partial||metadata.optJSONArray("components")!=null&&metadata.getJSONArray("components").toString().contains("\"icons\""))value.put("theme_icons",true);
    else if(!value.has("theme_icons"))value.put("theme_icons",value.has("managed_config"));
    for(var entry:prepared.entrySet()){
      String key=entry.getKey();File file=entry.getValue();target(key);
      boolean baseline=file!=null&&file.equals(saved("base",key))&&metadata.has("restore_components");
      if(file!=null&&!baseline&&!file.getCanonicalFile().toPath().startsWith(new File(store,"work").toPath()))throw new IOException("Theme staging escaped its directory");
      if(baseline)verify(file,original.optJSONObject(key));
      next.put(key,file==null?JSONObject.NULL:snapshot(file));
      if(!original.has(key))original.put(key,JSONObject.NULL);if(!old.has(key))old.put(key,JSONObject.NULL);
    }
    for(String key:keys(old))if(!next.has(key))next.put(key,JSONObject.NULL);
    verifyCurrent(value);
    JSONArray affected=new JSONArray();for(String key:keys(next))if(!partial||prepared.containsKey(key)){
      affected.put(key);backups.put(key,!exists(saved("base",key))&&matches(target(key),original.optJSONObject(key))?"base":"previous");
    }
    // Every next slot has one canonical staging location, independent of archive member paths.
    JSONArray originalStaged=new JSONArray();for(var entry:prepared.entrySet())if(entry.getValue()!=null&&entry.getValue().equals(saved("base",entry.getKey())))originalStaged.put(entry.getKey());
    value.put("original_staged",originalStaged).put("next",next).put("old",old).put("metadata",metadata).put("affected",affected).put("backups",backups).put("first",false).put("phase","staging-ready");save(value);
    File ready=new File(store,"next");if(!ready.mkdir())throw new IOException("Theme staging already exists");
    for(var entry:prepared.entrySet())if(entry.getValue()!=null)move(entry.getValue(),saved("next",entry.getKey()));
    value.put("phase","installing");save(value);recoverFiles();
  }
  void recoverFiles() throws Exception {
    JSONObject value=state();if(value==null)return;String phase=value.getString("phase");
    if(phase.equals("installing")){
      JSONObject next=value.getJSONObject("next"),old=value.getJSONObject("old");String group=value.getBoolean("first")?"base":"previous";
      List<String> affected=keys(next);if(value.has("affected")){affected=new ArrayList<>();JSONArray names=value.getJSONArray("affected");for(int i=0;i<names.length();i++)affected.add(names.getString(i));}
      for(String key:affected){
        File target=target(key),backup=saved(value.has("backups")?value.getJSONObject("backups").getString(key):group,key);JSONObject prior=old.optJSONObject(key),want=next.optJSONObject(key);
        if(matches(target,want)&&matches(backup,prior))continue;
        if(exists(target)){verify(target,prior);verify(backup,null);move(target,backup);}
        verify(target,null);verify(backup,prior);File staged=saved("next",key);verify(staged,want);if(want!=null)move(staged,target);
      }
      value.put("current",next).put("phase","activating");save(value);return;
    }
    if(phase.equals("restoring")){
      JSONObject current=value.getJSONObject("current"),original=value.getJSONObject("original");
      for(String key:keys(original)){
        File target=target(key),baseline=saved("base",key),discard=saved("discard",key);JSONObject want=original.optJSONObject(key),now=current.optJSONObject(key);
        if(matches(target,want)&&!exists(baseline))continue;
        if(exists(target)){verify(target,now);verify(discard,null);move(target,discard);}
        verify(target,null);verify(baseline,want);if(want!=null)move(baseline,target);
      }
      value.put("phase","restoring-config");save(value);
    }
  }
  void activated(JSONObject managedConfig) throws Exception {
    JSONObject value=state();if(!value.getString("phase").equals("activating"))throw new IOException("Theme activation is incomplete");
    verifyCurrent(value);value.remove("icon_refresh");value.remove("original_staged");value.remove("prior_theme_icons");value.put("managed_config",managedConfig).put("phase","active");save(value);
    // Previous applies remain retained until the native configuration has been verified.
    cleanPrevious(value);cleanWork();
  }
  private void remove(File file,JSONObject expected) throws Exception {
    IconThemeStore.verify(file,expected,true);if(!exists(file))return;
    List<String> names=keys(expected);names.sort(Comparator.comparingInt(String::length).reversed());
    for(String name:names){File child=name.isEmpty()?file:new File(file,name);if(exists(child)){Files.delete(child.toPath());point("delete:"+name);}}sync(file.getParentFile());
  }
  private void cleanPrevious(JSONObject value) throws Exception {
    if(value.has("old")&&!value.optBoolean("first"))for(String key:keys(value.getJSONObject("old")))if(!value.has("backups")||value.getJSONObject("backups").optString(key).equals("previous"))remove(saved("previous",key),value.getJSONObject("old").optJSONObject(key));
  }
  private void cleanWork() throws Exception {
    File work=new File(store,"work");if(!exists(work))return;directory(work);
    try(var stream=Files.walk(work.toPath())){
      List<Path> files=stream.collect(java.util.stream.Collectors.toList());if(files.size()>25000)throw new IOException("Unexpected theme staging size");
      for(Path path:files)if(Files.isSymbolicLink(path)||Os.lstat(path.toString()).st_uid!=Os.getuid()||(!Files.isDirectory(path)&&!Files.isRegularFile(path)))throw new IOException("Theme staging changed outside VFont");
      Collections.sort(files,Comparator.reverseOrder());
      for(Path path:files)Files.delete(path);
    }sync(store);
    for(String group:List.of("next","previous"))prune(new File(store,group));
  }
  private void abandonStaging(JSONObject value) throws Exception {
    Set<String> originals=new HashSet<>();JSONArray staged=value.optJSONArray("original_staged");if(staged!=null)for(int i=0;i<staged.length();i++)originals.add(staged.getString(i));
    if(value.has("next"))for(String key:keys(value.getJSONObject("next"))){
      if(originals.contains(key)){File file=saved("next",key);if(exists(file)){verify(file,value.getJSONObject("original").optJSONObject(key));move(file,saved("base",key));}verify(saved("base",key),value.getJSONObject("original").optJSONObject(key));}
      else remove(saved("next",key),value.getJSONObject("next").optJSONObject(key));
    }
    cleanWork();
    if(value.has("prior_theme_icons")){value.put("theme_icons",value.getBoolean("prior_theme_icons"));value.remove("prior_theme_icons");}
    for(String field:List.of("next","old","metadata","first","affected","backups","original_staged"))value.remove(field);
    if(value.has("prior_metadata")){value.put("metadata",value.getJSONObject("prior_metadata"));value.remove("prior_metadata");}
    if(!value.has("managed_config")){Files.delete(journal.toPath());sync(store);}
    else {value.put("phase","active");save(value);}
  }
  private void prune(File folder) throws Exception {
    if(!exists(folder))return;directory(folder);File[] children=folder.listFiles();if(children==null)throw new IOException("Cannot inspect theme staging");
    for(File child:children)if(child.isDirectory()&&!Files.isSymbolicLink(child.toPath()))prune(child);
    if(folder.list().length==0)Files.delete(folder.toPath());
  }
  boolean cancelPreparation() throws Exception {
    JSONObject value=state();
    if(value==null||!List.of("staging","staging-ready").contains(value.getString("phase")))return false;
    verifyCurrent(value);abandonStaging(value);return true;
  }
  void restoreFiles() throws Exception {
    recoverIconEdit();recoverFiles();JSONObject value=state();if(value==null)return;
    String phase=value.getString("phase");
    if(phase.equals("staging")||phase.equals("staging-ready")){verifyCurrent(value);abandonStaging(value);return;}
    else if(!phase.equals("restoring-config")){
      verifyCurrent(value);value.put("phase","restoring");save(value);recoverFiles();
    }
  }
  void restored() throws Exception {
    JSONObject value=state();if(value==null)return;if(!value.getString("phase").equals("restoring-config"))throw new IOException("Theme configuration recovery is incomplete");
    for(String key:keys(value.getJSONObject("original")))verify(target(key),value.getJSONObject("original").optJSONObject(key));
    for(String key:keys(value.getJSONObject("current")))remove(saved("discard",key),value.getJSONObject("current").optJSONObject(key));
    cleanPrevious(value);cleanWork();prune(new File(store,"base"));prune(new File(store,"discard"));
    Files.delete(journal.toPath());sync(store);point("journal:removed");
  }
  @Override public void close() throws IOException {if(lock!=null)lock.close();if(channel!=null)channel.close();}
}
