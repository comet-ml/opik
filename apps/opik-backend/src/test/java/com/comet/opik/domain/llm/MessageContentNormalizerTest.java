package com.comet.opik.domain.llm;

import dev.langchain4j.model.openai.internal.chat.ImageUrl;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class MessageContentNormalizerTest {

    @Test
    void renderImagePlaceholderUsesDelimitedFormat() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        ImageUrl imageUrl = new ImageUrl();
        imageUrl.setUrl("https://example.com/image.png");

        Method method = MessageContentNormalizer.class
                .getDeclaredMethod("renderImagePlaceholder", ImageUrl.class);
        method.setAccessible(true);

        String placeholder = (String) method.invoke(null, imageUrl);

        assertThat(placeholder)
                .isEqualTo("<<<image>>>https://example.com/image.png<<</image>>>");
    }

    @Test
    void renderImagePlaceholderSkipsBlankUrls() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        ImageUrl imageUrl = new ImageUrl();
        imageUrl.setUrl("   ");

        Method method = MessageContentNormalizer.class
                .getDeclaredMethod("renderImagePlaceholder", ImageUrl.class);
        method.setAccessible(true);

        String placeholder = (String) method.invoke(null, imageUrl);

        assertThat(placeholder).isEmpty();
    }
}
