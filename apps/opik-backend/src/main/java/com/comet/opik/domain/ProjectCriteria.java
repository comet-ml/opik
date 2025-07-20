package com.comet.opik.domain;

import lombok.Builder;
@Builder(toBuilder = true)
public record ProjectCriteria(String projectName) {

}
