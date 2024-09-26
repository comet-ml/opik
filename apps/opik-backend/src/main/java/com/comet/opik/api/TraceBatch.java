package com.comet.opik.api;

import com.comet.opik.infrastructure.ratelimit.RateEventContainer;
import com.fasterxml.jackson.annotation.JsonView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.util.List;

@Builder(toBuilder = true)
public record TraceBatch(@NotNull @Size(min = 1, max = 1000) @JsonView( {
        Trace.View.Write.class}) @Valid List<Trace> traces) implements RateEventContainer{

    @Override
    public long eventCount() {
        return traces.size();
    }
}
