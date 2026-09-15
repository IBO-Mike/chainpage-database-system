package edu.csu.chainpage.engine.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonCodecTest {

    private final JsonCodec codec = new JsonCodec();

    @Test
    void writesAndReadsTypedObject() {
        String json = codec.write(new Payload("student", 2));
        Payload payload = codec.read(json, Payload.class);

        assertEquals("student", payload.name());
        assertEquals(2, payload.count());
    }

    @Test
    void readsGenericObject() {
        Map<String, Object> object = codec.readObject("{\"kind\":\"SeqScan\"}");

        assertEquals("SeqScan", object.get("kind"));
    }

    @Test
    void wrapsInvalidJson() {
        assertThrows(JsonCodecException.class, () -> codec.read("{", Payload.class));
    }

    @Test
    void rejectsNullObjectMapper() {
        assertThrows(NullPointerException.class, () -> new JsonCodec((ObjectMapper) null));
    }

    @Test
    void normalizesIdentifiers() {
        assertEquals("student", codec.normalizeIdentifier("Student"));
        assertEquals("alice", codec.normalizeIdentifier("Alice"));
    }

    private record Payload(String name, int count) {
    }
}
