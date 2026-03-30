package com.comet.opik.domain;

import com.comet.opik.api.filter.Filter;
import lombok.Builder;

import java.util.List;

@Builder(toBuilder = true)
public record ProjectCriteria(String projectName, List<? extends Filter> filters) {

}
