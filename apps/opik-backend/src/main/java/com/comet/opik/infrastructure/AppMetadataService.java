package com.comet.opik.infrastructure;

import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import lombok.RequiredArgsConstructor;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

@ImplementedBy(AppMetadataServiceImpl.class)
public interface AppMetadataService {
    String getVersion();
}

@RequiredArgsConstructor(onConstructor_ = @Inject)
class AppMetadataServiceImpl implements AppMetadataService {
    @Config
    private final MetadataConfig config;

    @Override
    public String getVersion() {
        return config.getVersion();
    }
}
