package dev.fontlab;

import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import org.json.*;

/** A persistent two-resource transaction; original inodes remain available until reset. */
final class IconThemeStore {
  static final long MAX_FILE=16L*1024*1024,MAX_TOTAL=512L*1024*1024;
  static final String[] SLOTS={"renderer","style"};
  interface Step {void reached(String step) throws Exception;}
  private final File root,stock,store,images,work,stateFile;
  private final Step step;
  IconThemeStore(File root,File stock,Step step) throws Exception {
    this.root=root;this.stock=stock;this.step=step;
    directory(root);store=new File(root,"1999901990");images=new File(store,"images");
    work=new File(store,"work");stateFile=new File(store,"state.json");
    if(exists(store))checkStore();
  }
  private static boolean exists(File file){return Files.exists(file.toPath(),LinkOption.NOFOLLOW_LINKS);}
  private static void directory(File file) throws Exception {
    if(Files.isSymbolicLink(file.toPath())||!file.isDirectory()||!file.getCanonicalFile().equals(file.getAbsoluteFile()))
      throw new IOException("Unexpected theme directory");
  }
  private static void regular(File file,long limit) throws Exception {
    if(Files.isSymbolicLink(file.toPath())||!file.isFile()||file.length()>limit)throw new IOException("Unexpected theme file");
  }
  private void checkStore() throws Exception {
    directory(store);StructStat stat=Os.lstat(store.getPath());File marker=new File(store,"owner");regular(marker,64);
    if(stat.st_uid!=Os.getuid()||!new String(Files.readAllBytes(marker.toPath()),StandardCharsets.US_ASCII).equals("VFONT_ICON_STORE_1\n"))
      throw new IOException("Unknown theme recovery directory; existing files were kept");
    int mode=stat.st_mode&0777;
    // Stock Themes recursively chmods its theme root, including numeric VFont storage.
    if(mode==0777){Os.chmod(store.getPath(),0700);Os.chmod(marker.getPath(),0600);sync(store);}
    else if(mode!=0700)throw new IOException("Unknown theme recovery directory permissions; existing files were kept");
  }
  static String hash(File file) throws Exception {
    regular(file,MAX_TOTAL);MessageDigest digest=MessageDigest.getInstance("SHA-256");
    try(InputStream in=Files.newInputStream(file.toPath())){byte[] buffer=new byte[65536];int count;while((count=in.read(buffer))!=-1)digest.update(buffer,0,count);}
    StringBuilder out=new StringBuilder();for(byte b:digest.digest())out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();
  }
  private static void sync(File dir) throws Exception {
    directory(dir);var fd=Os.open(dir.getPath(),OsConstants.O_RDONLY,0);try{Os.fsync(fd);}finally{Os.close(fd);}
  }
  private void point(String name) throws Exception {if(step!=null)step.reached(name);}
  private static File child(File parent,String path) throws Exception {
    if(path.length()>512||path.startsWith("/")||path.contains("\\")||path.indexOf('\0')>=0||path.contains("\n")||path.contains("\r"))throw new IOException("Invalid theme member");
    if(!path.isEmpty())for(String part:path.split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals(".."))throw new IOException("Invalid theme member");
    return path.isEmpty()?parent:new File(parent,path);
  }
  private static JSONObject identity(File file) throws Exception {
    StructStat s=Os.lstat(file.getPath());boolean dir=OsConstants.S_ISDIR(s.st_mode);
    if(!dir&&!OsConstants.S_ISREG(s.st_mode))throw new IOException("Theme links and special files are unsupported");
    return new JSONObject().put("d",dir).put("ino",s.st_ino).put("mode",s.st_mode).put("uid",s.st_uid).put("gid",s.st_gid)
        .put("size",s.st_size).put("time",s.st_mtim.tv_sec+":"+s.st_mtim.tv_nsec)
        .put("context",Base64.getEncoder().encodeToString(Os.getxattr(file.getPath(),"security.selinux")));
  }
  private static void walk(File base,File file,JSONObject rows,long[] total,int depth) throws Exception {
    if(depth>20||rows.length()>=8192)throw new IOException("Theme tree exceeds the supported size");
    String name=base.toPath().relativize(file.toPath()).toString().replace(File.separatorChar,'/');child(base,name);
    JSONObject row=identity(file);rows.put(name,row);
    if(row.getBoolean("d")){
      directory(file);File[] files=file.listFiles();if(files==null)throw new IOException("Cannot read theme directory");
      Arrays.sort(files,Comparator.comparing(File::getName));for(File next:files)walk(base,next,rows,total,depth+1);
    }else{
      regular(file,MAX_FILE);total[0]+=file.length();if(total[0]>MAX_TOTAL)throw new IOException("Theme tree is too large");row.put("sha",hash(file));
    }
  }
  static JSONObject snapshot(File file) throws Exception {
    if(!exists(file))return null;JSONObject result=new JSONObject();walk(file,file,result,new long[]{0},0);return result;
  }
  private static List<String> keys(JSONObject object){List<String> out=new ArrayList<>();for(Iterator<String> i=object.keys();i.hasNext();)out.add(i.next());return out;}
  private static boolean same(JSONObject a,JSONObject b,String key) throws Exception {return a.get(key).toString().equals(b.get(key).toString());}
  static void verify(File file,JSONObject expected,boolean partial) throws Exception {
    JSONObject actual=snapshot(file);
    if(expected==null){if(actual!=null)throw new IOException("Theme files appeared outside VFont");return;}
    if(actual==null){if(partial)return;throw new IOException("A saved theme resource is missing");}
    if(expected.length()>8192||!expected.has(""))throw new IOException("Invalid theme recovery manifest");
    if(!partial&&actual.length()!=expected.length())throw new IOException("Theme files changed outside VFont; originals were kept");
    for(String path:keys(actual)){
      child(file,path);JSONObject want=expected.optJSONObject(path),got=actual.getJSONObject(path);
      if(want==null)throw new IOException("Unexpected theme member; originals were kept");
      for(String key:new String[]{"d","ino","mode","uid","gid","context"})if(!same(want,got,key))throw new IOException("Theme identity changed outside VFont; originals were kept");
      // Moving a directory changes its parent entry time; original file timestamps remain bound.
      if((!partial||!got.getBoolean("d"))&&!same(want,got,"size"))throw new IOException("Theme size changed outside VFont; originals were kept");
      if(!got.getBoolean("d")&&!same(want,got,"time"))throw new IOException("Theme timestamp changed outside VFont; originals were kept");
      if(!got.getBoolean("d")&&!same(want,got,"sha"))throw new IOException("Theme artwork changed outside VFont; originals were kept");
    }
  }
  private void remove(File file,JSONObject manifest) throws Exception {
    verify(file,manifest,true);if(!exists(file))return;
    List<String> paths=keys(manifest);paths.sort(Comparator.comparingInt(String::length).reversed());
    for(String path:paths){File next=child(file,path);if(exists(next)){Files.delete(next.toPath());point("delete:"+file.getName()+":"+path);}}
    sync(file.getParentFile());
  }
  private void json(File file,JSONObject value) throws Exception {
    File temp=new File(file.getParentFile(),file.getName()+".tmp");
    for(File f:new File[]{file,temp})if(exists(f)){regular(f,32L*1024*1024);if(Os.lstat(f.getPath()).st_uid!=Os.getuid())throw new IOException("Unexpected theme journal owner");}
    try(FileOutputStream out=new FileOutputStream(temp)){out.write(value.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
    Os.chmod(temp.getPath(),0600);Files.move(temp.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);sync(store);
  }
  private void save(JSONObject state) throws Exception {json(stateFile,state);point("journal:"+state.getString("phase"));}
  JSONObject state() throws Exception {
    if(!exists(stateFile))return null;regular(stateFile,32L*1024*1024);
    JSONObject state=new JSONObject(new String(Files.readAllBytes(stateFile.toPath()),StandardCharsets.UTF_8));
    if(state.optInt("schema")!=1||!state.optString("root").equals(root.getPath()))throw new IOException("Invalid theme recovery journal");
    JSONObject recorded=state.getJSONObject("root_identity"),now=identity(root);
    for(String key:new String[]{"ino","mode","uid","gid","context"})if(!same(recorded,now,key))throw new IOException("Theme root changed; recovery originals were kept");
    return state;
  }
  void initialize() throws Exception {
    if(exists(store)){checkStore();return;}
    File[] candidates=root.listFiles();if(candidates==null)throw new IOException("Cannot inspect theme recovery initialization");
    for(File candidate:candidates)if(candidate.getName().matches("\\.vfont-icons-init-[a-f0-9]{32}")){
      directory(candidate);if(Os.lstat(candidate.getPath()).st_uid!=Os.getuid())throw new IOException("Unknown theme initialization owner");
      String[] names=candidate.list();if(names==null||names.length>1||(names.length==1&&!names[0].equals("owner")))throw new IOException("Unknown theme initialization files were kept");
      if(names.length==1){File marker=new File(candidate,"owner");regular(marker,19);String text=new String(Files.readAllBytes(marker.toPath()),StandardCharsets.US_ASCII);
        if(!"VFONT_ICON_STORE_1\n".startsWith(text))throw new IOException("Unknown theme initialization contents");Files.delete(marker.toPath());}
      Files.delete(candidate.toPath());sync(root);
    }
    File initial=new File(root,".vfont-icons-init-"+UUID.randomUUID().toString().replace("-",""));
    if(!initial.mkdir())throw new IOException("Cannot prepare theme recovery store");Os.chmod(initial.getPath(),0700);
    File marker=new File(initial,"owner");try(FileOutputStream out=new FileOutputStream(marker)){out.write("VFONT_ICON_STORE_1\n".getBytes(StandardCharsets.US_ASCII));out.getFD().sync();}
    Os.chmod(marker.getPath(),0600);sync(initial);Files.move(initial.toPath(),store.toPath(),StandardCopyOption.ATOMIC_MOVE);sync(root);
  }
  File lock() throws Exception {initialize();return new File(store,"lock");}
  boolean present() throws Exception {return exists(stateFile)||refreshNeeded();}
  boolean refreshNeeded() throws Exception {
    File file=new File(store,"refresh.json");if(!exists(file))return false;regular(file,256*1024);
    JSONObject value=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
    if(value.optInt("schema")!=1||!value.optBoolean("pending"))throw new IOException("Invalid theme refresh journal");return true;
  }
  private void requestRefresh() throws Exception {
    if(!refreshNeeded()){
      Set<String> affected=new TreeSet<>();JSONObject state=state();
      if(state!=null)for(String field:new String[]{"choices","next_choices"}){JSONObject values=state.optJSONObject(field);if(values!=null)affected.addAll(keys(values));}
      json(new File(store,"refresh.json"),new JSONObject().put("schema",1).put("pending",true).put("keys",new JSONArray(affected)));point("refresh:pending");
    }
  }
  Set<String> refreshKeys() throws Exception {
    Set<String> keys=new TreeSet<>();if(!refreshNeeded())return keys;
    JSONObject value=new JSONObject(new String(Files.readAllBytes(new File(store,"refresh.json").toPath()),StandardCharsets.UTF_8));
    JSONArray names=value.optJSONArray("keys");if(names==null||names.length()>1000)throw new IOException("Invalid theme refresh targets");
    for(int i=0;i<names.length();i++){String name=names.getString(i);if(!name.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+"))throw new IOException("Invalid theme refresh target");keys.add(name);}return keys;
  }
  String preserveFolderBlur(String current) throws Exception {
    if(!refreshNeeded())throw new IOException("Missing theme refresh journal");
    if(current!=null&&!current.matches("[0-9]{1,3}"))throw new IOException("Unrecognized folder blur setting");
    File file=new File(store,"refresh.json");JSONObject value=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
    if(!value.has("folder_blur")){value.put("folder_blur",current==null?JSONObject.NULL:current);json(file,value);}
    String saved=value.isNull("folder_blur")?null:value.getString("folder_blur");
    if(saved!=null&&!saved.matches("[0-9]{1,3}"))throw new IOException("Invalid saved folder blur setting");return saved;
  }
  void refreshed() throws Exception {if(refreshNeeded()){Files.delete(new File(store,"refresh.json").toPath());sync(store);}}
  boolean approved(String profile) throws Exception {
    if(VerificationStore.recorded(VerificationStore.icons(),"theme",profile))return VerificationStore.has(VerificationStore.icons(),"theme",profile);
    File file=new File(store,"verified.json");if(!exists(file))return false;regular(file,4096);
    boolean accepted=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8)).optString("profile").equals(profile);
    if(accepted)VerificationStore.save(VerificationStore.icons(),"theme",profile,true);return accepted;
  }
  void approve(String profile) throws Exception {
    if(!profile.matches("[a-f0-9]{64}"))throw new IOException("Invalid theme verification profile");
    json(new File(store,"verified.json"),new JSONObject().put("profile",profile));
    VerificationStore.save(VerificationStore.icons(),"theme",profile,true);
  }
  void verifyCurrent() throws Exception {
    JSONObject state=state();if(state==null)return;
    for(String key:SLOTS){verify(target(key),state.getJSONObject("current").optJSONObject(key),false);
      verify(backup(key,true),state.getJSONObject("original").optJSONObject(key),false);}
  }
  private File target(String key){return key.equals("style")?new File(root,"style/1"):new File(root,"theme.xml");}
  private File backup(String key,boolean original){return new File(store,(original?"original-":"previous-")+key);}
  private File staged(String key){return key.equals("style")?new File(work,"style/1"):new File(work,"theme.xml");}
  private JSONObject resources() throws Exception {JSONObject out=new JSONObject();for(String key:SLOTS)out.put(key,snapshot(target(key)));return out;}
  private boolean matches(File file,JSONObject manifest) throws Exception {
    return manifest==null?!exists(file):exists(file)&&Os.lstat(file.getPath()).st_ino==manifest.getJSONObject("").getLong("ino");
  }
  private void move(File from,File to) throws Exception {
    if(exists(to))throw new IOException("Theme destination already exists; originals were kept");
    Files.move(from.toPath(),to.toPath(),StandardCopyOption.ATOMIC_MOVE);sync(from.getParentFile());if(!from.getParentFile().equals(to.getParentFile()))sync(to.getParentFile());point("move:"+from.getName()+":"+to.getName());
  }
  private void parent(JSONObject state) throws Exception {
    File parent=target("style").getParentFile();
    if(!exists(parent)){
      if(!state.optBoolean("parent_created")){state.put("parent_created",true);save(state);}
      if(!parent.mkdir())throw new IOException("Cannot prepare icon-style directory");Os.chmod(parent.getPath(),0755);sync(root);point("mkdir:style-parent");
    }
    directory(parent);
  }
  File base() throws Exception {
    JSONObject state=state();
    if(state==null)return exists(target("style"))?target("style"):stock;
    if(!state.optBoolean("base_data"))return new File(state.optString("stock",stock.getPath()));
    File original=backup("style",true);return exists(original)?original:target("style");
  }
  private void cleanWork(JSONObject state) throws Exception {
    if(!exists(work))return;directory(work);JSONObject expected=state.optJSONObject("archive_members");
    List<File> files=new ArrayList<>();try(var stream=Files.walk(work.toPath())){stream.forEach(path->files.add(path.toFile()));}
    if(files.size()>16500)throw new IOException("Unexpected theme staging size");
    for(File file:files){
      if(Files.isSymbolicLink(file.toPath())||Os.lstat(file.getPath()).st_uid!=Os.getuid())throw new IOException("Theme staging changed outside VFont");
      String path=work.toPath().relativize(file.toPath()).toString().replace(File.separatorChar,'/');
      if(file.isDirectory()){
        boolean allowed=path.isEmpty();if(expected!=null)for(String name:keys(expected))if(name.startsWith(path+"/")){allowed=true;break;}
        if(!allowed)throw new IOException("Unexpected theme staging directory");continue;
      }
      regular(file,MAX_TOTAL);
      if(path.equals("candidate.itz"))continue;
      boolean partial=path.endsWith(".vfont-partial");String name=partial?path.substring(0,path.length()-14):path;
      if(expected==null||!expected.has(name)||file.length()>MAX_FILE||(!partial&&!hash(file).equals(expected.getString(name))))throw new IOException("Unexpected staged theme file; originals were kept");
    }
    files.sort(Comparator.comparingInt((File f)->f.getPath().length()).reversed());
    for(File file:files){Files.delete(file.toPath());point("delete:work:"+file.getName());}sync(store);
  }
  private void cleanImages(JSONObject choices) throws Exception {
    if(!exists(images))return;directory(images);Set<String> keep=new HashSet<>();
    for(String key:keys(choices))keep.add(choices.getJSONObject(key).getString("sha")+".png");
    File[] files=images.listFiles();if(files==null||files.length>1500)throw new IOException("Unexpected saved-icon storage");
    for(File file:files){String name=file.getName();regular(file,4*1024*1024);
      if(Os.lstat(file.getPath()).st_uid!=Os.getuid()||!name.matches("[a-f0-9]{64}\\.png(\\.vfont-partial)?")||(!name.endsWith(".vfont-partial")&&!hash(file).equals(name.substring(0,64))))throw new IOException("Saved theme icon changed outside VFont");}
    for(File file:files)if(!keep.contains(file.getName())){Files.delete(file.toPath());point("delete:image");}sync(images);
  }
  private void extract(File archive,JSONObject members) throws Exception {
    try(ZipFile zip=new ZipFile(archive)){
      for(String name:keys(members)){
        if(!(name.startsWith("style/1/")||name.equals("theme.xml")))continue;
        ZipEntry entry=zip.getEntry(name);if(entry==null||entry.isDirectory()||entry.getSize()<0||entry.getSize()>MAX_FILE)throw new IOException("Invalid theme staging archive");
        File dest=child(work,name);Files.createDirectories(dest.getParentFile().toPath());
        File temp=new File(dest.getParentFile(),dest.getName()+".vfont-partial");
        try(InputStream in=zip.getInputStream(entry);OutputStream out=Files.newOutputStream(temp.toPath(),StandardOpenOption.CREATE_NEW)){
          byte[] buffer=new byte[65536];long size=0;int count;while((count=in.read(buffer))!=-1){size+=count;if(size>MAX_FILE)throw new IOException("Theme asset exceeds its limit");out.write(buffer,0,count);}
        }
        if(!hash(temp).equals(members.getString(name)))throw new IOException("Theme staging checksum mismatch");
        try(FileOutputStream out=new FileOutputStream(temp,true)){out.getFD().sync();}
        Os.chmod(temp.getPath(),0644);move(temp,dest);
      }
    }
    try(var paths=Files.walk(staged("style").toPath())){for(Path path:paths.collect(java.util.stream.Collectors.toList()))if(Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)){Os.chmod(path.toString(),0755);sync(path.toFile());}}
  }
  private JSONObject storeChoices(Map<String,File> input) throws Exception {
    if(!exists(images)){if(!images.mkdir())throw new IOException("Cannot save theme icon choices");Os.chmod(images.getPath(),0700);sync(store);}
    JSONObject out=new JSONObject();
    for(var choice:new TreeMap<>(input).entrySet()){
      regular(choice.getValue(),4*1024*1024);String sha=hash(choice.getValue());File saved=new File(images,sha+".png");
      if(!exists(saved)){
        File partial=new File(images,saved.getName()+".vfont-partial");
        try(InputStream in=Files.newInputStream(choice.getValue().toPath());OutputStream file=Files.newOutputStream(partial.toPath(),StandardOpenOption.CREATE_NEW)){FullThemeStore.copyStream(in,file);}
        if(!hash(partial).equals(sha))throw new IOException("The selected icon changed while it was copied");
        try(FileOutputStream file=new FileOutputStream(partial,true)){file.getFD().sync();}Os.chmod(partial.getPath(),0600);move(partial,saved);
      }
      if(!hash(saved).equals(sha))throw new IOException("The selected icon changed while it was saved");out.put(choice.getKey(),new JSONObject().put("sha",sha));
    }
    return out;
  }
  Map<String,File> choices() throws Exception {
    JSONObject state=state();Map<String,File> out=new TreeMap<>();if(state==null)return out;
    for(String key:keys(state.getJSONObject("choices"))){String sha=state.getJSONObject("choices").getJSONObject(key).getString("sha");if(!sha.matches("[a-f0-9]{64}"))throw new IOException("Invalid saved theme icon");File image=new File(images,sha+".png");if(!hash(image).equals(sha))throw new IOException("Saved theme icon changed");out.put(key,image);}
    return out;
  }
  void apply(Map<String,File> input,String profile,boolean trial,boolean baseData,String tx) throws Exception {
    if(input.isEmpty()||input.size()>500)throw new IOException("Select between one and 500 theme icons");
    recover();JSONObject state=state();
    long selectedBytes=0,baseBytes=0;
    for(File file:input.values()){regular(file,4*1024*1024);selectedBytes+=file.length();}
    JSONObject baseManifest=snapshot(base());if(baseManifest==null)throw new IOException("The original icon style is missing");
    for(String key:keys(baseManifest)){JSONObject row=baseManifest.getJSONObject(key);if(!row.getBoolean("d"))baseBytes+=row.getLong("size");}
    if(selectedBytes>MAX_TOTAL||baseBytes+4*selectedBytes>MAX_TOTAL)throw new IOException("Selected theme icons exceed the supported size");
    if(root.getUsableSpace()<2*(baseBytes+4*selectedBytes)+selectedBytes+64L*1024*1024)throw new IOException("Not enough free storage to retain and stage the theme safely");
    if(state==null){state=new JSONObject().put("schema",1).put("root",root.getPath()).put("root_identity",identity(root)).put("original",resources())
        .put("current",resources()).put("choices",new JSONObject()).put("base_data",baseData).put("stock",stock.getPath()).put("profile",profile).put("parent_created",false);}
    else if(!state.getString("profile").equals(profile))throw new IOException("Theme software changed; restore the saved original first");
    for(String key:SLOTS)verify(target(key),state.getJSONObject("current").optJSONObject(key),false);
    state.put("phase","staging").put("first",state.getJSONObject("choices").length()==0).put("old",state.getJSONObject("current"));save(state);
    if(!work.mkdir())throw new IOException("Theme staging directory already exists");Os.chmod(work.getPath(),0700);sync(store);point("mkdir:work");
    JSONObject selected=storeChoices(input);Map<String,File> files=new TreeMap<>();for(String key:keys(selected))files.put(key,new File(images,selected.getJSONObject(key).getString("sha")+".png"));
    File archive=new File(work,"candidate.itz");Map<String,String> manifest=IconThemeArchive.write(base(),files,"9900990",archive);
    state.put("archive_members",new JSONObject(manifest));save(state);extract(archive,state.getJSONObject("archive_members"));
    JSONObject next=new JSONObject();for(String key:SLOTS)next.put(key,snapshot(staged(key)));
    state.put("next",next).put("next_choices",selected).put("next_trial",trial).put("phase","installing").put("tx",tx);save(state);recover();
  }
  boolean recover() throws Exception {
    JSONObject state=state();if(state==null||state.getString("phase").equals("active"))return false;
    String phase=state.getString("phase");
    if(phase.equals("staging")){
      cleanWork(state);cleanImages(state.getJSONObject("choices"));
      if(state.getJSONObject("choices").length()==0){Files.delete(stateFile.toPath());sync(store);}else{state.put("phase","active");save(state);}return true;
    }
    if(phase.equals("installing")){
      requestRefresh();
      JSONObject next=state.getJSONObject("next"),old=state.getJSONObject("old");boolean first=state.getBoolean("first");
      for(String key:SLOTS){
        if(key.equals("style"))parent(state);
        File target=target(key),saved=backup(key,first);JSONObject want=next.optJSONObject(key),previous=old.optJSONObject(key);
        if(matches(target,want)){verify(target,want,false);verify(saved,previous,false);continue;}
        if(exists(target)){
          verify(target,previous,false);verify(saved,null,false);move(target,saved);
        }
        verify(saved,previous,false);verify(target,null,false);verify(staged(key),want,false);move(staged(key),target);
      }
      state.put("current",next).put("choices",state.getJSONObject("next_choices")).put("trial",state.getBoolean("next_trial")).put("phase","committed");save(state);phase="committed";
    }
    if(phase.equals("committed")){
      for(String key:SLOTS)verify(target(key),state.getJSONObject("current").optJSONObject(key),false);
      if(!state.getBoolean("first"))for(String key:SLOTS)remove(backup(key,false),state.getJSONObject("old").optJSONObject(key));
      cleanWork(state);cleanImages(state.getJSONObject("choices"));
      for(String key:new String[]{"next","next_choices","next_trial","old","archive_members"})state.remove(key);
      state.put("phase","active");save(state);return true;
    }
    if(phase.equals("restoring")){
      requestRefresh();
      for(String key:new String[]{"style","renderer"}){
        File target=target(key),original=backup(key,true),previous=backup(key,false);
        JSONObject baseline=state.getJSONObject("original").optJSONObject(key),current=state.getJSONObject("current").optJSONObject(key);
        if(matches(target,baseline)){verify(target,baseline,false);verify(original,null,false);continue;}
        if(exists(target)){verify(target,current,false);verify(previous,null,false);move(target,previous);}
        verify(target,null,false);verify(original,baseline,false);if(baseline!=null)move(original,target);
      }
      state.put("phase","restored");save(state);phase="restored";
    }
    if(phase.equals("restored")){
      for(String key:SLOTS){verify(target(key),state.getJSONObject("original").optJSONObject(key),false);remove(backup(key,false),state.getJSONObject("current").optJSONObject(key));}
      cleanImages(new JSONObject());cleanWork(state);
      File parent=target("style").getParentFile();if(state.optBoolean("parent_created")&&exists(parent)){directory(parent);String[] names=parent.list();if(names!=null&&names.length==0){Files.delete(parent.toPath());sync(root);point("delete:style-parent");}}
      Files.delete(stateFile.toPath());sync(store);point("journal:removed");return true;
    }
    throw new IOException("Unknown theme transaction phase; originals were kept");
  }
  void restore() throws Exception {
    recover();JSONObject state=state();if(state==null)return;
    for(String key:SLOTS)verify(target(key),state.getJSONObject("current").optJSONObject(key),false);
    state.put("phase","restoring");save(state);recover();
  }
}
