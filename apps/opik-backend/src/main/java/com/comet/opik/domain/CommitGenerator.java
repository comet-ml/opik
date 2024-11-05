package com.comet.opik.domain;

import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.util.UUID;

@UtilityClass
class CommitGenerator {

    public String generateCommit(@NonNull UUID id) {
        return id.toString().substring(id.toString().length() - 8);
    }
}
