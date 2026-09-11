package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class S3Config {

    @Valid @JsonProperty
    @NotNull private String s3Region;

    @Valid @JsonProperty
    @NotNull private String s3BucketName;

    @Valid @JsonProperty
    private long preSignUrlTimeoutSec;

    @Valid @JsonProperty
    private String s3Url;

    @Valid @JsonProperty
    private boolean isMinIO;
}
