@file:Keep

package io.legado.app.lib.cronet

import androidx.annotation.Keep
import io.legado.app.constant.AppLog
import io.legado.app.help.http.CookieManager.cookieJarHeader
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.DebugLog
import io.legado.app.utils.externalCache
import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.Request
import org.chromium.net.CronetEngine
import org.chromium.net.CronetEngine.Builder.HTTP_CACHE_DISK
import org.chromium.net.UploadDataProvider
import org.chromium.net.UrlRequest
import org.json.JSONObject
import splitties.init.appCtx

internal const val BUFFER_SIZE = 32 * 1024

val cronetEngine: CronetEngine? by lazy {
    CronetLoader.preDownload()
    // 同步安装 so 库
    try {
        CronetLoader.installSync()
    } catch (e: Throwable) {
        DebugLog.d("CronetHelper", "installSync failed: ${e.message}")
    }
    // 第一优先：通过反射直接构造 NativeCronetEngineBuilderImpl，绕过 Provider 发现机制
    if (CronetLoader.install()) {
        try {
            val implClass = Class.forName("org.chromium.net.impl.NativeCronetEngineBuilderImpl")
            val ctor = implClass.getConstructor(android.content.Context::class.java)
            val builder = ctor.newInstance(appCtx)
            // setLibraryLoader: 遍历方法查找，兼容不同 Cronet 版本的参数类型
            try {
                val loaderMethod = implClass.methods.firstOrNull {
                    it.name == "setLibraryLoader" && it.parameterCount == 1
                }
                if (loaderMethod != null) {
                    loaderMethod.invoke(builder, CronetLoader)
                } else {
                    DebugLog.d("CronetHelper", "setLibraryLoader method not found")
                }
            } catch (e: Throwable) {
                DebugLog.d("CronetHelper", "setLibraryLoader failed: ${e.message}")
            }
            try {
                implClass.getMethod("setStoragePath", String::class.java)
                    .invoke(builder, appCtx.externalCache.absolutePath)
            } catch (e: Throwable) {
                DebugLog.d("CronetHelper", "setStoragePath failed: ${e.message}")
            }
            try {
                implClass.getMethod("enableHttpCache", Int::class.java, Long::class.java)
                    .invoke(builder, HTTP_CACHE_DISK, (1024 * 1024 * 50).toLong())
            } catch (e: Throwable) {
                DebugLog.d("CronetHelper", "enableHttpCache failed: ${e.message}")
            }
            try {
                implClass.getMethod("enableQuic", Boolean::class.java)
                    .invoke(builder, true)
            } catch (e: Throwable) {
                DebugLog.d("CronetHelper", "enableQuic failed: ${e.message}")
            }
            try {
                implClass.getMethod("enableHttp2", Boolean::class.java)
                    .invoke(builder, true)
            } catch (e: Throwable) {
                DebugLog.d("CronetHelper", "enableHttp2 failed: ${e.message}")
            }
            try {
                implClass.getMethod(
                    "enablePublicKeyPinningBypassForLocalTrustAnchors",
                    Boolean::class.java
                ).invoke(builder, true)
            } catch (e: Throwable) {
                DebugLog.d("CronetHelper", "enablePublicKeyPinning failed: ${e.message}")
            }
            try {
                implClass.getMethod("enableBrotli", Boolean::class.java)
                    .invoke(builder, true)
            } catch (e: Throwable) {
                DebugLog.d("CronetHelper", "enableBrotli failed: ${e.message}")
            }
            try {
                implClass.getMethod("setExperimentalOptions", String::class.java)
                    .invoke(builder, options)
            } catch (e: Throwable) {
                DebugLog.d("CronetHelper", "setExperimentalOptions failed: ${e.message}")
            }
            val engine = implClass.getMethod("build").invoke(builder)
            if (engine is CronetEngine) {
                DebugLog.d("CronetHelper", "NativeCronetEngine created: ${engine.versionString}")
                return@lazy engine
            }
        } catch (e: Throwable) {
            AppLog.put("NativeCronetEngine反射构造失败", e)
        }
    }
    // 第二优先：ServiceLoader 路径（系统/GMS Cronet）
    try {
        val stdBuilder = CronetEngine.Builder(appCtx).apply {
            enableHttpCache(HTTP_CACHE_DISK, (1024 * 1024 * 50).toLong())
            enableQuic(true)
            enableHttp2(true)
            enablePublicKeyPinningBypassForLocalTrustAnchors(true)
            enableBrotli(true)
        }
        val engine = stdBuilder.build()
        DebugLog.d("CronetHelper", "ServiceLoader CronetEngine created: ${engine.versionString}")
        return@lazy engine
    } catch (e: Throwable) {
        AppLog.put("ServiceLoader CronetEngine 构造失败", e)
    }
    null
}

val options by lazy {
    val options = JSONObject()

    //设置域名映射规则
    //MAP hostname ip,MAP hostname ip
//    val host = JSONObject()
//    host.put("host_resolver_rules","")
//    options.put("HostResolverRules", host)

    //启用DnsHttpsSvcb更容易迁移到http3
    val dnsSvcb = JSONObject()
    dnsSvcb.put("enable", true)
    dnsSvcb.put("enable_insecure", true)
    dnsSvcb.put("use_alpn", true)
    options.put("UseDnsHttpsSvcb", dnsSvcb)

    options.put("AsyncDNS", JSONObject("{'enable':true}"))


    options.toString()
}

fun buildRequest(request: Request, callback: UrlRequest.Callback): UrlRequest? {
    val url = request.url.toString()
    val headers: Headers = request.headers
    val requestBody = request.body
    return cronetEngine?.newUrlRequestBuilder(
        url,
        callback,
        okHttpClient.dispatcher.executorService
    )?.apply {
        setHttpMethod(request.method)//设置
        allowDirectExecutor()
        headers.forEachIndexed { index, _ ->
            if (headers.name(index) == cookieJarHeader) return@forEachIndexed
            addHeader(headers.name(index), headers.value(index))
        }
        if (requestBody != null) {
            val contentType: MediaType? = requestBody.contentType()
            if (contentType != null) {
                addHeader("Content-Type", contentType.toString())
            } else {
                addHeader("Content-Type", "text/plain")
            }
            val provider: UploadDataProvider = if (requestBody.contentLength() > BUFFER_SIZE) {
                LargeBodyUploadProvider(requestBody, okHttpClient.dispatcher.executorService)
            } else {
                BodyUploadProvider(requestBody)
            }
            // Don't use provider.use{} — Cronet manages the provider lifecycle internally.
            // Calling close() prematurely would break async upload reads.
            this.setUploadDataProvider(provider, okHttpClient.dispatcher.executorService)

        }

    }?.build()

}
