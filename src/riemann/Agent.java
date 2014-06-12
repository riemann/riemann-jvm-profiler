package riemann.jvm_profiler;

import java.lang.instrument.Instrumentation;
import java.util.Scanner;
import clojure.java.api.Clojure;
import clojure.lang.Keyword;
import clojure.lang.IFn;
import clojure.lang.PersistentHashMap;
import clojure.lang.ITransientMap;
import clojure.lang.IPersistentMap;

public class Agent {
  // Clojure var
  public static IFn v(final String ns, final String var) {
    return Clojure.var(ns, var);
  }

  // Clojure.read
  public static Object r(final String x) {
    return Clojure.read(x);
  }

  // Parse arguments
  public static IPersistentMap argMap(final String args) {
    final Scanner sc = new Scanner(args).useDelimiter(",");
    ITransientMap m = PersistentHashMap.EMPTY.asTransient();
    try {
      while (sc.hasNext()) {
        // Parse k=v pairs and assoc! into map
        final String pair = sc.next();
        final int offset  = pair.indexOf("=");
        final String key  = pair.substring(0, offset);
        final String val  = pair.substring(offset + 1);
        if (key.equals("port") ||
            key.equals("dt")) {
          m.assoc(Keyword.intern(key), Integer.parseInt(val));
        } else if (key.equals("load")) {
          m.assoc(Keyword.intern(key), Float.parseFloat(val));
        } else {
          m.assoc(Keyword.intern(key), val);
        }
      }
      return m.persistent();
    } catch (Exception e) {
      System.err.println("Riemann-jvm-profiler agent takes a list of comma-separated k=v pairs for riemann.jvm-profiler/start. For instance, -javaagent:riemann-jvm-profiler.jar=host=my.riemann.host,port=5556,dt=10");
      System.exit(1);
      return null; // unreachable
    } finally {
      sc.close();
    }
  }

  // Start profiler
  public static void premain(final String           args,
                             final Instrumentation  instrumentation) {
    // Require instrumentation package
    v("clojure.core", "require").invoke(r("riemann.jvm-profiler"));

    // Start profiler
    v("riemann.jvm-profiler", "start-global!").invoke(argMap(args));
  }
}
