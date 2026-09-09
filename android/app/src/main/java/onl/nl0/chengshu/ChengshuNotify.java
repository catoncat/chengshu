package onl.nl0.chengshu;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

/** Posts an aggregated result notice. Permission denial must not fail conversion. */
final class ChengshuNotify {
  static final String CHANNEL = "chengshu.results";
  static final int ID = 41;
  static final String EXTRA_ITEM = "openItemId";
  static final String EXTRA_KIND = "notifyKind";

  static void ensureChannel(Context context) {
    if (Build.VERSION.SDK_INT < 26) return;
    NotificationManager manager = manager(context);
    if (manager == null) return;
    NotificationChannel channel = new NotificationChannel(
        CHANNEL, "成书结果", NotificationManager.IMPORTANCE_DEFAULT);
    channel.setDescription("处理完告诉你，不会把别的应用挤到后面");
    manager.createNotificationChannel(channel);
  }

  static void requestIfNeeded(Activity activity) {
    if (Build.VERSION.SDK_INT < 33) return;
    if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
        == PackageManager.PERMISSION_GRANTED) return;
    activity.requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, ID);
  }

  static void show(Context context, ResultsNotifier.Notice notice) {
    if (notice == null) return;
    ensureChannel(context);
    NotificationManager manager = manager(context);
    if (manager == null) return;
    Intent open = new Intent(context, ShareActivity.class);
    open.setAction(Intent.ACTION_MAIN);
    open.addCategory(Intent.CATEGORY_LAUNCHER);
    open.putExtra(EXTRA_ITEM, notice.itemId);
    open.putExtra(EXTRA_KIND, notice.kind);
    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
    if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
    PendingIntent pi = PendingIntent.getActivity(context, ID, open, flags);
    Notification.Builder builder = Build.VERSION.SDK_INT >= 26
        ? new Notification.Builder(context, CHANNEL)
        : new Notification.Builder(context);
    builder.setSmallIcon(android.R.drawable.stat_sys_download_done)
        .setContentTitle(notice.title)
        .setContentText(notice.text)
        .setAutoCancel(true)
        .setContentIntent(pi);
    try {
      manager.notify(ID, builder.build());
    } catch (SecurityException ignored) {
      /* Permission denied: the home list still has the result. */
    }
  }

  static void cancel(Context context) {
    NotificationManager manager = manager(context);
    if (manager != null) manager.cancel(ID);
  }

  static String itemIdOf(Intent intent) {
    if (intent == null) return "";
    String id = intent.getStringExtra(EXTRA_ITEM);
    return id == null ? "" : id;
  }

  private static NotificationManager manager(Context context) {
    return (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
  }
}
