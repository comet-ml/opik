package com.comet.opik.domain;

import com.comet.opik.api.DatasetVersion;
import com.comet.opik.api.DatasetVersion.DatasetVersionPage;
import com.comet.opik.api.DatasetVersionCreate;
import com.comet.opik.api.DatasetVersionTag;
import com.comet.opik.infrastructure.DatabaseUtils;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.common.base.Preconditions;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.NotFoundException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@ImplementedBy(DatasetVersionServiceImpl.class)
public interface DatasetVersionService {

    DatasetVersion commitVersion(UUID datasetId, DatasetVersionCreate request);

    DatasetVersionPage getVersions(UUID datasetId, int page, int size);

    Optional<DatasetVersion> getVersionByHash(UUID datasetId, String versionHash);

    Optional<DatasetVersion> getVersionByTag(UUID datasetId, String tag);

    Optional<DatasetVersion> getLatestVersion(UUID datasetId);

    void createTag(UUID datasetId, String versionHash, DatasetVersionTag tag);

    void deleteTag(UUID datasetId, String tag);

    UUID resolveVersionId(UUID datasetId, String hashOrTag);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class DatasetVersionServiceImpl implements DatasetVersionService {

    private final @NonNull IdGenerator idGenerator;
    private final @NonNull TransactionTemplate template;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull DatasetItemDAO datasetItemDAO;

    @Override
    public DatasetVersion commitVersion(@NonNull UUID datasetId, @NonNull DatasetVersionCreate request) {
        log.info("Committing version for dataset: '{}'", datasetId);

        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        // TODO OPIK-3015: Calculate hash based on actual dataset items from ClickHouse
        // For now, use timestamp-based hash as placeholder
        String versionHash = calculatePlaceholderVersionHash(datasetId);

        return template.inTransaction(WRITE, handle -> {
            var datasetVersionDAO = handle.attach(DatasetVersionDAO.class);

            // TODO OPIK-3015: Get actual item count and calculate diff stats from ClickHouse
            // For now, use placeholder values for metadata-only testing
            int itemsCount = 0;
            int itemsAdded = 0;
            int itemsModified = 0;
            int itemsDeleted = 0;

            // Create new version
            var versionId = idGenerator.generateId();
            var version = DatasetVersion.builder()
                    .id(versionId)
                    .datasetId(datasetId)
                    .versionHash(versionHash)
                    .itemsCount(itemsCount)
                    .itemsAdded(itemsAdded)
                    .itemsModified(itemsModified)
                    .itemsDeleted(itemsDeleted)
                    .changeDescription(request.changeDescription())
                    .metadata(request.metadata())
                    .createdBy(userName)
                    .lastUpdatedBy(userName)
                    .build();

            DatabaseUtils.handleStateDbDuplicateConstraint(
                    () -> datasetVersionDAO.insert(version, workspaceId),
                    "Version with hash '%s' already exists for dataset '%s'".formatted(versionHash, datasetId));

            log.info("Created version with hash '{}' for dataset '{}'", versionHash, datasetId);

            // Add tag if provided
            if (StringUtils.isNotBlank(request.tag())) {
                DatabaseUtils.handleStateDbDuplicateConstraint(
                        () -> datasetVersionDAO.insertTag(datasetId, request.tag(), versionId, userName, workspaceId),
                        "Tag '%s' already exists for this dataset".formatted(request.tag()));
            }

            // TODO OPIK-3015: Create immutable snapshots in ClickHouse dataset_item_versions table

            return datasetVersionDAO.findById(versionId, workspaceId).orElseThrow();
        });
    }

    @Override
    public DatasetVersionPage getVersions(@NonNull UUID datasetId, int page, int size) {
        Preconditions.checkArgument(page >= 1, "Page must be greater than or equal to 1");
        Preconditions.checkArgument(size >= 1, "Size must be greater than or equal to 1");

        log.info("Getting versions for dataset: '{}', page: '{}', size: '{}'", datasetId, page, size);

        String workspaceId = requestContext.get().getWorkspaceId();

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DatasetVersionDAO.class);

            int offset = (page - 1) * size;
            var versions = dao.findByDatasetId(datasetId, workspaceId, size, offset);
            var total = dao.countByDatasetId(datasetId, workspaceId);

            return new DatasetVersionPage(versions, page, size, total);
        });
    }

    @Override
    public Optional<DatasetVersion> getVersionByHash(@NonNull UUID datasetId, @NonNull String versionHash) {
        log.info("Getting version by hash for dataset: '{}', hash: '{}'", datasetId, versionHash);

        String workspaceId = requestContext.get().getWorkspaceId();

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DatasetVersionDAO.class);
            return dao.findByHash(datasetId, versionHash, workspaceId);
        });
    }

    @Override
    public Optional<DatasetVersion> getVersionByTag(@NonNull UUID datasetId, @NonNull String tag) {
        log.info("Getting version by tag for dataset: '{}', tag: '{}'", datasetId, tag);

        String workspaceId = requestContext.get().getWorkspaceId();

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DatasetVersionDAO.class);
            return dao.findByTag(datasetId, tag, workspaceId);
        });
    }

    @Override
    public Optional<DatasetVersion> getLatestVersion(@NonNull UUID datasetId) {
        log.info("Getting latest version for dataset: '{}'", datasetId);

        String workspaceId = requestContext.get().getWorkspaceId();

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DatasetVersionDAO.class);
            return dao.findLatestVersion(datasetId, workspaceId);
        });
    }

    @Override
    public void createTag(@NonNull UUID datasetId, @NonNull String versionHash,
            @NonNull DatasetVersionTag tagRequest) {
        log.info("Creating tag '{}' for version '{}' of dataset: '{}'", tagRequest.tag(), versionHash, datasetId);

        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(DatasetVersionDAO.class);

            // Find version by hash
            var version = dao.findByHash(datasetId, versionHash, workspaceId)
                    .orElseThrow(() -> new NotFoundException(
                            "Version with hash '%s' not found for dataset '%s'".formatted(versionHash, datasetId)));

            // Insert tag
            DatabaseUtils.handleStateDbDuplicateConstraint(
                    () -> dao.insertTag(datasetId, tagRequest.tag(), version.id(), userName, workspaceId),
                    "Tag '%s' already exists for this dataset".formatted(tagRequest.tag()));

            return null;
        });

        log.info("Created tag '{}' for version '{}' of dataset: '{}'", tagRequest.tag(), versionHash, datasetId);
    }

    @Override
    public void deleteTag(@NonNull UUID datasetId, @NonNull String tag) {
        log.info("Deleting tag '{}' from dataset: '{}'", tag, datasetId);

        String workspaceId = requestContext.get().getWorkspaceId();

        int deleted = template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(DatasetVersionDAO.class);
            return dao.deleteTag(datasetId, tag, workspaceId);
        });

        if (deleted == 0) {
            throw new NotFoundException("Tag '%s' not found for dataset '%s'".formatted(tag, datasetId));
        }

        log.info("Deleted tag '{}' from dataset: '{}'", tag, datasetId);
    }

    @Override
    public UUID resolveVersionId(@NonNull UUID datasetId, @NonNull String hashOrTag) {
        log.info("Resolving version ID for dataset: '{}', hashOrTag: '{}'", datasetId, hashOrTag);

        String workspaceId = requestContext.get().getWorkspaceId();

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DatasetVersionDAO.class);

            // Try to find by hash first
            var versionByHash = dao.findByHash(datasetId, hashOrTag, workspaceId);
            if (versionByHash.isPresent()) {
                return versionByHash.get().id();
            }

            // Try to find by tag
            var versionByTag = dao.findByTag(datasetId, hashOrTag, workspaceId);
            if (versionByTag.isPresent()) {
                return versionByTag.get().id();
            }

            throw new NotFoundException(
                    "Version not found for dataset '%s' with hash or tag '%s'".formatted(datasetId, hashOrTag));
        });
    }

    /**
     * Calculate placeholder hash for version identification.
     * TODO OPIK-3015: Replace with actual content-based hash from dataset items.
     */
    private String calculatePlaceholderVersionHash(UUID datasetId) {
        try {
            // Use timestamp + dataset ID for unique hash per commit
            String input = datasetId.toString() + ":" + System.currentTimeMillis();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));

            // Convert to hex string (first 16 chars for display)
            StringBuilder hexString = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                String hex = Integer.toHexString(0xff & hashBytes[i]);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }

            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
}
