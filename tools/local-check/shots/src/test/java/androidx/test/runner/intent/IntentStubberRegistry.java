// Substitut minimal d'androidx.test (inaccessible hors maven.google.com) pour Robolectric.
package androidx.test.runner.intent;
public final class IntentStubberRegistry { public static IntentStubber getInstance() { return null; } public static boolean isLoaded() { return false; } }
