// Substitut minimal d'androidx.test (inaccessible hors maven.google.com) pour Robolectric.
package androidx.test.runner.intent; public interface IntentStubber { android.app.Instrumentation.ActivityResult getActivityResultForIntent(android.content.Intent i); }
