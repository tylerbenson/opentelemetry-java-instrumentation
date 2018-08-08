package datadog.trace.agent.tooling;

import datadog.opentracing.DDTracer;
import datadog.trace.bootstrap.WeakMap;
import io.opentracing.Tracer;
import io.opentracing.util.GlobalTracer;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TracerInstaller {
  /** Register a global tracer if no global tracer is already registered. */
  public static synchronized void installGlobalTracer() {
    registerWeakMapProvider();

    if (!GlobalTracer.isRegistered()) {
      final Tracer resolved = new DDTracer();
      try {
        GlobalTracer.register(resolved);
      } catch (final RuntimeException re) {
        log.warn("Failed to register tracer '" + resolved + "'", re);
      }
    } else {
      log.debug("GlobalTracer already registered.");
    }
  }

  public static void logVersionInfo() {
    VersionLogger.logAllVersions();
    log.debug(GlobalTracer.class.getName() + " loaded on " + GlobalTracer.class.getClassLoader());
    log.debug(
        AgentInstaller.class.getName() + " loaded on " + AgentInstaller.class.getClassLoader());
  }

  private static void registerWeakMapProvider() {
    WeakMap.Provider.registerIfAbsent(new WeakMapSuppliers.WCMManaged());
    //    WeakMap.Provider.registerIfAbsent(new WeakMapSuppliers.WCMInline());
    //    WeakMap.Provider.registerIfAbsent(new WeakMapSuppliers.Guava());
  }
}
