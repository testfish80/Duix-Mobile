#!/usr/bin/env python3
"""电脑端实时发送器：GPT-SoVITS 克隆语音 -> 手机 Duix demo 口型。

接线（和已交付的 NetworkAudioStreamer.kt / pc_sender_8765.py 同一套约定）：
  * 手机 demo 里填电脑的局域网 IP（如 192.168.1.10），不要填 127.0.0.1，
    点“开始推流”连到电脑的 8765 端口；一句话发完、连接关闭，
    手机端就 stopPush()，下一句需手机再点一次“开始推流”。
  * GPT-SoVITS 跑在同一台电脑上时，sender 连它用 127.0.0.1:9880 就行，
    这个地址和手机填的局域网 IP 是两回事，别混淆。
  * 手机端只收 16k 单声道 16-bit 裸 PCM，每句至少 1 秒（32000 字节）：
    这里 ffmpeg 负责把 SoVITS 的 WAV 流重采样到 16k/mono/s16le，
    流结束时若不足 32000 字节，自动补静音到 32000 字节再关连接。

用法（单句）：
    python sovits_sender_8765.py --text "欢迎来到直播间" \
        --ref-audio ref.wav --prompt-text "参考音频对应的文字"
    # ref-audio / prompt-text 不填时，用下面 REF_AUDIO / PROMPT_TEXT 的默认值；
    # GPT-SoVITS 地址默认 http://127.0.0.1:9880/tts，可用 --sovits-url 改。

用法（连续测多句，手机每句重连一次）：
    python sovits_sender_8765.py --loop --ref-audio ref.wav \
        --prompt-text "参考音频对应的文字"
    # 随后在终端逐行输入一句话，回车即等手机连接并发这一句；空行跳过，Ctrl+C 退出。

前置：电脑上要有 ffmpeg，且 GPT-SoVITS 已起在 9880（python api_v2.py -a 0.0.0.0 -p 9880）。
注意：本脚本只负责给手机口型的那一路 PCM；给观众听的那路音频不要走手机喇叭，
应直接在电脑上进 OBS（见之前链路：OBS 采 scrcpy 手机屏 + 电脑这路 TTS 音频）。

限度：未对照用户真实的 GPT-SoVITS 实例跑通过。/tts 的字段名按 api_v2 的
streaming_mode / media_type 惯例写（streaming_mode=true, media_type=wav）；
若用户那边的 SoVITS 版本字段名不同，先按它返回的报错改 --sovits-url / 字段，
手机端代码不用改。
"""
import argparse
import json
import socket
import subprocess
import sys
import threading
import urllib.request

PORT = 8765
MIN_BYTES = 32000  # 1 秒 16k mono s16le
CHUNK = 6400

# 默认值只是占位，真正跑之前改成自己的参考音频路径和对应文字。
REF_AUDIO = "ref.wav"
PROMPT_TEXT = ""
SOVITS_URL = "http://127.0.0.1:9880/tts"


def fetch_and_send(text, ref_audio, prompt_text, sovits_url, conn):
    """向 SoVITS 要流式 WAV，经 ffmpeg 转 16k PCM，按块发给手机。返回总字节数。"""
    payload = {
        "text": text,
        "text_lang": "zh",
        "ref_audio_path": ref_audio,
        "prompt_text": prompt_text,
        "prompt_lang": "zh",
        "text_split_method": "cut5",
        "batch_size": 1,
        "streaming_mode": True,
        "media_type": "wav",
    }
    req = urllib.request.Request(
        sovits_url,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    total = 0
    # ffmpeg 直接吃 SoVITS 的 WAV 流（它自己解析 WAV 头、重采样），
    # 不用手动去掉 44 字节头，也不用假设 SoVITS 的原始采样率。
    # 关键：喂 stdin 和读 stdout 必须并发。旧版是先把全部 WAV 写进
    # stdin 再读 stdout，长句（实测 30 秒）会把 ffmpeg 的 stdout
    # 管道写满、ffmpeg 停读 stdin、发送端再也写不进 stdin，整句卡死；
    # 6 秒短句因总量没超过管道缓冲才碰巧跑通。所以这里用 feeder
    # 线程写 stdin，主线程同时读 stdout 发给手机。
    ffmpeg = subprocess.Popen(
        ["ffmpeg", "-v", "error", "-i", "pipe:0",
         "-ar", "16000", "-ac", "1", "-f", "s16le", "pipe:1"],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE,
    )

    def _feed():
        try:
            with urllib.request.urlopen(req, timeout=120) as resp:
                while True:
                    data = resp.read(8192)
                    if not data:
                        break
                    try:
                        ffmpeg.stdin.write(data)
                    except (BrokenPipeError, ValueError):
                        break
        except Exception:
            pass
        finally:
            try:
                ffmpeg.stdin.close()
            except Exception:
                pass

    feeder = threading.Thread(target=_feed, daemon=True)
    try:
        feeder.start()
        while True:
            pcm = ffmpeg.stdout.read(CHUNK)
            if not pcm:
                break
            if len(pcm) % 2 == 1:
                pcm += b"\x00"
            conn.sendall(pcm)
            total += len(pcm)
    finally:
        feeder.join(timeout=5)
        try:
            ffmpeg.stdin.close()
        except Exception:
            pass
        if ffmpeg.poll() is None:
            ffmpeg.terminate()
        ffmpeg.wait()
    # 不够 1 秒的补静音，否则 Duix 口型不触发；偶数字节对齐已在上面保证。
    if total < MIN_BYTES:
        pad = MIN_BYTES - total
        if pad % 2 == 1:
            pad += 1
        conn.sendall(b"\x00" * pad)
        total += pad
    return total


def serve_once(text, ref_audio, prompt_text, sovits_url, srv):
    print(f"waiting for phone on 0.0.0.0:{PORT} ... (text: {text!r})", flush=True)
    conn, addr = srv.accept()
    print(f"phone connected: {addr}, streaming...", flush=True)
    try:
        total = fetch_and_send(text, ref_audio, prompt_text, sovits_url, conn)
        print(f"sent {total} bytes, connection closed (phone will stopPush)", flush=True)
    except Exception as e:
        print(f"send failed: {e}", flush=True)
    finally:
        conn.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--text", default="", help="要说的一句话（--loop 时可省略）")
    ap.add_argument("--ref-audio", default=REF_AUDIO)
    ap.add_argument("--prompt-text", default=PROMPT_TEXT)
    ap.add_argument("--sovits-url", default=SOVITS_URL)
    ap.add_argument("--loop", action="store_true", help="交互循环：逐行输入句子")
    args = ap.parse_args()

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", PORT))
    srv.listen(1)
    print(f"listening on 0.0.0.0:{PORT}, GPT-SoVITS: {args.sovits_url}", flush=True)
    print("Windows 防火墙要放行 8765 TCP 入站，否则手机连不上。", flush=True)
    try:
        if args.loop:
            print("输入一句话回车即发（空行跳过，Ctrl+C 退出），每句需手机重新点开始推流。", flush=True)
            while True:
                try:
                    line = input("> ").strip()
                except EOFError:
                    break
                if not line:
                    continue
                serve_once(line, args.ref_audio, args.prompt_text, args.sovits_url, srv)
        else:
            if not args.text:
                print("请给 --text，或用 --loop 交互输入。", file=sys.stderr)
                return 2
            serve_once(args.text, args.ref_audio, args.prompt_text, args.sovits_url, srv)
    except KeyboardInterrupt:
        print("\nstopped", flush=True)
    finally:
        srv.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
