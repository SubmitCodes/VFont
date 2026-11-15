package dev.fontlab;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Selected baseline resources use the same journaled publication as component Apply. */
final class ThemeRestore {
  final Map<String,File> resources=new TreeMap<>();
  final JSONObject metadata;
  ThemeRestore(FullThemeStore store,File work,File root,Set<String> parts)throws Exception {
    JSONObject state=store.state();metadata=new JSONObject(state.getJSONObject("metadata").toString());
    for(String key:List.of("home_wallpaper","home_sha256","lock_wallpaper","lock_sha256","lock_file","lock_id","native_resources"))metadata.remove(key);
    if(parts.contains("icons")){metadata.remove("adjustable_icons");metadata.remove("normalized_icons");}
    metadata.put("partial",true).put("components",new JSONArray(parts)).put("restore_components",new JSONArray(parts));
    Set<String> slots=new TreeSet<>(FullThemeStore.keys(state.getJSONObject("original")));slots.addAll(FullThemeStore.keys(state.getJSONObject("current")));
    for(String slot:slots){
      boolean selected=slot.equals("custom")?parts.contains("icons"):slot.startsWith("unlock/")?parts.contains("lock"):
          slot.startsWith("theme/")&&parts.contains(ThemeParts.group(slot.substring(6)));
      if(selected&&!slot.equals("theme/wallpaper")&&!slot.equals("theme/launcher")&&!store.originalActive(slot))resources.put(slot,store.original(slot));
    }
    if((parts.contains("icons")||parts.contains("launcher"))&&!store.originalActive("theme/launcher")){
      if(parts.contains("icons")&&parts.contains("launcher"))resources.put("theme/launcher",store.original("theme/launcher"));
      else {File output=new File(work,"restored-launcher");ThemeParts.launcher(store.original("theme/launcher"),new File(root,"launcher"),output,parts);resources.put("theme/launcher",output);}
    }
    if(parts.contains("status")&&!store.originalActive("theme/com.android.systemui")){
      File output=new File(work,"restored-systemui");ThemeParts.status(store.original("theme/com.android.systemui"),new File(root,"com.android.systemui"),output,false);
      android.system.Os.chmod(output.getPath(),0644);resources.put("theme/com.android.systemui",output);
    }
    if(parts.contains("home")||parts.contains("lockpaper")){
      File current=new File(root,"wallpaper"),baseline=store.original("theme/wallpaper"),output=new File(work,"restored-wallpaper");
      FullThemeBundle.copy(current,output);if(!output.exists()&&!output.mkdir())throw new IOException("Cannot prepare wallpaper recovery");
      for(String side:List.of("home","lockpaper"))if(parts.contains(side)){
        String prefix=side.equals("home")?"default_wallpaper.":"default_lock_wallpaper.";
        for(File file:Objects.requireNonNull(output.listFiles()))if(file.getName().startsWith(prefix))Files.delete(file.toPath());
        if(baseline!=null)for(File file:Objects.requireNonNull(baseline.listFiles()))if(file.getName().startsWith(prefix)){
          if(Files.isSymbolicLink(file.toPath())||!file.isFile()||file.length()>64L*1024*1024)throw new IOException("Unsupported original wallpaper resource");
          File target=new File(output,file.getName());Files.copy(file.toPath(),target.toPath());android.system.Os.chmod(target.getPath(),0644);
          try(FileOutputStream sync=new FileOutputStream(target,true)){sync.getFD().sync();}
        }
      }
      android.system.Os.chmod(output.getPath(),0755);
      resources.put("theme/wallpaper",output);
    }
  }
}
