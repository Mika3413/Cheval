// Substitut minimal d'androidx.test (inaccessible hors maven.google.com) pour Robolectric.
package androidx.test.internal.runner.lifecycle;
import androidx.test.runner.lifecycle.*;
public final class ApplicationLifecycleMonitorImpl implements ApplicationLifecycleMonitor {
  public ApplicationLifecycleMonitorImpl() {}
  public void signalLifecycleChange(android.app.Application app, ApplicationStage s) {}
}
