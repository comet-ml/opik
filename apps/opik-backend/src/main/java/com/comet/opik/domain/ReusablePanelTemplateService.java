package com.comet.opik.domain;

import com.comet.opik.api.DashboardPanel;
import com.comet.opik.api.ReusablePanelTemplate;
import com.comet.opik.api.ReusablePanelTemplateUpdate;
import com.google.inject.ImplementedBy;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@ImplementedBy(ReusablePanelTemplateServiceImpl.class)
public interface ReusablePanelTemplateService {

    ReusablePanelTemplate create(ReusablePanelTemplate template);

    ReusablePanelTemplate update(UUID id, ReusablePanelTemplateUpdate templateUpdate);

    void delete(UUID id);

    void delete(Set<UUID> ids);

    ReusablePanelTemplate findById(UUID id);

    List<ReusablePanelTemplate> findAll();

    List<ReusablePanelTemplate> findByType(DashboardPanel.PanelType type);
}