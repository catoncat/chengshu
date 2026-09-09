package onl.nl0.chengshu;

import java.util.*;
import org.json.JSONObject;

final class JobRepository {
  static final String RECEIVED = "RECEIVED";
  static final String QUEUED = "QUEUED";
  static final String SNAPSHOT_READY = "SNAPSHOT_READY";
  static final String PACKAGING = "PACKAGING";
  static final String SAVED = "SAVED";
  static final String CANCELLED = "CANCELLED";
  static final String FAILED_RETRYABLE = "FAILED_RETRYABLE";
  static final String FAILED_FINAL = "FAILED_FINAL";
  static final String NEEDS_USER = "NEEDS_USER";

  static final class Job {
    final String id, articleId, requestKey, state, format, snapshotId, ownerToken, errorCode, receiptId;
    final int generation, expectedRevision, attempt;
    final long createdAt, updatedAt;
    Job(JSONObject o) {
      id = o.optString("id");
      articleId = o.optString("articleId");
      requestKey = o.optString("requestKey");
      state = o.optString("state");
      format = o.optString("format");
      snapshotId = o.optString("snapshotId");
      ownerToken = o.optString("ownerToken");
      errorCode = o.optString("errorCode");
      receiptId = o.optString("receiptId");
      generation = o.optInt("generation", 1);
      expectedRevision = o.optInt("expectedRevision", 1);
      attempt = o.optInt("attempt", 0);
      createdAt = o.optLong("createdAt");
      updatedAt = o.optLong("updatedAt");
    }
  }

  private final Store store;
  JobRepository(Store store) { this.store = store; }

  Job create(String articleId, String requestKey, String format) throws Exception {
    return store.locked(() -> {
      for (JSONObject row : store.list("jobs")) {
        if (requestKey.equals(row.optString("requestKey")) && !terminal(row.optString("state")))
          return new Job(row);
      }
      String id = LocalArchive.digest(("job\n" + requestKey + "\n" + System.nanoTime()).getBytes("UTF-8")).substring(0, 24);
      long now = System.currentTimeMillis();
      JSONObject row = new JSONObject();
      row.put("id", id);
      row.put("articleId", articleId);
      row.put("requestKey", requestKey);
      row.put("state", QUEUED);
      row.put("format", format);
      row.put("snapshotId", "");
      row.put("generation", 1);
      row.put("expectedRevision", 1);
      row.put("ownerToken", "");
      row.put("attempt", 0);
      row.put("errorCode", "");
      row.put("receiptId", "");
      row.put("createdAt", now);
      row.put("updatedAt", now);
      store.put("jobs", id, row);
      JSONObject active = new JSONObject();
      active.put("requestKey", requestKey);
      active.put("jobId", id);
      store.put("active_requests", requestKey.replace(':', '_'), active);
      return new Job(row);
    });
  }

  Job claim(String jobId) throws Exception {
    return store.locked(() -> {
      JSONObject row = store.get("jobs", jobId);
      if (row == null || terminal(row.optString("state"))) return row == null ? null : new Job(row);
      String token = UUID.randomUUID().toString();
      row.put("ownerToken", token);
      row.put("state", row.optString("snapshotId").isEmpty() ? QUEUED : PACKAGING);
      row.put("updatedAt", System.currentTimeMillis());
      row.put("attempt", row.optInt("attempt") + 1);
      store.put("jobs", jobId, row);
      return new Job(row);
    });
  }

  Job attachSnapshot(String jobId, String snapshotId, String ownerToken) throws Exception {
    return store.locked(() -> {
      JSONObject row = requireOwner(jobId, ownerToken);
      row.put("snapshotId", snapshotId);
      row.put("state", SNAPSHOT_READY);
      row.put("updatedAt", System.currentTimeMillis());
      store.put("jobs", jobId, row);
      return new Job(row);
    });
  }

  Job cancel(String jobId) throws Exception {
    return store.locked(() -> {
      JSONObject row = store.get("jobs", jobId);
      if (row == null) return null;
      if (SAVED.equals(row.optString("state"))) return new Job(row);
      row.put("generation", row.optInt("generation") + 1);
      row.put("ownerToken", "");
      row.put("state", CANCELLED);
      row.put("updatedAt", System.currentTimeMillis());
      store.put("jobs", jobId, row);
      store.remove("active_requests", row.optString("requestKey").replace(':', '_'));
      return new Job(row);
    });
  }

  Job fail(String jobId, String ownerToken, String code, boolean retryable) throws Exception {
    return store.locked(() -> {
      JSONObject row = requireOwner(jobId, ownerToken);
      row.put("errorCode", code);
      row.put("state", retryable && row.optInt("attempt") < 3 ? FAILED_RETRYABLE : FAILED_FINAL);
      row.put("updatedAt", System.currentTimeMillis());
      store.put("jobs", jobId, row);
      return new Job(row);
    });
  }

  Job commit(String jobId, String ownerToken, int expectedGeneration, int expectedRevision,
      String artifactId, String receiptId) throws Exception {
    return store.locked(() -> {
      JSONObject row = store.get("jobs", jobId);
      if (row == null) throw new IllegalStateException("任务不存在");
      if (JobRepository.SAVED.equals(row.optString("state")) && !row.optString("receiptId").isEmpty())
        return new Job(row);
      if (terminal(row.optString("state"))) throw new IllegalStateException("旧任务不能覆盖新结果");
      if (ownerToken == null || ownerToken.isEmpty() || !ownerToken.equals(row.optString("ownerToken")))
        throw new IllegalStateException("旧任务不能覆盖新结果");
      if (row.optInt("generation") != expectedGeneration) throw new IllegalStateException("旧任务不能覆盖新结果");
      JSONObject article = store.get("articles", row.optString("articleId"));
      if (article != null && article.optLong("deletedAt") > 0) throw new IllegalStateException("文章已删除");
      if (article != null && article.optInt("revision") != expectedRevision)
        throw new IllegalStateException("旧任务不能覆盖新结果");
      if (SAVED.equals(row.optString("state")) && !row.optString("receiptId").isEmpty())
        return new Job(row);
      row.put("state", SAVED);
      row.put("receiptId", receiptId);
      row.put("updatedAt", System.currentTimeMillis());
      store.put("jobs", jobId, row);
      JSONObject receipt = new JSONObject();
      receipt.put("id", receiptId);
      receipt.put("jobId", jobId);
      receipt.put("generation", expectedGeneration);
      receipt.put("artifactId", artifactId);
      receipt.put("publishedAt", System.currentTimeMillis());
      store.put("receipts", receiptId, receipt);
      store.remove("active_requests", row.optString("requestKey").replace(':', '_'));
      return new Job(row);
    });
  }

  List<Job> unfinished() throws Exception {
    return store.locked(() -> {
      List<Job> jobs = new ArrayList<>();
      for (JSONObject row : store.list("jobs")) {
        if (row.optBoolean("corrupt")) continue;
        if (!terminal(row.optString("state"))) jobs.add(new Job(row));
      }
      return jobs;
    });
  }

  Job get(String id) throws Exception {
    return store.locked(() -> {
      JSONObject row = store.get("jobs", id);
      return row == null ? null : new Job(row);
    });
  }

  private JSONObject requireOwner(String jobId, String ownerToken) throws Exception {
    JSONObject row = store.get("jobs", jobId);
    if (row == null) throw new IllegalStateException("任务不存在");
    if (!ownerToken.equals(row.optString("ownerToken"))) throw new IllegalStateException("旧任务不能覆盖新结果");
    if (terminal(row.optString("state"))) throw new IllegalStateException("任务已结束");
    return row;
  }

  static boolean terminal(String state) {
    return SAVED.equals(state) || CANCELLED.equals(state) || FAILED_FINAL.equals(state);
  }
}
