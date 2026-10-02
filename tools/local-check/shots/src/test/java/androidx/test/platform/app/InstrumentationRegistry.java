// Substitut minimal d'androidx.test (inaccessible hors maven.google.com) pour Robolectric.
package androidx.test.platform.app;
public final class InstrumentationRegistry {
  private static android.app.Instrumentation inst; private static android.os.Bundle args = new android.os.Bundle();
  public static void registerInstance(android.app.Instrumentation i, android.os.Bundle a) { inst = i; args = a; }
  public static android.app.Instrumentation getInstrumentation() { return inst; }
  public static android.os.Bundle getArguments() { return args; }
}
