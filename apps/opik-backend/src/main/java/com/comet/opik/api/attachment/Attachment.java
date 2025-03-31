package com.comet.opik.api.attachment;

import com.comet.opik.api.Page;
import com.comet.opik.api.Project;
import com.comet.opik.api.ProviderApiKey;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.util.List;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Attachment(
        String link,
        @NotNull String fileName,
        @NotNull long fileSize,
        @NotNull String mimeType) {

    @Builder(toBuilder = true)
    public record AttachmentPage(
            @JsonView( {
                    Project.View.Public.class}) int page,
            @JsonView({ProviderApiKey.View.Public.class}) int size,
            @JsonView({ProviderApiKey.View.Public.class}) long total,
            @JsonView({ProviderApiKey.View.Public.class}) List<Attachment> content,
            @JsonView({ProviderApiKey.View.Public.class}) List<String> sortableBy)
            implements
                Page<Attachment>{

        public static AttachmentPage empty(int page) {
            return new AttachmentPage(page, 0, 0, List.of(), List.of());
        }
    }
}
