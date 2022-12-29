/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.smoketest;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

public class SpanEventConfigChecker {

  public static void main(String[] args) throws InterruptedException {
    Tracer tracer = GlobalOpenTelemetry.getTracer("span-event");
    Baggage baggage;
    Span empty = Span.current();
    assert empty == null;
    Span parent = tracer.spanBuilder("parent").startSpan();
    try (Scope parentScope = parent.makeCurrent()) {
      Thread.sleep(1);
      Span span1 = tracer.spanBuilder("child-1").startSpan();
      try (Scope scope = span1.makeCurrent()) {
        baggage = Baggage.current();
        Thread.sleep(1);
      } finally {
        span1.end();
      }
      String entryValue = baggage.getEntryValue("between-children");
      assert entryValue != null;
      System.out.println(entryValue);
      Thread.sleep(1);
      try (Scope scope = baggage.makeCurrent()) {
        Span span = tracer.spanBuilder("child-2").startSpan();
        Thread.sleep(1);
        span.end();
      }
      Thread.sleep(1);
    } finally {
      parent.end();
    }
  }

  private SpanEventConfigChecker() {}
}
