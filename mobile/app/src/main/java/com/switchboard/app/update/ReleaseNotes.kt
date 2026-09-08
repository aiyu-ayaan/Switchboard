package com.switchboard.app.update

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * A GitHub release body, drawn rather than printed.
 *
 * The body is `### Features` with a list under it, `**bold**`, and the odd
 * fenced block. Putting that on screen exactly as it arrives — hashes,
 * asterisks and backticks included — is the shape that makes somebody stop
 * reading the thing telling them what changed.
 *
 * Deliberately small: headings at one size, bullets with the marker in a gutter
 * of its own, fenced code on its own ground, and the inline marks. A changelog
 * is three of those shapes and never a document.
 */
sealed interface NoteBlock {
    data class Heading(val text: String) : NoteBlock
    data class Bullet(val text: String) : NoteBlock
    data class Code(val text: String) : NoteBlock
    data class Paragraph(val text: String) : NoteBlock
}

private val HEADING = Regex("""^\s*#{1,6}\s+(.*)$""")
private val BULLET = Regex("""^\s*(?:[-*+]|\d+\.)\s+(.*)$""")
private val FENCE = Regex("""^\s*```""")

/** The rule under a table header, which would otherwise draw as a row of dashes. */
private val TABLE_RULE = Regex("""^\s*\|?[\s:|-]*-[\s:|-]*\|[\s:|-]*$""")

fun parseNotes(markdown: String): List<NoteBlock> {
    val blocks = mutableListOf<NoteBlock>()
    val paragraph = mutableListOf<String>()
    var fence: MutableList<String>? = null

    fun flush() {
        val text = paragraph.joinToString(" ").trim()
        if (text.isNotEmpty()) blocks += NoteBlock.Paragraph(text)
        paragraph.clear()
    }

    markdown.replace("\r\n", "\n").split("\n").forEach { line ->
        when {
            FENCE.containsMatchIn(line) -> {
                val open = fence
                if (open != null) {
                    blocks += NoteBlock.Code(open.joinToString("\n"))
                    fence = null
                } else {
                    flush()
                    fence = mutableListOf()
                }
            }

            fence != null -> fence?.add(line)

            HEADING.matchEntire(line) != null -> {
                flush()
                blocks += NoteBlock.Heading(HEADING.matchEntire(line)!!.groupValues[1].trim())
            }

            BULLET.matchEntire(line) != null -> {
                flush()
                blocks += NoteBlock.Bullet(BULLET.matchEntire(line)!!.groupValues[1].trim())
            }

            TABLE_RULE.matchEntire(line) != null -> Unit

            line.isBlank() -> flush()

            else -> paragraph += line.trim()
        }
    }

    fence?.let { blocks += NoteBlock.Code(it.joinToString("\n")) }
    flush()
    return blocks
}

private val INLINE = Regex("""(\*\*[^*]+\*\*|`[^`]+`|_[^_]+_)""")

/** `**bold**`, `` `code` `` and `_italic_`, which is all a release note uses. */
fun inlineMarkup(text: String): AnnotatedString = buildAnnotatedString {
    var last = 0
    INLINE.findAll(text).forEach { match ->
        if (match.range.first > last) append(text.substring(last, match.range.first))
        val token = match.value
        val style = when {
            token.startsWith("**") -> SpanStyle(fontWeight = FontWeight.SemiBold)
            token.startsWith("`") -> SpanStyle(fontFamily = FontFamily.Monospace)
            else -> SpanStyle(fontStyle = FontStyle.Italic)
        }
        val inner = if (token.startsWith("**")) token.drop(2).dropLast(2) else token.drop(1).dropLast(1)
        withStyle(style) { append(inner) }
        last = match.range.last + 1
    }
    if (last < text.length) append(text.substring(last))
}

@Composable
fun ReleaseNotes(markdown: String, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { parseNotes(markdown) }
    if (blocks.isEmpty()) {
        Text(
            "This release came with no notes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is NoteBlock.Heading -> Text(
                    block.text,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 6.dp),
                )

                is NoteBlock.Bullet -> Row {
                    Text(
                        "•",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(16.dp),
                    )
                    Text(
                        inlineMarkup(block.text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is NoteBlock.Code -> Text(
                    block.text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(8.dp),
                        )
                        .horizontalScroll(rememberScrollState())
                        .padding(PaddingValues(horizontal = 10.dp, vertical = 8.dp)),
                )

                is NoteBlock.Paragraph -> Text(
                    inlineMarkup(block.text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
