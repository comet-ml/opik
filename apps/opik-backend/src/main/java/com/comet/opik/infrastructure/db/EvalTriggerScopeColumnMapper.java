package com.comet.opik.infrastructure.db;

import com.comet.opik.api.evaluators.EvalTriggerScope;

public class EvalTriggerScopeColumnMapper extends AbstractEnumColumnMapper<EvalTriggerScope> {
    public EvalTriggerScopeColumnMapper() {
        super(EvalTriggerScope::fromString, "eval_trigger_scope");
    }
}
