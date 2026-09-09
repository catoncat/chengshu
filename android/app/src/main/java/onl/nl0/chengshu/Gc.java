package onl.nl0.chengshu;

import java.io.File;
import java.util.*;

final class Gc {
  static final class Report {
    final long bytes;
    final int files;
    final List<File> orphans;
    Report(List<File> orphans) {
      this.orphans = orphans;
      int n = 0; long b = 0;
      for (File file : orphans) { n++; b += file.length(); }
      files = n; bytes = b;
    }
  }

  static Report dryRun(ArticleRepository articles) throws Exception {
    Set<String> live = articles.referencedBlobs();
    return new Report(articles.store.blobs.orphans(live));
  }

  static Report sweep(ArticleRepository articles, boolean includePartFiles) throws Exception {
    Report report = dryRun(articles);
    for (File file : report.orphans) {
      if (!includePartFiles && file.getName().endsWith(".part")) continue;
      // Never delete a hash still referenced after dry-run.
      if (file.getName().matches("[0-9a-f]{64}") && articles.referencedBlobs().contains(file.getName())) continue;
      file.delete();
    }
    return report;
  }
}
