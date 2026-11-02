# 电脑推流 + 绿幕背景（test 模块改动说明）

## 手机端（已改进 CallActivity）

- 新增 `NetworkAudioStreamer.kt`：连同一局域网内电脑的 8765 端口，收
  16k 单声道 16-bit 裸 PCM，经 `startPush / pushPcm / stopPush` 喂给 Duix 做口型。
  一句发完或断线后自动重连等下一句，不用每句手动再点；点“停止推流”、
  点“停止播放”或退出页面才真正停。原有的 Play PCM / Play WAV / 录音 /
  随机动作 / 静音都保留。
- CallActivity 新增一栏：电脑 IP 输入框 + “开始推流”按钮 + 状态行，
  模型初始化完成（`initOk`）后按钮才可用。IP 填电脑的局域网地址，
  不要填 127.0.0.1。
- 背景改成纯绿（`#00FF00`）：不再加载 `bg/bg1.png`，根布局和背景层
  都是纯绿，数字人层透明，绿底从人物后面透出来，OBS 里用色度键
  （Chroma Key）抠掉绿底即可叠到产品画面上。
- Manifest 不用改：本 fork 的 `AndroidManifest.xml` 里本来就有
  `INTERNET` 权限。

## 电脑端（本目录两个脚本，端口都是 8765）

- `pc_sender_8765.py`：先测通口型用。先
  `ffmpeg -i tts.mp3 -ar 16000 -ac 1 -f s16le out.pcm`，再
  `python pc_sender_8765.py out.pcm`。
- `sovits_sender_8765.py`：正式用。GPT-SoVITS 克隆语音流式推给手机，
  如 `python sovits_sender_8765.py --text "欢迎来到直播间" --ref-audio ref.wav
  --prompt-text "参考音频对应的文字"`；`--loop` 可逐行连续发。
  默认 GPT-SoVITS 在 `http://127.0.0.1:9880/tts`，字段按 api_v2 惯例，
  未对照真实实例跑通过，报错先按返回改字段，手机端不用改。
- Windows 防火墙要放行 8765 TCP 入站；观众听的那路音频直接在电脑上
  进 OBS，不要走手机喇叭。
