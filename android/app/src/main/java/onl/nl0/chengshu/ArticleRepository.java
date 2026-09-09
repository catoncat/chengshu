package onl.nl0.chengshu;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.json.JSONObject;

final class ArticleRepository {
  static final String PACKAGER = "chengshu-local-1";
  final Store store;
  final JobRepository jobs;

  ArticleRepository(File root) {
    this.store = new Store(root);
    this.jobs = new JobRepository(store);
  }

  String ensureArticle(String url, String title) throws Exception {
    return store.locked(() -> {
      String v2 = Urls.normalize(url, 2);
      String v1 = Urls.normalize(url, 1);
      JSONObject alias = store.get("url_aliases", Urls.aliasKey(url, 2).replace(':', '_'));
      if (alias == null) alias = store.get("url_aliases", Urls.aliasKey(url, 1).replace(':', '_'));
      if (alias != null) {
        JSONObject article = store.get("articles", alias.optString("articleId"));
        if (article != null && article.optLong("deletedAt") == 0) return article.optString("id");
      }
      String id = Urls.articleId(v2);
      JSONObject existing = store.get("articles", id);
      long now = System.currentTimeMillis();
      if (existing == null) {
        existing = new JSONObject();
        existing.put("id", id);
        existing.put("originalUrl", url);
        existing.put("displayUrl", v2);
        existing.put("identityVersion", Urls.VERSION);
        existing.put("title", title == null ? "" : title);
        existing.put("createdAt", now);
        existing.put("currentSnapshotId", "");
        existing.put("revision", 1);
        existing.put("deletedAt", 0);
        existing.put("legacy", false);
      }
      existing.put("updatedAt", now);
      store.put("articles", id, existing);
      putAlias(url, 1, id);
      putAlias(url, 2, id);
      if (!v1.equals(v2)) putAlias(v1, 1, id);
      return id;
    });
  }

  String saveSnapshot(String url, PageExtractor.Article article) throws Exception {
    byte[] html = article.content.getBytes(StandardCharsets.UTF_8);
    File blob = store.blobs.put(html);
    String hash = blob.getName();
    return store.locked(() -> {
      String articleId = ensureArticleUnlocked(url, article.title);
      String snapshotId = LocalArchive.digest(("snap\n" + articleId + "\n" + hash).getBytes(StandardCharsets.UTF_8)).substring(0, 24);
      JSONObject row = new JSONObject();
      row.put("id", snapshotId);
      row.put("articleId", articleId);
      row.put("htmlBlobHash", hash);
      row.put("baseUrl", article.sourceUrl == null || article.sourceUrl.isEmpty() ? url : article.sourceUrl);
      row.put("sourceTitle", article.title);
      row.put("byline", article.byline == null ? "" : article.byline);
      row.put("capturedAt", System.currentTimeMillis());
      row.put("extractorVersion", "defuddle-webview");
      row.put("qualityJson", QualityReport.evaluate(article.content, 0, 0).json().toString());
      store.put("snapshots", snapshotId, row);
      JSONObject articleRow = store.get("articles", articleId);
      articleRow.put("currentSnapshotId", snapshotId);
      articleRow.put("title", article.title);
      articleRow.put("updatedAt", System.currentTimeMillis());
      store.put("articles", articleId, articleRow);
      return snapshotId;
    });
  }

  PageExtractor.Article snapshot(String url) throws Exception {
    return store.locked(() -> {
      String id = findId(url);
      if (id == null) return null;
      JSONObject article = store.get("articles", id);
      if (article == null || article.optString("currentSnapshotId").isEmpty()) return null;
      JSONObject snap = store.get("snapshots", article.optString("currentSnapshotId"));
      if (snap == null) return null;
      File file = store.blobs.file(snap.optString("htmlBlobHash"));
      if (file == null) return null;
      String html = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
      return new PageExtractor.Article(snap.optString("sourceTitle"), snap.optString("byline"), html, snap.optString("baseUrl"));
    });
  }

  String publish(String url, String title, Format format, String snapshotId, byte[] body, QualityReport quality)
      throws Exception {
    File blob = store.blobs.put(body);
    String hash = blob.getName();
    return store.locked(() -> {
      String articleId = ensureArticleUnlocked(url, title);
      JSONObject article = store.get("articles", articleId);
      String artifactId = LocalArchive.digest((articleId + format.id + hash).getBytes(StandardCharsets.UTF_8)).substring(0, 24);
      JSONObject artifact = new JSONObject();
      artifact.put("id", artifactId);
      artifact.put("articleId", articleId);
      artifact.put("snapshotId", snapshotId == null ? "" : snapshotId);
      artifact.put("format", format.id);
      artifact.put("optionsHash", format.id + ":" + PACKAGER);
      artifact.put("packagerVersion", PACKAGER);
      artifact.put("blobHash", hash);
      artifact.put("byteLength", body.length);
      artifact.put("qualityJson", quality == null ? "" : quality.json().toString());
      artifact.put("createdAt", System.currentTimeMillis());
      artifact.put("legacySource", false);
      store.put("artifacts", artifactId, artifact);
      article.put("title", title);
      article.put("updatedAt", System.currentTimeMillis());
      store.put("articles", articleId, article);
      return artifactId;
    });
  }

  /** Write the blob outside the catalog lock. Cataloging happens only inside JobRepository.commit. */
  JSONObject prepareArtifact(String articleId, String title, Format format, String snapshotId,
      byte[] body, QualityReport quality) throws Exception {
    File blob = store.blobs.put(body);
    String hash = blob.getName();
    String artifactId = LocalArchive.digest((articleId + format.id + hash).getBytes(StandardCharsets.UTF_8)).substring(0, 24);
    JSONObject artifact = new JSONObject();
    artifact.put("id", artifactId);
    artifact.put("articleId", articleId);
    artifact.put("snapshotId", snapshotId == null ? "" : snapshotId);
    artifact.put("format", format.id);
    artifact.put("optionsHash", format.id + ":" + PACKAGER);
    artifact.put("packagerVersion", PACKAGER);
    artifact.put("blobHash", hash);
    artifact.put("byteLength", body.length);
    artifact.put("qualityJson", quality == null ? "" : quality.json().toString());
    artifact.put("createdAt", System.currentTimeMillis());
    artifact.put("legacySource", false);
    artifact.put("sourceTitle", title == null ? "" : title);
    return artifact;
  }

  File artifactFile(String articleId, Format format) throws Exception {
    return store.locked(() -> {
      JSONObject newest = null;
      for (JSONObject row : store.list("artifacts")) {
        if (!articleId.equals(row.optString("articleId"))) continue;
        if (!format.id.equals(row.optString("format"))) continue;
        if (newest == null || row.optLong("createdAt") > newest.optLong("createdAt")) newest = row;
      }
      return newest == null ? null : store.blobs.file(newest.optString("blobHash"));
    });
  }

  QualityReport artifactQuality(String articleId, Format format) throws Exception {
    return store.locked(() -> {
      JSONObject newest = null;
      for (JSONObject row : store.list("artifacts")) {
        if (!articleId.equals(row.optString("articleId")) || !format.id.equals(row.optString("format"))) continue;
        if (newest == null || row.optLong("createdAt") > newest.optLong("createdAt")) newest = row;
      }
      return newest == null ? new QualityReport() : QualityReport.parse(newest.optString("qualityJson"));
    });
  }

  void delete(String articleId) throws Exception {
    store.locked(() -> {
      JSONObject article = store.get("articles", articleId);
      if (article == null) return null;
      article.put("deletedAt", System.currentTimeMillis());
      article.put("revision", article.optInt("revision") + 1);
      store.put("articles", articleId, article);
      for (JSONObject job : store.list("jobs")) {
        if (articleId.equals(job.optString("articleId")) && !JobRepository.terminal(job.optString("state"))) {
          job.put("generation", job.optInt("generation") + 1);
          job.put("state", JobRepository.CANCELLED);
          job.put("ownerToken", "");
          store.put("jobs", job.optString("id"), job);
        }
      }
      return null;
    });
  }

  List<JSONObject> visibleArticles() throws Exception {
    return store.locked(() -> {
      List<JSONObject> rows = new ArrayList<>();
      for (JSONObject row : store.list("articles")) {
        if (row.optBoolean("corrupt")) continue;
        if (row.optLong("deletedAt") > 0) continue;
        boolean hasFile = false;
        for (JSONObject artifact : store.list("artifacts")) {
          if (row.optString("id").equals(artifact.optString("articleId"))) { hasFile = true; break; }
        }
        if (hasFile) rows.add(row);
      }
      rows.sort((a, b) -> Long.compare(b.optLong("updatedAt"), a.optLong("updatedAt")));
      return rows;
    });
  }

  void importLegacy(Library library) throws Exception {
    for (Library.Item item : library.list()) {
      store.locked(() -> {
        if (findId(item.url) != null) return null;
        String id = ensureArticleUnlocked(item.url, item.title);
        JSONObject article = store.get("articles", id);
        article.put("legacy", true);
        article.put("title", item.title);
        store.put("articles", id, article);
        for (String formatId : item.formats) {
          Format format = Format.of(formatId);
          File file = library.file(item, format);
          if (file == null || !file.isFile()) continue;
          byte[] body = Files.readAllBytes(file.toPath());
          File blob = store.blobs.put(body);
          JSONObject artifact = new JSONObject();
          String artifactId = blob.getName().substring(0, 24);
          artifact.put("id", artifactId);
          artifact.put("articleId", id);
          artifact.put("snapshotId", "");
          artifact.put("format", format.id);
          artifact.put("optionsHash", "legacy");
          artifact.put("packagerVersion", "legacy");
          artifact.put("blobHash", blob.getName());
          artifact.put("byteLength", body.length);
          artifact.put("qualityJson", library.warning(item, format));
          artifact.put("createdAt", item.updated);
          artifact.put("legacySource", true);
          store.put("artifacts", artifactId, artifact);
        }
        PageExtractor.Article snap = library.snapshot(item.url);
        if (snap != null) saveSnapshotUnlocked(item.url, snap);
        return null;
      });
    }
  }

  Set<String> referencedBlobs() throws Exception {
    return store.locked(() -> {
      Set<String> hashes = new HashSet<>();
      for (JSONObject row : store.list("snapshots")) hashes.add(row.optString("htmlBlobHash"));
      for (JSONObject row : store.list("artifacts")) hashes.add(row.optString("blobHash"));
      hashes.remove("");
      return hashes;
    });
  }

  private void putAlias(String url, int version, String articleId) throws Exception {
    JSONObject alias = new JSONObject();
    alias.put("aliasKey", Urls.aliasKey(url, version));
    alias.put("identityVersion", version);
    alias.put("articleId", articleId);
    store.put("url_aliases", Urls.aliasKey(url, version).replace(':', '_'), alias);
  }

  private String findId(String url) throws Exception {
    JSONObject alias = store.get("url_aliases", Urls.aliasKey(url, 2).replace(':', '_'));
    if (alias == null) alias = store.get("url_aliases", Urls.aliasKey(url, 1).replace(':', '_'));
    return alias == null ? null : alias.optString("articleId");
  }

  private String ensureArticleUnlocked(String url, String title) throws Exception {
    String id = findId(url);
    if (id != null) return id;
    // nested locked() is same thread holding PROCESS_LOCK already; call public path only outside.
    String v2 = Urls.normalize(url, 2);
    id = Urls.articleId(v2);
    JSONObject existing = store.get("articles", id);
    long now = System.currentTimeMillis();
    if (existing == null) {
      existing = new JSONObject();
      existing.put("id", id);
      existing.put("originalUrl", url);
      existing.put("displayUrl", v2);
      existing.put("identityVersion", Urls.VERSION);
      existing.put("title", title == null ? "" : title);
      existing.put("createdAt", now);
      existing.put("currentSnapshotId", "");
      existing.put("revision", 1);
      existing.put("deletedAt", 0);
      existing.put("legacy", false);
    }
    existing.put("updatedAt", now);
    store.put("articles", id, existing);
    putAlias(url, 1, id);
    putAlias(url, 2, id);
    return id;
  }

  private void saveSnapshotUnlocked(String url, PageExtractor.Article article) throws Exception {
    byte[] html = article.content.getBytes(StandardCharsets.UTF_8);
    File blob = store.blobs.put(html);
    String articleId = ensureArticleUnlocked(url, article.title);
    String snapshotId = LocalArchive.digest(("snap\n" + articleId + "\n" + blob.getName()).getBytes(StandardCharsets.UTF_8)).substring(0, 24);
    JSONObject row = new JSONObject();
    row.put("id", snapshotId);
    row.put("articleId", articleId);
    row.put("htmlBlobHash", blob.getName());
    row.put("baseUrl", article.sourceUrl);
    row.put("sourceTitle", article.title);
    row.put("byline", article.byline);
    row.put("capturedAt", System.currentTimeMillis());
    row.put("extractorVersion", "legacy");
    row.put("qualityJson", "");
    store.put("snapshots", snapshotId, row);
  }
}
