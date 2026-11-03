package com.comet.opik.infrastructure.llm.gemini;

import com.comet.opik.domain.llm.langchain4j.OpikGeminiChatModel;
import dev.langchain4j.model.chat.ChatModel;
import lombok.experimental.UtilityClass;

@UtilityClass
class GeminiTestClients {

    // The module bakes Google's endpoint into its HTTP service and offers no way to change it after the build, and the
    // generator under test does the build. The service reads the field on every call, so swapping it is enough; the
    // request and its serialization stay the module's own.
    static <T> T pointedAt(T model, String baseUrl) {
        try {
            var target = model instanceof OpikGeminiChatModel wrapper ? delegateOf(wrapper) : model;
            var serviceField = target.getClass().getSuperclass().getDeclaredField("geminiService");
            serviceField.setAccessible(true);
            var service = serviceField.get(target);

            var baseUrlField = service.getClass().getDeclaredField("baseUrl");
            baseUrlField.setAccessible(true);
            baseUrlField.set(service, baseUrl);

            return model;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Could not point the Gemini model at the stub; the module's internals may have "
                    + "changed", e);
        }
    }

    private static ChatModel delegateOf(OpikGeminiChatModel wrapper) throws ReflectiveOperationException {
        var delegateField = OpikGeminiChatModel.class.getDeclaredField("delegate");
        delegateField.setAccessible(true);
        return (ChatModel) delegateField.get(wrapper);
    }
}
