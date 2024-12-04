package com.comet.opik.infrastructure.job;

import io.dropwizard.core.setup.Environment;
import io.dropwizard.jobs.Job;
import io.dropwizard.jobs.annotations.Every;
import io.dropwizard.jobs.annotations.On;
import io.dropwizard.jobs.annotations.OnApplicationStart;
import io.dropwizard.jobs.annotations.OnApplicationStop;
import ru.vyarus.dropwizard.guice.debug.util.RenderUtils;
import ru.vyarus.dropwizard.guice.module.installer.FeatureInstaller;
import ru.vyarus.dropwizard.guice.module.installer.install.InstanceInstaller;
import ru.vyarus.dropwizard.guice.module.installer.util.FeatureUtils;
import ru.vyarus.dropwizard.guice.module.installer.util.Reporter;

public class JobGuiceyInstaller implements FeatureInstaller, InstanceInstaller<Job> {

    private final Reporter reporter = new Reporter(JobGuiceyInstaller.class, "jobs =");

    @Override
    public boolean matches(Class<?> type) {
        return FeatureUtils.hasAnnotation(type, Every.class)
                || FeatureUtils.hasAnnotation(type, On.class)
                || FeatureUtils.hasAnnotation(type, OnApplicationStart.class)
                || FeatureUtils.hasAnnotation(type, OnApplicationStop.class);
    }

    @Override
    public void report() {
        reporter.report();
    }

    @Override
    public void install(Environment environment, Job job) {
        reporter.line(RenderUtils.renderClassLine(FeatureUtils.getInstanceClass(job)));
        environment.jersey().register(job);
    }

}
