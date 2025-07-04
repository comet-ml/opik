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
import org.apache.commons.collections4.ListUtils;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.sql.SQLIntegrityConstraintViolationException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@ImplementedBy(DashboardServiceImpl.class)
public interface DashboardService {

    Dashboard create(Dashboard dashboard);

    Dashboard update(UUID id, DashboardUpdate dashboardUpdate);

    void delete(UUID id);

    void delete(Set<UUID> ids);

    Dashboard findById(UUID id);

    List<Dashboard> findAll();

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
                if (ListUtils.emptyIfNull(sectionsToCreate).isEmpty()) {
                    // Create a default section
                    UUID defaultSectionId = idGenerator.generateId();
                    dao.insertSection(defaultSectionId, id, "Default Section", 0, userName);
                } else {
                    // Insert provided sections and panels
                    for (int i = 0; i < sectionsToCreate.size(); i++) {
                        DashboardSection section = sectionsToCreate.get(i);
                        UUID sectionId = idGenerator.generateId();

                        dao.insertSection(sectionId, id, section.title(),
                                section.positionOrder() != null ? section.positionOrder() : i,
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
                updateSectionsAndPanels(dao, id, dashboardUpdate.sections(), userName);
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
    public List<Dashboard> findAll() {
        String workspaceId = requestContext.get().getWorkspaceId();

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DashboardDAO.class);

            List<Dashboard> dashboards = dao.findAll(workspaceId);

            // Load sections and panels for each dashboard
            return dashboards.stream()
                    .map(dashboard -> {
                        List<DashboardSection> sections = loadSectionsWithPanels(dao, dashboard.id());
                        return dashboard.toBuilder()
                                .sections(sections)
                                .build();
                    })
                    .toList();
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
            dao.insertSection(sectionId, dashboardId, title, positionOrder, userName);

            return DashboardSection.builder()
                    .id(sectionId)
                    .title(title)
                    .positionOrder(positionOrder)
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
            } catch (IllegalArgumentException e) {
                throw new RuntimeException("Error converting configuration or layout to JSON - invalid input format",
                        e);
            }

            dao.insertPanel(panelId, sectionId, name, type, configJson, layoutJson, userName);

            return DashboardPanel.builder()
                    .id(panelId)
                    .sectionId(sectionId)
                    .name(name)
                    .type(DashboardPanel.PanelType.valueOf(type.toUpperCase()))
                    .configuration(configJson)
                    .layout(layoutJson)
                    .createdBy(userName)
                    .lastUpdatedBy(userName)
                    .build();
        });
    }

    private void updateSectionsAndPanels(DashboardDAO dao, UUID dashboardId, List<DashboardSection> updatedSections,
            String userName) {
        // Get existing sections and panels
        List<DashboardSection> existingSections = loadSectionsWithPanels(dao, dashboardId);
        Map<UUID, DashboardSection> existingSectionMap = existingSections.stream()
                .collect(Collectors.toMap(DashboardSection::id, section -> section));

        // Track which sections and panels we need to keep
        Set<UUID> sectionsToKeep = new HashSet<>();
        Set<UUID> panelsToKeep = new HashSet<>();

        // Process updated sections
        for (int i = 0; i < updatedSections.size(); i++) {
            DashboardSection updatedSection = updatedSections.get(i);
            UUID sectionId = updatedSection.id();

            if (sectionId != null && existingSectionMap.containsKey(sectionId)) {
                // Update existing section
                dao.updateSection(sectionId, updatedSection.title(),
                        updatedSection.positionOrder() != null ? updatedSection.positionOrder() : i, userName);
                sectionsToKeep.add(sectionId);
            } else {
                // Insert new section
                sectionId = idGenerator.generateId();
                dao.insertSection(sectionId, dashboardId, updatedSection.title(),
                        updatedSection.positionOrder() != null ? updatedSection.positionOrder() : i, userName);
                sectionsToKeep.add(sectionId);
            }

            // Process panels for this section
            if (updatedSection.panels() != null) {
                DashboardSection existingSection = existingSectionMap.get(updatedSection.id());
                Map<UUID, DashboardPanel> existingPanelMap = existingSection != null && existingSection.panels() != null
                        ? existingSection.panels().stream()
                                .collect(Collectors.toMap(DashboardPanel::id, panel -> panel))
                        : new HashMap<>();

                for (DashboardPanel updatedPanel : updatedSection.panels()) {
                    UUID panelId = updatedPanel.id();

                    if (panelId != null && existingPanelMap.containsKey(panelId)) {
                        // Update existing panel
                        dao.updatePanel(panelId, updatedPanel.name(), updatedPanel.type().name().toLowerCase(),
                                updatedPanel.configuration(), updatedPanel.layout(), userName);
                        panelsToKeep.add(panelId);
                    } else {
                        // Insert new panel
                        panelId = idGenerator.generateId();
                        dao.insertPanel(panelId, sectionId, updatedPanel.name(),
                                updatedPanel.type().name().toLowerCase(),
                                updatedPanel.configuration(), updatedPanel.layout(), userName);
                        panelsToKeep.add(panelId);
                    }
                }
            }
        }

        // Delete sections that are no longer present
        List<UUID> sectionsToDelete = existingSections.stream()
                .map(DashboardSection::id)
                .filter(id -> !sectionsToKeep.contains(id))
                .toList();
        if (!sectionsToDelete.isEmpty()) {
            dao.deleteSectionsByIds(sectionsToDelete);
        }

        // Delete panels that are no longer present (they will become orphaned)
        List<UUID> panelsToDelete = existingSections.stream()
                .flatMap(section -> section.panels() != null ? section.panels().stream() : Stream.empty())
                .map(DashboardPanel::id)
                .filter(id -> !panelsToKeep.contains(id))
                .toList();
        if (!panelsToDelete.isEmpty()) {
            dao.deletePanelsByIds(panelsToDelete);
        }
    }

    private List<DashboardSection> loadSectionsWithPanels(DashboardDAO dao, UUID dashboardId) {
        List<DashboardSection> sections = dao.findSectionsByDashboardId(dashboardId);

        if (sections.isEmpty()) {
            return sections;
        }

        // Collect all section IDs for a single DB call
        List<UUID> sectionIds = sections.stream()
                .map(DashboardSection::id)
                .toList();

        // Get all panels for all sections in one DB call
        List<DashboardPanel> allPanels = dao.findPanelsBySectionIds(sectionIds);

        // Group panels by section ID (excluding orphaned panels with null sectionId)
        Map<UUID, List<DashboardPanel>> sectionPanels = allPanels.stream()
                .filter(panel -> panel.sectionId() != null)
                .collect(Collectors.groupingBy(DashboardPanel::sectionId));

        return sections.stream()
                .map(section -> section.toBuilder()
                        .panels(sectionPanels.getOrDefault(section.id(), new ArrayList<>()))
                        .build())
                .toList();
    }
}
