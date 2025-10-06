package com.comet.opik.domain;

import com.comet.opik.api.events.OpenSourceWelcomeWizardSubmitted;
import com.comet.opik.api.opensourcewelcomewizard.OpenSourceWelcomeWizardSubmission;
import com.comet.opik.api.opensourcewelcomewizard.OpenSourceWelcomeWizardTracking;
import com.google.common.eventbus.EventBus;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;

@ImplementedBy(OpenSourceWelcomeWizardTrackingService.Impl.class)
public interface OpenSourceWelcomeWizardTrackingService {

    Optional<OpenSourceWelcomeWizardTracking> getTrackingStatus(String workspaceId);

    OpenSourceWelcomeWizardTracking submitWizard(String workspaceId, String userName,
            OpenSourceWelcomeWizardSubmission submission);

    @Slf4j
    @Singleton
    @RequiredArgsConstructor(onConstructor_ = @Inject)
    class Impl implements OpenSourceWelcomeWizardTrackingService {

        private final @NonNull TransactionTemplate transactionTemplate;
        private final @NonNull EventBus eventBus;

        @Override
        public Optional<OpenSourceWelcomeWizardTracking> getTrackingStatus(String workspaceId) {
            return transactionTemplate.inTransaction(handle -> {
                var dao = handle.attach(OpenSourceWelcomeWizardTrackingDAO.class);
                return dao.findByWorkspaceId(workspaceId);
            });
        }

        @Override
        public OpenSourceWelcomeWizardTracking submitWizard(String workspaceId, String userName,
                OpenSourceWelcomeWizardSubmission submission) {
            log.info("Submitting OSS welcome wizard for workspace: '{}'", workspaceId);

            var result = transactionTemplate.inTransaction(handle -> {
                var dao = handle.attach(OpenSourceWelcomeWizardTrackingDAO.class);
                var existingTracking = dao.findByWorkspaceId(workspaceId);

                var now = Instant.now();
                var tracking = OpenSourceWelcomeWizardTracking.builder()
                        .workspaceId(workspaceId)
                        .completed(true)
                        .email(submission.email())
                        .role(submission.role())
                        .integrations(submission.integrations())
                        .joinBetaProgram(submission.joinBetaProgram())
                        .submittedAt(now)
                        .createdBy(userName)
                        .lastUpdatedBy(userName);

                if (existingTracking.isPresent()) {
                    tracking = tracking
                            .createdAt(existingTracking.get().createdAt())
                            .createdBy(existingTracking.get().createdBy());
                    var updated = tracking.build();
                    dao.update(updated);
                    log.info("Updated OSS welcome wizard tracking for workspace: '{}'", workspaceId);
                    return updated;
                } else {
                    tracking = tracking.createdAt(now);
                    var created = tracking.build();
                    dao.save(created);
                    log.info("Created OSS welcome wizard tracking for workspace: '{}'", workspaceId);
                    return created;
                }
            });

            // Publish event for BI tracking
            var event = OpenSourceWelcomeWizardSubmitted.builder()
                    .workspaceId(workspaceId)
                    .email(submission.email())
                    .role(submission.role())
                    .integrations(submission.integrations())
                    .joinBetaProgram(submission.joinBetaProgram())
                    .build();

            eventBus.post(event);
            log.info("OSS welcome wizard submitted event published for workspace: '{}'", workspaceId);

            return result;
        }
    }
}
