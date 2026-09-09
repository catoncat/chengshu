package onl.nl0.chengshu;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Structural EPUB 2 gate used in JVM tests. Not a substitute for the official EPUBCheck
 * binary; it proves mimetype, container, OPF/NCX well-formedness, spine files and unique ids.
 */
final class EpubInspect {
  static final class Report {
    final List<String> errors = new ArrayList<>();
    boolean ok() { return errors.isEmpty(); }
    @Override public String toString() { return errors.toString(); }
  }

  static Report inspect(byte[] epub) {
    Report report = new Report();
    if (epub == null || epub.length < 30) {
      report.errors.add("文件过小，不是 EPUB");
      return report;
    }
    Map<String, byte[]> files = new LinkedHashMap<>();
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(epub))) {
      ZipEntry first = zip.getNextEntry();
      if (first == null || !"mimetype".equals(first.getName())) {
        report.errors.add("第一项必须是 mimetype");
      } else {
        if (first.getMethod() != ZipEntry.STORED) report.errors.add("mimetype 必须未压缩");
        if (first.getExtra() != null && first.getExtra().length > 0) report.errors.add("mimetype 不能带 extra");
        String mime = new String(zip.readAllBytes(), StandardCharsets.US_ASCII);
        if (!"application/epub+zip".equals(mime)) report.errors.add("mimetype 内容必须是 application/epub+zip");
        files.put(first.getName(), mime.getBytes(StandardCharsets.US_ASCII));
      }
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        if (unsafePath(entry.getName())) {
          report.errors.add("压缩包路径不安全: " + entry.getName());
          continue;
        }
        files.put(entry.getName(), zip.readAllBytes());
      }
    } catch (Exception e) {
      report.errors.add("无法作为 ZIP 读取");
      return report;
    }
    byte[] container = files.get("META-INF/container.xml");
    if (container == null) {
      report.errors.add("缺少 META-INF/container.xml");
      return report;
    }
    Document containerDoc = xml(container, report, "container.xml");
    if (containerDoc == null) return report;
    String opfPath = "";
    NodeList roots = containerDoc.getElementsByTagName("rootfile");
    if (roots.getLength() == 0) report.errors.add("container 没有 rootfile");
    else {
      opfPath = ((Element) roots.item(0)).getAttribute("full-path");
      if (opfPath.isEmpty() || !files.containsKey(opfPath)) report.errors.add("container 指向的 OPF 不存在");
    }
    if (opfPath.isEmpty() || !files.containsKey(opfPath)) return report;
    Document opf = xml(files.get(opfPath), report, "content.opf");
    if (opf == null) return report;
    if (!files.containsKey(dirOf(opfPath) + "toc.ncx") && files.get("OEBPS/toc.ncx") == null)
      report.errors.add("缺少 toc.ncx");
    else {
      byte[] ncx = files.get(dirOf(opfPath) + "toc.ncx");
      if (ncx == null) ncx = files.get("OEBPS/toc.ncx");
      xml(ncx, report, "toc.ncx");
    }
    String base = dirOf(opfPath);
    NodeList items = opf.getElementsByTagName("item");
    Map<String, String> hrefById = new LinkedHashMap<>();
    for (int i = 0; i < items.getLength(); i++) {
      Element item = (Element) items.item(i);
      String id = item.getAttribute("id");
      String href = item.getAttribute("href");
      if (id.isEmpty() || href.isEmpty()) continue;
      hrefById.put(id, href);
      String path = resolve(base, href);
      if (!files.containsKey(path)) report.errors.add("manifest 缺少文件: " + path);
    }
    NodeList refs = opf.getElementsByTagName("itemref");
    if (refs.getLength() == 0) report.errors.add("spine 为空");
    for (int i = 0; i < refs.getLength(); i++) {
      String idref = ((Element) refs.item(i)).getAttribute("idref");
      String href = hrefById.get(idref);
      if (href == null) report.errors.add("spine 引用了不存在的 id: " + idref);
    }
    java.util.Set<String> ids = new java.util.HashSet<>();
    for (Map.Entry<String, byte[]> file : files.entrySet()) {
      if (!file.getKey().endsWith(".xhtml")) continue;
      String html = new String(file.getValue(), StandardCharsets.UTF_8);
      int from = 0;
      while (true) {
        int at = html.indexOf(" id=\"", from);
        if (at < 0) break;
        int end = html.indexOf('"', at + 5);
        if (end < 0) break;
        String id = html.substring(at + 5, end);
        if (!id.isEmpty() && !ids.add(id)) report.errors.add("重复 id: " + id);
        from = end + 1;
      }
    }
    return report;
  }

  static boolean unsafePath(String name) {
    if (name == null || name.isEmpty()) return true;
    String n = name.replace('\\', '/');
    if (n.startsWith("/") || n.startsWith("../") || n.contains("/../")) return true;
    if (n.equals("..") || n.endsWith("/..")) return true;
    return n.contains(":");
  }

  private static Document xml(byte[] bytes, Report report, String label) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    } catch (Exception e) {
      report.errors.add(label + " 不是合法 XML");
      return null;
    }
  }

  private static String dirOf(String path) {
    int slash = path.lastIndexOf('/');
    return slash < 0 ? "" : path.substring(0, slash + 1);
  }

  private static String resolve(String base, String href) {
    if (href.startsWith("/")) return href.substring(1);
    if (!href.contains("..")) return base + href;
    return base + href;
  }
}
