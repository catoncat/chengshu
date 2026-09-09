package onl.nl0.chengshu;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/**
 * Packs already-saved snapshots without needing a particular Activity instance.
 * Dynamic pages still require a visible WebView (NEEDS_USER). WorkManager is not
 * used: JVM tests and the current AGP stack run this executor plus startup reconcile.
 */
final class ConversionCoordinator {
  interface Packager {
    LocalEpub.Result epub(PageExtractor.Article article, LocalEpub.ImageLoader images) throws Exception;
  }

  private final ArticleRepository articles;
  private final JobRepository jobs;
  private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "chengshu-pack");
    t.setDaemon(true);
    return t;
  });

  ConversionCoordinator(ArticleRepository articles) {
    this.articles = articles;
    this.jobs = articles.jobs;
  }

  JobRepository.Job enqueueSavedSnapshot(String url, String format, PageExtractor.Article article) throws Exception {
    String articleId = articles.ensureArticle(url, article.title);
    String snapshotId = articles.saveSnapshot(url, article);
    String requestKey = articleId + ":" + format + ":" + snapshotId;
    JobRepository.Job job = jobs.create(articleId, requestKey, format);
    JobRepository.Job claimed = jobs.claim(job.id);
    if (claimed == null) return job;
    return jobs.attachSnapshot(claimed.id, snapshotId, claimed.ownerToken);
  }

  File runInline(JobRepository.Job job, PageExtractor.Article article, LocalEpub.ImageLoader images) throws Exception {
    JobRepository.Job claimed = job.ownerToken.isEmpty() ? jobs.claim(job.id) : job;
    if (claimed == null) throw new IllegalStateException("任务不存在");
    if (JobRepository.SAVED.equals(claimed.state)) {
      return articles.artifactFile(claimed.articleId, Format.of(claimed.format));
    }
    if (JobRepository.terminal(claimed.state) || claimed.ownerToken.isEmpty())
      throw new IllegalStateException("旧任务不能覆盖新结果");
    try {
      Format format = Format.of(claimed.format);
      QualityReport quality;
      byte[] body;
      String title = article.title;
      if (format == Format.EPUB) {
        LocalEpub.Result result = LocalEpub.build(
            article.sourceUrl == null || article.sourceUrl.isEmpty() ? claimed.articleId : article.sourceUrl,
            article.title, article.byline, article.content, images);
        body = result.bytes;
        title = result.title;
        quality = QualityReport.evaluate(article.content, result.embeddedImages, result.missingImages);
        if (!result.warning.isEmpty() && quality.empty())
          quality.add("IMAGE_PARTIAL", "warning", result.warning);
      } else if (format == Format.PDF) {
        throw new UnsupportedOperationException("PDF_SERVER");
      } else {
        LocalPack.Result result = LocalPack.build(format, article.sourceUrl, article.title, article.byline, article.content);
        body = result.bytes;
        title = result.title;
        quality = QualityReport.evaluate(article.content, 0, 0);
      }
      // Blob first, then one catalog transaction that re-checks owner/generation/revision.
      JSONObject artifact = articles.prepareArtifact(claimed.articleId, title, format, claimed.snapshotId, body, quality);
      String artifactId = artifact.optString("id");
      String receiptId = "rcpt-" + artifactId;
      jobs.commit(claimed.id, claimed.ownerToken, claimed.generation, claimed.expectedRevision, artifactId, receiptId, artifact);
      return articles.artifactFile(claimed.articleId, format);
    } catch (UnsupportedOperationException e) {
      throw e;
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      jobs.fail(claimed.id, claimed.ownerToken, "CONVERSION_FAILED", true);
      throw e;
    }
  }

  List<JobRepository.Job> reconcile() throws Exception {
    return jobs.unfinished();
  }

  void shutdown() { pool.shutdownNow(); }
}
