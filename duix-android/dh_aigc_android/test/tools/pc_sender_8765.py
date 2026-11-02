#!/usr/bin/env python3
"""电脑端测试发送器：同一局域网，监听 8765，给手机 Duix demo 发 PCM。

用法（先测通这一段，再换 GPT-SoVITS 实时流）：
    python pc_sender_8765.py out.pcm
    # out.pcm 须是 16k 单声道 16-bit 裸 PCM，不够 1 秒的这里自动补静音到 32000 字节
    # 手机 demo 里填电脑的局域网 IP（如 192.168.1.10），不要填 127.0.0.1

Windows 防火墙要放行 8765 TCP 入站，否则手机连不上。
实时版：把下面 read PCM 文件那一段，换成 ffmpeg 从 GPT-SoVITS 流式 WAV
转 16k PCM 的 stdout，按块 sendall 即可，手机端代码不用改。
"""
import socket
import sys

PORT = 8765

def main() -> None:
    pcm_path = sys.argv[1] if len(sys.argv) > 1 else "out.pcm"
    with open(pcm_path, "rb") as f:
        data = f.read()
    if len(data) < 32000:  # Duix 每段至少 1 秒，否则口型不触发
        data += b"\x00" * (32000 - len(data))
    if len(data) % 2 == 1:
        data += b"\x00"

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", PORT))
    srv.listen(1)
    print(f"listening on 0.0.0.0:{PORT}, waiting for phone, pcm={len(data)} bytes")
    while True:
        conn, addr = srv.accept()
        print(f"phone connected: {addr}, sending...")
        try:
            conn.sendall(data)
        except Exception as e:
            print(f"send failed: {e}")
        finally:
            conn.close()
            print("sent, connection closed (phone will stopPush)")

if __name__ == "__main__":
    main()
