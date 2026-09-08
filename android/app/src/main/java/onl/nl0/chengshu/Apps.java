package onl.nl0.chengshu;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;
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
    "com.google.android.apps.docs",
    "com.tencent.mobileqq",
    "com.tencent.mm",
    "com.tencent.wetype",
    "com.netease.cloudmusic",
    "com.termux",
    "com.termux.app"
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

  static Intent viewProbe(Uri data, String mime) {
    Intent probe = new Intent(Intent.ACTION_VIEW);
    probe.setDataAndType(data, mime);
    probe.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    probe.addCategory(Intent.CATEGORY_DEFAULT);
    return probe;
  }

  static ArrayList<ComponentName> excludeComponents(
      Context context, Format format, SharedPreferences prefs) {
    Set<String> skip = hidden(prefs);
    ArrayList<ComponentName> out = new ArrayList<>();
    for (ResolveInfo info : query(context, format)) {
      if (info.activityInfo == null) continue;
      if (!skip.contains(info.activityInfo.packageName)) continue;
      out.add(new ComponentName(info.activityInfo.packageName, info.activityInfo.name));
    }
    return out;
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
    LinkedHashMap<String, Entry> unique = new LinkedHashMap<>();
    for (ResolveInfo info : query(context, format)) add(context, unique, info);
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

  private static List<ResolveInfo> query(Context context, Format format) {
    PackageManager pm = context.getPackageManager();
    LinkedHashMap<String, ResolveInfo> unique = new LinkedHashMap<>();
    Uri data = probeUri(context, format);
    String[] mimes = new String[1 + format.extraMimes.length];
    mimes[0] = format.mime;
    System.arraycopy(format.extraMimes, 0, mimes, 1, format.extraMimes.length);
    for (String mime : mimes) {
      remember(unique, pm.queryIntentActivities(viewProbe(data, mime), PackageManager.MATCH_ALL));
      Intent typed = new Intent(Intent.ACTION_VIEW);
      typed.setType(mime);
      remember(unique, pm.queryIntentActivities(typed, PackageManager.MATCH_ALL));
    }
    return new ArrayList<>(unique.values());
  }

  private static void remember(LinkedHashMap<String, ResolveInfo> unique, List<ResolveInfo> infos) {
    for (ResolveInfo info : infos) {
      if (info.activityInfo == null) continue;
      String key = info.activityInfo.packageName + "/" + info.activityInfo.name;
      if (!unique.containsKey(key)) unique.put(key, info);
    }
  }

  static Uri probeUri(Context context, Format format) {
    File dir = new File(context.getCacheDir(), "probe");
    if (!dir.isDirectory()) dir.mkdirs();
    File file = new File(dir, "probe" + format.ext);
    if (!file.exists()) {
      try (FileOutputStream out = new FileOutputStream(file)) {
        out.write(new byte[] {'P', 'K'});
      } catch (Exception ignored) {
        return Uri.parse("content://" + context.getPackageName() + ".files/probe" + format.ext);
      }
    }
    return FileProvider.getUriForFile(context, context.getPackageName() + ".files", file);
  }

  private static void add(Context context, LinkedHashMap<String, Entry> unique, ResolveInfo info) {
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
