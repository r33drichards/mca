package com.btone.c.util;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

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
        // Force 0644 so the agent (running as a different user, e.g. ubuntu)
        // can read the bridge config. Without this, systemd's default umask
        // 077 produces 0600 on the temp file and the perms carry through the
        // atomic move — leaving every restart broken until someone chmods.
        Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-r--r--");
        FileAttribute<Set<PosixFilePermission>> attr =
                PosixFilePermissions.asFileAttribute(perms);
        Path tmp;
        try {
            tmp = Files.createTempFile(path.getParent(), "btone-", ".tmp", attr);
        } catch (UnsupportedOperationException e) {
            // Non-POSIX filesystem (e.g. Windows). Fall back to default perms.
            tmp = Files.createTempFile(path.getParent(), "btone-", ".tmp");
        }
        Files.writeString(tmp, M.writeValueAsString(this));
        Files.move(tmp, path,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        // Re-set perms after atomic move in case ATOMIC_MOVE preserved
        // older perms from a previous file at `path`.
        try {
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystem.
        }
    }

    public static ConnectionConfig readFrom(Path path) throws IOException {
        return M.readValue(Files.readString(path), ConnectionConfig.class);
    }
}
