package com.comet.opik.api.resources.oauth;

import lombok.Builder;

@Builder(toBuilder = true)
public record ConsentResponse(String redirectTo) {
}
