package com.downls10

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles user-initiated blob downloads.
 * The JavaScript bridge is attached only for the lifetime of an active transfer.
 * Each callback requires an unpredictable, app-generated transfer token and the
 * page must remain on the same HTTP(S) origin that created the blob URL.
 */
class BlobDownloadBridge(context: Context) {

    private class Transfer(
        val suggestedName: String?,
        val mimeHint: String?,
        val webView: WebView,
        val origin: String
    ) {
        @Volatile var sink: DownloadsRepository.LocalSink? = null
        var totalBytes: Long = 0L
        var receivedBytes: Long = 0L
    }

    private val appContext = context.applicationContext
    private val transfers = ConcurrentHashMap<String, Transfer>()
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val MAX_BLOB_BYTES = 512L * 1024L * 1024L
        private const val MAX_CHUNK_BYTES = 512 * 1024
        private const val MAX_ENCODED_CHUNK_CHARS = 700_000
        private const val TRANSFER_TIMEOUT_MS = 120_000L
    }

    private fun secureOrigin(rawUrl: String?): String? {
        if (rawUrl.isNullOrBlank()) return null
        return try {
            val uri = Uri.parse(rawUrl)
            val scheme = uri.scheme?.lowercase() ?: return null
            val host = uri.host?.lowercase() ?: return null
            if (scheme != "https" && scheme != "http") return null
            if (uri.userInfo != null) return null
            val port = uri.port
            val defaultPort = (scheme == "https" && port == 443) ||
                (scheme == "http" && port == 80) || port == -1
            scheme + "://" + host + if (defaultPort) "" else ":$port"
        } catch (_: Exception) {
            null
        }
    }

    private fun isCurrentOrigin(t: Transfer): Boolean =
        secureOrigin(t.webView.url) == t.origin

    fun start(webView: WebView, blobUrl: String, suggestedName: String?, mimeHint: String?) {
        val pageOrigin = secureOrigin(webView.url)
        val blobOrigin = try {
            val blobUri = Uri.parse(blobUrl)
            if (!blobUri.scheme.equals("blob", ignoreCase = true)) null
            else secureOrigin(blobUri.schemeSpecificPart)
        } catch (_: Exception) {
            null
        }

        // Reject opaque origins (blob:null/...) and blobs from another origin.
        if (pageOrigin == null || blobOrigin == null || pageOrigin != blobOrigin) {
            DownloadsRepository.showMessage(appContext, "تم رفض تنزيل blob لأن مصدره غير موثوق أو تغيّر.")
            return
        }

        // Avoid concurrent bridge sessions on the same WebView. This keeps the
        // temporary JavaScript interface and transfer state small and predictable.
        if (transfers.values.any { it.webView === webView }) {
            DownloadsRepository.showMessage(appContext, "يوجد تنزيل Blob قيد التنفيذ في هذه الصفحة.")
            return
        }

        val token = UUID.randomUUID().toString()
        val transfer = Transfer(suggestedName, mimeHint, webView, pageOrigin)
        transfers[token] = transfer
        webView.addJavascriptInterface(this, "DownLS10Blob")

        val js = """
            (function() {
                var token = ${JSONObject.quote(token)};
                var url = ${JSONObject.quote(blobUrl)};
                var bridge = window.DownLS10Blob;
                if (!bridge) return;
                var xhr = new XMLHttpRequest();
                xhr.open('GET', url, true);
                xhr.responseType = 'blob';
                xhr.onload = function() {
                    var blob = xhr.response;
                    if (!blob) { bridge.fail(token, 'empty'); return; }
                    bridge.begin(token, blob.type || '', String(blob.size));
                    var CHUNK = 512 * 1024;
                    var offset = 0;
                    function next() {
                        if (offset >= blob.size) { bridge.end(token); return; }
                        var reader = new FileReader();
                        reader.onload = function() {
                            var r = String(reader.result);
                            bridge.chunk(token, r.substring(r.indexOf(',') + 1));
                            offset += CHUNK;
                            next();
                        };
                        reader.onerror = function() { bridge.fail(token, 'read error'); };
                        reader.readAsDataURL(blob.slice(offset, offset + CHUNK));
                    }
                    next();
                };
                xhr.onerror = function() { bridge.fail(token, 'network error'); };
                xhr.send();
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)

        // Enforce a hard deadline for the entire transfer, including transfers
        // that started writing but then stopped sending chunks.
        mainHandler.postDelayed({
            val t = transfers[token]
            if (t != null && transfers.remove(token, t)) {
                val sink = t.sink
                if (sink != null) {
                    DownloadsRepository.failLocalSink(appContext, sink, "blob transfer timed out")
                } else {
                    DownloadsRepository.showMessage(appContext, "انتهت مهلة تنزيل Blob.")
                }
                detachBridgeIfIdle(t.webView)
            }
        }, TRANSFER_TIMEOUT_MS)
    }

    private fun detachBridgeIfIdle(webView: WebView) {
        mainHandler.post {
            if (transfers.values.none { it.webView === webView }) {
                try { webView.removeJavascriptInterface("DownLS10Blob") } catch (_: Exception) { }
            }
        }
    }

    private fun abortTransfer(token: String, t: Transfer, reason: String) {
        if (!transfers.remove(token, t)) return
        val sink = t.sink
        if (sink != null) DownloadsRepository.failLocalSink(appContext, sink, reason)
        detachBridgeIfIdle(t.webView)
    }

    @JavascriptInterface
    fun begin(token: String, mime: String?, size: String?) {
        val t = transfers[token] ?: return
        if (!isCurrentOrigin(t)) {
            abortTransfer(token, t, "blob origin changed")
            return
        }

        val total = size?.toLongOrNull()
        if (total == null || total < 0L || total > MAX_BLOB_BYTES) {
            abortTransfer(token, t, "invalid or oversized blob")
            return
        }

        var failed = false
        synchronized(t) {
            if (transfers[token] !== t || t.sink != null) return
            t.totalBytes = total
            val effectiveMime = if (!mime.isNullOrBlank()) mime.take(128) else t.mimeHint
            val sink = DownloadsRepository.openLocalSink(
                appContext, t.suggestedName, effectiveMime, total, "blob:"
            )
            if (sink == null) failed = true else t.sink = sink
        }
        if (failed) abortTransfer(token, t, "could not open local sink")
    }

    @JavascriptInterface
    fun chunk(token: String, base64: String?) {
        val t = transfers[token] ?: return
        if (!isCurrentOrigin(t)) {
            abortTransfer(token, t, "blob origin changed")
            return
        }
        if (base64.isNullOrEmpty() || base64.length > MAX_ENCODED_CHUNK_CHARS) {
            abortTransfer(token, t, "invalid or oversized blob chunk")
            return
        }

        val bytes = try {
            Base64.decode(base64, Base64.DEFAULT)
        } catch (_: Exception) {
            abortTransfer(token, t, "invalid base64 chunk")
            return
        }
        if (bytes.isEmpty() || bytes.size > MAX_CHUNK_BYTES) {
            abortTransfer(token, t, "invalid chunk size")
            return
        }

        var failed = false
        synchronized(t) {
            if (transfers[token] !== t) return
            val sink = t.sink ?: return
            if (t.receivedBytes + bytes.size.toLong() > t.totalBytes) {
                failed = true
            } else {
                try {
                    DownloadsRepository.writeLocalSink(appContext, sink, bytes, bytes.size)
                    t.receivedBytes += bytes.size.toLong()
                } catch (_: Exception) {
                    failed = true
                }
            }
        }
        if (failed) abortTransfer(token, t, "blob size or write validation failed")
    }

    @JavascriptInterface
    fun end(token: String) {
        val t = transfers[token] ?: return
        if (!isCurrentOrigin(t)) {
            abortTransfer(token, t, "blob origin changed")
            return
        }

        val sink = synchronized(t) {
            if (transfers[token] !== t) return
            if (t.receivedBytes != t.totalBytes) null else t.sink
        }
        if (sink == null) {
            abortTransfer(token, t, "incomplete blob transfer")
            return
        }
        if (transfers.remove(token, t)) {
            DownloadsRepository.finishLocalSink(appContext, sink)
            detachBridgeIfIdle(t.webView)
        }
    }

    @JavascriptInterface
    fun fail(token: String, message: String?) {
        val t = transfers[token] ?: return
        if (!transfers.remove(token, t)) return
        detachBridgeIfIdle(t.webView)
        val sink = t.sink
        if (sink != null) {
            DownloadsRepository.failLocalSink(appContext, sink, message?.take(160))
        } else {
            val name = t.suggestedName
            DownloadsRepository.showMessage(
                appContext,
                "فشل تنزيل الملف" + if (name.isNullOrBlank()) "" else ": $name"
            )
        }
    }
}
