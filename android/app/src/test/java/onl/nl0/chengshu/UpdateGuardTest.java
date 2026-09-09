package onl.nl0.chengshu;

import static org.junit.Assert.*;
import java.io.IOException;
import org.junit.Test;

public class UpdateGuardTest {
  @Test public void rejectsWrongHostWrongChannelAndNonNewerCode() throws Exception {
    Update.Info ok = Update.parse("{\"versionCode\":14,\"versionName\":\"1.13\",\"apk\":\"https://0nl.onl/chengshu.apk\",\"sha256\":\"abc\",\"size\":10,\"channel\":\"stable\"}");
    Update.assertSafe(ok, 13, "stable");
    try {
      Update.assertSafe(Update.parse("{\"versionCode\":14,\"apk\":\"https://evil.example/x.apk\",\"channel\":\"stable\"}"), 13, "stable");
      fail();
    } catch (IOException expected) { }
    try {
      Update.assertSafe(Update.parse("{\"versionCode\":14,\"apk\":\"https://0nl.onl/chengshu.apk\",\"channel\":\"preview\"}"), 13, "stable");
      fail();
    } catch (IOException expected) { }
    try {
      Update.assertSafe(ok, 14, "stable");
      fail();
    } catch (IOException expected) { }
  }

  @Test public void oldManifestWithoutHashStillParses() throws Exception {
    Update.Info info = Update.parse("{\"versionCode\":12,\"versionName\":\"1.11\",\"apk\":\"https://0nl.onl/chengshu.apk\"}");
    assertEquals(12, info.versionCode);
    assertEquals("", info.sha256);
  }
}
