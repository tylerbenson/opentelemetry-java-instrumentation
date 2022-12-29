/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.tooling.instrumentation;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import java.util.Map;

public final class DurationTrackingSpanProcessor implements SpanProcessor {
  private final Map<String, String> stopSpanNameEventName;

  public DurationTrackingSpanProcessor(Map<String, String> stopSpanNameEventName) {
    this.stopSpanNameEventName = stopSpanNameEventName;
  }

  @Override
  public void onStart(Context parentContext, ReadWriteSpan span) {
    String stopEventName = stopSpanNameEventName.get(span.getName());
    if (stopEventName != null) {
      String value = Baggage.fromContext(parentContext).getEntryValue(stopEventName);
      if (value != null) {
        try {
          long startEpochNanos = Long.parseLong(value);
          // Using span's start time as event end time.
          long endEpochNanos = span.toSpanData().getStartEpochNanos();
          long duration = endEpochNanos - startEpochNanos;
          // TODO cache concat
          span.setAttribute(stopEventName + " duration nanos", duration);
        } catch (NumberFormatException ex) {
          // ignored.
        }
      }
    }
  }

  @Override
  public boolean isStartRequired() {
    return true;
  }

  @Override
  public void onEnd(ReadableSpan span) {
  }

  @Override
  public boolean isEndRequired() {
    return false;
  }
}
