package onl.nl0.chengshu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class LibraryStemTest {
  @Test
  public void neverUsesBodyAsFilename() {
    assertFalse(Library.fileStem("成书").equalsIgnoreCase("body"));
    assertEquals("book", Library.fileStem("body"));
    assertEquals("book", Library.fileStem("BODY"));
    assertEquals("book", Library.fileStem(""));
    assertEquals("book", Library.fileStem("../"));
  }

  @Test
  public void keepsReadableTitle() {
    assertEquals("成书", Library.fileStem("成书"));
    assertEquals("A long article", Library.fileStem("A long article"));
  }
}
