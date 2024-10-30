package com.comet.opik.infrastructure;

import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import lombok.RequiredArgsConstructor;

@ImplementedBy(AppMetadataServiceImpl.class)
public interface AppMetadataService {
    String getVersion();
}

@RequiredArgsConstructor(onConstructor_ = @Inject)
class AppMetadataServiceImpl implements AppMetadataService {
    private final OpikConfiguration config;

    @Override
    public String getVersion() {
        return config.getMetadata().getVersion();
    }
}
