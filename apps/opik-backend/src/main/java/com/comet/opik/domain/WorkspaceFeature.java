package com.comet.opik.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** An Opik feature the platform switches on per organization, under the name the platform knows it by. */
@Getter
@RequiredArgsConstructor
public enum WorkspaceFeature {

    GUARDRAILS("guardrails");

    private final String value;
}
