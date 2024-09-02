package com.comet.opik.infrastructure.json;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.TreeNode;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Provider;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;

@Provider
@Produces(MediaType.APPLICATION_OCTET_STREAM)
public class JsonNodeMessageBodyWriter implements MessageBodyWriter<TreeNode> {

    @Override
    public boolean isWriteable(Class aClass, Type type, Annotation[] annotations, MediaType mediaType) {
        return TreeNode.class.isAssignableFrom(aClass);
    }

    @Override
    public void writeTo(TreeNode treeNode, Class aClass, Type type, Annotation[] annotations, MediaType mediaType,
            MultivaluedMap multivaluedMap, OutputStream outputStream) throws IOException, WebApplicationException {
        outputStream.write(JsonUtils.writeValueAsString(treeNode).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public long getSize(TreeNode objectNode, Class<?> type, Type genericType, Annotation[] annotations,
            MediaType mediaType) {
        return JsonUtils.writeValueAsString(objectNode).getBytes(StandardCharsets.UTF_8).length;
    }
}
