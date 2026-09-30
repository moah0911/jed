package dev.jed.app.ui

import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * A small Markdown reader for preview: headings, bold, italic, inline code,
 * tasks, bullets, `[[wikilinks]]` (tappable) and `#tags`. The editor stays
 * plain text; this is the reading face, not a second editor.
 */
@Composable
fun MarkdownPreview(
    text: String,
    modifier: Modifier = Modifier,
    onWikilink: (String) -> Unit = {},
) {
    val styled = remember(text) { renderMarkdown(text) }
    ClickableText(
        text = styled,
        modifier = modifier,
        style = MaterialTheme.typography.bodyLarge.copy(
            color = MaterialTheme.colorScheme.onSurface,
            lineHeight = 24.sp,
        ),
        onClick = { offset ->
            styled.getStringAnnotations("link", offset, offset).firstOrNull()?.let {
                onWikilink(it.item)
            }
        },
    )
}

private fun renderMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    val codeBg = Color(0xFF808080)
    for (raw in text.lines()) {
        val line = raw
        val start = length
        when {
            line.startsWith("### ") -> {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 18.sp))
                appendInline(line.removePrefix("### "))
                pop()
            }
            line.startsWith("## ") -> {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp))
                appendInline(line.removePrefix("## "))
                pop()
            }
            line.startsWith("# ") -> {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp))
                appendInline(line.removePrefix("# "))
                pop()
            }
            line.trimStart().startsWith("- [ ] ") || line.trimStart().startsWith("- [x] ") -> {
                val done = line.trimStart().startsWith("- [x] ")
                append(if (done) "☑ " else "☐ ")
                appendInline(line.trimStart().removePrefix("- [ ] ").removePrefix("- [x] "))
            }
            line.trimStart().startsWith("- ") -> {
                append("• ")
                appendInline(line.trimStart().removePrefix("- "))
            }
            line.trimStart().matches(Regex("\\d+\\. .*")) -> {
                append(line.trimStart().substringBefore(" ") + " ")
                appendInline(line.trimStart().substringAfter(" "))
            }
            line.trimStart().startsWith("> ") -> {
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                appendInline(line.trimStart().removePrefix("> "))
                pop()
            }
            line.trimStart().startsWith("```") -> {
                pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = codeBg))
                append(line.trim())
                pop()
            }
            else -> appendInline(line)
        }
        if (start == 0 && length == start) append("")
        append("\n")
    }
}

private fun AnnotatedString.Builder.appendInline(line: String) {
    var i = 0
    val link = Regex("\\[\\[([^\\]]+)]]")
    val bold = Regex("\\*\\*(.+?)\\*\\*")
    val italic = Regex("(?<!\\*)\\*([^\\*\\n]+)\\*(?!\\*)")
    val code = Regex("`([^`\\n]+)`")
    val tag = Regex("(^|[\\s(\\[])#([A-Za-z0-9][A-Za-z0-9_/-]*)")
    // Walk wikilinks first so taps resolve; inner styles apply after.
    val parts = link.split(line)
    val links = link.findAll(line).toList()
    parts.forEachIndexed { idx, part ->
        appendStyled(part, bold, italic, code, tag)
        if (idx < links.size) {
            val title = links[idx].groupValues[1].substringBefore("#").trim()
            pushStringAnnotation("link", title)
            pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Color(0xFF2E7D32)))
            append("[[${links[idx].groupValues[1]}]]")
            pop()
            pop()
        }
    }
}

private fun AnnotatedString.Builder.appendStyled(
    part: String,
    bold: Regex,
    italic: Regex,
    code: Regex,
    tag: Regex,
) {
    // Inline code first (contents are literal), then bold/italic/tags.
    var rest = part
    var m = code.find(rest)
    while (m != null) {
        appendMixed(rest.substring(0, m.range.first), bold, italic, tag)
        pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp))
        append(m.groupValues[1])
        pop()
        rest = rest.substring(m.range.last + 1)
        m = code.find(rest)
    }
    appendMixed(rest, bold, italic, tag)
}

private fun AnnotatedString.Builder.appendMixed(
    s: String,
    bold: Regex,
    italic: Regex,
    tag: Regex,
) {
    // Bold runs; inside each, italic and tags.
    val bparts = bold.split(s)
    val bms = bold.findAll(s).toList()
    bparts.forEachIndexed { idx, p ->
        appendItalicTag(p, italic, tag, false)
        if (idx < bms.size) {
            pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
            appendItalicTag(bms[idx].groupValues[1], italic, tag, true)
            pop()
        }
    }
}

private fun AnnotatedString.Builder.appendItalicTag(s: String, italic: Regex, tag: Regex, inBold: Boolean) {
    val iparts = italic.split(s)
    val ims = italic.findAll(s).toList()
    iparts.forEachIndexed { idx, p ->
        appendTags(p, tag)
        if (idx < ims.size) {
            pushStyle(SpanStyle(fontStyle = FontStyle.Italic, fontWeight = if (inBold) FontWeight.Bold else null))
            appendTags(ims[idx].groupValues[1], tag)
            pop()
        }
    }
}

private fun AnnotatedString.Builder.appendTags(s: String, tag: Regex) {
    var last = 0
    for (m in tag.findAll(s)) {
        append(s.substring(last, m.range.first))
        pushStyle(SpanStyle(color = Color(0xFF1565C0)))
        append(m.value.trimStart(' ', '(', '['))
        pop()
        last = m.range.last + 1
    }
    append(s.substring(last))
}
