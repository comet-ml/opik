package com.comet.opik.api.error;

import java.util.List;

public record ErrorMessage(List<String> errors) {
}
