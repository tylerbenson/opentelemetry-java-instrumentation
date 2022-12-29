/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.tooling;

import static io.opentelemetry.javaagent.tooling.OpenTelemetryInstaller.installOpenTelemetrySdk;
import static io.opentelemetry.javaagent.tooling.SafeServiceLoader.load;
import static io.opentelemetry.javaagent.tooling.SafeServiceLoader.loadOrdered;
import static io.opentelemetry.javaagent.tooling.Utils.getResourceName;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.SEVERE;
import static net.bytebuddy.matcher.ElementMatchers.any;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextStorage;
import io.opentelemetry.context.Scope;
import io.opentelemetry.instrumentation.api.internal.ConfigPropertiesUtil;
import io.opentelemetry.instrumentation.api.internal.EmbeddedInstrumentationProperties;
import io.opentelemetry.javaagent.bootstrap.AgentClassLoader;
import io.opentelemetry.javaagent.bootstrap.BootstrapPackagePrefixesHolder;
import io.opentelemetry.javaagent.bootstrap.ClassFileTransformerHolder;
import io.opentelemetry.javaagent.bootstrap.DefineClassHelper;
import io.opentelemetry.javaagent.bootstrap.InstrumentedTaskClasses;
import io.opentelemetry.javaagent.bootstrap.internal.InstrumentationConfig;
import io.opentelemetry.javaagent.extension.AgentListener;
import io.opentelemetry.javaagent.extension.ignore.IgnoredTypesConfigurer;
import io.opentelemetry.javaagent.tooling.asyncannotationsupport.WeakRefAsyncOperationEndStrategies;
import io.opentelemetry.javaagent.tooling.bootstrap.BootstrapPackagesBuilderImpl;
import io.opentelemetry.javaagent.tooling.bootstrap.BootstrapPackagesConfigurer;
import io.opentelemetry.javaagent.tooling.bytebuddy.SafeTypeStrategy;
import io.opentelemetry.javaagent.tooling.config.AgentConfig;
import io.opentelemetry.javaagent.tooling.config.ConfigPropertiesBridge;
import io.opentelemetry.javaagent.tooling.ignore.IgnoredClassLoadersMatcher;
import io.opentelemetry.javaagent.tooling.ignore.IgnoredTypesBuilderImpl;
import io.opentelemetry.javaagent.tooling.ignore.IgnoredTypesMatcher;
import io.opentelemetry.javaagent.tooling.instrumentation.SpanDurationTrackingContextStorage;
import io.opentelemetry.javaagent.tooling.muzzle.AgentTooling;
import io.opentelemetry.javaagent.tooling.util.Trie;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import javax.annotation.Nullable;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.description.type.TypeDefinition;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.scaffold.MethodGraph;
import net.bytebuddy.utility.JavaModule;

public class AgentInstaller {

  private static final Logger logger = Logger.getLogger(AgentInstaller.class.getName());

  static final String JAVAAGENT_ENABLED_CONFIG = "otel.javaagent.enabled";

  // This property may be set to force synchronous AgentListener#afterAgent() execution: the
  // condition for delaying the AgentListener initialization is pretty broad and in case it covers
  // too much javaagent users can file a bug, force sync execution by setting this property to true
  // and continue using the javaagent
  private static final String FORCE_SYNCHRONOUS_AGENT_LISTENERS_CONFIG =
      "otel.javaagent.experimental.force-synchronous-agent-listeners";

  private static final String STRICT_CONTEXT_STRESSOR_MILLIS =
      "otel.javaagent.testing.strict-context-stressor-millis";

  private static final String TRACK_START_EVENT_NAME =
      "otel.javaagent.track.start.event.name";

  static final String TRACK_STOP_EVENT_NAME =
      "otel.javaagent.track.stop.event.name";

  private static final Map<String, List<Runnable>> CLASS_LOAD_CALLBACKS = new HashMap<>();

  public static void installBytebuddyAgent(Instrumentation inst, ClassLoader extensionClassLoader) {
    addByteBuddyRawSetting();

    Integer strictContextStressorMillis = Integer.getInteger(STRICT_CONTEXT_STRESSOR_MILLIS);
    if (strictContextStressorMillis != null) {
      io.opentelemetry.context.ContextStorage.addWrapper(
          storage -> new StrictContextStressor(storage, strictContextStressorMillis));
    }

    logVersionInfo();
    if (ConfigPropertiesUtil.getBoolean(JAVAAGENT_ENABLED_CONFIG, true)) {
      setupUnsafe(inst);
      List<AgentListener> agentListeners = loadOrdered(AgentListener.class, extensionClassLoader);
      installBytebuddyAgent(inst, extensionClassLoader, agentListeners);
    } else {
      logger.fine("Tracing is disabled, not installing instrumentations.");
    }
  }

  private static void installBytebuddyAgent(
      Instrumentation inst,
      ClassLoader extensionClassLoader,
      Iterable<AgentListener> agentListeners) {

    WeakRefAsyncOperationEndStrategies.initialize();

    EmbeddedInstrumentationProperties.setPropertiesLoader(extensionClassLoader);

    setDefineClassHandler();

    // If noop OpenTelemetry is enabled, autoConfiguredSdk will be null and AgentListeners are not
    // called
    AutoConfiguredOpenTelemetrySdk autoConfiguredSdk =
        installOpenTelemetrySdk(extensionClassLoader);

    ConfigProperties sdkConfig = autoConfiguredSdk.getConfig();
    InstrumentationConfig.internalInitializeConfig(new ConfigPropertiesBridge(sdkConfig));
    copyNecessaryConfigToSystemProperties(sdkConfig);

    Map<String, String> startSpanEventMapping = sdkConfig.getMap(TRACK_START_EVENT_NAME);
    if (!startSpanEventMapping.isEmpty()) {
      io.opentelemetry.context.ContextStorage.addWrapper(
          storage -> new SpanDurationTrackingContextStorage(storage, startSpanEventMapping));
    }

    setBootstrapPackages(sdkConfig, extensionClassLoader);

    for (BeforeAgentListener agentListener :
        loadOrdered(BeforeAgentListener.class, extensionClassLoader)) {
      agentListener.beforeAgent(autoConfiguredSdk);
    }

    AgentBuilder agentBuilder =
        new AgentBuilder.Default(
                // default method graph compiler inspects the class hierarchy, we don't need it, so
                // we use a simpler and faster strategy instead
                new ByteBuddy().with(MethodGraph.Compiler.ForDeclaredMethods.INSTANCE))
            .disableClassFormatChanges()
            // disableClassFormatChanges sets type strategy to TypeStrategy.Default.REDEFINE_FROZEN
            // we'll wrap it with our own strategy
            .with(new SafeTypeStrategy(AgentBuilder.TypeStrategy.Default.REDEFINE_FROZEN))
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .with(new RedefinitionDiscoveryStrategy())
            .with(AgentBuilder.DescriptionStrategy.Default.POOL_ONLY)
            .with(AgentTooling.poolStrategy())
            .with(new ClassLoadListener())
            .with(AgentTooling.locationStrategy());
    if (JavaModule.isSupported()) {
      agentBuilder = agentBuilder.with(new ExposeAgentBootstrapListener(inst));
    }

    agentBuilder = configureIgnoredTypes(sdkConfig, extensionClassLoader, agentBuilder);

    if (AgentConfig.isDebugModeEnabled(sdkConfig)) {
      agentBuilder =
          agentBuilder
              .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
              .with(new RedefinitionDiscoveryStrategy())
              .with(new RedefinitionLoggingListener())
              .with(new TransformLoggingListener());
    }

    int numberOfLoadedExtensions = 0;
    for (AgentExtension agentExtension : loadOrdered(AgentExtension.class, extensionClassLoader)) {
      if (logger.isLoggable(FINE)) {
        logger.log(
            FINE,
            "Loading extension {0} [class {1}]",
            new Object[] {agentExtension.extensionName(), agentExtension.getClass().getName()});
      }
      try {
        agentBuilder = agentExtension.extend(agentBuilder, sdkConfig);
        numberOfLoadedExtensions++;
      } catch (Exception | LinkageError e) {
        logger.log(
            SEVERE,
            "Unable to load extension "
                + agentExtension.extensionName()
                + " [class "
                + agentExtension.getClass().getName()
                + "]",
            e);
      }
    }
    logger.log(FINE, "Installed {0} extension(s)", numberOfLoadedExtensions);

    ResettableClassFileTransformer resettableClassFileTransformer = agentBuilder.installOn(inst);
    ClassFileTransformerHolder.setClassFileTransformer(resettableClassFileTransformer);

    runAfterAgentListeners(agentListeners, autoConfiguredSdk);
  }

  private static void copyNecessaryConfigToSystemProperties(ConfigProperties config) {
    String value = config.getString("otel.instrumentation.experimental.span-suppression-strategy");
    if (value != null) {
      System.setProperty("otel.instrumentation.experimental.span-suppression-strategy", value);
    }
  }

  private static void setupUnsafe(Instrumentation inst) {
    try {
      UnsafeInitializer.initialize(inst, AgentInstaller.class.getClassLoader());
    } catch (UnsupportedClassVersionError exception) {
      // ignore
    }
  }

  private static void setBootstrapPackages(
      ConfigProperties config, ClassLoader extensionClassLoader) {
    BootstrapPackagesBuilderImpl builder = new BootstrapPackagesBuilderImpl();
    for (BootstrapPackagesConfigurer configurer :
        load(BootstrapPackagesConfigurer.class, extensionClassLoader)) {
      configurer.configure(builder, config);
    }
    BootstrapPackagePrefixesHolder.setBoostrapPackagePrefixes(builder.build());
  }

  private static void setDefineClassHandler() {
    DefineClassHelper.internalSetHandler(DefineClassHandler.INSTANCE);
  }

  private static AgentBuilder configureIgnoredTypes(
      ConfigProperties config, ClassLoader extensionClassLoader, AgentBuilder agentBuilder) {
    IgnoredTypesBuilderImpl builder = new IgnoredTypesBuilderImpl();
    for (IgnoredTypesConfigurer configurer :
        loadOrdered(IgnoredTypesConfigurer.class, extensionClassLoader)) {
      configurer.configure(builder, config);
    }

    Trie<Boolean> ignoredTasksTrie = builder.buildIgnoredTasksTrie();
    InstrumentedTaskClasses.setIgnoredTaskClassesPredicate(ignoredTasksTrie::contains);

    return agentBuilder
        .ignore(any(), new IgnoredClassLoadersMatcher(builder.buildIgnoredClassLoadersTrie()))
        .or(new IgnoredTypesMatcher(builder.buildIgnoredTypesTrie()))
        .or(
            (typeDescription, classLoader, module, classBeingRedefined, protectionDomain) -> {
              return HelperInjector.isInjectedClass(classLoader, typeDescription.getName());
            });
  }

  private static void runAfterAgentListeners(
      Iterable<AgentListener> agentListeners, AutoConfiguredOpenTelemetrySdk autoConfiguredSdk) {
    // java.util.logging.LogManager maintains a final static LogManager, which is created during
    // class initialization. Some AgentListener implementations may use JRE bootstrap classes
    // which touch this class (e.g. JFR classes or some MBeans).
    // It is worth noting that starting from Java 9 (JEP 264) Java platform classes no longer use
    // JUL directly, but instead they use a new System.Logger interface, so the LogManager issue
    // applies mainly to Java 8.
    // This means applications which require a custom LogManager may not have a chance to set the
    // global LogManager if one of those AgentListeners runs first: it will incorrectly
    // set the global LogManager to the default JVM one in cases where the instrumented application
    // sets the LogManager system property or when the custom LogManager class is not on the system
    // classpath.
    // Our solution is to delay the initialization of AgentListeners when we detect a custom
    // log manager being used.
    // Once we see the LogManager class loading, it's safe to run AgentListener#afterAgent() because
    // the application is already setting the global LogManager and AgentListener won't be able
    // to touch it due to class loader locking.
    boolean shouldForceSynchronousAgentListenersCalls =
        autoConfiguredSdk.getConfig().getBoolean(FORCE_SYNCHRONOUS_AGENT_LISTENERS_CONFIG, false);
    boolean javaBefore9 = isJavaBefore9();
    if (!shouldForceSynchronousAgentListenersCalls && javaBefore9 && isAppUsingCustomLogManager()) {
      logger.fine("Custom JUL LogManager detected: delaying AgentListener#afterAgent() calls");
      registerClassLoadCallback(
          "java.util.logging.LogManager",
          new DelayedAfterAgentCallback(agentListeners, autoConfiguredSdk));
    } else {
      if (javaBefore9) {
        // force LogManager to be initialized while we are single-threaded, because if we wait,
        // LogManager initialization can cause a deadlock in Java 8 if done by two different threads
        LogManager.getLogManager();
      }
      for (AgentListener agentListener : agentListeners) {
        agentListener.afterAgent(autoConfiguredSdk);
      }
    }
  }

  private static boolean isJavaBefore9() {
    return System.getProperty("java.version").startsWith("1.");
  }

  private static void addByteBuddyRawSetting() {
    String savedPropertyValue = System.getProperty(TypeDefinition.RAW_TYPES_PROPERTY);
    try {
      System.setProperty(TypeDefinition.RAW_TYPES_PROPERTY, "true");
      boolean rawTypes = TypeDescription.AbstractBase.RAW_TYPES;
      if (!rawTypes) {
        logger.log(FINE, "Too late to enable {0}", TypeDefinition.RAW_TYPES_PROPERTY);
      }
    } finally {
      if (savedPropertyValue == null) {
        System.clearProperty(TypeDefinition.RAW_TYPES_PROPERTY);
      } else {
        System.setProperty(TypeDefinition.RAW_TYPES_PROPERTY, savedPropertyValue);
      }
    }
  }

  static class RedefinitionLoggingListener implements AgentBuilder.RedefinitionStrategy.Listener {

    private static final Logger logger =
        Logger.getLogger(RedefinitionLoggingListener.class.getName());

    @Override
    public void onBatch(int index, List<Class<?>> batch, List<Class<?>> types) {}

    @Override
    public Iterable<? extends List<Class<?>>> onError(
        int index, List<Class<?>> batch, Throwable throwable, List<Class<?>> types) {
      if (logger.isLoggable(FINE)) {
        logger.log(
            FINE,
            "Exception while retransforming " + batch.size() + " classes: " + batch,
            throwable);
      }
      return Collections.emptyList();
    }

    @Override
    public void onComplete(
        int amount, List<Class<?>> types, Map<List<Class<?>>, Throwable> failures) {}
  }

  static class TransformLoggingListener extends AgentBuilder.Listener.Adapter {

    private static final TransformSafeLogger logger =
        TransformSafeLogger.getLogger(TransformLoggingListener.class);

    @Override
    public void onError(
        String typeName,
        ClassLoader classLoader,
        JavaModule module,
        boolean loaded,
        Throwable throwable) {

      if (logger.isLoggable(FINE)) {
        logger.log(
            FINE,
            "Failed to handle {0} for transformation on class loader {1}",
            new Object[] {typeName, classLoader},
            throwable);
      }
    }

    @Override
    public void onTransformation(
        TypeDescription typeDescription,
        ClassLoader classLoader,
        JavaModule module,
        boolean loaded,
        DynamicType dynamicType) {
      if (logger.isLoggable(FINE)) {
        logger.log(
            FINE, "Transformed {0} -- {1}", new Object[] {typeDescription.getName(), classLoader});
      }
    }
  }

  /**
   * Register a callback to run when a class is loading.
   *
   * <p>Caveats:
   *
   * <ul>
   *   <li>This callback will be invoked by a jvm class transformer.
   *   <li>Classes filtered out by {@link AgentInstaller}'s skip list will not be matched.
   * </ul>
   *
   * @param className name of the class to match against
   * @param callback runnable to invoke when class name matches
   */
  public static void registerClassLoadCallback(String className, Runnable callback) {
    synchronized (CLASS_LOAD_CALLBACKS) {
      List<Runnable> callbacks =
          CLASS_LOAD_CALLBACKS.computeIfAbsent(className, k -> new ArrayList<>());
      callbacks.add(callback);
    }
  }

  private static class DelayedAfterAgentCallback implements Runnable {

    private final Iterable<AgentListener> agentListeners;
    private final AutoConfiguredOpenTelemetrySdk autoConfiguredSdk;

    private DelayedAfterAgentCallback(
        Iterable<AgentListener> agentListeners, AutoConfiguredOpenTelemetrySdk autoConfiguredSdk) {
      this.agentListeners = agentListeners;
      this.autoConfiguredSdk = autoConfiguredSdk;
    }

    @Override
    public void run() {
      /*
       * This callback is called from within bytecode transformer. This can be a problem if callback tries
       * to load classes being transformed. To avoid this we start a thread here that calls the callback.
       * This seems to resolve this problem.
       */
      Thread thread = new Thread(this::runAgentListeners);
      thread.setName("delayed-agent-listeners");
      thread.setDaemon(true);
      thread.start();
    }

    private void runAgentListeners() {
      for (AgentListener agentListener : agentListeners) {
        try {
          agentListener.afterAgent(autoConfiguredSdk);
        } catch (RuntimeException e) {
          logger.log(SEVERE, "Failed to execute " + agentListener.getClass().getName(), e);
        }
      }
    }
  }

  private static class ClassLoadListener extends AgentBuilder.Listener.Adapter {
    @Override
    public void onComplete(
        String typeName, ClassLoader classLoader, JavaModule javaModule, boolean b) {
      synchronized (CLASS_LOAD_CALLBACKS) {
        List<Runnable> callbacks = CLASS_LOAD_CALLBACKS.get(typeName);
        if (callbacks != null) {
          for (Runnable callback : callbacks) {
            callback.run();
          }
        }
      }
    }
  }

  private static class RedefinitionDiscoveryStrategy
      implements AgentBuilder.RedefinitionStrategy.DiscoveryStrategy {
    private static final AgentBuilder.RedefinitionStrategy.DiscoveryStrategy delegate =
        AgentBuilder.RedefinitionStrategy.DiscoveryStrategy.Reiterating.INSTANCE;

    @Override
    public Iterable<Iterable<Class<?>>> resolve(Instrumentation instrumentation) {
      // filter out our agent classes and injected helper classes
      return () ->
          streamOf(delegate.resolve(instrumentation))
              .map(RedefinitionDiscoveryStrategy::filterClasses)
              .iterator();
    }

    private static Iterable<Class<?>> filterClasses(Iterable<Class<?>> classes) {
      return () -> streamOf(classes).filter(c -> !isIgnored(c)).iterator();
    }

    private static <T> Stream<T> streamOf(Iterable<T> iterable) {
      return StreamSupport.stream(iterable.spliterator(), false);
    }

    private static boolean isIgnored(Class<?> c) {
      ClassLoader cl = c.getClassLoader();
      if (cl instanceof AgentClassLoader || cl instanceof ExtensionClassLoader) {
        return true;
      }
      // ignore generate byte buddy helper class
      if (c.getName().startsWith("java.lang.ClassLoader$ByteBuddyAccessor$")) {
        return true;
      }

      return HelperInjector.isInjectedClass(c);
    }
  }

  /** Detect if the instrumented application is using a custom JUL LogManager. */
  private static boolean isAppUsingCustomLogManager() {
    String jbossHome = System.getenv("JBOSS_HOME");
    if (jbossHome != null) {
      logger.log(FINE, "Found JBoss: {0}; assuming app is using custom LogManager", jbossHome);
      // JBoss/Wildfly is known to set a custom log manager after startup.
      // Originally we were checking for the presence of a jboss class,
      // but it seems some non-jboss applications have jboss classes on the classpath.
      // This would cause AgentListener#afterAgent() calls to be delayed indefinitely.
      // Checking for an environment variable required by jboss instead.
      return true;
    }

    String customLogManager = System.getProperty("java.util.logging.manager");
    if (customLogManager != null) {
      logger.log(
          FINE,
          "Detected custom LogManager configuration: java.util.logging.manager={0}",
          customLogManager);
      boolean onSysClasspath =
          ClassLoader.getSystemResource(getResourceName(customLogManager)) != null;
      if (logger.isLoggable(FINE)) {
        logger.log(
            FINE,
            "Class {0} is on system classpath: {1}delaying AgentInstaller#afterAgent()",
            new Object[] {customLogManager, onSysClasspath ? "not " : ""});
      }
      // Some applications set java.util.logging.manager but never actually initialize the logger.
      // Check to see if the configured manager is on the system classpath.
      // If so, it should be safe to initialize AgentInstaller which will setup the log manager:
      // LogManager tries to load the implementation first using system CL, then falls back to
      // current context CL
      return !onSysClasspath;
    }

    return false;
  }

  private static void logVersionInfo() {
    VersionLogger.logAllVersions();
    if (logger.isLoggable(FINE)) {
      logger.log(
          FINE,
          "{0} loaded on {1}",
          new Object[] {AgentInstaller.class.getName(), AgentInstaller.class.getClassLoader()});
    }
  }

  private AgentInstaller() {}

  private static class StrictContextStressor implements ContextStorage, AutoCloseable {

    private final ContextStorage contextStorage;
    private final int sleepMillis;

    private StrictContextStressor(ContextStorage contextStorage, int sleepMillis) {
      this.contextStorage = contextStorage;
      this.sleepMillis = sleepMillis;
    }

    @Override
    public Scope attach(Context toAttach) {
      return wrap(contextStorage.attach(toAttach));
    }

    @Nullable
    @Override
    public Context current() {
      return contextStorage.current();
    }

    @Override
    public void close() throws Exception {
      if (contextStorage instanceof AutoCloseable) {
        ((AutoCloseable) contextStorage).close();
      }
    }

    private Scope wrap(Scope scope) {
      return () -> {
        try {
          Thread.sleep(sleepMillis);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        scope.close();
      };
    }
  }
}
