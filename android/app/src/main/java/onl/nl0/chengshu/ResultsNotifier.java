package onl.nl0.chengshu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Aggregates conversion outcomes so the UI can post one notification instead of a burst.
 * Does not itself talk to NotificationManager.
 */
final class ResultsNotifier {
  static final ResultsNotifier NOOP = new ResultsNotifier(notice -> { });

  interface Listener {
    void onNotice(Notice notice);
  }

  static final class Event {
    final String kind;
    final String title;
    final String format;
    final String itemId;
    Event(String kind, String title, String format, String itemId) {
      this.kind = kind == null ? "" : kind;
      this.title = title == null ? "" : title;
      this.format = format == null ? "" : format;
      this.itemId = itemId == null ? "" : itemId;
    }
  }

  static final class Notice {
    final String title;
    final String text;
    final String itemId;
    final String kind;
    final int count;
    Notice(String title, String text, String itemId, String kind, int count) {
      this.title = title;
      this.text = text;
      this.itemId = itemId == null ? "" : itemId;
      this.kind = kind;
      this.count = count;
    }
  }

  private final Object lock = new Object();
  private final List<Event> pending = new ArrayList<>();
  private final Listener listener;

  ResultsNotifier(Listener listener) {
    this.listener = listener == null ? notice -> { } : listener;
  }

  void saved(String title, String format, String itemId) {
    emit(new Event("saved", title, format, itemId));
  }

  void failed(String title, String code) {
    emit(new Event("failed", title, code == null ? "" : code, ""));
  }

  void clear() {
    synchronized (lock) { pending.clear(); }
  }

  List<Event> snapshot() {
    synchronized (lock) { return Collections.unmodifiableList(new ArrayList<>(pending)); }
  }

  private void emit(Event event) {
    Notice notice;
    synchronized (lock) {
      pending.add(event);
      notice = summarize(new ArrayList<>(pending));
    }
    if (notice != null) listener.onNotice(notice);
  }

  static Notice summarize(List<Event> events) {
    if (events == null || events.isEmpty()) return null;
    int saved = 0;
    int failed = 0;
    Event lastSaved = null;
    Event lastFailed = null;
    for (Event event : events) {
      if ("saved".equals(event.kind)) {
        saved++;
        lastSaved = event;
      } else {
        failed++;
        lastFailed = event;
      }
    }
    if (saved > 0 && failed == 0) {
      if (saved == 1) {
        String label = lastSaved.title.isEmpty() ? "这篇" : lastSaved.title;
        return new Notice("文件已保存", label, lastSaved.itemId, "saved", 1);
      }
      return new Notice(saved + " 篇文章已保存", "点开可继续阅读", "", "saved", saved);
    }
    if (failed > 0 && saved == 0) {
      if (failed == 1) {
        String label = lastFailed.title.isEmpty() ? "这次没能完成" : lastFailed.title;
        return new Notice("这次没能完成", label, "", "failed", 1);
      }
      return new Notice(failed + " 篇没能完成", "点开可重试，已保存的文件还在", "", "failed", failed);
    }
    return new Notice("成书有新结果", saved + " 篇已保存，" + failed + " 篇没完成", "", "mixed", saved + failed);
  }
}
