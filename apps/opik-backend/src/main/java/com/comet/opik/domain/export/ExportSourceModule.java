package com.comet.opik.domain.export;

import com.google.inject.AbstractModule;
import com.google.inject.multibindings.Multibinder;

/**
 * The one place an exportable surface gets registered.
 *
 * <p>A new export type is an {@link com.comet.opik.api.ExportParams} record, an {@link ExportSource}, and one
 * {@code addBinding().to(...)} line below. The schema, the job record, the processor and the REST layer are
 * untouched.</p>
 */
public class ExportSourceModule extends AbstractModule {

    @Override
    protected void configure() {
        Multibinder<ExportSource> sources = Multibinder.newSetBinder(binder(), ExportSource.class);

        sources.addBinding().to(DatasetExportSource.class);
        sources.addBinding().to(ExperimentItemsExportSource.class);
    }
}
