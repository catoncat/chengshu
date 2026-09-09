package onl.nl0.chengshu;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.*;
import java.net.*;
import java.util.Base64;

/** Loads only image bytes; never submits article text to any service. */
final class EpubImages {
  private static final java.util.concurrent.Semaphore DECODE = new java.util.concurrent.Semaphore(1);
  static LocalEpub.Image load(String url) throws Exception {
    byte[] bytes;
    if (url.startsWith("data:")) {
      int comma = url.indexOf(',');
      if (comma < 0 || !url.substring(0, comma).endsWith(";base64")
          || url.length() > LocalEpub.MAX_IMAGE_BYTES * 4L / 3 + 128)
        throw new IOException("图片数据无效");
      bytes = Base64.getDecoder().decode(url.substring(comma + 1));
    } else {
      URL target = new URL(url);
      if (!target.getProtocol().equals("https") && !target.getProtocol().equals("http"))
        throw new IOException("不支持的图片地址");
      HttpURLConnection connection = (HttpURLConnection) target.openConnection();
      connection.setConnectTimeout(8000); connection.setReadTimeout(8000);
      connection.setRequestProperty("User-Agent", "Chengshu/" + BuildConfig.VERSION_NAME);
      connection.setRequestProperty("Accept", "image/png,image/jpeg,image/gif,image/webp;q=0.8,*/*;q=0.1");
      try {
        if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300)
          throw new IOException("无法获取图片");
        if (connection.getContentLengthLong() > LocalEpub.MAX_IMAGE_BYTES) throw new IOException("图片过大");
        try (InputStream in = connection.getInputStream()) { bytes = readBounded(in); }
      } finally { connection.disconnect(); }
    }
    if (bytes.length == 0 || bytes.length > LocalEpub.MAX_IMAGE_BYTES) throw new IOException("图片过大或为空");
    String mime = sniff(bytes);
    if (mime != null) return new LocalEpub.Image(bytes, mime);
    // EPUB 2 readers do not universally support WebP. Decode locally, with a pixel budget.
    BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
    BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0
        || (long) bounds.outWidth * bounds.outHeight > 4_000_000)
      throw new IOException("阅读器不支持此图片格式或尺寸");
    DECODE.acquire();
    try {
    Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
    if (bitmap == null) throw new IOException("无法读取图片");
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("图片转换失败");
      if (out.size() > LocalEpub.MAX_IMAGE_BYTES) throw new IOException("转换后的图片过大");
      return new LocalEpub.Image(out.toByteArray(), "image/png");
    } finally { bitmap.recycle(); }
    } finally { DECODE.release(); }
  }
  private static byte[] readBounded(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[16384]; int n;
    while ((n = in.read(buffer)) != -1) {
      if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
      if (out.size() + n > LocalEpub.MAX_IMAGE_BYTES) throw new IOException("图片过大");
      out.write(buffer, 0, n);
    }
    return out.toByteArray();
  }
  private static String sniff(byte[] b) {
    if (b.length >= 8 && b[0] == (byte) 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
        && b[4] == 13 && b[5] == 10 && b[6] == 26 && b[7] == 10) return "image/png";
    if (b.length >= 3 && b[0] == (byte) 0xff && b[1] == (byte) 0xd8 && b[2] == (byte) 0xff) return "image/jpeg";
    if (b.length >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8'
        && (b[4] == '7' || b[4] == '9') && b[5] == 'a') return "image/gif";
    return null;
  }
}
