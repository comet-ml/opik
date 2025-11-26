package com.comet.opik.domain;

import com.comet.opik.api.DatasetVersionDiff;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

@Mapper
public interface DatasetVersionDiffMapper {

    DatasetVersionDiffMapper INSTANCE = Mappers.getMapper(DatasetVersionDiffMapper.class);

    /**
     * Converts domain-layer diff statistics to API-layer diff statistics.
     *
     * @param domainStats Domain statistics from DAO layer
     * @return API statistics for JSON response
     */
    DatasetVersionDiff.DiffStatistics toApiDiffStatistics(DatasetVersionDiffStats domainStats);
}
