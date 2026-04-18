package com.btone.c.util;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Connection bootstrap file written by the mod, read by the agent (or wrapping
 * runner). Atomic write so a partial read can never see a half-written token.
 */
public record ConnectionConfig(
        @JsonProperty("port") int port,
        @JsonProperty("token") String token,
        @JsonProperty("version") String version) {
    private static final ObjectMapper M = new ObjectMapper();

    @JsonCreator
    public ConnectionConfig {}

    public void writeTo(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Path tmp = Files.createTempFile(path.getParent(), "btone-", ".tmp");
        Files.writeString(tmp, M.writeValueAsString(this));
        Files.move(tmp, path,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }

    public static ConnectionConfig readFrom(Path path) throws IOException {
        return M.readValue(Files.readString(path), ConnectionConfig.class);
    }
}
