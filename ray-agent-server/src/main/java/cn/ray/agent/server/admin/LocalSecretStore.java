package cn.ray.agent.server.admin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 密钥只落本机文件，数据库与API只持引用/是否已配置。空输入表示保留，清空须明确选择。 */
@Component
public class LocalSecretStore {
    private final Path file;
    private final Environment env;
    public LocalSecretStore(@Value("${ray.admin.secrets-file:./.ray-data/admin/secrets.properties}") String filename, Environment env) {
        this.file = Path.of(filename).toAbsolutePath().normalize(); this.env = env;
    }
    private Properties load() throws java.io.IOException {
        var data = new Properties();
        if (Files.exists(file)) try (var reader = Files.newBufferedReader(file,StandardCharsets.UTF_8)) { data.load(reader); }
        return data;
    }
    public synchronized String resolve(String reference) {
        if (reference == null || reference.isBlank()) return "";
        if (reference.startsWith("env:")) return env.getProperty(reference.substring(4),"");
        try { return load().getProperty(reference,""); }
        catch (java.io.IOException e) { throw new IllegalStateException("无法读取本机密钥文件"); }
    }
    public synchronized String replace(String old, String key, boolean clear) {
        if (clear) return "";
        if (key == null || key.isBlank()) return old;
        if (key.length()>2000 || key.contains("\n") || key.contains("\r")) throw new IllegalArgumentException("invalid credential");
        try {
            var data = load(); String id = "local:"+UUID.randomUUID(); data.setProperty(id,key);
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(),"secrets-",".tmp");
            try {
                try (var writer = Files.newBufferedWriter(temp,StandardCharsets.UTF_8)) { data.store(writer,"Local secrets - never commit"); }
                Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
            } finally { Files.deleteIfExists(temp); }
            return id;
        } catch (java.io.IOException e) { throw new IllegalStateException("无法保存本机密钥文件"); }
    }
}
