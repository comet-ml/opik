package com.comet.opik.infrastructure;

import io.dropwizard.db.DataSourceFactory;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.stream.Collectors;

@UtilityClass
@Slf4j
public class DatabaseUtils {
    public static DataSourceFactory filterProperties(DataSourceFactory dataSourceFactory) {
        var filteredProperties = dataSourceFactory.getProperties()
                .entrySet()
                .stream()
                .filter(entry -> StringUtils.isNotBlank(entry.getValue()))
                .collect(Collectors.toMap(
                        java.util.Map.Entry::getKey,
                        java.util.Map.Entry::getValue));
        dataSourceFactory.setProperties(filteredProperties);

        return dataSourceFactory;
    }
}
