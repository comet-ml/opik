package com.comet.opik.utils;

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
        return StringUtils.isEmpty(projectName) ? DEFAULT_PROJECT : projectName;
    }

}
