package io.opentelemetry.javaagent.tooling.instrumentation;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextStorage;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.ReadableSpan;
import java.util.Map;
import javax.annotation.Nullable;

public class SpanDurationTrackingContextStorage implements ContextStorage {
  private final ContextStorage delegate;
  private final Map<String, String> startSpanNameEventName;

  public SpanDurationTrackingContextStorage(ContextStorage delegate,
      Map<String, String> startSpanNameEventName) {
    this.delegate = delegate;
    this.startSpanNameEventName = startSpanNameEventName;
  }

  @Override
  public Scope attach(Context toAttach) {
    Span span = Span.fromContext(toAttach);
    if (span instanceof ReadableSpan) {
      String spanName = ((ReadableSpan) span).getName();
      String eventName = startSpanNameEventName.get(spanName);
      if (eventName != null) {
        Baggage baggage = Baggage.fromContext(toAttach);
        String entryValue = baggage.getEntryValue(eventName);
        if (entryValue == null) {
          long startEpochNanos = ((ReadableSpan) span).toSpanData().getStartEpochNanos();
          Baggage updatedBaggage = baggage.toBuilder()
              .put(eventName, String.valueOf(startEpochNanos))
              .build();
          toAttach = toAttach.with(updatedBaggage);
        }
      }
    }
    return delegate.attach(toAttach);
  }

  @Nullable
  @Override
  public Context current() {
    return delegate.current();
  }
}
