package com.comet.opik.domain;

import com.comet.opik.api.DashboardTemplate;
import com.comet.opik.api.DashboardTemplateUpdate;
import com.google.inject.ImplementedBy;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@ImplementedBy(DashboardTemplateServiceImpl.class)
public interface DashboardTemplateService {

    DashboardTemplate create(DashboardTemplate dashboardTemplate);

    DashboardTemplate update(UUID id, DashboardTemplateUpdate dashboardTemplateUpdate);

    void delete(UUID id);

    void delete(Set<UUID> ids);

    DashboardTemplate findById(UUID id);

    List<DashboardTemplate> findAll();
}