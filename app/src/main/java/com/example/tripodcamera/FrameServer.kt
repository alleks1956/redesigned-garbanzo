package com.example.tripodcamera

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.util.Size
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Мини-веб-сервер без внешних библиотек.
 *  /              страница с последним кадром (обновляется сама)
 *  /gallery       миниатюры последних кадров
 *  /latest.jpg    последний кадр
 *  /photo/N       кадр номер N (полный размер)
 *  /thumb/N       миниатюра кадра N
 *  /status        JSON: сколько кадров и имя последнего
 * Кадры читаются из MediaStore по Uri, в памяти хранится только список.
 * Все адреса закрыты паролем (HTTP Basic: имя любое, пароль задаётся в приложении).
 */
class FrameServer(private val resolver: ContentResolver, @Volatile var password: String) {

    data class Frame(val name: String, val uri: Uri)

    private val frames = CopyOnWriteArrayList<Frame>()
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var serverSocket: ServerSocket? = null

    fun add(name: String, uri: Uri) {
        frames.add(Frame(name, uri))
    }

    fun start(): Boolean {
        if (serverSocket != null) return true
        return try {
            val ss = ServerSocket(PORT)
            serverSocket = ss
            pool.execute {
                while (!ss.isClosed) {
                    try {
                        val s = ss.accept()
                        pool.execute { handle(s) }
                    } catch (e: Exception) {
                        if (!ss.isClosed) Log.e(TAG, "accept: $e")
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось запустить сервер: $e")
            false
        }
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        pool.shutdownNow()
    }

    private fun handle(socket: Socket) {
        socket.use { sock ->
            sock.soTimeout = 10_000
            val reader = sock.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val requestLine = reader.readLine() ?: return
            var auth: String? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Authorization:", ignoreCase = true)) {
                    auth = line.substringAfter(':').trim()
                }
            }
            val path = requestLine.split(" ").getOrNull(1)?.substringBefore('?') ?: "/"
            val out = sock.getOutputStream()
            if (!authorized(auth)) {
                try {
                    Thread.sleep(500) // замедляет подбор пароля
                    respond(
                        out, 401, "text/plain; charset=utf-8", "Нужен пароль".toByteArray(),
                        extra = "WWW-Authenticate: Basic realm=\"Tripod Camera\"\r\n"
                    )
                } catch (_: Exception) {
                }
                return
            }
            try {
                route(path, out)
            } catch (e: Exception) {
                Log.e(TAG, "route $path: $e")
                try {
                    respond(out, 500, "text/plain; charset=utf-8", "Ошибка".toByteArray())
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun authorized(header: String?): Boolean {
        val pw = password
        if (pw.isEmpty() || header == null || !header.startsWith("Basic ", ignoreCase = true)) return false
        return try {
            val decoded = String(Base64.decode(header.substring(6).trim(), Base64.DEFAULT), Charsets.UTF_8)
            val given = decoded.substringAfter(':', "")
            MessageDigest.isEqual(given.toByteArray(Charsets.UTF_8), pw.toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            false
        }
    }

    private fun route(path: String, out: OutputStream) {
        when {
            path == "/" -> respond(out, 200, HTML, INDEX_PAGE.toByteArray())
            path == "/status" -> {
                val json = "{\"count\":" + frames.size + ",\"name\":\"" +
                    (frames.lastOrNull()?.name ?: "").replace("\"", "") + "\"}"
                respond(out, 200, "application/json; charset=utf-8", json.toByteArray())
            }
            path == "/gallery" -> respond(out, 200, HTML, galleryPage().toByteArray())
            path == "/latest.jpg" -> sendPhoto(out, frames.lastOrNull(), "no-store")
            path.startsWith("/photo/") ->
                sendPhoto(out, frames.getOrNull(path.removePrefix("/photo/").toIntOrNull() ?: -1), "max-age=3600")
            path.startsWith("/thumb/") ->
                sendThumb(out, frames.getOrNull(path.removePrefix("/thumb/").toIntOrNull() ?: -1))
            else -> respond(out, 404, "text/plain; charset=utf-8", "Не найдено".toByteArray())
        }
    }

    private fun sendPhoto(out: OutputStream, frame: Frame?, cache: String) {
        if (frame == null) {
            respond(out, 404, "text/plain; charset=utf-8", "Кадров пока нет".toByteArray())
            return
        }
        val bytes = resolver.openInputStream(frame.uri)?.use { it.readBytes() }
        if (bytes == null) respond(out, 404, "text/plain; charset=utf-8", "Файл недоступен".toByteArray())
        else respond(out, 200, "image/jpeg", bytes, cache)
    }

    private fun sendThumb(out: OutputStream, frame: Frame?) {
        if (frame == null) {
            respond(out, 404, "text/plain; charset=utf-8", "Нет кадра".toByteArray())
            return
        }
        val bmp = resolver.loadThumbnail(frame.uri, Size(320, 240), null)
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, bos)
        respond(out, 200, "image/jpeg", bos.toByteArray(), "max-age=3600")
    }

    private fun galleryPage(): String {
        val total = frames.size
        val sb = StringBuilder()
        sb.append("<!doctype html><html><head><meta charset=utf-8>")
        sb.append("<meta name=viewport content=\"width=device-width,initial-scale=1\">")
        sb.append("<title>Галерея</title><style>")
        sb.append("body{margin:8px;background:#111;color:#ddd;font-family:sans-serif}")
        sb.append("a{color:#8cf}img{width:160px;margin:2px;vertical-align:top}</style></head><body>")
        sb.append("<p><a href=/>Последний кадр</a> · Всего кадров: ").append(total)
        sb.append(" · показаны последние 60 (обновите страницу, чтобы увидеть новые)</p>")
        for (i in total - 1 downTo maxOf(0, total - 60)) {
            sb.append("<a href=/photo/").append(i).append("><img loading=lazy src=/thumb/")
                .append(i).append("></a>")
        }
        sb.append("</body></html>")
        return sb.toString()
    }

    private fun respond(
        out: OutputStream,
        code: Int,
        type: String,
        body: ByteArray,
        cache: String = "no-store",
        extra: String = ""
    ) {
        val status = when (code) {
            200 -> "OK"
            401 -> "Unauthorized"
            404 -> "Not Found"
            else -> "Error"
        }
        val head = "HTTP/1.1 $code $status\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: $cache\r\n" +
            "Connection: close\r\n" + extra + "\r\n"
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    companion object {
        const val PORT = 8080
        private const val TAG = "FrameServer"
        private const val HTML = "text/html; charset=utf-8"

        /** IPv4-адрес телефона в локальной сети (Wi-Fi или точка доступа), либо null. */
        fun localAddress(): String? = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        } catch (e: Exception) {
            null
        }

        private val INDEX_PAGE = """
<!doctype html><html><head><meta charset=utf-8>
<meta name=viewport content="width=device-width,initial-scale=1">
<title>Tripod Camera</title>
<style>
body{margin:0;background:#111;color:#ddd;font-family:sans-serif;text-align:center}
img{max-width:100%;max-height:88vh}a{color:#8cf}
</style></head><body>
<p id=info>Ожидание кадров…</p>
<img id=im>
<p><a href=/gallery>Галерея</a></p>
<script>
var last = -1;
function tick() {
  fetch('/status').then(function (r) { return r.json(); }).then(function (s) {
    if (s.count > 0 && s.count !== last) {
      last = s.count;
      var n = new Image();
      n.onload = function () { document.getElementById('im').src = n.src; };
      n.src = '/latest.jpg?' + Date.now();
    }
    document.getElementById('info').textContent =
      s.count > 0 ? ('Кадров: ' + s.count + ', последний: ' + s.name) : 'Ожидание кадров…';
  }).catch(function () {
    document.getElementById('info').textContent = 'Нет связи с телефоном';
  });
}
tick();
setInterval(tick, 2000);
</script></body></html>
"""
    }
}
