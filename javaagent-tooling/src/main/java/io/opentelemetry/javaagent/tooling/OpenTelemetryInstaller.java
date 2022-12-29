/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.tooling;

import static io.opentelemetry.javaagent.tooling.AgentInstaller.TRACK_STOP_EVENT_NAME;

import io.opentelemetry.javaagent.bootstrap.OpenTelemetrySdkAccess;
import io.opentelemetry.javaagent.tooling.instrumentation.DurationTrackingSpanProcessor;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdkBuilder;
import io.opentelemetry.sdk.autoconfigure.spi.internal.DefaultConfigProperties;
import io.opentelemetry.sdk.common.CompletableResultCode;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

public final class OpenTelemetryInstaller {

  /**
   * Install the {@link OpenTelemetrySdk} using autoconfigure, and return the {@link
   * AutoConfiguredOpenTelemetrySdk}.
   *
   * @return the {@link AutoConfiguredOpenTelemetrySdk}
   */
  public static AutoConfiguredOpenTelemetrySdk installOpenTelemetrySdk(
      ClassLoader extensionClassLoader) {
    DefaultConfigProperties defaultConfig = DefaultConfigProperties.create(
        Collections.emptyMap());
    Map<String, String> stopSpanEventMapping = defaultConfig.getMap(TRACK_STOP_EVENT_NAME);

    AutoConfiguredOpenTelemetrySdkBuilder sdkBuilder = AutoConfiguredOpenTelemetrySdk.builder()
        .setResultAsGlobal(true)
        .setServiceClassLoader(extensionClassLoader);

    sdkBuilder
        .addTracerProviderCustomizer((tpBuilder, config) -> tpBuilder
            .addSpanProcessor(new DurationTrackingSpanProcessor(stopSpanEventMapping)));
    AutoConfiguredOpenTelemetrySdk autoConfiguredSdk = sdkBuilder.build();
    OpenTelemetrySdk sdk = autoConfiguredSdk.getOpenTelemetrySdk();

    OpenTelemetrySdkAccess.internalSetForceFlush(
        (timeout, unit) -> {
          CompletableResultCode traceResult = sdk.getSdkTracerProvider().forceFlush();
          CompletableResultCode metricsResult = sdk.getSdkMeterProvider().forceFlush();
          CompletableResultCode logsResult = sdk.getSdkLoggerProvider().forceFlush();
          CompletableResultCode.ofAll(Arrays.asList(traceResult, metricsResult, logsResult))
              .join(timeout, unit);
        });

    return autoConfiguredSdk;
  }

  private OpenTelemetryInstaller() {}
}
