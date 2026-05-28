package com.comet.opik.utils;

import com.comet.opik.api.Project;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;

import static com.comet.opik.domain.ProjectService.DEFAULT_PROJECT;
import static com.comet.opik.domain.ProjectService.DEFAULT_WORKSPACE_NAME;

@UtilityClass
public class WorkspaceUtils {

    public static String getWorkspaceName(String workspaceName) {
        return StringUtils.isEmpty(workspaceName) ? DEFAULT_WORKSPACE_NAME : workspaceName;
    }

    public static String getProjectName(String projectName) {
        return StringUtils.isBlank(projectName) ? DEFAULT_PROJECT : projectName.strip();
    }

    public static String stripProjectName(Project project) {
        return project == null ? null : project.name().strip();
    }

}
