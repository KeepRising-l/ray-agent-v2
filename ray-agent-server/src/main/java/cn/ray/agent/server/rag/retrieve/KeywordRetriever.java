package cn.ray.agent.server.rag.retrieve;

import cn.ray.agent.server.rag.embedding.EmbeddingProfile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/** 个人小数据集的字面匹配：英文/编号与中文二元组，不冒称中文分词或BM25。 */
@Repository
public class KeywordRetriever {
    private static final Pattern WORDS = Pattern.compile("[a-z0-9]+(?:[-_][a-z0-9]+)*|[\\p{IsHan}]+");
    private final JdbcTemplate jdbc;

    public KeywordRetriever(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 先保留包含数字的标识符，避免长中文问题把真正的编号挤出有限关键词预算。 */
    public static List<String> terms(String question) {
        var normalized = Normalizer.normalize(question, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        var identifiers = new LinkedHashSet<String>();
        var words = new LinkedHashSet<String>();
        var matcher = WORDS.matcher(normalized);
        while (matcher.find()) {
            String value = matcher.group();
            if (value.matches(".*[0-9].*")) identifiers.add(value);
            else if (value.matches("[\\p{IsHan}]+")) {
                int[] points = value.codePoints().toArray();
                for (int i = 0; i + 1 < points.length; i++) words.add(new String(points, i, 2));
            } else if (value.length() > 1) words.add(value);
        }
        identifiers.addAll(words);
        return identifiers.stream().filter(value -> value.length() <= 100).limit(24).toList();
    }

    public List<RetrievedChunk> search(RetrievalQuery query, EmbeddingProfile profile, float[] vector) {
        profile.validate(vector);
        var terms = terms(query.question());
        if (terms.isEmpty()) return List.of();
        String literalRank = String.join("+", terms.stream().map(term ->
                "CASE WHEN strpos(lower(content),?)>0 THEN " + (term.matches(".*[0-9].*") ? 8 : 1) + " ELSE 0 END").toList());
        String sql = "WITH eligible AS MATERIALIZED (SELECT c.*,d.kb_id,d.id AS document_id,d.name,v.version,x.embedding"
                + AuthorizedChunkSql.from(query.filter()) + "), ranked AS (SELECT *, (" + literalRank + ") AS lexical_rank "
                + "FROM eligible) SELECT *,1-(embedding OPERATOR(public.<=>) CAST(? AS public.vector)) AS score "
                + "FROM ranked WHERE lexical_rank>0 ORDER BY lexical_rank DESC,id LIMIT ?";
        var args = AuthorizedChunkSql.args(query.filter(), profile);
        args.addAll(terms);
        args.add(IntStream.range(0, vector.length).mapToObj(i -> Float.toString(vector[i])).collect(java.util.stream.Collectors.joining(",", "[", "]")));
        args.add(query.limit());
        // score仍保留该片段真实的余弦相似度，字面排序和RRF值不伪装成相似度。
        return jdbc.query(sql, (r, n) -> new RetrievedChunk(r.getString("id"), r.getString("kb_id"), r.getString("document_id"),
                r.getString("name"), r.getString("version_id"), r.getInt("version"), r.getString("content"), (Integer) r.getObject("page"),
                r.getInt("paragraph"), r.getString("heading"), r.getInt("start_offset"), r.getInt("end_offset"), r.getDouble("score")), args.toArray())
                .stream().filter(hit -> query.minimumScore() == null || hit.score() >= query.minimumScore()).toList();
    }
}
