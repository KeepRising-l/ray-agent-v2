package cn.ray.agent.server.knowledge;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/** 原始文件只存本机受忽略目录；用户文件名从不参与物理路径。 */
@Component
public final class DocumentFileStore {
    private final Path root;

    public DocumentFileStore(@Value("${ray.knowledge.storage-root:./.ray-data/documents}") String directory) {
        try {
            root = Path.of(directory).toAbsolutePath().normalize();
            Files.createDirectories(root);
            if (Files.isSymbolicLink(root)) {
                throw new IllegalArgumentException("storage root must not be a symbolic link");
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("无法建立文档存储目录");
        }
    }

    public String save(byte[] bytes) throws java.io.IOException {
        String key = UUID.randomUUID() + ".bin";
        Path pending = root.resolve(key + ".part");
        try {
            Files.write(pending, bytes, StandardOpenOption.CREATE_NEW);
            try {
                Files.move(pending, path(key), StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(pending, path(key));
            }
            return key;
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    public byte[] read(String key, String expectedHash) throws java.io.IOException {
        Path file = path(key);
        if (Files.size(file) > 5 * 1024 * 1024) {
            throw new java.io.IOException("file exceeds limit");
        }
        byte[] bytes;
        try (var input = Files.newInputStream(file)) {
            bytes = input.readNBytes(5 * 1024 * 1024 + 1);
        }
        if (bytes.length > 5 * 1024 * 1024) {
            throw new java.io.IOException("file exceeds limit");
        }
        if (!sha256(bytes).equals(expectedHash)) {
            throw new java.io.IOException("file checksum mismatch");
        }
        return bytes;
    }

    public void discard(String key) throws java.io.IOException {
        Files.deleteIfExists(path(key));
    }

    private Path path(String key) {
        if (!key.matches("[a-f0-9-]{36}\\.bin")) {
            throw new IllegalArgumentException("invalid storage key");
        }
        Path file = root.resolve(key).normalize();
        if (!file.getParent().equals(root) || Files.isSymbolicLink(file)) {
            throw new IllegalArgumentException("invalid storage path");
        }
        return file;
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
