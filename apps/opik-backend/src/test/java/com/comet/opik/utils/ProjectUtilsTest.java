package com.comet.opik.utils;

import com.comet.opik.api.Project;
import com.comet.opik.domain.ProjectDAO;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.util.List;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;

public class ProjectUtilsTest {
    public static List<Project> findProjectByNames(TransactionTemplate template, String workspaceId,
            List<String> projectNames) {
        return template.inTransaction(READ_ONLY, handle -> {

            var repository = handle.attach(ProjectDAO.class);

            return repository.findByNames(workspaceId, projectNames);
        });
    }
}
