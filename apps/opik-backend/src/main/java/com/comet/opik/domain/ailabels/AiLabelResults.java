package com.comet.opik.domain.ailabels;

import lombok.Builder;
import lombok.NonNull;

import java.util.List;

/** One item's rows. The {@code labels} are written before the {@code statuses}, so a {@code done} implies its values. */
@Builder(toBuilder = true)
public record AiLabelResults(@NonNull List<AiLabel> labels, @NonNull List<AiLabelStatus> statuses) {
}
