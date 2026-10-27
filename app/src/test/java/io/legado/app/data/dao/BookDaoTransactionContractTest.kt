package io.legado.app.data.dao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 契约测试：books 表的多行列表读取必须走事务。
 *
 * 跨 CursorWindow 的列表读取在补窗时会重跑查询，
 * 读取期间 books 被并发修改（更新检查翻转 type 标志、进度写 durChapterTime、删书）会让补窗结果少于 Cursor 记录的行数，
 * 抛 IllegalStateException("Couldn't read row ...")，见 docs/http-tts-compatibility-audit-2026-10-17.md 的设备复现。
 * @Transaction 让每次发射在稳定快照里完成读取。
 *
 * Room 注解是 CLASS 保留、运行时反射拿不到，故直接扫 DAO 源码做契约检查。
 * LIMIT 10 的预览查询单窗装得下，不跨窗补窗，豁免本契约。
 */
class BookDaoTransactionContractTest {

    private val declaration = Regex("""^    fun (\w+)\(.*\): Flow<List<""")

    @Test
    fun unboundedListFlowsAreTransactional() {
        val daoFile = File("src/main/java/io/legado/app/data/dao/BookDao.kt")
        assertTrue("找不到 BookDao.kt（测试工作目录不对？）: ${daoFile.absolutePath}", daoFile.isFile)

        val (examined, offenders) = findOffenders(daoFile.readLines())

        assertTrue("BookDao 列表 Flow 解析数异常（检查器失效？）: $examined", examined >= 30)
        assertTrue(
            "无 LIMIT 的列表 Flow 缺少 @Transaction（跨 CursorWindow 读取会竞争）: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun checkerDetectsMissingTransactionAndExemptsLimit() {
        val (examined, offenders) = findOffenders(
            listOf(
                "    @Query(\"select * from t\")",
                "    fun bad(): Flow<List<Book>>",
                "    @Query(\"select * from t LIMIT 10\")",
                "    @Transaction",
                "    fun preview(): Flow<List<Book>>",
                "    @Query(\"select * from t where x = 1\")",
                "    @Transaction",
                "    fun good(): Flow<List<Book>>",
            )
        )

        assertEquals(3, examined)
        assertEquals(listOf("bad"), offenders)
    }

    /**
     * 解析 DAO 源码行：@Query 后若紧跟列表 Flow 声明，则必须带 @Transaction，
     * 除非 @Query 含 LIMIT（单窗读取不跨窗补窗）。返回（解析到的声明数, 违规方法名）。
     */
    private fun findOffenders(lines: List<String>): Pair<Int, List<String>> {
        val offenders = mutableListOf<String>()
        var examined = 0
        var i = 0
        while (i < lines.size) {
            if (!lines[i].trimStart().startsWith("@Query")) {
                i++
                continue
            }
            // @Query(...) 参数可能跨行；SQL 括号自身成对，用计数找结尾
            val query = StringBuilder(lines[i])
            var j = i
            while (query.count { it == '(' } > query.count { it == ')' } && j + 1 < lines.size) {
                j++
                query.append('\n').append(lines[j])
            }
            var hasTransaction = false
            var k = j + 1
            while (k < lines.size && lines[k].trimStart().startsWith("@")) {
                if (lines[k].trim() == "@Transaction") hasTransaction = true
                k++
            }
            val decl = lines.getOrNull(k)?.let { declaration.find(it) }
            if (decl != null) {
                examined++
                if (!query.contains("limit", ignoreCase = true) && !hasTransaction) {
                    offenders += decl.groupValues[1]
                }
            }
            i = k + 1
        }
        return examined to offenders
    }
}
