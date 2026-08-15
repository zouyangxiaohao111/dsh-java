package dev.dsh.cordis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ScaffoldTest {
    static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void jacksonAndJunitWork() throws Exception {
        JsonNode node = MAPPER.readTree("{\"a\":1}");
        assertThat(node.get("a").asInt()).isEqualTo(1);
    }
}
