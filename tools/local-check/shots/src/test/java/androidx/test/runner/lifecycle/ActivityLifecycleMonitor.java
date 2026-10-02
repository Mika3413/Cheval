// Substitut minimal d'androidx.test (inaccessible hors maven.google.com) pour Robolectric.
package androidx.test.runner.lifecycle;
public interface ActivityLifecycleMonitor {
  void addLifecycleCallback(ActivityLifecycleCallback c); void removeLifecycleCallback(ActivityLifecycleCallback c);
  Stage getLifecycleStageOf(android.app.Activity a); java.util.Collection<android.app.Activity> getActivitiesInStage(Stage s);
}
