package com.comet.opik.infrastructure;

import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

@ImplementedBy(AppMetadataServiceImpl.class)
public interface AppMetadataService {
    String getVersion();
    static String readVersionFile() throws IOException {
        String versionFile = "../../version.txt";

        try (BufferedReader br = new BufferedReader(new FileReader(versionFile))) {
            return br.readLine();
        }
    }
}

@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
class AppMetadataServiceImpl implements AppMetadataService {
    @NonNull private final OpikConfiguration config;

    @Override
    public String getVersion() {
        var version = config.getMetadata().getVersion();
        if (StringUtils.isEmpty(version) || version.equals("latest")) {
            try {
                return AppMetadataService.readVersionFile();
            } catch (IOException e) {
                log.warn("could not get concrete opik version from file", e);
            }
        }

        return version;
    }
}
