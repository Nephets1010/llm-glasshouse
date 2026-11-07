/*
 * Copyright 2026 the llm-glasshouse authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.nephets.glasshouse.core.attribution;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import io.github.nephets.glasshouse.core.audit.AuditEvent;
import io.github.nephets.glasshouse.core.audit.EventType;
import io.github.nephets.glasshouse.core.pricing.PriceEntry;
import io.github.nephets.glasshouse.core.pricing.PriceSource;
import io.github.nephets.glasshouse.core.pricing.PriceUnit;
import io.github.nephets.glasshouse.core.usage.Usage;

final class AttributionTestFixtures {

    private AttributionTestFixtures() {}

    static PriceEntry entry(String provider, String model, String in, String out) {
        return new PriceEntry(
                provider,
                model,
                "CNY",
                PriceUnit.PER_1K_TOKENS,
                new BigDecimal(in),
                new BigDecimal(out),
                null,
                null,
                LocalDate.parse("2026-01-01"),
                PriceSource.OFFICIAL);
    }

    static AuditEvent event(
            String tenant,
            String bizLine,
            String feature,
            String promptVersion,
            long inTokens,
            long outTokens) {
        return new AuditEvent(
                EventType.MODEL_CALL,
                "4bf92f3577b34da6a3ce929d0e0e4736",
                "00f067aa0ba902b7",
                Instant.parse("2026-10-09T12:00:00Z"),
                tenant,
                bizLine,
                feature,
                promptVersion,
                null,
                null,
                null,
                null,
                Usage.of(inTokens, outTokens),
                BigDecimal.ZERO,
                "CNY",
                "sha256:fixture",
                null);
    }

    static Usage usage(long inTokens, long outTokens) {
        return Usage.of(inTokens, outTokens);
    }

    static BigDecimal cost(String value) {
        return new BigDecimal(value);
    }

    static AttributionKey tenantKey(String tenant) {
        return AttributionKey.empty().with(AttributionDimension.TENANT, tenant);
    }

    static AttributionKey key(
            String provider,
            String model,
            String tenant,
            String bizLine,
            String feature,
            String promptVersion,
            String agentStep) {
        return new AttributionKey(
                provider, model, tenant, bizLine, feature, promptVersion, agentStep);
    }
}
