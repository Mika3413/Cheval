// Substitut minimal d'androidx.test (inaccessible hors maven.google.com) pour Robolectric.
package androidx.test.runner.lifecycle;
public final class ActivityLifecycleMonitorRegistry {
  private static ActivityLifecycleMonitor m;
  public static ActivityLifecycleMonitor getInstance() { return m; }
  public static void registerInstance(ActivityLifecycleMonitor x) { m = x; }
}
