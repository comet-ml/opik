package com.comet.opik.domain;

import com.comet.opik.api.DatasetVersion;
import com.comet.opik.api.DatasetVersionCreate;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

import java.util.UUID;

@Mapper
interface DatasetVersionMapper {

    DatasetVersionMapper INSTANCE = Mappers.getMapper(DatasetVersionMapper.class);

    @Mapping(target = "id", source = "versionId")
    @Mapping(target = "changeDescription", source = "request.changeDescription")
    @Mapping(target = "metadata", source = "request.metadata")
    @Mapping(target = "createdBy", source = "userName")
    @Mapping(target = "lastUpdatedBy", source = "userName")
    DatasetVersion toDatasetVersion(
            UUID versionId,
            UUID datasetId,
            String versionHash,
            int itemsTotal,
            int itemsAdded,
            int itemsModified,
            int itemsDeleted,
            DatasetVersionCreate request,
            String userName);
}
