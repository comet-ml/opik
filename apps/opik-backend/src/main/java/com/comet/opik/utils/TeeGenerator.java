package com.comet.opik.utils;

import com.fasterxml.jackson.core.Base64Variant;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.SerializableString;
import com.fasterxml.jackson.core.util.JsonGeneratorDelegate;
import lombok.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Forwards every write to two generators, so one walk of a value writes it twice: the full payload JSON into the
 * delegate and its slim copy into {@code second}. Context, codec and feature queries answer from the delegate.
 *
 * <p>Built on {@link JsonGeneratorDelegate} with {@code delegateCopyMethods} off, so {@code writeObject},
 * {@code writeTree}, {@code writePOJO} and {@code copyCurrent*} are broken into the token writes below and reach
 * both sides. Every other write method of the delegate is overridden here: one left out would write only the full
 * side, which {@code TeeGeneratorTest} guards against. Closing it closes only {@code second}, as the delegate may
 * be a generator the caller still writes to.
 */
class TeeGenerator extends JsonGeneratorDelegate {

    private final JsonGenerator second;

    TeeGenerator(@NonNull JsonGenerator first, @NonNull JsonGenerator second) {
        super(first, false);
        this.second = second;
    }

    @Override
    public void assignCurrentValue(Object value) {
        delegate.assignCurrentValue(value);
        second.assignCurrentValue(value);
    }

    @Deprecated
    @Override
    public void setCurrentValue(Object value) {
        assignCurrentValue(value);
    }

    @Override
    public void writeStartArray() throws IOException {
        delegate.writeStartArray();
        second.writeStartArray();
    }

    @Deprecated
    @Override
    public void writeStartArray(int size) throws IOException {
        delegate.writeStartArray(size);
        second.writeStartArray(size);
    }

    @Override
    public void writeStartArray(Object forValue) throws IOException {
        delegate.writeStartArray(forValue);
        second.writeStartArray(forValue);
    }

    @Override
    public void writeStartArray(Object forValue, int size) throws IOException {
        delegate.writeStartArray(forValue, size);
        second.writeStartArray(forValue, size);
    }

    @Override
    public void writeEndArray() throws IOException {
        delegate.writeEndArray();
        second.writeEndArray();
    }

    @Override
    public void writeStartObject() throws IOException {
        delegate.writeStartObject();
        second.writeStartObject();
    }

    @Override
    public void writeStartObject(Object forValue) throws IOException {
        delegate.writeStartObject(forValue);
        second.writeStartObject(forValue);
    }

    @Override
    public void writeStartObject(Object forValue, int size) throws IOException {
        delegate.writeStartObject(forValue, size);
        second.writeStartObject(forValue, size);
    }

    @Override
    public void writeEndObject() throws IOException {
        delegate.writeEndObject();
        second.writeEndObject();
    }

    @Override
    public void writeFieldName(String name) throws IOException {
        delegate.writeFieldName(name);
        second.writeFieldName(name);
    }

    @Override
    public void writeFieldName(SerializableString name) throws IOException {
        delegate.writeFieldName(name);
        second.writeFieldName(name);
    }

    @Override
    public void writeFieldId(long id) throws IOException {
        delegate.writeFieldId(id);
        second.writeFieldId(id);
    }

    @Override
    public void writeArray(int[] array, int offset, int length) throws IOException {
        delegate.writeArray(array, offset, length);
        second.writeArray(array, offset, length);
    }

    @Override
    public void writeArray(long[] array, int offset, int length) throws IOException {
        delegate.writeArray(array, offset, length);
        second.writeArray(array, offset, length);
    }

    @Override
    public void writeArray(double[] array, int offset, int length) throws IOException {
        delegate.writeArray(array, offset, length);
        second.writeArray(array, offset, length);
    }

    @Override
    public void writeArray(String[] array, int offset, int length) throws IOException {
        delegate.writeArray(array, offset, length);
        second.writeArray(array, offset, length);
    }

    @Override
    public void writeString(String text) throws IOException {
        delegate.writeString(text);
        second.writeString(text);
    }

    /** A reader can be read once, so it is read into a string that both sides write. */
    @Override
    public void writeString(Reader reader, int length) throws IOException {
        var text = new StringBuilder(Math.max(length, 16));
        var buffer = new char[4096];
        int remaining = length < 0 ? Integer.MAX_VALUE : length;
        int read;
        while (remaining > 0 && (read = reader.read(buffer, 0, Math.min(buffer.length, remaining))) >= 0) {
            text.append(buffer, 0, read);
            remaining -= read;
        }
        writeString(text.toString());
    }

    @Override
    public void writeString(char[] text, int offset, int length) throws IOException {
        delegate.writeString(text, offset, length);
        second.writeString(text, offset, length);
    }

    @Override
    public void writeString(SerializableString text) throws IOException {
        delegate.writeString(text);
        second.writeString(text);
    }

    @Override
    public void writeRawUTF8String(byte[] text, int offset, int length) throws IOException {
        delegate.writeRawUTF8String(text, offset, length);
        second.writeRawUTF8String(text, offset, length);
    }

    @Override
    public void writeUTF8String(byte[] text, int offset, int length) throws IOException {
        delegate.writeUTF8String(text, offset, length);
        second.writeUTF8String(text, offset, length);
    }

    @Override
    public void writeRaw(String text) throws IOException {
        delegate.writeRaw(text);
        second.writeRaw(text);
    }

    @Override
    public void writeRaw(String text, int offset, int length) throws IOException {
        delegate.writeRaw(text, offset, length);
        second.writeRaw(text, offset, length);
    }

    @Override
    public void writeRaw(SerializableString raw) throws IOException {
        delegate.writeRaw(raw);
        second.writeRaw(raw);
    }

    @Override
    public void writeRaw(char[] text, int offset, int length) throws IOException {
        delegate.writeRaw(text, offset, length);
        second.writeRaw(text, offset, length);
    }

    @Override
    public void writeRaw(char c) throws IOException {
        delegate.writeRaw(c);
        second.writeRaw(c);
    }

    @Override
    public void writeRawValue(String text) throws IOException {
        delegate.writeRawValue(text);
        second.writeRawValue(text);
    }

    @Override
    public void writeRawValue(String text, int offset, int length) throws IOException {
        delegate.writeRawValue(text, offset, length);
        second.writeRawValue(text, offset, length);
    }

    @Override
    public void writeRawValue(char[] text, int offset, int length) throws IOException {
        delegate.writeRawValue(text, offset, length);
        second.writeRawValue(text, offset, length);
    }

    @Override
    public void writeBinary(Base64Variant variant, byte[] data, int offset, int length) throws IOException {
        delegate.writeBinary(variant, data, offset, length);
        second.writeBinary(variant, data, offset, length);
    }

    /** A stream can be read once, so it is read into an array that both sides write. */
    @Override
    public int writeBinary(Base64Variant variant, InputStream data, int length) throws IOException {
        byte[] bytes = length < 0 ? data.readAllBytes() : data.readNBytes(length);
        writeBinary(variant, bytes, 0, bytes.length);
        return bytes.length;
    }

    @Override
    public void writeNumber(short value) throws IOException {
        delegate.writeNumber(value);
        second.writeNumber(value);
    }

    @Override
    public void writeNumber(int value) throws IOException {
        delegate.writeNumber(value);
        second.writeNumber(value);
    }

    @Override
    public void writeNumber(long value) throws IOException {
        delegate.writeNumber(value);
        second.writeNumber(value);
    }

    @Override
    public void writeNumber(BigInteger value) throws IOException {
        delegate.writeNumber(value);
        second.writeNumber(value);
    }

    @Override
    public void writeNumber(double value) throws IOException {
        delegate.writeNumber(value);
        second.writeNumber(value);
    }

    @Override
    public void writeNumber(float value) throws IOException {
        delegate.writeNumber(value);
        second.writeNumber(value);
    }

    @Override
    public void writeNumber(BigDecimal value) throws IOException {
        delegate.writeNumber(value);
        second.writeNumber(value);
    }

    @Override
    public void writeNumber(String encodedValue) throws IOException {
        delegate.writeNumber(encodedValue);
        second.writeNumber(encodedValue);
    }

    @Override
    public void writeNumber(char[] encodedValue, int offset, int length) throws IOException {
        delegate.writeNumber(encodedValue, offset, length);
        second.writeNumber(encodedValue, offset, length);
    }

    @Override
    public void writeBoolean(boolean state) throws IOException {
        delegate.writeBoolean(state);
        second.writeBoolean(state);
    }

    @Override
    public void writeNull() throws IOException {
        delegate.writeNull();
        second.writeNull();
    }

    @Override
    public void writeOmittedField(String name) throws IOException {
        delegate.writeOmittedField(name);
        second.writeOmittedField(name);
    }

    @Override
    public void writeObjectId(Object id) throws IOException {
        delegate.writeObjectId(id);
        second.writeObjectId(id);
    }

    @Override
    public void writeObjectRef(Object reference) throws IOException {
        delegate.writeObjectRef(reference);
        second.writeObjectRef(reference);
    }

    @Override
    public void writeTypeId(Object id) throws IOException {
        delegate.writeTypeId(id);
        second.writeTypeId(id);
    }

    @Override
    public void writeEmbeddedObject(Object object) throws IOException {
        delegate.writeEmbeddedObject(object);
        second.writeEmbeddedObject(object);
    }

    @Override
    public void flush() throws IOException {
        delegate.flush();
        second.flush();
    }

    @Override
    public void close() throws IOException {
        second.close();
    }
}
