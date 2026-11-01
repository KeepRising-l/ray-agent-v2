package cn.ray.agent.server.rag.retrieve;

import cn.ray.agent.server.rag.embedding.EmbeddingProfile;
import java.util.*;

/** 两条召回共用同一份范围过滤；关键词不能绕过owner、有效版本和当前向量空间。 */
public final class AuthorizedChunkSql {
    private AuthorizedChunkSql() { }

    public static String from(RetrievalFilter filter) {
        return " FROM ra_chunk_vector x JOIN ra_document_chunk c ON c.id=x.chunk_id "
                + "JOIN ra_document_version v ON v.id=c.version_id JOIN ra_document d ON d.id=v.document_id "
                + "JOIN ra_knowledge_base k ON k.id=d.kb_id WHERE d.owner_id=? AND k.owner_id=? "
                + "AND d.kb_id IN (" + String.join(",", Collections.nCopies(filter.knowledgeBaseIds().size(), "?")) + ") "
                + "AND d.deleted_at IS NULL AND d.active_version_id=v.id AND v.status='READY' "
                + "AND x.profile_id=? AND v.embedding_profile=? AND x.dimension=?";
    }

    public static ArrayList<Object> args(RetrievalFilter filter, EmbeddingProfile profile) {
        var values = new ArrayList<Object>();
        values.add(filter.ownerId());
        values.add(filter.ownerId());
        values.addAll(filter.knowledgeBaseIds());
        values.add(profile.id());
        values.add(profile.id());
        values.add(profile.dimension());
        return values;
    }
}
