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
    @Mapping(target = "datasetId", source = "datasetId")
    @Mapping(target = "versionHash", source = "versionHash")
    @Mapping(target = "tags", ignore = true)
    @Mapping(target = "itemsTotal", source = "itemsTotal")
    @Mapping(target = "itemsAdded", source = "itemsAdded")
    @Mapping(target = "itemsModified", source = "itemsModified")
    @Mapping(target = "itemsDeleted", source = "itemsDeleted")
    @Mapping(target = "changeDescription", source = "request.changeDescription")
    @Mapping(target = "metadata", source = "request.metadata")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "createdBy", source = "userName")
    @Mapping(target = "lastUpdatedAt", ignore = true)
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
