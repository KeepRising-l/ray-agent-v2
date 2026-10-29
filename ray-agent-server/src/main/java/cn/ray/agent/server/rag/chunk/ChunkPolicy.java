package cn.ray.agent.server.rag.chunk;

/** 参数按 Unicode 码点计数；首版取 800 字符、120 字符重叠，不宣称是普遍最优参数。 */
public record ChunkPolicy(int maxCharacters, int overlapCharacters) {
    public static final ChunkPolicy DEFAULT = new ChunkPolicy(800, 120);
    public ChunkPolicy {
        if (maxCharacters < 50 || maxCharacters > 2000 || overlapCharacters < 0 || overlapCharacters >= maxCharacters / 2) {
            throw new IllegalArgumentException("invalid chunk policy");
        }
    }

    public String id() {
        return "paragraph-chars-" + maxCharacters + "-overlap-" + overlapCharacters + "-v1";
    }
}
