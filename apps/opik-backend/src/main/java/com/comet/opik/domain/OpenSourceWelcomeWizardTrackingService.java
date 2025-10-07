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

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@ImplementedBy(OpenSourceWelcomeWizardTrackingService.Impl.class)
public interface OpenSourceWelcomeWizardTrackingService {

    OpenSourceWelcomeWizardTracking getTrackingStatus(String workspaceId);

    void submitWizard(String workspaceId, OpenSourceWelcomeWizardSubmission submission);

    @Slf4j
    @Singleton
    @RequiredArgsConstructor(onConstructor_ = @Inject)
    class Impl implements OpenSourceWelcomeWizardTrackingService {

        private final @NonNull TransactionTemplate transactionTemplate;
        private final @NonNull EventBus eventBus;

        @Override
        public OpenSourceWelcomeWizardTracking getTrackingStatus(String workspaceId) {
            boolean completed = transactionTemplate.inTransaction(READ_ONLY, handle -> {
                var dao = handle.attach(OpenSourceWelcomeWizardTrackingDAO.class);
                return dao.isCompleted(OpenSourceWelcomeWizardTrackingDAO.EVENT_TYPE_PREFIX, workspaceId);
            });

            return OpenSourceWelcomeWizardTracking.builder()
                    .completed(completed)
                    .build();
        }

        @Override
        public void submitWizard(String workspaceId, OpenSourceWelcomeWizardSubmission submission) {
            log.info("Submitting OSS welcome wizard for workspace: '{}'", workspaceId);

            // Mark as completed in database
            transactionTemplate.inTransaction(WRITE, handle -> {
                var dao = handle.attach(OpenSourceWelcomeWizardTrackingDAO.class);

                // Only insert if not already completed (INSERT will fail on duplicate, which is fine)
                if (!dao.isCompleted(OpenSourceWelcomeWizardTrackingDAO.EVENT_TYPE_PREFIX, workspaceId)) {
                    dao.markCompleted(OpenSourceWelcomeWizardTrackingDAO.EVENT_TYPE_PREFIX, workspaceId);
                    log.info("Marked OSS welcome wizard as completed for workspace: '{}'", workspaceId);
                }
                return null;
            });

            // Publish event for BI tracking (still send full data to BI)
            var event = new OpenSourceWelcomeWizardSubmitted(
                    workspaceId,
                    submission.email(),
                    submission.role(),
                    submission.integrations(),
                    submission.joinBetaProgram());

            eventBus.post(event);
            log.info("OSS welcome wizard submitted event published for workspace: '{}'", workspaceId);
        }
    }
}
