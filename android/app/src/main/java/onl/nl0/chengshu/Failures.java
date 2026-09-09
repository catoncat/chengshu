package onl.nl0.chengshu;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;

/** Maps a thrown error onto a short, honest user-facing code. Not a stack trace. */
final class Failures {
  static final String NETWORK = "NETWORK_UNAVAILABLE";
  static final String TIMEOUT = "SOURCE_TIMEOUT";
  static final String AUTH = "AUTH_REQUIRED";
  static final String EMPTY = "CONTENT_EMPTY";
  static final String STORAGE = "STORAGE_FULL";
  static final String CONVERSION = "CONVERSION_FAILED";

  static String code(Exception e) {
    if (e == null) return CONVERSION;
    if (e instanceof UnknownHostException || e instanceof ConnectException
        || e instanceof SocketTimeoutException || e instanceof SocketException
        || e instanceof InterruptedIOException)
      return NETWORK;
    String raw = e.getMessage() == null ? "" : e.getMessage();
    String lower = raw.toLowerCase(Locale.ROOT);
    if (lower.contains("enospc") || lower.contains("no space") || raw.contains("空间不足")
        || lower.contains("enomem"))
      return STORAGE;
    if (raw.contains("没有提取") || raw.contains("没有生成") || raw.contains("没有拿到"))
      return EMPTY;
    if (lower.contains("failed to connect") || lower.contains("timeout")
        || lower.contains("unknownhost") || lower.contains("unable to resolve")
        || lower.contains("network is unreachable") || lower.contains("econnrefused")
        || lower.contains("ehostunreach") || lower.contains("eai_again"))
      return NETWORK;
    return CONVERSION;
  }

  static String fromQuality(QualityReport report) {
    if (report == null) return CONVERSION;
    for (QualityReport.Issue issue : report.issues) {
      if (!"error".equals(issue.severity)) continue;
      if (AUTH.equals(issue.code)) return AUTH;
      if (EMPTY.equals(issue.code)) return EMPTY;
    }
    return CONVERSION;
  }

  static String message(String code) {
    if (NETWORK.equals(code)) return "等待网络。连上网后点重试即可，已保存的书还在。";
    if (TIMEOUT.equals(code)) return "这一页读得太慢，没有保存新文件。可以重试，或先查看原文。";
    if (AUTH.equals(code)) return "需要在浏览器里先打开这一页。成书不会绕过登录或付费墙。";
    if (EMPTY.equals(code)) return "这次没有拿到可阅读的正文。原链接还在，可以重试或查看原文。";
    if (STORAGE.equals(code)) return "存储空间不足，没有写入新文件。已保存的书未改动。";
    return "这次没能完成，链接和已保存的内容仍在。可以重试，或先回到最近。";
  }

  static String attention(String code) {
    if (NETWORK.equals(code)) return "等待网络 · 点此重试";
    if (TIMEOUT.equals(code)) return "读取超时 · 点此重试";
    if (AUTH.equals(code)) return "需要先在浏览器打开 · 点此继续";
    if (EMPTY.equals(code)) return "没有正文 · 点此重试";
    if (STORAGE.equals(code)) return "空间不足 · 点此重试";
    return "这次没能完成 · 点此重试";
  }

  static IOException asIo(String code) {
    return new IOException(message(code));
  }
}
