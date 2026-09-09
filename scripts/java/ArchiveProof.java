package onl.nl0.chengshu;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Runs the production store in real processes, including an uncatchable halt before publication. */
public final class ArchiveProof {
  private static int checks;
  private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
  private static void check(boolean value, String label) {
    if (!value) throw new AssertionError(label);
    checks++; System.out.println("PASS " + label);
  }
  private static String text(File file) throws IOException { return Files.readString(file.toPath()); }
  public static void main(String[] args) throws Exception {
    File root = new File(args[0]);
    if (args.length > 1 && args[1].equals("crash")) {
      new LocalArchive(root, () -> Runtime.getRuntime().halt(83)).put("article",
          Map.of("title", "not published"), Map.of("epub", bytes("new incomplete publication")));
      throw new AssertionError("child must halt");
    }
    if (args.length > 1 && args[1].equals("writer")) {
      LocalArchive store = new LocalArchive(root);
      for (int i = 0; i < 20; i++) store.put("parallel", Map.of("worker-" + args[2] + "-" + i, "yes"), Map.of());
      return;
    }
    LocalArchive store = new LocalArchive(root);
    store.put("article", Map.of("title", "original"), Map.of("epub", bytes("original saved file")));
    check(text(new LocalArchive(root).file("article", "epub")).equals("original saved file"), "reopen preserves committed bytes");
    Process crash = child(root, "crash", "");
    check(crash.waitFor() == 83, "real child halted between blob write and manifest publish");
    check(store.get("article").getProperty("title").equals("original"), "crash keeps old metadata");
    check(text(store.file("article", "epub")).equals("original saved file"), "crash keeps old readable file");
    store.put("article", Map.of("title", "new title"), Map.of("epub", bytes("new committed file")));
    check(text(store.file("article", "epub")).equals("new committed file"), "retry commits complete replacement");
    Process a = child(root, "writer", "a"), b = child(root, "writer", "b");
    check(a.waitFor() == 0 && b.waitFor() == 0, "concurrent writer processes exit successfully");
    check(store.get("parallel").size() == 41, "cross-process read-modify-write loses no metadata");
    ExecutorService pool = Executors.newFixedThreadPool(8);
    List<Future<?>> writes = new ArrayList<>();
    for (int i = 0; i < 64; i++) { final int n = i; writes.add(pool.submit(() -> {
      try { new LocalArchive(root).put("threaded", Map.of("key" + n, "yes"), Map.of()); }
      catch (IOException e) { throw new UncheckedIOException(e); }
    })); }
    for (Future<?> write : writes) write.get(); pool.shutdown();
    check(store.get("threaded").size() == 65, "independent store instances serialize concurrent threads");
    for (int i = 0; i < 205; i++) store.put("book" + i, Map.of("title", "saved"), Map.of("epub", bytes("content " + i)));
    check(text(store.file("book0", "epub")).equals("content 0") && store.list().size() >= 205,
        "more than 200 saved articles never evicts old content");
    try { store.put("article", Map.of(), Map.of("epub", new byte[0])); throw new AssertionError(); }
    catch (IOException expected) { check(text(store.file("article", "epub")).equals("new committed file"), "empty result cannot overwrite saved file"); }
    File original = store.file("article", "epub"); Files.writeString(original.toPath(), "damaged");
    try { store.file("article", "epub"); throw new AssertionError(); }
    catch (IOException expected) { check(true, "corrupt file is detected rather than handed off as success"); }
    store.put("article", Map.of(), Map.of("epub", bytes("new committed file")));
    check(text(store.file("article", "epub")).equals("new committed file"), "retry repairs corrupted content-addressed file");
    try { store.put("../escape", Map.of(), Map.of("epub", bytes("x"))); throw new AssertionError(); }
    catch (IOException expected) { check(true, "invalid article identity cannot escape storage root"); }
    store.delete("article");
    check(store.file("article", "epub") == null && "true".equals(store.get("article").getProperty("deleted")), "delete tombstone prevents legacy resurrection");
    PendingShares inbox = new PendingShares(new File(root, "inbox"));
    PendingShares.Job job = inbox.capture("url", "https://example.org", "Title", "", false);
    check(new PendingShares(new File(root, "inbox")).list().size() == 1, "pending share survives store recreation");
    check(inbox.capture("url", "https://example.org", "Title", "", false).id.equals(job.id) && inbox.list().size() == 1,
        "repeated delivery deduplicates unfinished share");
    job = inbox.choose(job, "epub"); inbox.fail(job, Failures.NETWORK);
    PendingShares.Job failed = inbox.list().get(0);
    check(failed.format.equals("epub") && Failures.NETWORK.equals(failed.errorCode)
        && failed.error.contains("网络"), "classified failure survives restart");
    job = inbox.choose(job, "epub");
    check(inbox.list().get(0).error.isEmpty() && inbox.list().get(0).errorCode.isEmpty(),
        "retry clears classified failure");
    check(inbox.claim(job) && !new PendingShares(new File(root, "inbox")).claim(job), "one in-process owner per conversion");
    inbox.release(job); check(inbox.claim(job), "released conversion can retry"); inbox.release(job);
    inbox.complete(job); check(inbox.list().isEmpty(), "acknowledged share leaves pending list");
    inbox.capture("url", "https://example.org", "Title", "", false);
    check(inbox.list().size() == 1, "later intentional share can reopen completed identity");
    System.out.println("ARCHIVE PROOF: " + checks + " checks passed");
  }
  private static Process child(File root, String mode, String worker) throws IOException {
    return new ProcessBuilder(new File(System.getProperty("java.home"), "bin/java").toString(),
        "-cp", System.getProperty("java.class.path"), ArchiveProof.class.getName(), root.toString(), mode, worker)
        .inheritIO().start();
  }
}
