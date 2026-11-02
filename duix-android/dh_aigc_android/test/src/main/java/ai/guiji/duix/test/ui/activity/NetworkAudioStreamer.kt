// NetworkAudioStreamer.kt — 电脑端 8765 网络音频输入（同一局域网）。
// 作用：连电脑的 8765 端口，收 16k 单声道 16-bit 裸 PCM，
// 经 startPush / pushPcm / stopPush 喂给 Duix 做口型。
// 约定（电脑端必须保证）：PCM 已是 16k/mono/s16le，每句总量 >= 32000 字节（1 秒），
// 不够的由电脑端补静音。手机端这里只做偶数字节对齐，不做重采样。
// 断线行为：只要还在推流状态，一句发完（电脑端关连接）或断线后都会
// 自动重连等下一句，不用每句手动再点一次；点“停止推流”或退出页面才真正停。

package ai.guiji.duix.test.ui.activity

import java.io.BufferedInputStream
import java.net.InetSocketAddress
import java.net.Socket

// 适配 CallActivity 里已有的 Duix 对象：DUIX.pushPcm 只有一个 ByteArray 参数，
// 所以接线处把 (data, length) 转成 duix.pushPcm(data.copyOf(length))，这个类本身不用动。
interface DuixPcmSink {
    fun startPush()
    fun pushPcm(data: ByteArray, length: Int)
    fun stopPush()
}

class NetworkAudioStreamer(
    private val sink: DuixPcmSink,
    private val onStatus: (String) -> Unit
) {
    @Volatile private var running = false
    @Volatile private var pushed = false
    private var worker: Thread? = null
    private var socket: Socket? = null

    fun start(host: String, port: Int = 8765) {
        if (running) return
        running = true
        worker = Thread { runLoop(host, port) }.apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) { }
        worker?.interrupt()
        stopPushOnce()
    }

    private fun startPushOnce() {
        if (!pushed) {
            sink.startPush()
            pushed = true
        }
    }

    private fun stopPushOnce() {
        if (pushed) {
            try { sink.stopPush() } catch (_: Exception) { }
            pushed = false
        }
    }

    private fun runLoop(host: String, port: Int) {
        while (running && !Thread.currentThread().isInterrupted) {
            try {
                onStatus("连接中：$host:$port")
                val s = Socket()
                socket = s
                s.connect(InetSocketAddress(host, port), 5000)
                s.soTimeout = 0 // 流式长连接，不设读超时，靠 stop() 关 socket 打断
                onStatus("已连接，开始推流")
                startPushOnce()

                val input = BufferedInputStream(s.getInputStream())
                val buf = ByteArray(6400)
                var pendingByte: Int = -1 // 上一块奇数字节时留下的 1 字节，下块开头补上

                while (running && !Thread.currentThread().isInterrupted) {
                    // 先把上块留下的 1 字节放进本块开头
                    var offset = 0
                    if (pendingByte >= 0) {
                        buf[0] = pendingByte.toByte()
                        offset = 1
                        pendingByte = -1
                    }
                    val n = input.read(buf, offset, buf.size - offset)
                    if (n < 0) break // 电脑端发完关连接 = 这句播完，等下一句重连
                    var total = offset + n
                    if (total % 2 == 1) {
                        // 16-bit 采样必须是偶数字节，留 1 字节给下块，别直接推奇数块
                        pendingByte = buf[total - 1].toInt() and 0xFF
                        total -= 1
                    }
                    if (total > 0) {
                        sink.pushPcm(buf, total)
                    }
                }
            } catch (e: Exception) {
                if (running) onStatus("断线：${e.message}，1 秒后重连")
            } finally {
                stopPushOnce()
                try { socket?.close() } catch (_: Exception) { }
                socket = null
            }
            if (running) {
                onStatus("等待下一句，自动重连中…")
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
            }
        }
    }
}
