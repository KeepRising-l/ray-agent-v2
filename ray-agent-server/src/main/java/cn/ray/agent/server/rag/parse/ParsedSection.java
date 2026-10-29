package cn.ray.agent.server.rag.parse;

/** 规范化后的一个段落；PDF 保留一基页码，文本保留一基段落号，标题供后续引用。 */
public record ParsedSection(String content, Integer page, int paragraph, String heading) { }
