package com.comet.opik.domain;

import com.comet.opik.api.events.WelcomeWizardSubmitted;
import com.comet.opik.api.welcomewizard.WelcomeWizardSubmission;
import com.comet.opik.api.welcomewizard.WelcomeWizardTracking;
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

@ImplementedBy(WelcomeWizardTrackingService.Impl.class)
public interface WelcomeWizardTrackingService {

    WelcomeWizardTracking getTrackingStatus(String workspaceId);

    void submitWizard(String workspaceId, WelcomeWizardSubmission submission);

    @Slf4j
    @Singleton
    @RequiredArgsConstructor(onConstructor_ = @Inject)
    class Impl implements WelcomeWizardTrackingService {

        private final @NonNull TransactionTemplate transactionTemplate;
        private final @NonNull EventBus eventBus;

        @Override
        public WelcomeWizardTracking getTrackingStatus(String workspaceId) {
            boolean completed = transactionTemplate.inTransaction(READ_ONLY, handle -> {
                var dao = handle.attach(WelcomeWizardTrackingDAO.class);
                return dao.isCompleted(WelcomeWizardTrackingDAO.EVENT_TYPE_PREFIX, workspaceId);
            });

            return WelcomeWizardTracking.builder()
                    .completed(completed)
                    .build();
        }

        @Override
        public void submitWizard(String workspaceId, WelcomeWizardSubmission submission) {
            log.info("Submitting welcome wizard for workspace: '{}'", workspaceId);

            // Mark as completed in database
            transactionTemplate.inTransaction(WRITE, handle -> {
                var dao = handle.attach(WelcomeWizardTrackingDAO.class);

                // Only insert if not already completed (INSERT will fail on duplicate, which is fine)
                if (!dao.isCompleted(WelcomeWizardTrackingDAO.EVENT_TYPE_PREFIX, workspaceId)) {
                    dao.markCompleted(WelcomeWizardTrackingDAO.EVENT_TYPE_PREFIX, workspaceId);
                    log.info("Marked welcome wizard as completed for workspace: '{}'", workspaceId);
                }
                return null;
            });

            // Publish event for BI tracking (still send full data to BI)
            var event = new WelcomeWizardSubmitted(
                    workspaceId,
                    submission.email(),
                    submission.role(),
                    submission.integrations(),
                    submission.joinBetaProgram());

            eventBus.post(event);
            log.info("Welcome wizard submitted event published for workspace: '{}'", workspaceId);
        }
    }
}
