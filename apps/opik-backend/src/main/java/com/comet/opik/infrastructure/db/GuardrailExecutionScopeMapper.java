package com.comet.opik.infrastructure.db;

import com.comet.opik.api.GuardrailExecutionScope;

public class GuardrailExecutionScopeMapper extends AbstractEnumColumnMapper<GuardrailExecutionScope> {
    public GuardrailExecutionScopeMapper() {
        super(GuardrailExecutionScope::fromString, "guardrail_execution_scope");
    }
}
