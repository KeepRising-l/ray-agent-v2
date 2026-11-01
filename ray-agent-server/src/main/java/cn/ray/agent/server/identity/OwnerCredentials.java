package cn.ray.agent.server.identity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

/** 单个个人账号。首次启动生成随机口令，只写本机忽略目录；重启读取同一个BCrypt散列，不重置账号。 */
@Component
public final class OwnerCredentials {
    private final String username;
    private final String passwordHash;

    public OwnerCredentials(@Value("${ray.auth.credentials-file:./.ray-data/identity/owner.properties}") String filename) throws java.io.IOException {
        Path file = Path.of(filename).toAbsolutePath().normalize();
        Files.createDirectories(file.getParent());
        if (!Files.exists(file)) {
            byte[] random = new byte[24];
            new SecureRandom().nextBytes(random);
            String password = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
            var properties = new Properties();
            properties.setProperty("username", "owner");
            properties.setProperty("password-hash", new BCryptPasswordEncoder().encode(password));
            // CREATE_NEW避免误覆盖已有账号。初始口令单独保存，不进日志、API或数据库。
            try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
                properties.store(writer, "Local owner account - never commit");
            }
            Files.writeString(file.resolveSibling("initial-login.txt"), "username=owner\npassword=" + password + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        }
        var properties = new Properties();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { properties.load(reader); }
        username = properties.getProperty("username", "");
        passwordHash = properties.getProperty("password-hash", "");
        if (!username.matches("[a-zA-Z0-9_-]{1,40}") || !passwordHash.startsWith("$2")) {
            throw new IllegalStateException("本机个人账号配置无效");
        }
    }

    public String username() { return username; }
    public String passwordHash() { return passwordHash; }
}
