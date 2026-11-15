package dev.fontlab;

import java.util.*;
import java.util.stream.Collectors;

/** One catalog supplies routes, search and the explanation attached to every tool. */
final class ToolCatalog {
  record Category(String id,String title,String summary,int icon,int hue) {}
  record Tool(String id,String category,String title,String summary,String what,String how,String needs,String restore,int icon,int hue) {}
  static final List<Category> CATEGORIES=List.of(
      new Category("fonts","Fonts and emoji","Make every word feel yours",R.drawable.vf_rounded_format_size_24,270),
      new Category("themes","Themes and wallpaper","A whole look, or just the parts you love",R.drawable.vf_rounded_palette_24,95),
      new Category("icons","Icons and home screen","Give your apps a new look",R.drawable.vf_rounded_grid_view_24,40),
      new Category("system","Connection and recovery","Set up access and keep a way back",R.drawable.vf_rounded_shield_24,190),
      new Category("support","Help and about","Check your device and find your way",R.drawable.vf_rounded_help_24,320));
  static final List<Tool> TOOLS=List.of(
      new Tool("fonts","fonts","Text and emoji fonts","Import, preview and apply a font",
          "Change the phone's primary font using a local text or emoji font. Some apps use their own fonts. Text and emoji are not independent slots; an emoji-only file can use system text fallback.",
          "Choose a font file, inspect its preview, then tap Apply font. Collections let you choose a face. The preview can include system fallback.",
          "Shizuku for Apply and Restore. Preview and importing work without it. A native-access test is needed once for new software profiles.",
          "Restore returns the phone to the system default font. Removing your selected file only removes VFont's copy; it does not change the active phone font.",R.drawable.vf_rounded_format_size_24,270),
      new Tool("themes","themes","Full themes","Import a local Vivo theme",
          "Apply the supported wallpaper, lock screen, icons and system styling included in a Vivo .itz theme. VFont saves the first setup for recovery.",
          "Choose a theme, inspect its pictures and included components, then review Apply full theme. Keep VFont open until it finishes.",
          "Shizuku for Apply and Restore. Full Apply currently supports static home and lock wallpapers and reviewed native theme formats.",
          "Restore previous setup returns the saved theme, wallpapers, lock screen and app icons. Applying another full theme keeps that first recovery backup.",R.drawable.vf_rounded_palette_24,95),
      new Tool("components","themes","Theme components","Keep everything you don't choose",
          "Borrow selected parts of an imported theme: status artwork, System UI, wallpapers, lock screen, app icons or home screen styling. Custom-image and clock editors are still future work.",
          "Import or choose a theme, select its components directly, then review your selection before Apply. Later selections keep your earlier component edits.",
          "Shizuku and a compatible theme archive. Status-only selection recognizes supported native battery and network artwork; formats vary between themes.",
            "Check the parts to undo, then use Restore selected. Other parts stay applied and their backup remains available. Full Themes can restore the entire saved setup.",R.drawable.vf_rounded_wallpaper_24,185),
      new Tool("icons","icons","App icons","Change one app at a time",
          "Replace an app's home-screen icon with artwork from a pack, an imported theme, another installed app or your own image. Other icons stay as they are.",
          "Find an app, tap Choose artwork or Change artwork and inspect its preview. Tap Apply icon when it looks right. The left image shows the app's original artwork.",
          "Shizuku for Apply and Restore, plus native refresh support for that app. An icon trial is needed once for a new software or icon-style profile.",
          "Restore original icon recovers the saved original. If a full theme supplied the icon layer, Use theme icon removes only that override.",R.drawable.vf_rounded_grid_view_24,40),
      new Tool("icon_packs","icons","Icon packs","Preview and change a whole set",
          "Apply matching artwork from an installed icon pack or imported theme to selected apps. Individual choices remain available for apps without a match.",
          "Choose a pack, use Matching, Pack, Selected, No match or All to filter, tap rows to select, or use Select shown, Clear shown and Select options before Review.",
          "An installed icon pack or imported theme, Shizuku, and supported native refresh. Successful icon trials are remembered for their software and style.",
          "Restore selected apps or all changed icons from Icon options. Theme-icon overrides can be removed while keeping the full theme applied.",R.drawable.vf_rounded_brush_24,15),
      new Tool("connection","system","Connection and checks","Connect Shizuku and check access",
          "Connect VFont to Shizuku so it can use Vivo's existing customization services. This page shows the connection and native font-access check.",
          "Start Shizuku via Wireless debugging or ADB, then Connect here. On new software, run Test native access before applying a font. Icon tools handle their own icon trial.",
          "Shizuku 13 or newer started through ADB or Wireless debugging (shell UID 2000; root mode is not supported). VFont does not request root access.",
          "Connecting and checking do not change the selected font, theme or app icons. Successful checks are remembered while the relevant software and style stay the same.",R.drawable.vf_rounded_phone_android_24,190),
      new Tool("recovery","system","Restore your look","Find the right Restore for each tool",
          "Each customization tool owns its own recovery. Full-theme recovery returns the setup saved before its first Apply; font Restore returns the system default font.",
          "Open the relevant tool below and inspect its Restore option. If Shizuku is unavailable for fonts, revert in Vivo Settings -> Display & brightness -> Font style (or i Theme -> Local).",
          "Shizuku for native Restore. Keep VFont's recovery data until the matching tool reports that Restore finished.",
          "This page only opens the recovery tools. It does not restore anything automatically or remove a backup.",R.drawable.vf_rounded_shield_24,155),
      new Tool("support","support","Device support","Review checks and save a report",
          "Inspect device compatibility and review recent VFont results. Support reports are saved locally for you to share manually.",
          "Tap Check device, review the report and choose Save support report if you want to share it. System-file exports are optional and separate.",
          "Basic checks work without Shizuku. Connecting it enables deeper read-only checks.",
          "Support checks change no font, icon or phone setting. Clearing VFont's diagnostic history does not clear selections or appearance backups.",R.drawable.vf_rounded_help_24,320),
      new Tool("about","support","About VFont","Version, design credits and what's supported",
          "VFont is a local, non-root customization toolkit for compatible Vivo and iQOO phones. It has been physically tested on the owner's X200 Ultra; other devices still need verification.",
          "Use the category pages to find a tool, or search by what you want to change. Long-press any tool for its explanation; the Help button offers the same information.",
          "No connection is needed to browse the app or read help. A device check does not guarantee every theme, icon pack or font will render correctly.",
          "Your source files, selections and recovery data remain managed by their original tools. This interface update does not apply a phone theme.",R.drawable.vf_rounded_info_24,220));
  static Tool tool(String id){return TOOLS.stream().filter(t->t.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown tool"));}
  static Category category(String id){return CATEGORIES.stream().filter(c->c.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown category"));}
  static List<Tool> search(String query){String[] words=query.trim().toLowerCase(Locale.ROOT).split("\\s+");return TOOLS.stream().filter(t->{String hay=(t.title()+" "+t.summary()+" "+t.what()+" "+category(t.category()).title()).toLowerCase(Locale.ROOT);for(String word:words)if(!hay.contains(word))return false;return true;}).collect(Collectors.toList());}
}
