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
private const val TAG = "CronetHelper"
private const val CACHE_SIZE = 50L * 1024 * 1024

val cronetEngine: CronetEngine? by lazy {
    CronetLoader.preDownload()
    try {
        CronetLoader.installSync()
    } catch (e: Exception) {
        DebugLog.d(TAG, "installSync failed: ${e.message}")
    }
    createNativeEngine() ?: createStandardEngine()
}

val options by lazy {
    JSONObject().apply {
        // 启用DnsHttpsSvcb更容易迁移到http3
        put("UseDnsHttpsSvcb", JSONObject().apply {
            put("enable", true)
            put("enable_insecure", true)
            put("use_alpn", true)
        })
        put("AsyncDNS", JSONObject("{'enable':true}"))
    }.toString()
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
        setHttpMethod(request.method)
        allowDirectExecutor()
        headers.forEachIndexed { index, _ ->
            if (headers.name(index) == cookieJarHeader) return@forEachIndexed
            addHeader(headers.name(index), headers.value(index))
        }
        if (requestBody != null) {
            requestBody.contentType()?.let { addHeader("Content-Type", it.toString()) }
            val provider: UploadDataProvider = if (requestBody.contentLength() > BUFFER_SIZE) {
                LargeBodyUploadProvider(requestBody, okHttpClient.dispatcher.executorService)
            } else {
                BodyUploadProvider(requestBody)
            }
            // Don't use provider.use{} — Cronet manages the provider lifecycle internally.
            // Calling close() prematurely would break async upload reads.
            setUploadDataProvider(provider, okHttpClient.dispatcher.executorService)
        }
    }?.build()
}

/**
 * 第一优先：通过反射直接构造 NativeCronetEngineBuilderImpl，绕过 Provider 发现机制
 */
private fun createNativeEngine(): CronetEngine? {
    if (!CronetLoader.install()) return null
    return try {
        val implClass = Class.forName("org.chromium.net.impl.NativeCronetEngineBuilderImpl")
        val builder = implClass.getConstructor(android.content.Context::class.java)
            .newInstance(appCtx)
        reflectInvoke(implClass, builder, "setLibraryLoader", CronetLoader, byName = true)
        reflectInvoke(implClass, builder, "setStoragePath", appCtx.externalCache.absolutePath)
        reflectInvoke(implClass, builder, "enableHttpCache", HTTP_CACHE_DISK, CACHE_SIZE)
        reflectInvoke(implClass, builder, "enableQuic", true)
        reflectInvoke(implClass, builder, "enablePublicKeyPinningBypassForLocalTrustAnchors", true)
        reflectInvoke(implClass, builder, "enableBrotli", true)
        reflectInvoke(implClass, builder, "setExperimentalOptions", options)
        val engine = implClass.getMethod("build").invoke(builder)
        if (engine is CronetEngine) {
            DebugLog.d(TAG, "NativeCronetEngine created: ${engine.versionString}")
            engine
        } else null
    } catch (e: Exception) {
        AppLog.put("NativeCronetEngine反射构造失败", e)
        null
    }
}

/**
 * 第二优先：ServiceLoader 路径（系统/GMS Cronet）
 */
private fun createStandardEngine(): CronetEngine? {
    return try {
        val engine = CronetEngine.Builder(appCtx).apply {
            enableHttpCache(HTTP_CACHE_DISK, CACHE_SIZE)
            enableQuic(true)
            enablePublicKeyPinningBypassForLocalTrustAnchors(true)
            enableBrotli(true)
        }.build()
        DebugLog.d(TAG, "ServiceLoader CronetEngine created: ${engine.versionString}")
        engine
    } catch (e: Exception) {
        AppLog.put("ServiceLoader CronetEngine 构造失败", e)
        null
    }
}

/**
 * 反射调用指定方法，失败时静默降级。
 * [byName] = true 时按方法名模糊匹配（适用于参数类型不确定的场景）。
 */
private fun reflectInvoke(clazz: Class<*>, obj: Any, method: String, vararg args: Any, byName: Boolean = false) {
    try {
        val m = if (byName) {
            clazz.methods.firstOrNull { it.name == method && it.parameterCount == args.size }
                ?: return DebugLog.d(TAG, "$method not found")
        } else {
            clazz.getMethod(method, *args.map { toPrimitiveType(it) }.toTypedArray())
        }
        m.invoke(obj, *args)
    } catch (e: Exception) {
        DebugLog.d(TAG, "$method failed: ${e.message}")
    }
}

/** 装箱类型转基本类型，确保 getMethod 匹配正确的参数签名。 */
private fun toPrimitiveType(arg: Any): Class<*> = when (arg) {
    is Boolean -> Boolean::class.java
    is Int -> Int::class.java
    is Long -> Long::class.java
    is Float -> Float::class.java
    is Double -> Double::class.java
    else -> arg.javaClass
}
