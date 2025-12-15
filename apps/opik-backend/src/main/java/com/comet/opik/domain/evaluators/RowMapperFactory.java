package com.comet.opik.domain.evaluators;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Functional interface for creating AutomationRuleEvaluatorModel instances from row mapper data.
 * Each AutomationRuleEvaluatorType holds a reference to its specific factory method.
 *
 * This pattern eliminates switch statements by delegating construction to the type itself,
 * similar to how RedisStreamCodec uses Supplier&lt;Codec&gt;.
 */
@FunctionalInterface
public interface RowMapperFactory {

    /**
     * Creates an AutomationRuleEvaluatorModel instance from row mapper data.
     *
     * @param common Common fields extracted from the ResultSet
     * @param codeNode JSON representation of the type-specific code field
     * @param objectMapper ObjectMapper for JSON deserialization
     * @return Fully constructed model instance
     * @throws JsonProcessingException if JSON parsing fails
     */
    AutomationRuleEvaluatorModel<?> create(
            AutomationRuleEvaluatorWithProjectRowMapper.CommonFields common,
            JsonNode codeNode,
            ObjectMapper objectMapper) throws JsonProcessingException;
}
