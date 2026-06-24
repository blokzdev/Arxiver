package dev.blokz.arxiver.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.blokz.arxiver.ui.theme.Spacing
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListBlock
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser
import org.commonmark.node.Text as MdTextNode

/**
 * Renders GitHub-flavored markdown as native Compose (Phase P-Rich R0). Parsing uses
 * the pure-JVM commonmark library (offline, no Compose-version coupling); this walks the
 * AST and emits Compose — paragraphs/inlines as [AnnotatedString], headings, lists,
 * GFM tables, code, block quotes, rules. Fenced code blocks keep their info string (the
 * routing hook for the later math/diagram tiers); for now they render as code boxes.
 *
 * Inline builders are pure (theme colors passed in via [MdPalette]) so they're unit-
 * testable. When [onCitationClick] is supplied, `[n]` references render as tappable
 * links (R0 citations). No remote resources are loaded — render is fully offline.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
    onCitationClick: ((Int) -> Unit)? = null,
) {
    val document = remember(markdown) { MARKDOWN_PARSER.parse(markdown) }
    val palette =
        MdPalette(
            link = MaterialTheme.colorScheme.primary,
            citation = MaterialTheme.colorScheme.primary,
            codeBackground = MaterialTheme.colorScheme.surfaceVariant,
        )
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Blocks(document, color, palette, onCitationClick)
    }
}

/** Theme colors threaded into the pure inline builders. */
data class MdPalette(val link: Color, val citation: Color, val codeBackground: Color)

private val MARKDOWN_PARSER: Parser =
    Parser.builder().extensions(listOf(TablesExtension.create())).build()

private val CITATION_REF = Regex("""\[(\d{1,3})]""")

@Composable
private fun Blocks(
    parent: Node,
    color: Color,
    palette: MdPalette,
    onCite: ((Int) -> Unit)?,
) {
    var node = parent.firstChild
    while (node != null) {
        Block(node, color, palette, onCite)
        node = node.next
    }
}

@Composable
private fun Block(
    node: Node,
    color: Color,
    palette: MdPalette,
    onCite: ((Int) -> Unit)?,
) {
    when (node) {
        is Heading ->
            Text(
                inlineString(node, palette, onCite),
                style =
                    when (node.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        3 -> MaterialTheme.typography.titleSmall
                        else -> MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                    },
                color = color,
            )

        is Paragraph ->
            Text(inlineString(node, palette, onCite), style = MaterialTheme.typography.bodyMedium, color = color)

        is BulletList -> ListBlockUi(node, ordered = false, color, palette, onCite)
        is OrderedList -> ListBlockUi(node, ordered = true, color, palette, onCite)

        is BlockQuote ->
            Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                Box(
                    modifier =
                        Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.outlineVariant),
                )
                Column(modifier = Modifier.padding(start = Spacing.sm)) {
                    Blocks(node, color.copy(alpha = 0.8f), palette, onCite)
                }
            }

        is FencedCodeBlock -> CodeBox(node.literal.trimEnd('\n'), palette, color)
        is IndentedCodeBlock -> CodeBox(node.literal.trimEnd('\n'), palette, color)
        is ThematicBreak -> HorizontalDivider()
        is TableBlock -> MarkdownTable(node, color, palette, onCite)

        else -> {
            // Unknown/unsupported block: render any inline text it carries, never crash.
            val text = inlineString(node, palette, onCite)
            if (text.isNotEmpty()) {
                Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
            }
        }
    }
}

@Composable
private fun ListBlockUi(
    list: ListBlock,
    ordered: Boolean,
    color: Color,
    palette: MdPalette,
    onCite: ((Int) -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        var item = list.firstChild
        var index = 1
        while (item != null) {
            Row {
                Text(
                    text = if (ordered) "$index." else "•",
                    style = MaterialTheme.typography.bodyMedium,
                    color = color,
                    modifier = Modifier.width(if (ordered) 24.dp else 16.dp),
                )
                Column(modifier = Modifier.weight(1f)) { Blocks(item, color, palette, onCite) }
            }
            item = item.next
            index++
        }
    }
}

@Composable
private fun CodeBox(
    code: String,
    palette: MdPalette,
    color: Color,
) {
    Text(
        text = code,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = color,
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .background(palette.codeBackground, RoundedCornerShape(Spacing.xs))
                .padding(Spacing.sm),
    )
}

@Composable
private fun MarkdownTable(
    table: TableBlock,
    color: Color,
    palette: MdPalette,
    onCite: ((Int) -> Unit)?,
) {
    val border = MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .border(1.dp, border, RoundedCornerShape(Spacing.xs)),
    ) {
        var section = table.firstChild
        while (section != null) {
            val header = section is TableHead
            var row = section.firstChild
            while (row != null) {
                if (row is TableRow) {
                    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                        var cell = row.firstChild
                        while (cell != null) {
                            if (cell is TableCell) {
                                Text(
                                    text = inlineString(cell, palette, onCite),
                                    style =
                                        if (header) {
                                            MaterialTheme.typography.labelLarge
                                        } else {
                                            MaterialTheme.typography.bodySmall
                                        },
                                    color = color,
                                    modifier = Modifier.weight(1f).padding(Spacing.sm),
                                )
                            }
                            cell = cell.next
                        }
                    }
                    if (section is TableHead || row.next != null) HorizontalDivider(color = border)
                }
                row = row.next
            }
            section = section.next
            if (section is TableBody) HorizontalDivider(color = border)
        }
    }
}

// --- pure inline builders (theme colors passed in, so unit-testable) ---

/**
 * The styled inline content of [parent] as an [AnnotatedString]. Citations are overlaid on
 * the *finished* text (not per text node) so a `[n]` split across inline nodes is still
 * caught; they become tappable links only when [onCite] is wired.
 */
internal fun inlineString(
    parent: Node,
    palette: MdPalette,
    onCite: ((Int) -> Unit)?,
): AnnotatedString {
    val base = buildAnnotatedString { appendInlines(parent, palette) }
    return if (onCite == null) base else withCitationLinks(base, palette, onCite)
}

internal fun withCitationLinks(
    base: AnnotatedString,
    palette: MdPalette,
    onCite: (Int) -> Unit,
): AnnotatedString =
    buildAnnotatedString {
        append(base)
        for (match in CITATION_REF.findAll(base.text)) {
            val n = match.groupValues[1].toInt()
            addLink(
                LinkAnnotation.Clickable(
                    tag = "cite:$n",
                    styles = TextLinkStyles(SpanStyle(color = palette.citation, fontWeight = FontWeight.Medium)),
                ) { onCite(n) },
                start = match.range.first,
                end = match.range.last + 1,
            )
        }
    }

private fun AnnotatedString.Builder.appendInlines(
    parent: Node,
    palette: MdPalette,
) {
    var node = parent.firstChild
    while (node != null) {
        when (node) {
            is MdTextNode -> append(node.literal)
            is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInlines(node, palette) }
            is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInlines(node, palette) }
            is Code ->
                withStyle(
                    SpanStyle(fontFamily = FontFamily.Monospace, background = palette.codeBackground),
                ) { append(node.literal) }
            is Link ->
                withLink(
                    LinkAnnotation.Url(
                        node.destination,
                        TextLinkStyles(SpanStyle(color = palette.link, textDecoration = TextDecoration.Underline)),
                    ),
                ) { appendInlines(node, palette) }
            is SoftLineBreak -> append(" ")
            is HardLineBreak -> append("\n")
            else -> appendInlines(node, palette)
        }
        node = node.next
    }
}
