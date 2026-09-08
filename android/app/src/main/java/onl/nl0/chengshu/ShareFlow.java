package onl.nl0.chengshu;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ShareFlow {
  static final int FLAG_LAUNCHED_FROM_HISTORY = 0x00100000;
  static final int FLAG_NEW_TASK = 0x10000000;
  static final String ACTION_SEND = "android.intent.action.SEND";
  private static final Pattern URL_RE = Pattern.compile("https?://\\S+");

  private ShareFlow() {}

  static boolean shouldConvertShare(String action, int flags) {
    if (!ACTION_SEND.equals(action)) return false;
    return (flags & FLAG_LAUNCHED_FROM_HISTORY) == 0;
  }

  static boolean finishActivityAfterOpen(boolean openedChooser) {
    // Keep this activity alive after both the system chooser and a remembered
    // reader. finish() / finishAndRemoveTask() tears down the share-target task
    // and on some OEMs drops the FileProvider grant before the reader opens.
    return false;
  }

  static boolean removeTaskAfterOpen() {
    return false;
  }

  static String extractUrl(String extraText, String dataString) {
    String text = extraText == null ? "" : extraText;
    Matcher m = URL_RE.matcher(text);
    if (m.find()) {
      String found = m.group();
      while (found.endsWith(")") || found.endsWith("。") || found.endsWith(".")) {
        found = found.substring(0, found.length() - 1);
      }
      return found;
    }
    if (dataString != null && dataString.startsWith("http")) return dataString;
    return null;
  }

  static String extractTitle(String subject, String extraText) {
    if (subject != null && !subject.trim().isEmpty()) return subject.trim();
    if (extraText == null) return "";
    String[] lines = extraText.split("\n");
    if (lines.length > 1 && !lines[0].startsWith("http")) return lines[0].trim();
    return "";
  }

  static String shareFileName(String title, String ext) {
    return Library.fileStem(title) + ext;
  }
}
