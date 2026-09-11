package com.comet.opik.domain;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;

import java.util.List;

@Builder(toBuilder = true)
public record FreeFormSqlResult(List<JsonNode> rows, long resultRows, long readBytes) {
}
