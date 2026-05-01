package com.comet.opik.api.resources.v1.events.tools;

import com.google.inject.AbstractModule;
import com.google.inject.multibindings.Multibinder;

/**
 * Binds the LLM-as-judge tool executors via a Guice multibinder so
 * {@link ToolRegistry} can collect them as a {@code Set<ToolExecutor>}.
 * Adding a new tool is a single {@code addBinding().to(...)} line.
 */
public class ToolsModule extends AbstractModule {

    @Override
    protected void configure() {
        Multibinder<ToolExecutor> tools = Multibinder.newSetBinder(binder(), ToolExecutor.class);
        tools.addBinding().to(GetTraceSpansTool.class);
        tools.addBinding().to(ReadTool.class);
        tools.addBinding().to(JqTool.class);
        tools.addBinding().to(SearchTool.class);
    }
}