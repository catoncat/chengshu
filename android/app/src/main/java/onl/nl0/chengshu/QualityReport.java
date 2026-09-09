package onl.nl0.chengshu;

import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

final class QualityReport {
  static final class Issue {
    final String code, severity, message;
    Issue(String code, String severity, String message) {
      this.code = code; this.severity = severity; this.message = message;
    }
  }

  final List<Issue> issues = new ArrayList<>();

  void add(String code, String severity, String message) {
    issues.add(new Issue(code, severity, message));
  }

  boolean empty() { return issues.isEmpty(); }

  boolean blocking() {
    for (Issue issue : issues) if ("error".equals(issue.severity)) return true;
    return false;
  }

  String summary() {
    if (issues.isEmpty()) return "";
    StringBuilder out = new StringBuilder();
    for (Issue issue : issues) {
      if (out.length() > 0) out.append(" ");
      out.append(issue.message);
    }
    return out.toString();
  }

  String fingerprint() {
    StringBuilder out = new StringBuilder();
    for (Issue issue : issues) out.append(issue.code).append(':').append(issue.message).append('|');
    return out.toString();
  }

  JSONObject json() {
    JSONObject o = new JSONObject();
    JSONArray arr = new JSONArray();
    try {
      for (Issue issue : issues) {
        JSONObject one = new JSONObject();
        one.put("code", issue.code);
        one.put("severity", issue.severity);
        one.put("message", issue.message);
        arr.put(one);
      }
      o.put("issues", arr);
    } catch (Exception ignored) { /* quality json is advisory */ }
    return o;
  }

  static QualityReport parse(String raw) {
    QualityReport report = new QualityReport();
    if (raw == null || raw.isEmpty()) return report;
    try {
      JSONArray arr = new JSONObject(raw).optJSONArray("issues");
      if (arr == null) return report;
      for (int i = 0; i < arr.length(); i++) {
        JSONObject o = arr.getJSONObject(i);
        report.add(o.optString("code"), o.optString("severity"), o.optString("message"));
      }
    } catch (Exception ignored) { /* treat unreadable reports as empty rather than invent issues */ }
    return report;
  }

  static QualityReport evaluate(String html, int embedded, int missing) {
    QualityReport report = new QualityReport();
    String text = html == null ? "" : html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    String lower = text.toLowerCase(Locale.ROOT);
    if (text.isEmpty()) report.add("CONTENT_EMPTY", "error", "没有提取到可阅读的正文");
    if (lower.contains("verify you are human") || lower.contains("请登录") || lower.contains("sign in to continue")
        || lower.contains("enable javascript") || lower.contains("captcha"))
      report.add("AUTH_REQUIRED", "error", "这一页需要登录或验证，没有当成正文保存");
    if (text.length() > 0 && text.length() < 80 && missing == 0)
      report.add("CONTENT_SUSPECT", "warning", "正文很短，可能不是完整文章");
    if (missing > 0) report.add("IMAGE_PARTIAL", "warning", missing + " 张图片未能保存（网络、格式或资源限制），正文已保留。");
    if (embedded + missing > 0 && missing == embedded + missing && missing > 3)
      report.add("IMAGE_PARTIAL", "warning", "图片全部未能保存");
    return report;
  }
}
