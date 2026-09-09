package onl.nl0.chengshu;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** A durable inbox, not a background-execution guarantee. Unfinished shares stay retryable. */
final class PendingShares {
  private static final Set<String> RUNNING = ConcurrentHashMap.newKeySet();
  private static final AtomicLong REVISION = new AtomicLong();
  private final LocalArchive store;
  static long revision() { return REVISION.get(); }
  static final class Job {
    final String id, url, title, format, error, errorCode;
    final boolean force;
    final long created;
    Job(String id, Properties p) {
      this.id = id; url = p.getProperty("url", ""); title = p.getProperty("title", "");
      format = p.getProperty("format", ""); error = p.getProperty("error", "");
      errorCode = p.getProperty("errorCode", "");
      force = Boolean.parseBoolean(p.getProperty("force", "false"));
      created = Long.parseLong(p.getProperty("created", "0"));
    }
  }
  PendingShares(File root) { store = new LocalArchive(root); }

  Job capture(String identity, String url, String title, String format, boolean force) throws IOException {
    String id = LocalArchive.digest((identity + "\n" + format + "\n" + force).getBytes(StandardCharsets.UTF_8));
    Properties existing = store.get(id);
    if (!existing.isEmpty() && !"true".equals(existing.getProperty("deleted"))) return new Job(id, existing);
    Map<String, String> meta = new HashMap<>();
    meta.put("url", url); meta.put("title", title == null ? "" : title); meta.put("format", format);
    meta.put("force", Boolean.toString(force)); meta.put("created", Long.toString(System.currentTimeMillis()));
    meta.put("error", "");
    Job job = new Job(id, store.put(id, meta, Collections.emptyMap()));
    REVISION.incrementAndGet(); return job;
  }
  Job choose(Job job, String format) throws IOException {
    Map<String, String> patch = new HashMap<>(); patch.put("format", format); patch.put("error", ""); patch.put("errorCode", "");
    Job updated = new Job(job.id, store.put(job.id, patch, Collections.emptyMap()));
    REVISION.incrementAndGet(); return updated;
  }
  void fail(Job job) throws IOException { fail(job, Failures.CONVERSION); }

  void fail(Job job, String code) throws IOException {
    if (code == null || code.isEmpty()) code = Failures.CONVERSION;
    Map<String, String> patch = new HashMap<>();
    patch.put("error", Failures.attention(code));
    patch.put("errorCode", code);
    store.put(job.id, patch, Collections.emptyMap());
    REVISION.incrementAndGet();
  }
  void complete(Job job) throws IOException { store.delete(job.id); REVISION.incrementAndGet(); }
  List<Job> list() throws IOException {
    List<Job> jobs = new ArrayList<>();
    for (Map.Entry<String, Properties> entry : store.list().entrySet()) {
      if (!"true".equals(entry.getValue().getProperty("deleted"))) jobs.add(new Job(entry.getKey(), entry.getValue()));
    }
    jobs.sort((a, b) -> Long.compare(a.created, b.created));
    return jobs;
  }
  boolean claim(Job job) {
    synchronized (RUNNING) {
      // The renderer is process-wide. Never cancel another Activity's active extraction.
      boolean claimed = RUNNING.isEmpty() && RUNNING.add(job.id);
      REVISION.incrementAndGet(); return claimed;
    }
  }
  void release(Job job) { RUNNING.remove(job.id); REVISION.incrementAndGet(); }
  boolean running(Job job) { return RUNNING.contains(job.id); }
}
