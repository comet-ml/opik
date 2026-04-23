package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.UIConfig;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UIConfigResource")
class UIConfigResourceTest {

    @Test
    @DisplayName("returns the configured defaultPageSize value")
    void returnsConfiguredDefaultPageSize() {
        UIConfig uiConfig = new UIConfig();
        uiConfig.setDefaultPageSize(25);

        OpikConfiguration config = new OpikConfiguration();
        config.setUiConfig(uiConfig);

        UIConfigResource resource = new UIConfigResource(config);

        Response response = resource.getUIConfig();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getEntity()).isEqualTo(uiConfig);
        assertThat(((UIConfig) response.getEntity()).getDefaultPageSize()).isEqualTo(25);
    }

    @Test
    @DisplayName("defaults to 100 when no override is provided")
    void returnsDefaultWhenNotOverridden() {
        OpikConfiguration config = new OpikConfiguration();
        UIConfigResource resource = new UIConfigResource(config);

        Response response = resource.getUIConfig();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(((UIConfig) response.getEntity()).getDefaultPageSize()).isEqualTo(100);
    }
}
