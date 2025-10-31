package com.comet.opik.infrastructure.queues;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

public enum Queue {

    OPTIMIZER_CLOUD("opik:optimizer-cloud", "opik_backend.rq_worker.process_optimizer_job"),
    ;

    @JsonValue
    private final String queueName;

    @Getter
    private final String functionName;

    Queue(String queueName, String functionName) {
        this.queueName = queueName;
        this.functionName = functionName;
    }

    @Override
    public String toString() {
        return queueName;
    }

}
