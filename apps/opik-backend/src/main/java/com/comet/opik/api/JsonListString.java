package com.comet.opik.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(anyOf = {Object.class, Object[].class, String.class})
public class JsonListString {
}
