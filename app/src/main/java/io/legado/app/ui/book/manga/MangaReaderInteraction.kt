package io.legado.app.ui.book.manga

internal fun mangaChapterLoadingItem(
    chapterIndex: Int,
    message: String,
    failed: Boolean,
) = MangaReaderItemUi.ChapterEdge(
    key = "chapter-placeholder:$chapterIndex:${if (failed) "failed" else "loading"}",
    message = message,
    loading = !failed,
    retryChapterIndex = chapterIndex.takeIf { failed },
    fullScreen = true,
)

internal fun mangaClickRegionIndex(
    x: Float,
    y: Float,
    width: Int,
    height: Int,
): Int {
    val column = (x / (width.coerceAtLeast(1) / 3f)).toInt().coerceIn(0, 2)
    val row = (y / (height.coerceAtLeast(1) / 3f)).toInt().coerceIn(0, 2)
    return row * 3 + column
}

internal fun mangaClickActionAt(
    clickActions: List<Int>,
    x: Float,
    y: Float,
    width: Int,
    height: Int,
): Int = clickActions.getOrNull(mangaClickRegionIndex(x, y, width, height)) ?: 0

internal fun nextMangaClickAction(action: Int): Int = when (action) {
    -1 -> 0
    0 -> 1
    1 -> 2
    2 -> 3
    3 -> 4
    else -> -1
}

/**
 * 找 [direction] 方向上的下一个「真实页」：跳过 ChapterTransition/ChapterEdge 等非页项。
 *
 * 直接以相邻下标做 PageStep 时，目标落在过渡页上既无法推进，scrollRequest 也因过渡页非
 * Page 而永远不清除（Pager 卡在章节边界）。保证步进永远落在实际页面。
 */
internal fun nextPageItemIndex(
    items: List<MangaReaderItemUi>,
    currentIndex: Int,
    direction: Int,
): Int? {
    var index = currentIndex + direction
    while (index in items.indices) {
        if (items[index] is MangaReaderItemUi.Page) return index
        index += direction
    }
    return null
}

/**
 * Chooses the page that represents a Webtoon viewport.
 *
 * Normally the last visible page is a useful reading-progress anchor. At a chapter boundary it
 * is not: a number of short pages from the adjacent chapter can be visible at once, so using the
 * last one promotes the session directly to that chapter's final visible page. Once the current
 * chapter has left the viewport, use the first visible page when entering a later chapter and
 * the last visible page when entering an earlier one. Those are the pages adjacent to the
 * boundary in reading order.
 */
internal fun mangaWebtoonFocusedPageIndex(
    items: List<MangaReaderItemUi>,
    visibleItemIndices: List<Int>,
    currentChapterIndex: Int,
): Int? {
    val visiblePages = visibleItemIndices.mapNotNull { index ->
        (items.getOrNull(index) as? MangaReaderItemUi.Page)?.let { index to it }
    }
    if (visiblePages.isEmpty()) return null
    if (visiblePages.any { (_, page) -> page.chapterIndex == currentChapterIndex }) {
        return visiblePages.last().first
    }
    return when {
        visiblePages.first().second.chapterIndex > currentChapterIndex -> visiblePages.first().first
        visiblePages.last().second.chapterIndex < currentChapterIndex -> visiblePages.last().first
        else -> visiblePages.last().first
    }
}

/** A programmatic position restore must not be overwritten by the old viewport's first callback. */
internal fun acceptsMangaVisibleItem(
    requestedItemIndex: Int?,
    reportedItemIndex: Int,
): Boolean = requestedItemIndex == null || requestedItemIndex == reportedItemIndex

internal fun shouldExposeMangaPages(currentChapterFinished: Boolean): Boolean =
    currentChapterFinished

enum class MangaChapterSwitch { NONE, NEXT, PREVIOUS }

/**
 * 决定聚焦页是否触发章节切换：只认「用户当前聚焦的那一页」所在章节。
 *
 * 焦点页由阅读器上报（Webtoon 为视口底部页、Pager 为当前页/跨页），因此不依赖
 * 「本章是否仍可见」这类在窗口重建/定位期间会闪断的启发式，避免误切。
 */
internal fun mangaChapterSwitchDecision(
    currentChapterIndex: Int,
    visibleChapterIndex: Int,
    currentChapterVisible: Boolean,
): MangaChapterSwitch = when {
    currentChapterIndex < visibleChapterIndex ->
        if (currentChapterVisible) MangaChapterSwitch.NONE else MangaChapterSwitch.NEXT

    currentChapterIndex > visibleChapterIndex ->
        if (currentChapterVisible) MangaChapterSwitch.NONE else MangaChapterSwitch.PREVIOUS

    else -> MangaChapterSwitch.NONE
}

internal fun shouldForceMangaChapterPosition(
    hasPages: Boolean,
    isLoading: Boolean,
    currentBookUrl: String,
    targetBookUrl: String,
    pendingExplicitChapterIndex: Int?,
    targetChapterIndex: Int,
): Boolean =
    !hasPages || isLoading || currentBookUrl != targetBookUrl ||
        pendingExplicitChapterIndex == targetChapterIndex
