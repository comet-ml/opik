package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class S3Config {
    @Valid @JsonProperty
    private boolean isEKSPod;

    @Valid @JsonProperty
    @NotNull private String s3Key;

    @Valid @JsonProperty
    @NotNull private String s3Secret;

    @Valid @JsonProperty
    @NotNull private String s3Region;

    @Valid @JsonProperty
    @NotNull private String s3BucketName;

    @Valid @JsonProperty
    private long preSignUrlTimeoutSec;
}
