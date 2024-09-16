package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record TraceBatch(@NotNull @Size(min = 1, max = 1000) @JsonView( {
        Trace.View.Write.class}) @Valid List<Trace> traces){
}
