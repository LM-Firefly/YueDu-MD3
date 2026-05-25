package io.legado.app.web

import android.graphics.Bitmap
import io.ktor.http.*
import io.ktor.server.request.path
import io.ktor.http.content.*
import io.ktor.serialization.gson.*
import io.ktor.server.application.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.receiveText
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.util.pipeline.PipelineContext
import io.ktor.util.toMap
import io.legado.app.api.ReturnData
import io.legado.app.api.controller.BookController
import io.legado.app.api.controller.BookSourceController
import io.legado.app.api.controller.ReplaceRuleController
import io.legado.app.api.controller.RssSourceController
import io.legado.app.model.localBook.LocalBook
import io.legado.app.service.WebService
import io.legado.app.utils.LogUtils
import io.legado.app.utils.stackTraceStr
import io.legado.app.web.socket.BookSearchWebSocket
import io.legado.app.web.socket.BookSourceDebugWebSocket
import io.legado.app.web.socket.RssSourceDebugWebSocket
import io.legado.app.web.utils.AssetsWeb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.ByteArrayOutputStream
import java.io.File

class KtorServer(private val port: Int) {
    private var server: io.ktor.server.engine.ApplicationEngine? = null
    private var wsServer: io.ktor.server.engine.ApplicationEngine? = null
    private val assetsWeb = AssetsWeb("web")

    fun start() {
        val s = embeddedServer(CIO, port = port) {
            install(ContentNegotiation) {
                gson {
                    setLenient()
                }
            }
            install(CORS) {
                anyHost()
                allowHeader(HttpHeaders.ContentType)
                allowMethod(HttpMethod.Options)
                allowMethod(HttpMethod.Post)
                allowMethod(HttpMethod.Get)
            }

            routing {
                post("/saveBookSource") { call.handlePost { BookSourceController.saveSource(it) } }
                post("/saveBookSources") { call.handlePost { BookSourceController.saveSources(it) } }
                post("/deleteBookSources") { call.handlePost { BookSourceController.deleteSources(it) } }
                post("/saveBook") { call.handlePost { BookController.saveBook(it) } }
                post("/deleteBook") { call.handlePost { BookController.deleteBook(it) } }
                post("/saveBookProgress") { call.handlePost { BookController.saveBookProgress(it) } }
                post("/addLocalBook") {
                    WebService.serve()
                    val multipart = call.receiveMultipart()
                    var fileName: String? = null
                    val tempFile = File(appCtx.cacheDir, "upload_${System.currentTimeMillis()}")
                    try {
                        multipart.forEachPart { part ->
                            when (part) {
                                is PartData.FormItem -> {
                                    if (part.name == "fileName") fileName = part.value
                                }
                                is PartData.FileItem -> {
                                    part.streamProvider().use { input ->
                                        tempFile.outputStream().use { output ->
                                            input.copyTo(output)
                                        }
                                    }
                                    if (fileName == null) {
                                        fileName = part.originalFileName
                                    }
                                }
                                else -> {}
                            }
                            part.dispose()
                        }
                        if (fileName != null && tempFile.exists()) {
                            val returnData = withContext(Dispatchers.IO) {
                                kotlin.runCatching {
                                    tempFile.inputStream().use {
                                        val uri = LocalBook.saveBookFile(it, fileName!!)
                                        LocalBook.importFile(uri)
                                        ReturnData().setData(true)
                                    }
                                }.getOrElse {
                                    LogUtils.e(TAG, it.stackTraceStr)
                                    ReturnData().setErrorMsg(it.localizedMessage ?: "Save book error")
                                }
                            }
                            call.respondReturnData(returnData)
                        } else {
                            call.respond(HttpStatusCode.BadRequest, "Missing fileName or fileData")
                        }
                    } finally {
                        if (tempFile.exists()) tempFile.delete()
                    }
                }
                post("/saveReadConfig") { call.handlePost { BookController.saveWebReadConfig(it) } }
                post("/saveRssSource") { call.handlePost { RssSourceController.saveSource(it) } }
                post("/saveRssSources") { call.handlePost { RssSourceController.saveSources(it) } }
                post("/deleteRssSources") { call.handlePost { RssSourceController.deleteSources(it) } }
                post("/saveReplaceRule") { call.handlePost { ReplaceRuleController.saveRule(it) } }
                post("/deleteReplaceRule") { call.handlePost { ReplaceRuleController.delete(it) } }
                post("/testReplaceRule") { call.handlePost { ReplaceRuleController.testRule(it) } }

                get("/getBookSource") { call.handleGet { BookSourceController.getSource(it) } }
                get("/getBookSources") { call.handleGet { BookSourceController.sources } }
                get("/getBookshelf") { call.handleGet { BookController.bookshelf } }
                get("/getChapterList") { call.handleGet { BookController.getChapterList(it) } }
                get("/refreshToc") { call.handleGet { BookController.refreshToc(it) } }
                get("/getBookContent") { call.handleGet { BookController.getBookContent(it) } }
                get("/cover") { call.handleGet { BookController.getCover(it) } }
                get("/image") { call.handleGet { BookController.getImg(it) } }
                get("/getReadConfig") { call.handleGet { BookController.getWebReadConfig() } }
                get("/getRssSource") { call.handleGet { RssSourceController.getSource(it) } }
                get("/getRssSources") { call.handleGet { RssSourceController.sources } }
                get("/getReplaceRules") { call.handleGet { ReplaceRuleController.allRules } }

                get("/{...}") {
                    WebService.serve()
                    var uri = call.request.path().toString()
                    if (uri.endsWith("/")) uri += "index.html"
                    val inputStream = assetsWeb.getInputStream(uri)
                    if (inputStream != null) {
                        inputStream.use { stream ->
                            call.respondBytes(stream.readBytes(), ContentType.parse(assetsWeb.getMimeType(uri)))
                        }
                    } else {
                        call.respond(HttpStatusCode.NotFound)
                    }
                }
            }
        }
        s.start(wait = false)
        server = s as io.ktor.server.engine.ApplicationEngine
    }

    fun startWebSocket(wsPort: Int) {
        val ws = embeddedServer(CIO, port = wsPort) {
            install(WebSockets)
            routing {
                webSocket("/bookSourceDebug") {
                    BookSourceDebugWebSocket(this).handle()
                }
                webSocket("/rssSourceDebug") {
                    RssSourceDebugWebSocket(this).handle()
                }
                webSocket("/searchBook") {
                    BookSearchWebSocket(this).handle()
                }
            }
        }
        ws.start(wait = false)
        wsServer = ws as io.ktor.server.engine.ApplicationEngine
    }

    fun stop() {
        server?.stop(1000, 1000)
        wsServer?.stop(1000, 1000)
    }

    private suspend fun ApplicationCall.handlePost(
        block: suspend (String?) -> ReturnData
    ) {
        WebService.serve()
        try {
            val postData = receiveText()
            val returnData = block(postData)
            respondReturnData(returnData)
        } catch (e: Exception) {
            LogUtils.e(TAG, e.stackTraceStr)
            respondText(e.message ?: "Unknown error")
        }
    }

    private suspend fun ApplicationCall.handleGet(
        block: (Map<String, List<String>>) -> ReturnData?
    ) {
        WebService.serve()
        try {
            val parameters = request.queryParameters.toMap()
            val returnData = block(parameters)
            if (returnData != null) {
                respondReturnData(returnData)
            } else {
                respond(HttpStatusCode.NotFound)
            }
        } catch (e: Exception) {
            LogUtils.e(TAG, e.stackTraceStr)
            respondText(e.message ?: "Unknown error")
        }
    }

    private suspend fun ApplicationCall.respondReturnData(returnData: ReturnData) {
        if (returnData.data is Bitmap) {
            val bitmap = returnData.data as Bitmap
            val outputStream = ByteArrayOutputStream()
            withContext(Dispatchers.IO) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            }
            respondBytes(outputStream.toByteArray(), ContentType.Image.PNG)
        } else {
            respond(returnData)
        }
    }

    companion object {
        private const val TAG = "KtorServer"
    }
}
