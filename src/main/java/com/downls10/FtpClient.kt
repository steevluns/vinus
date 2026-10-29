package com.downls10

import java.io.BufferedReader
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * عميل FTP بسيط (بدون تشفير) يكفي لتنزيل الملفات مع الاستئناف.
 * يستخدم الوضع السلبي (EPSV ثم PASV) ويتصل دائماً بعنوان الخادم نفسه.
 */
class FtpClient(private val host: String, private val port: Int) {

    init {
        require(host.isNotBlank() && host.none { it == '\\r' || it == '\\n' || it == '\\u0000' }) {
            "اسم خادم FTP غير صالح"
        }
        require(port in 1..65535) { "منفذ FTP غير صالح" }
    }

    private fun validateArgument(value: String, label: String) {
        if (value.length > 4096 || value.any { it == '\\r' || it == '\\n' || it == '\\u0000' }) {
            throw IOException("$label غير صالح")
        }
    }

    private class Reply(val code: Int, val text: String)

    private var control: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: OutputStream? = null
    private var dataSocket: Socket? = null

    private fun readReply(): Reply {
        val r = reader ?: throw IOException("FTP غير متصل")
        var line = r.readLine() ?: throw EOFException("انقطع اتصال FTP")
        if (line.length < 3 || !line.substring(0, 3).all { it.isDigit() }) {
            throw IOException("رد FTP غير مفهوم")
        }
        val code = line.substring(0, 3).toInt()
        val text = StringBuilder(line)
        if (line.length > 3 && line[3] == '-') {
            while (true) {
                line = r.readLine() ?: throw EOFException("انقطع اتصال FTP")
                text.append('\n').append(line)
                if (line.length >= 4 && line.startsWith("$code ")) break
            }
        }
        return Reply(code, text.toString())
    }

    private fun send(command: String): Reply {
        // منع حقن أوامر FTP عبر CR/LF أو NUL في اسم المستخدم أو كلمة المرور أو المسار.
        validateArgument(command, "أمر FTP")
        val w = writer ?: throw IOException("FTP غير متصل")
        w.write((command + "\r\n").toByteArray(Charsets.UTF_8))
        w.flush()
        return readReply()
    }

    fun connect(user: String, password: String) {
        validateArgument(user, "اسم المستخدم")
        validateArgument(password, "كلمة المرور")
        val socket = Socket()
        socket.connect(InetSocketAddress(host, port), 20000)
        socket.soTimeout = 30000
        socket.keepAlive = true
        socket.tcpNoDelay = true
        control = socket
        reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
        writer = socket.getOutputStream()

        var reply = readReply()
        if (reply.code != 220) throw NonRetryableDownloadException("رفض خادم FTP الاتصال (${reply.code})")
        reply = send("USER $user")
        if (reply.code == 331 || reply.code == 332) reply = send("PASS $password")
        if (reply.code != 230) throw NonRetryableDownloadException("فشل تسجيل الدخول إلى خادم FTP")
        reply = send("TYPE I")
        if (reply.code != 200) throw NonRetryableDownloadException("تعذّر ضبط وضع النقل الثنائي")
    }

    /** حجم الملف أو -1 إن لم يدعم الخادم الأمر SIZE. */
    fun size(path: String): Long {
        val reply = send("SIZE $path")
        return if (reply.code == 213) reply.text.substring(4).trim().toLongOrNull() ?: -1L else -1L
    }

    private fun openPassive() {
        var reply = send("EPSV")
        val dataPort: Int
        if (reply.code == 229) {
            val m = Regex("""\(\|\|\|(\d+)\|\)""").find(reply.text) ?: throw IOException("رد EPSV غير مفهوم")
            dataPort = m.groupValues[1].toIntOrNull()
                ?.takeIf { it in 1..65535 }
                ?: throw IOException("منفذ EPSV غير صالح")
        } else {
            reply = send("PASV")
            if (reply.code != 227) throw NonRetryableDownloadException("الخادم لا يدعم الوضع السلبي")
            val m = Regex("""(\d+),(\d+),(\d+),(\d+),(\d+),(\d+)""").find(reply.text)
                ?: throw IOException("رد PASV غير مفهوم")
            dataPort = m.groupValues[5].toInt() * 256 + m.groupValues[6].toInt()
            if (dataPort !in 1..65535) throw IOException("منفذ PASV غير صالح")
        }
        val socket = Socket()
        socket.connect(InetSocketAddress(host, dataPort), 20000)
        socket.soTimeout = 30000
        dataSocket = socket
    }

    /**
     * يبدأ جلب الملف من الموضع offset، ويرجع (مجرى البيانات، الموضع الفعلي).
     * إذا لم يدعم الخادم الاستئناف يبدأ من الصفر ويرجع الموضع 0.
     */
    fun retrieve(path: String, offset: Long): Pair<InputStream, Long> {
        openPassive()
        var actualOffset = 0L
        if (offset > 0) {
            val rest = send("REST $offset")
            if (rest.code == 350) actualOffset = offset
        }
        val reply = send("RETR $path")
        if (reply.code != 150 && reply.code != 125) {
            throw NonRetryableDownloadException("تعذّر جلب الملف من خادم FTP (${reply.code})")
        }
        val stream = dataSocket?.getInputStream() ?: throw IOException("قناة البيانات مغلقة")
        return Pair(stream, actualOffset)
    }

    fun close() {
        try { dataSocket?.close() } catch (_: Exception) { }
        try { writer?.write("QUIT\r\n".toByteArray()); writer?.flush() } catch (_: Exception) { }
        try { control?.close() } catch (_: Exception) { }
    }
}
