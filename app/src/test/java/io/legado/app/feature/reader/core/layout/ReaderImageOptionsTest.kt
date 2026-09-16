package io.legado.app.feature.reader.core.layout

import io.legado.app.feature.reader.core.model.ReaderElement
import io.legado.app.feature.reader.core.model.ReaderTextStyle
import io.legado.app.feature.reader.core.source.ReaderChapterSourceParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderImageOptionsTest {
    private val shaper = ReaderTextShaper { text ->
        GlyphClusters(text.map(Char::toString), List(text.length) { 10f })
    }
    private val baseStyle = ReaderChapterMeasureStyle(
        ReaderTextStyle(0, 10f), ReaderTextStyle(0, 10f), 0,
        ReaderTextAlignment.START, ReaderTextAlignment.START,
        imageAvailableWidthPx = 100f,
    )
    private val config = ReaderPaginationConfig(0, "", 100, 200, 0f, 0f, 0f, 0f, 10f, 8f)

    private suspend fun measure(
        options: ReaderImageOptions,
        style: ReaderChapterMeasureStyle = baseStyle,
        dimensions: ReaderImageDimensions = ReaderImageDimensions(100f, 50f),
    ): ReaderChapterMeasureResult.Success {
        val source = ReaderChapterSourceParser.parse(
            0, "", listOf("<img src=\"image\">"), false, false,
        )
        return ReaderChapterBlockMeasurer(
            shaper, shaper, { dimensions }, imageOptionsResolver = { options },
        ).measure(source, style) as ReaderChapterMeasureResult.Success
    }

    @Test fun globalTextModeKeepsLargeImagesInline() = runBlocking {
        val result = measure(ReaderImageOptions(), baseStyle.copy(imageLayoutMode = ReaderImageLayoutMode.INLINE))
        assertTrue(result.blocks.single() is ReaderMeasuredBlock.InlineParagraph)
    }

    @Test
    fun inlineImageStaysWithinParagraphLineNotTextArea() = runBlocking {
        val result = measure(
            ReaderImageOptions(ReaderImageLayoutMode.INLINE, action = "run()"),
            baseStyle.copy(
                imageLayoutMode = ReaderImageLayoutMode.INLINE,
                imageAvailableWidthPx = 100f,
            ),
            ReaderImageDimensions(100f, 50f),
        )
        val paragraph = result.blocks.single() as ReaderMeasuredBlock.InlineParagraph
        val image = paragraph.items.single() as ReaderMeasuredInlineItem.Image
        // 文字嵌入图只受当前行高约束（10px 字号），不允许放大到铺满可视文字区。
        assertEquals(20f, image.widthPx, 0f)
        assertEquals(10f, image.heightPx, 0f)
        assertEquals("run()", image.action)
    }

    @Test
    fun requestedFractionAppliesWithinLineCap() = runBlocking {
        val result = measure(ReaderImageOptions(requestedWidthFraction = .5f))
        val paragraph = result.blocks.single() as ReaderMeasuredBlock.InlineParagraph
        val image = paragraph.items.single() as ReaderMeasuredInlineItem.Image
        assertEquals(20f, image.widthPx, 0f)
        assertEquals(10f, image.heightPx, 0f)
    }

    @Test
    fun perImageFullStyleOverridesGlobalInlineMode() = runBlocking {
        val result = measure(
            ReaderImageOptions(ReaderImageLayoutMode.FULL_WIDTH, action = "run()"),
            baseStyle.copy(imageLayoutMode = ReaderImageLayoutMode.INLINE),
            ReaderImageDimensions(20f, 10f),
        )
        val block = result.blocks.single() as ReaderMeasuredBlock.Image
        assertEquals(ReaderImageScaleMode.FIT_WIDTH, block.scaleMode)
        assertEquals("run()", block.action)
        val image = ReaderPaginator.paginateBlocks(result.blocks, config)
            .single().elements.single() as ReaderElement.Image
        assertEquals(100f, image.bounds.width, 0f)
        assertEquals(50f, image.bounds.height, 0f)
        assertEquals("run()", image.action)
    }

    @Test fun rightAlignmentAndSinglePageOverridesReachPaginator() = runBlocking {
        val right = measure(ReaderImageOptions(
            layoutMode = ReaderImageLayoutMode.STANDALONE,
            horizontalAlignment = ReaderTextAlignment.END,
        )).blocks.single() as ReaderMeasuredBlock.Image
        val rightImage = ReaderPaginator.paginateBlocks(listOf(right), config.copy(viewportWidthPx = 140))
            .single().elements.single() as ReaderElement.Image
        assertEquals(40f, rightImage.bounds.left, 0f)

        val single = measure(
            ReaderImageOptions(ReaderImageLayoutMode.SINGLE_PAGE),
            baseStyle.copy(imageLayoutMode = ReaderImageLayoutMode.INLINE),
        ).blocks.single() as ReaderMeasuredBlock.Image
        assertTrue(single.pageBreakBefore)
        assertTrue(single.pageBreakAfter)
        assertEquals(ReaderImageScaleMode.FIT_PAGE, single.scaleMode)
    }
}
