package com.comet.opik.api.validation;

import java.util.UUID;

public interface HasProjectIdentifier {
    UUID projectId();

    String projectName();
}
