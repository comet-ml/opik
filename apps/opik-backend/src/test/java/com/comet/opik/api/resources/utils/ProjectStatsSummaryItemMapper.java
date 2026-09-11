package com.comet.opik.api.resources.utils;

import com.comet.opik.api.Project;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import static com.comet.opik.api.ProjectStatsSummary.ProjectStatsSummaryItem;

@Mapper
public interface ProjectStatsSummaryItemMapper {

    ProjectStatsSummaryItemMapper INSTANCE = org.mapstruct.factory.Mappers
            .getMapper(ProjectStatsSummaryItemMapper.class);

    @Mapping(target = "projectId", source = "id")
    ProjectStatsSummaryItem mapFromProject(Project project);
}
