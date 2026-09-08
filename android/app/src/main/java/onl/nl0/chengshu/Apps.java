package onl.nl0.chengshu;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

final class Apps {
  static final String[] PINNED = {
    "org.koreader.launcher",
    "org.koreader.launcher.fdroid",
    "com.foobnix.pdf.reader",
    "com.foobnix.pro.pdf.reader",
    "io.legado.app.release",
    "io.legado.app",
    "org.coolreader",
    "org.readera",
    "com.flyersoft.moonreaderp",
    "com.flyersoft.moonreader",
    "com.tencent.weread",
    "com.tencent.weread.online",
    "info.plateaukao.einkbro",
    "md.obsidian",
    "net.gsantner.markor"
  };

  static final String[] NOISY = {
    "com.android.bluetooth",
    "com.android.nfc",
    "com.android.intentresolver",
    "com.google.android.gms",
    "com.android.systemui",
    "com.google.android.packageinstaller",
    "com.google.android.apps.nbu.files",
    "com.google.android.apps.docs"
  };

  static final class Entry {
    final String packageName;
    final String label;
    final Drawable icon;
    final boolean pinned;

    Entry(String packageName, String label, Drawable icon, boolean pinned) {
      this.packageName = packageName;
      this.label = label;
      this.icon = icon;
      this.pinned = pinned;
    }
  }

  static Set<String> hidden(SharedPreferences prefs) {
    Set<String> set = new HashSet<>(prefs.getStringSet("hidden_apps", new HashSet<>()));
    for (String pkg : NOISY) set.add(pkg);
    return set;
  }

  static Set<String> userHidden(SharedPreferences prefs) {
    return new HashSet<>(prefs.getStringSet("hidden_apps", new HashSet<>()));
  }

  static void hide(SharedPreferences prefs, String pkg) {
    Set<String> set = userHidden(prefs);
    set.add(pkg);
    prefs.edit().putStringSet("hidden_apps", set).apply();
  }

  static void unhide(SharedPreferences prefs, String pkg) {
    Set<String> set = userHidden(prefs);
    set.remove(pkg);
    prefs.edit().putStringSet("hidden_apps", set).apply();
  }

  static List<Entry> visible(Context context, Format format, SharedPreferences prefs) {
    return list(context, format, hidden(prefs), false);
  }

  static List<Entry> hiddenEntries(Context context, Format format, SharedPreferences prefs) {
    return list(context, format, Collections.emptySet(), true, userHidden(prefs));
  }

  private static List<Entry> list(
      Context context, Format format, Set<String> skip, boolean onlyHidden) {
    return list(context, format, skip, onlyHidden, skip);
  }

  private static List<Entry> list(
      Context context,
      Format format,
      Set<String> skip,
      boolean onlyHidden,
      Set<String> hiddenFilter) {
    PackageManager pm = context.getPackageManager();
    LinkedHashMap<String, Entry> unique = new LinkedHashMap<>();
    for (ResolveInfo info : query(pm, format.mime)) add(context, unique, info, format);
    for (String extra : format.extraMimes) {
      for (ResolveInfo info : query(pm, extra)) add(context, unique, info, format);
    }
    List<Entry> pinned = new ArrayList<>();
    List<Entry> rest = new ArrayList<>();
    for (Entry entry : unique.values()) {
      if (entry.packageName.equals(context.getPackageName())) continue;
      boolean isHidden = hiddenFilter.contains(entry.packageName);
      if (onlyHidden && !isHidden) continue;
      if (!onlyHidden && skip.contains(entry.packageName)) continue;
      if (entry.pinned) pinned.add(entry);
      else rest.add(entry);
    }
    Collections.sort(rest, (a, b) -> a.label.compareToIgnoreCase(b.label));
    pinned.addAll(rest);
    return pinned;
  }

  private static List<ResolveInfo> query(PackageManager pm, String mime) {
    Intent probe = new Intent(Intent.ACTION_VIEW);
    probe.setType(mime);
    probe.addCategory(Intent.CATEGORY_DEFAULT);
    return pm.queryIntentActivities(probe, PackageManager.MATCH_ALL);
  }

  private static void add(
      Context context, LinkedHashMap<String, Entry> unique, ResolveInfo info, Format format) {
    if (info.activityInfo == null) return;
    String pkg = info.activityInfo.packageName;
    if (unique.containsKey(pkg)) return;
    PackageManager pm = context.getPackageManager();
    CharSequence label = info.loadLabel(pm);
    boolean pinned = false;
    for (String pin : PINNED) {
      if (pin.equals(pkg)) {
        pinned = true;
        break;
      }
    }
    unique.put(
        pkg, new Entry(pkg, label == null ? pkg : label.toString(), info.loadIcon(pm), pinned));
  }
}
