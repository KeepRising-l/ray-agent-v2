/** 只生成有限的文本块；由Vue文本节点转义，不把模型输出作为HTML执行。 */
export function messageBlocks(content = '') {
  const lines = content.replaceAll('\r\n', '\n').split('\n')
  const blocks = []
  let paragraph = []
  const flush = () => { if (paragraph.length) { blocks.push({ type: 'paragraph', text: paragraph.join('\n') }); paragraph = [] } }
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]
    if (/^```/.test(line)) {
      flush()
      const language = line.slice(3).trim().slice(0, 24)
      const code = []
      while (++i < lines.length && !/^```/.test(lines[i])) code.push(lines[i])
      blocks.push({ type: 'code', language, text: code.join('\n') })
    } else if (!line.trim()) flush()
    else if (/^#{1,4}\s/.test(line)) {
      flush(); const heading = line.match(/^(#{1,4})\s+(.*)$/)
      blocks.push({ type: 'heading', level: Math.min(heading[1].length + 1, 5), text: heading[2] })
    } else if (/^\s*(?:[-*]|\d+\.)\s+/.test(line)) {
      flush()
      const ordered = /^\s*\d+\./.test(line)
      const expression = ordered ? /^\s*\d+\.\s+(.*)$/ : /^\s*[-*]\s+(.*)$/
      const items = [line.match(expression)[1]]
      while (i+1 < lines.length && expression.test(lines[i+1])) items.push(lines[++i].match(expression)[1])
      blocks.push({ type: ordered ? 'ordered' : 'list', items })
    } else if (/^>\s?/.test(line)) {
      flush(); blocks.push({ type: 'quote', text: line.replace(/^>\s?/, '') })
    } else paragraph.push(line)
  }
  flush()
  return blocks
}
export function inlineParts(text = '') {
  return text.split(/(\[(?:K|W)\d+\]|\*\*[^*\n]+\*\*|`[^`\n]+`)/g).filter(Boolean).map(value => {
    if (/^\[(K|W)\d+\]$/.test(value)) return { type: 'source', text: value, id: value.slice(1,-1) }
    if (value.startsWith('**')) return { type: 'strong', text: value.slice(2,-2) }
    if (value.startsWith('`')) return { type: 'code', text: value.slice(1,-1) }
    return { type: 'text', text: value }
  })
}
