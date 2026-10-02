// Substitut minimal d'androidx.test (inaccessible hors maven.google.com) pour Robolectric.
package androidx.test.internal.runner.intent;
public final class IntentMonitorImpl implements androidx.test.runner.intent.IntentMonitor { public IntentMonitorImpl() {} public void signalIntent(android.content.Intent i) {} }
