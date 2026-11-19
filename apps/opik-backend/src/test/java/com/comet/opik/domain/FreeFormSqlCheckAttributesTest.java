package com.comet.opik.domain;

import com.comet.opik.infrastructure.FreeFormSqlPostRunCheckConfig;
import io.opentelemetry.api.common.AttributeKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Free-form SQL post-run check metric attributes")
class FreeFormSqlCheckAttributesTest {

    @Test
    @DisplayName("a violation carries its workspace, reason and table; a verified query only mode, outcome and account")
    void onlyViolationsCarryTheirContext() {
        var workspaceId = UUID.randomUUID().toString();
        var violation = FreeFormSqlPolicyViolation.builder().table("opik.traces").reason("read without a row policy")
                .build();

        var violated = FreeFormSqlQueryService.checkAttributes(FreeFormSqlPostRunCheckConfig.Mode.AUDIT,
                FreeFormSqlQueryService.CheckOutcome.VIOLATION, FreeFormSqlAccount.EXTENDED, violation, workspaceId);
        var verified = FreeFormSqlQueryService.checkAttributes(FreeFormSqlPostRunCheckConfig.Mode.AUDIT,
                FreeFormSqlQueryService.CheckOutcome.VERIFIED, FreeFormSqlAccount.EXTENDED, null, workspaceId);

        assertThat(asMap(violated)).isEqualTo(Map.of("mode", "audit", "outcome", "violation", "account", "extended",
                "workspace_id", workspaceId, "reason", "read without a row policy", "table", "opik.traces"));
        assertThat(asMap(verified)).isEqualTo(Map.of("mode", "audit", "outcome", "verified", "account", "extended"));
    }

    private static Map<String, Object> asMap(io.opentelemetry.api.common.Attributes attributes) {
        return attributes.asMap().entrySet().stream()
                .collect(Collectors.toMap(entry -> ((AttributeKey<?>) entry.getKey()).getKey(), Map.Entry::getValue));
    }
}
