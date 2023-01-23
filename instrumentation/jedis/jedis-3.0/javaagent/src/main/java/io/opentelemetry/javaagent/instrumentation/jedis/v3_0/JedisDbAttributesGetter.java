/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.jedis.v3_0;

import io.opentelemetry.instrumentation.api.instrumenter.db.DbClientAttributesGetter;
import io.opentelemetry.semconv.trace.attributes.SemanticAttributes;
import javax.annotation.Nullable;

final class JedisDbAttributesGetter implements DbClientAttributesGetter<JedisRequest> {

  @Override
  public String getSystem(JedisRequest request) {
    return SemanticAttributes.DbSystemValues.REDIS;
  }

  @Override
  @Nullable
  public String getUser(JedisRequest request) {
    return null;
  }

  @Override
  public String getName(JedisRequest request) {
    return null;
  }

  @Override
  public String getConnectionString(JedisRequest request) {
    return null;
  }

  @Override
  public String getStatement(JedisRequest request) {
    return request.getStatement();
  }

  @Override
  public String getOperation(JedisRequest request) {
    return request.getOperation();
  }
}
