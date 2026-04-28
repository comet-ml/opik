package com.comet.opik.infrastructure;

import com.comet.opik.utils.JsonUtils;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceTogglesConfigTest {

    // The frontend reads `default_page_size` from /v1/private/toggles/. If the
    // Java field, the @JsonProperty wiring, or the global SnakeCaseStrategy is
    // changed, the FE silently falls back to 100. This locks the wire format.
    @Test
    void defaultPageSize_serializesAsSnakeCase() throws Exception {
        var config = new ServiceTogglesConfig();
        config.setDefaultPageSize(42);

        String json = JsonUtils.getMapper().writeValueAsString(config);

        assertThat(json).contains("\"default_page_size\":42");
    }
}
