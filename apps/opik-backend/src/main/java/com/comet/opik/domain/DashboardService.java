package com.comet.opik.domain;

import com.comet.opik.api.Dashboard;
import com.comet.opik.api.DashboardPanel;
import com.comet.opik.api.DashboardSection;
import com.comet.opik.api.DashboardUpdate;
import com.comet.opik.api.ExperimentDashboard;
import com.comet.opik.api.error.EntityAlreadyExistsException;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.NotFoundException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.sql.SQLIntegrityConstraintViolationException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@ImplementedBy(DashboardServiceImpl.class)
public interface DashboardService {

    Dashboard create(Dashboard dashboard);

    Dashboard update(UUID id, DashboardUpdate dashboardUpdate);

    void delete(UUID id);

    void delete(Set<UUID> ids);

    Dashboard findById(UUID id);

    Optional<Dashboard> findByName(String name);

    Dashboard.DashboardPage find(int page, int size);

    void associateExperimentWithDashboard(UUID experimentId, UUID dashboardId);

    void removeExperimentDashboardAssociation(UUID experimentId);

    Optional<ExperimentDashboard> findExperimentDashboard(UUID experimentId);

    // Section and Panel creation methods
    DashboardSection createSection(UUID dashboardId, String title, Integer positionOrder);

    DashboardPanel createPanel(UUID dashboardId, UUID sectionId, String name, String type, Object configuration,
            Object layout);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class DashboardServiceImpl implements DashboardService {

    private static final String DASHBOARD_ALREADY_EXISTS = "Dashboard already exists";

    private final @NonNull IdGenerator idGenerator;
    private final @NonNull TransactionTemplate template;
    private final @NonNull Provider<RequestContext> requestContext;

    @Override
    public Dashboard create(@NonNull Dashboard dashboard) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        UUID id = idGenerator.generateId();

        try {
            return template.inTransaction(WRITE, handle -> {
                var dao = handle.attach(DashboardDAO.class);

                // Insert dashboard
                dao.insert(id, dashboard.name(), dashboard.description(), workspaceId, userName);

                // Insert sections and panels if provided, or create a default section
                List<DashboardSection> sectionsToCreate = dashboard.sections();
                if (sectionsToCreate == null || sectionsToCreate.isEmpty()) {
                    // Create a default section
                    UUID defaultSectionId = idGenerator.generateId();
                    dao.insertSection(defaultSectionId, id, "Default Section", 0, true, userName);
                } else {
                    // Insert provided sections and panels
                    for (int i = 0; i < sectionsToCreate.size(); i++) {
                        DashboardSection section = sectionsToCreate.get(i);
                        UUID sectionId = idGenerator.generateId();

                        dao.insertSection(sectionId, id, section.title(),
                                section.positionOrder() != null ? section.positionOrder() : i,
                                section.isExpanded() != null ? section.isExpanded() : true,
                                userName);

                        // Insert panels for this section
                        if (section.panels() != null) {
                            for (DashboardPanel panel : section.panels()) {
                                UUID panelId = idGenerator.generateId();
                                dao.insertPanel(panelId, sectionId, panel.name(),
                                        panel.type().name().toLowerCase(),
                                        panel.configuration(), panel.layout(), userName);
                            }
                        }
                    }
                }

                return findById(id);
            });
        } catch (UnableToExecuteStatementException e) {
            if (e.getCause() instanceof SQLIntegrityConstraintViolationException) {
                throw new EntityAlreadyExistsException(new ErrorMessage(List.of(DASHBOARD_ALREADY_EXISTS)));
            }
            throw e;
        }
    }

    @Override
    public Dashboard update(@NonNull UUID id, @NonNull DashboardUpdate dashboardUpdate) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        return template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(DashboardDAO.class);

            // Update dashboard metadata
            if (dashboardUpdate.name() != null || dashboardUpdate.description() != null) {
                Dashboard existing = findById(id);
                String name = dashboardUpdate.name() != null ? dashboardUpdate.name() : existing.name();
                String description = dashboardUpdate.description() != null
                        ? dashboardUpdate.description()
                        : existing.description();

                int updated = dao.update(id, name, description, workspaceId, userName);
                if (updated == 0) {
                    throw new NotFoundException("Dashboard not found");
                }
            }

            // Update sections and panels if provided
            if (dashboardUpdate.sections() != null) {
                // Delete existing sections and panels
                dao.deletePanelsByDashboardId(id);
                dao.deleteSectionsByDashboardId(id);

                // Insert new sections and panels
                for (int i = 0; i < dashboardUpdate.sections().size(); i++) {
                    DashboardSection section = dashboardUpdate.sections().get(i);
                    UUID sectionId = section.id() != null ? section.id() : idGenerator.generateId();

                    dao.insertSection(sectionId, id, section.title(),
                            section.positionOrder() != null ? section.positionOrder() : i,
                            section.isExpanded() != null ? section.isExpanded() : true,
                            userName);

                    // Insert panels for this section
                    if (section.panels() != null) {
                        for (DashboardPanel panel : section.panels()) {
                            UUID panelId = panel.id() != null ? panel.id() : idGenerator.generateId();
                            dao.insertPanel(panelId, sectionId, panel.name(),
                                    panel.type().name().toLowerCase(),
                                    panel.configuration(), panel.layout(), userName);
                        }
                    }
                }
            }

            return findById(id);
        });
    }

    @Override
    public void delete(@NonNull UUID id) {
        String workspaceId = requestContext.get().getWorkspaceId();

        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(DashboardDAO.class);
            dao.delete(id, workspaceId);
            return null;
        });
    }

    @Override
    public void delete(@NonNull Set<UUID> ids) {
        if (ids.isEmpty()) {
            log.info("ids list is empty, returning");
            return;
        }

        String workspaceId = requestContext.get().getWorkspaceId();

        template.inTransaction(WRITE, handle -> {
            handle.attach(DashboardDAO.class).delete(ids, workspaceId);
            return null;
        });
    }

    @Override
    public Dashboard findById(@NonNull UUID id) {
        String workspaceId = requestContext.get().getWorkspaceId();

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DashboardDAO.class);

            Dashboard dashboard = dao.findById(id, workspaceId)
                    .orElseThrow(() -> new NotFoundException("Dashboard not found"));

            // Load sections and panels
            List<DashboardSection> sections = loadSectionsWithPanels(dao, id);

            return dashboard.toBuilder()
                    .sections(sections)
                    .build();
        });
    }

    @Override
    public Optional<Dashboard> findByName(@NonNull String name) {
        String workspaceId = requestContext.get().getWorkspaceId();

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DashboardDAO.class);

            Optional<Dashboard> dashboardOpt = dao.findByName(name, workspaceId);
            if (dashboardOpt.isEmpty()) {
                return Optional.empty();
            }

            Dashboard dashboard = dashboardOpt.get();
            List<DashboardSection> sections = loadSectionsWithPanels(dao, dashboard.id());

            return Optional.of(dashboard.toBuilder()
                    .sections(sections)
                    .build());
        });
    }

    @Override
    public Dashboard.DashboardPage find(int page, int size) {
        String workspaceId = requestContext.get().getWorkspaceId();

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DashboardDAO.class);

            int offset = (page - 1) * size;
            List<Dashboard> dashboards = dao.findByWorkspaceId(workspaceId, size, offset);
            long total = dao.countByWorkspaceId(workspaceId);

            // Load sections and panels for each dashboard
            List<Dashboard> enrichedDashboards = new ArrayList<>();
            for (Dashboard dashboard : dashboards) {
                List<DashboardSection> sections = loadSectionsWithPanels(dao, dashboard.id());
                enrichedDashboards.add(dashboard.toBuilder().sections(sections).build());
            }

            return Dashboard.DashboardPage.builder()
                    .content(enrichedDashboards)
                    .page(page)
                    .size(size)
                    .total(total)
                    .build();
        });
    }

    @Override
    public void associateExperimentWithDashboard(@NonNull UUID experimentId, @NonNull UUID dashboardId) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(DashboardDAO.class);
            dao.associateExperimentWithDashboard(experimentId, dashboardId, workspaceId, userName);
            return null;
        });
    }

    @Override
    public void removeExperimentDashboardAssociation(@NonNull UUID experimentId) {
        String workspaceId = requestContext.get().getWorkspaceId();

        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(DashboardDAO.class);
            dao.removeExperimentDashboardAssociation(experimentId, workspaceId);
            return null;
        });
    }

    @Override
    public Optional<ExperimentDashboard> findExperimentDashboard(@NonNull UUID experimentId) {
        String workspaceId = requestContext.get().getWorkspaceId();

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DashboardDAO.class);
            return dao.findExperimentDashboard(experimentId, workspaceId);
        });
    }

    @Override
    public DashboardSection createSection(UUID dashboardId, String title, Integer positionOrder) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        UUID sectionId = idGenerator.generateId();

        return template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(DashboardDAO.class);
            dao.insertSection(sectionId, dashboardId, title, positionOrder, true, userName);

            return DashboardSection.builder()
                    .id(sectionId)
                    .title(title)
                    .positionOrder(positionOrder)
                    .isExpanded(true)
                    .createdBy(userName)
                    .lastUpdatedBy(userName)
                    .build();
        });
    }

    @Override
    public DashboardPanel createPanel(UUID dashboardId, UUID sectionId, String name, String type, Object configuration,
            Object layout) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        UUID panelId = idGenerator.generateId();

        return template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(DashboardDAO.class);

            // Convert configuration and layout to JsonNode
            JsonNode configJson = null;
            JsonNode layoutJson = null;

            try {
                if (configuration != null) {
                    configJson = com.comet.opik.utils.JsonUtils.MAPPER.valueToTree(configuration);
                }
                if (layout != null) {
                    layoutJson = com.comet.opik.utils.JsonUtils.MAPPER.valueToTree(layout);
                }
            } catch (Exception e) {
                throw new RuntimeException("Error converting configuration or layout to JSON", e);
            }

            dao.insertPanel(panelId, sectionId, name, type, configJson, layoutJson, userName);

            return DashboardPanel.builder()
                    .id(panelId)
                    .name(name)
                    .type(DashboardPanel.PanelType.valueOf(type.toUpperCase()))
                    .configuration(configJson)
                    .layout(layoutJson)
                    .createdBy(userName)
                    .lastUpdatedBy(userName)
                    .build();
        });
    }

    private List<DashboardSection> loadSectionsWithPanels(DashboardDAO dao, UUID dashboardId) {
        List<DashboardSection> sections = dao.findSectionsByDashboardId(dashboardId);

        Map<UUID, List<DashboardPanel>> sectionPanels = new HashMap<>();
        for (DashboardSection section : sections) {
            List<DashboardPanel> panels = dao.findPanelsBySectionId(section.id());
            sectionPanels.put(section.id(), panels);
        }

        return sections.stream()
                .map(section -> section.toBuilder()
                        .panels(sectionPanels.getOrDefault(section.id(), new ArrayList<>()))
                        .build())
                .toList();
    }
}