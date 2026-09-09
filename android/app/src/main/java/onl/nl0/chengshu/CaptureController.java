package onl.nl0.chengshu;

import android.app.Activity;

/**
 * Bounded capture experiment (B04). A WebView still needs a visible Activity on current Android.
 * Background capture is explicitly NEEDS_USER; this class does not keep a hidden WebView alive.
 */
final class CaptureController {
  static final class Decision {
    final boolean canCaptureInBackground;
    final String fallback;
    Decision(boolean canCaptureInBackground, String fallback) {
      this.canCaptureInBackground = canCaptureInBackground;
      this.fallback = fallback;
    }
  }

  static Decision experimentResult() {
    return new Decision(false, JobRepository.NEEDS_USER);
  }

  static void extract(Activity activity, String url, PageExtractor.Done listener) {
    if (activity == null) {
      listener.onDone(null);
      return;
    }
    PageExtractor.extract(activity, url, listener);
  }
}
