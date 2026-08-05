package com.blink.dtn.crowd

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Local HTTP/WS-ish endpoint so iPhone Safari can join the event feed without an App Store app.
 * Bind on hotspot gateway (e.g. 192.168.43.1:8787). Serves embedded PWA shell.
 */
object EventAnchorServer {
    private const val TAG = "EventAnchor"
    const val PORT = 8787

    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    @Volatile
    var lastBindHint: String = "http://127.0.0.1:$PORT"
        private set

    fun isRunning(): Boolean = running.get()

    fun start(context: Context) {
        if (!running.compareAndSet(false, true)) return
        thread(name = "event-anchor", isDaemon = true) {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress("0.0.0.0", PORT))
                server = ss
                lastBindHint = "http://192.168.43.1:$PORT"
                Log.i(TAG, "listening on 0.0.0.0:$PORT")
                while (running.get()) {
                    val socket = runCatching { ss.accept() }.getOrNull() ?: break
                    pool.execute { handle(socket, context) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "server stopped: ${e.message}")
            } finally {
                running.set(false)
                runCatching { server?.close() }
                server = null
            }
        }
    }

    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        server = null
    }

    private fun handle(socket: Socket, context: Context) {
        try {
            socket.soTimeout = 8_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val requestLine = reader.readLine() ?: return
            while (true) {
                val h = reader.readLine() ?: break
                if (h.isBlank()) break
            }
            val path = requestLine.split(" ").getOrNull(1) ?: "/"
            val out = OutputStreamWriter(socket.getOutputStream())
            when {
                path.startsWith("/api/feed") -> {
                    val body = feedJson()
                    writeResponse(out, 200, "application/json; charset=utf-8", body)
                }
                path.startsWith("/api/room") -> {
                    val room = EventRoomStore.current()
                    val body = if (room == null) {
                        """{"ok":false}"""
                    } else {
                        """{"ok":true,"id":${jsonStr(room.id)},"title":${jsonStr(room.title)}}"""
                    }
                    writeResponse(out, 200, "application/json; charset=utf-8", body)
                }
                path.startsWith("/api/post") && requestLine.startsWith("POST") -> {
                    // Minimal: query ?text= for PWA simplicity
                    val q = path.substringAfter('?', "")
                    val text = q.split('&')
                        .firstOrNull { it.startsWith("text=") }
                        ?.removePrefix("text=")
                        ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                        .orEmpty()
                    if (text.isNotBlank()) {
                        val clipped = text.take(72)
                        CrowdFeed.add(
                            kind = com.blink.dtn.ble.CrowdFrame.KIND_PUBLIC,
                            text = clipped,
                            fromHash = "iphone".hashCode(),
                            roomId = EventRoomStore.current()?.id,
                            mine = false
                        )
                        // Bridge PWA → BLE stadium plane when mesh service is up.
                        runCatching {
                            com.blink.dtn.ble.BleMeshManager.tryBridgeCrowd(
                                com.blink.dtn.ble.CrowdFrame.KIND_PUBLIC,
                                clipped,
                                "iphone".hashCode()
                            )
                        }
                    }
                    writeResponse(out, 200, "application/json", """{"ok":true}""")
                }
                else -> writeResponse(out, 200, "text/html; charset=utf-8", pwaHtml(context))
            }
            out.flush()
        } catch (e: Exception) {
            Log.d(TAG, "client: ${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun feedJson(): String {
        val items = CrowdFeed.feed.value.take(80)
        val arr = items.joinToString(",") { item ->
            """{"kind":${item.kind},"text":${jsonStr(item.text)},"at":${item.at},"mine":${item.mine}}"""
        }
        return """{"items":[$arr]}"""
    }

    private fun jsonStr(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "") + "\""

    private fun writeResponse(out: OutputStreamWriter, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        out.write("HTTP/1.1 $code OK\r\n")
        out.write("Content-Type: $type\r\n")
        out.write("Content-Length: ${bytes.size}\r\n")
        out.write("Access-Control-Allow-Origin: *\r\n")
        out.write("Connection: close\r\n\r\n")
        out.write(body)
    }

    private fun pwaHtml(context: Context): String {
        val room = EventRoomStore.current()
        val title = room?.title ?: "TukTuk Nearby"
        return """
<!DOCTYPE html>
<html lang="ru">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover"/>
<meta name="apple-mobile-web-app-capable" content="yes"/>
<title>$title — TukTuk</title>
<style>
body{margin:0;font-family:-apple-system,system-ui,sans-serif;background:#0a0b0d;color:#e8e8e8}
header{padding:16px;border-bottom:1px solid #222}
h1{font-size:18px;margin:0}
.sub{color:#8a8a8a;font-size:13px;margin-top:4px}
#feed{padding:12px;display:flex;flex-direction:column;gap:8px;min-height:60vh}
.item{padding:10px 12px;background:#14161a;border-radius:12px;font-size:15px}
.sos{border:1px solid #c44}
form{display:flex;gap:8px;padding:12px;position:sticky;bottom:0;background:#0a0b0d}
input{flex:1;padding:12px;border-radius:12px;border:1px solid #333;background:#111;color:#fff}
button{padding:12px 16px;border:0;border-radius:12px;background:#b8f24a;color:#111;font-weight:600}
</style>
</head>
<body>
<header>
  <h1>$title</h1>
  <div class="sub">Работает без App Store · через Wi‑Fi якоря Android · VPS/Oracle не трогаем</div>
</header>
<div id="feed"></div>
<form id="f">
  <input id="t" maxlength="72" placeholder="Короткое сообщение рядом…" autocomplete="off"/>
  <button type="submit">→</button>
</form>
<script>
async function refresh(){
  try{
    const r=await fetch('/api/feed'); const j=await r.json();
    const el=document.getElementById('feed');
    el.innerHTML=(j.items||[]).map(i=>'<div class="item'+(i.kind===3?' sos':'')+'">'+esc(i.text)+'</div>').join('')||'<div class="sub" style="padding:12px">Пока тихо — напишите первым</div>';
  }catch(e){}
}
function esc(s){return String(s).replace(/[&<>]/g,c=>({ '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));}
document.getElementById('f').onsubmit=async(e)=>{
  e.preventDefault();
  const t=document.getElementById('t').value.trim(); if(!t) return;
  await fetch('/api/post?text='+encodeURIComponent(t),{method:'POST'});
  document.getElementById('t').value=''; refresh();
};
refresh(); setInterval(refresh,2500);
</script>
</body>
</html>
        """.trimIndent()
    }
}
