package com.comet.opik.api;

import lombok.Builder;

import java.util.List;

@Builder(toBuilder = true)
public record ExperimentGroupItem(
        List<String> groupValues) {
}