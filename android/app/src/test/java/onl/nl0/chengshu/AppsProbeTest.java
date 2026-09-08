package onl.nl0.chengshu;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AppsProbeTest {
  @Test
  public void koreaderIsPinnedSoItSurfacesWhenTheSystemSeesIt() {
    boolean found = false;
    for (String pkg : Apps.PINNED) {
      if (pkg.startsWith("org.koreader.launcher")) found = true;
    }
    assertTrue(found);
  }

  @Test
  public void noisyCatchAllsAreNotOfferedAsReaders() {
    String joined = String.join(",", Apps.NOISY);
    assertTrue(joined.contains("com.tencent.mobileqq"));
    assertTrue(joined.contains("com.termux"));
    assertFalse(joined.contains("org.koreader"));
    assertFalse(joined.contains("com.tencent.weread"));
  }
}
