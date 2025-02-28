package com.comet.opik.extensions;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.TestInstancePostProcessor;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;

public class DropwizardAppExtensionProvider
        implements
            TestInstancePostProcessor,
            BeforeAllCallback,
            AfterAllCallback,
            BeforeEachCallback,
            AfterEachCallback,
            ParameterResolver {

    private TestDropwizardAppExtension app;

    @Override
    public void postProcessTestInstance(Object testInstance, ExtensionContext context) throws Exception {
        Optional<Map.Entry<Field, Object>> fieldAndInstance = ReflectionUtils.findAnnotatedField(testInstance,
                RegisterApp.class);

        if (fieldAndInstance.isPresent()) {
            Field field = fieldAndInstance.get().getKey();
            field.setAccessible(true);

            if (field.get(fieldAndInstance.get().getValue()) instanceof TestDropwizardAppExtension currentApp) {
                this.app = currentApp;
                return;
            }
        }

        throw new IllegalStateException(
                "Field of type TestDropwizardAppExtension with @RegisterApp annotation not found");
    }

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        app.afterAll(context);
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        app.afterEach(context);
    }

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        app.beforeAll(context);
    }

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        app.beforeEach(context);
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        return app.supportsParameter(parameterContext, extensionContext);
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        return app.resolveParameter(parameterContext, extensionContext);
    }
}
