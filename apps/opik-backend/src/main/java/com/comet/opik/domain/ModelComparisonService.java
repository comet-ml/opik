package com.comet.opik.domain;

import com.comet.opik.api.ModelComparison;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.infrastructure.db.TransactionTemplate;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import jakarta.ws.rs.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Handle;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@Singleton
@RequiredArgsConstructor(onConstructor_ = @__({@Inject}))
@Slf4j
public class ModelComparisonService {

    private final @NonNull ModelComparisonDao modelComparisonDao;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull TransactionTemplate transactionTemplate;
    private final @NonNull ModelAnalysisService modelAnalysisService;

    public ModelComparison.ModelComparisonPage getModelComparisons(
            int page, 
            int size, 
            String sorting, 
            String search
    ) {
        return transactionTemplate.inTransaction(READ_ONLY, handle -> {
            var repository = handle.attach(ModelComparisonDao.class);
            return repository.findModelComparisons(page, size, sorting, search);
        });
    }

    public ModelComparison createModelComparison(ModelComparison request) {
        return transactionTemplate.inTransaction(WRITE, handle -> {
            var repository = handle.attach(ModelComparisonDao.class);
            
            var comparison = request.toBuilder()
                    .id(idGenerator.generate())
                    .createdAt(java.time.Instant.now())
                    .lastUpdatedAt(java.time.Instant.now())
                    .build();
            
            return repository.create(comparison);
        });
    }

    public ModelComparison getModelComparison(UUID id) {
        return transactionTemplate.inTransaction(READ_ONLY, handle -> {
            var repository = handle.attach(ModelComparisonDao.class);
            return repository.findById(id)
                    .orElseThrow(() -> new NotFoundException("Model comparison not found: '%s'".formatted(id)));
        });
    }

    public ModelComparison updateModelComparison(UUID id, ModelComparison request) {
        return transactionTemplate.inTransaction(WRITE, handle -> {
            var repository = handle.attach(ModelComparisonDao.class);
            
            var existingComparison = repository.findById(id)
                    .orElseThrow(() -> new NotFoundException("Model comparison not found: '%s'".formatted(id)));
            
            var updatedComparison = existingComparison.toBuilder()
                    .name(request.name())
                    .description(request.description())
                    .modelIds(request.modelIds())
                    .datasetNames(request.datasetNames())
                    .filters(request.filters())
                    .lastUpdatedAt(java.time.Instant.now())
                    .build();
            
            return repository.update(updatedComparison);
        });
    }

    public void deleteModelComparison(UUID id) {
        transactionTemplate.inTransaction(WRITE, handle -> {
            var repository = handle.attach(ModelComparisonDao.class);
            
            var existingComparison = repository.findById(id)
                    .orElseThrow(() -> new NotFoundException("Model comparison not found: '%s'".formatted(id)));
            
            repository.delete(id);
            return null;
        });
    }

    public ModelComparison runAnalysis(UUID id) {
        return transactionTemplate.inTransaction(WRITE, handle -> {
            var repository = handle.attach(ModelComparisonDao.class);
            
            var comparison = repository.findById(id)
                    .orElseThrow(() -> new NotFoundException("Model comparison not found: '%s'".formatted(id)));
            
            log.info("Running analysis for model comparison: '{}' with models: '{}'", 
                    id, comparison.modelIds());
            
            // Run comprehensive analysis
            var results = modelAnalysisService.analyzeModels(
                    comparison.modelIds(),
                    comparison.datasetNames(),
                    comparison.filters()
            );
            
            var updatedComparison = comparison.toBuilder()
                    .results(results)
                    .lastUpdatedAt(java.time.Instant.now())
                    .build();
            
            return repository.update(updatedComparison);
        });
    }

    public List<Map<String, Object>> getAvailableModels() {
        return transactionTemplate.inTransaction(READ_ONLY, handle -> {
            var repository = handle.attach(ModelComparisonDao.class);
            return repository.getAvailableModels();
        });
    }

    public List<Map<String, Object>> getAvailableDatasets() {
        return transactionTemplate.inTransaction(READ_ONLY, handle -> {
            var repository = handle.attach(ModelComparisonDao.class);
            return repository.getAvailableDatasets();
        });
    }

    public Map<String, Object> exportResults(UUID id, String format) {
        return transactionTemplate.inTransaction(READ_ONLY, handle -> {
            var repository = handle.attach(ModelComparisonDao.class);
            
            var comparison = repository.findById(id)
                    .orElseThrow(() -> new NotFoundException("Model comparison not found: '%s'".formatted(id)));
            
            return modelAnalysisService.exportResults(comparison, format);
        });
    }
}