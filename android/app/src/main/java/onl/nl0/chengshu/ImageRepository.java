package onl.nl0.chengshu;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded image cache. Cancel sets a flag that load() honors; it cannot abort a socket already returned. */
final class ImageRepository {
  private final BlobStore blobs;
  private final LocalEpub.ImageLoader loader;
  private final AtomicBoolean cancelled = new AtomicBoolean(false);
  private final ConcurrentHashMap<String, String> remembered = new ConcurrentHashMap<>();
  private final AtomicInteger fetches = new AtomicInteger();

  ImageRepository(BlobStore blobs) { this(blobs, EpubImages::load); }

  ImageRepository(BlobStore blobs, LocalEpub.ImageLoader loader) {
    this.blobs = blobs;
    this.loader = loader == null ? EpubImages::load : loader;
  }

  void cancel() { cancelled.set(true); }

  void reset() { cancelled.set(false); }

  int fetches() { return fetches.get(); }

  LocalEpub.Image load(String url) throws Exception {
    if (cancelled.get() || Thread.currentThread().isInterrupted())
      throw new java.io.InterruptedIOException();
    String rememberedHash = remembered.get(url);
    if (rememberedHash != null) {
      File file = blobs.file(rememberedHash);
      if (file != null) return decode(Files.readAllBytes(file.toPath()));
    }
    LocalEpub.Image image = loader.load(url);
    fetches.incrementAndGet();
    if (image == null || image.bytes == null || image.bytes.length == 0) return image;
    File stored = blobs.put(image.bytes);
    remembered.put(url, stored.getName());
    return image;
  }

  private static LocalEpub.Image decode(byte[] bytes) throws Exception {
    if (bytes.length >= 8 && bytes[0] == (byte) 0x89) return new LocalEpub.Image(bytes, "image/png");
    if (bytes.length >= 3 && bytes[0] == (byte) 0xff) return new LocalEpub.Image(bytes, "image/jpeg");
    if (bytes.length >= 6 && bytes[0] == 'G') return new LocalEpub.Image(bytes, "image/gif");
    return new LocalEpub.Image(bytes, "image/png");
  }
}
