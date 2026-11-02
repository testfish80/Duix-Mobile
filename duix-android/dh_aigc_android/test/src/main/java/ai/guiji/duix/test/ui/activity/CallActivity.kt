package ai.guiji.duix.test.ui.activity

import ai.guiji.duix.sdk.client.Constant
import ai.guiji.duix.sdk.client.DUIX
import ai.guiji.duix.sdk.client.loader.ModelInfo
import ai.guiji.duix.sdk.client.render.DUIXRenderer
import ai.guiji.duix.sdk.client.thread.RenderThread
import ai.guiji.duix.test.R
import ai.guiji.duix.test.databinding.ActivityCallBinding
import ai.guiji.duix.test.ui.adapter.MotionAdapter
import ai.guiji.duix.test.ui.dialog.AudioRecordDialog
import ai.guiji.duix.test.util.StringUtils
import android.Manifest
import android.annotation.SuppressLint
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.widget.CompoundButton
import android.widget.Toast
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream


class CallActivity : BaseActivity() {

    companion object {
        const val GL_CONTEXT_VERSION = 2
    }

    private var modelUrl = ""
    private var debug = false
    private var mMessage = ""

    @SuppressLint("SetTextI18n")
    private fun applyMessage(msg: String){
        if (debug){
            runOnUiThread {
                if (!uiHidden) binding.tvDebug.visibility = View.VISIBLE
                if (mMessage.length > 10000){
                    mMessage = ""
                }
                mMessage = "${StringUtils.dateToStringMS4()} $msg\n$mMessage"
                binding.tvDebug.text = mMessage
            }
        }

    }

    private lateinit var binding: ActivityCallBinding
    private var streamer: NetworkAudioStreamer? = null
    private var streaming = false
    // 一键隐藏/显示：隐藏时先记下每个覆盖层控件当时的可见状态，
    // 显示时按原样恢复（而不是一律改回 VISIBLE），这样随机动作、
    // 调试信息这些本来就是 invisible/gone 的不会被误打开。
    // 注意：btnToggleUi 本身不在这个列表里，永远保留可点。
    private var uiHidden = false
    private var savedVisibilities = mutableMapOf<View, Int>()
    private fun overlayViews(): List<View> = listOf(
        binding.switchMute, binding.tvMotionTips, binding.rvMotion,
        binding.btnRandomMotion, binding.llStream, binding.btnRecord,
        binding.btnPlayPCM, binding.btnPlayWAV, binding.btnStopPlay,
        binding.tvAITips, binding.tvDebug
    )
    private fun toggleUi() {
        if (uiHidden) {
            savedVisibilities.forEach { (view, visibility) -> view.visibility = visibility }
            savedVisibilities.clear()
            uiHidden = false
            binding.btnToggleUi.text = "隐藏界面"
            binding.btnToggleUi.alpha = 1.0f
        } else {
            savedVisibilities = overlayViews().associateWith { it.visibility }.toMutableMap()
            overlayViews().forEach { it.visibility = View.GONE }
            uiHidden = true
            binding.btnToggleUi.text = "显示界面"
            binding.btnToggleUi.alpha = 0.5f
        }
    }
    private var duix: DUIX? = null
    private var mDUIXRender: DUIXRenderer? = null
    private var mModelInfo: ModelInfo?=null     // 加载的模型信息

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepScreenOn()
//        val audioManager = mContext.getSystemService(AUDIO_SERVICE) as AudioManager
//        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
//        audioManager.isSpeakerphoneOn = true
        binding = ActivityCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        modelUrl = intent.getStringExtra("modelUrl") ?: ""
        debug = intent.getBooleanExtra("debug", false)

        // 绿幕背景：不用 bg1.png 图片了，纯绿方便 OBS 做色度抠像；
        // 数字人层本身是透明的，绿底会从人物后面透出来。
        binding.ivBg.setImageDrawable(null)
        binding.ivBg.setBackgroundColor(Color.GREEN)

        binding.glTextureView.setEGLContextClientVersion(GL_CONTEXT_VERSION)
        binding.glTextureView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
//        binding.glTextureView.preserveEGLContextOnPause = true
        binding.glTextureView.isOpaque = false

        binding.switchMute.setOnCheckedChangeListener(object : CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(
                buttonView: CompoundButton?,
                isChecked: Boolean,
            ) {
                if (isChecked) {
                    duix?.setVolume(0.0F)
                } else {
                    duix?.setVolume(1.0F)
                }
            }
        })

        binding.btnRecord.setOnClickListener {
            requestPermission(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }

        binding.btnPlayPCM.setOnClickListener {
            applyMessage("start play pcm")
            playPCMStream()
        }

        binding.btnPlayWAV.setOnClickListener {
            applyMessage("start play wav")
            playWAVFile()
        }

        binding.btnRandomMotion.setOnClickListener {
            applyMessage("start random motion")
            duix?.startRandomMotion(true)
        }
        binding.btnStopPlay.setOnClickListener {
            if (streaming) stopStreaming()
            duix?.stopAudio()
        }

        binding.btnToggleUi.setOnClickListener { toggleUi() }

        binding.btnStream.setOnClickListener {
            if (streaming) {
                stopStreaming()
            } else {
                val host = binding.etPcIp.text.toString().trim()
                if (host.isEmpty()) {
                    Toast.makeText(mContext, "先填电脑 IP", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                streamer = NetworkAudioStreamer(object : DuixPcmSink {
                    override fun startPush() {
                        duix?.startPush()
                    }

                    override fun pushPcm(data: ByteArray, length: Int) {
                        duix?.pushPcm(data.copyOf(length))
                    }

                    override fun stopPush() {
                        duix?.stopPush()
                    }
                }) { status ->
                    runOnUiThread { binding.tvStreamStatus.text = status }
                }
                streamer?.start(host, 8765)
                streaming = true
                binding.btnStream.text = "停止推流"
            }
        }

        mDUIXRender =
            DUIXRenderer(
                mContext,
                binding.glTextureView
            )
        binding.glTextureView.setRenderer(mDUIXRender)
        binding.glTextureView.renderMode =
            GLSurfaceView.RENDERMODE_WHEN_DIRTY      // 一定要在设置完Render之后再调用

        duix = DUIX(mContext, modelUrl, mDUIXRender) { event, msg, info ->
            when (event) {
                Constant.CALLBACK_EVENT_INIT_READY -> {
                    mModelInfo = info as ModelInfo
                    Log.i(TAG, "CALLBACK_EVENT_INIT_READY: $mModelInfo")
                    initOk()
                }

                Constant.CALLBACK_EVENT_INIT_ERROR -> {
                    runOnUiThread {
                        applyMessage("init error: $msg")
                        Log.e(TAG, "CALLBACK_EVENT_INIT_ERROR: $msg")
                        Toast.makeText(mContext, "Initialization exception: $msg", Toast.LENGTH_SHORT).show()
                    }
                }

                Constant.CALLBACK_EVENT_AUDIO_PLAY_START -> {
                    applyMessage("callback audio play start")
                    Log.i(TAG, "CALLBACK_EVENT_AUDIO_PLAY_START")
                }

                Constant.CALLBACK_EVENT_AUDIO_PLAY_END -> {
                    applyMessage("callback audio play end")
                    Log.i(TAG, "CALLBACK_EVENT_PLAY_END")
                }

                Constant.CALLBACK_EVENT_AUDIO_PLAY_ERROR -> {
                    applyMessage("callback audio play error: $msg")
                    Log.e(TAG, "CALLBACK_EVENT_PLAY_ERROR: $msg")
                }

                Constant.CALLBACK_EVENT_MOTION_START -> {
                    applyMessage("callback motion play start")
                    Log.e(TAG, "CALLBACK_EVENT_MOTION_START")
                }

                Constant.CALLBACK_EVENT_MOTION_END -> {
                    applyMessage("callback motion play end")
                    Log.e(TAG, "CALLBACK_EVENT_MOTION_END")
                }
            }
        }
        // Rendering status callback
//        duix?.setReporter(object : RenderThread.Reporter {
//            override fun onRenderStat(
//                resultCode: Int,
//                isLip: Boolean,
//                useTime: Long,
//            ) {
//
//            }
//        })
        applyMessage("start init")
        duix?.init()
    }

    private fun initOk() {
        Log.i(TAG, "init ok")
        applyMessage("init ok")
        runOnUiThread {
            binding.btnRecord.isEnabled = true
            binding.btnPlayPCM.isEnabled = true
            binding.btnPlayWAV.isEnabled = true
            binding.switchMute.isEnabled = true
            binding.btnStopPlay.isEnabled = true
            binding.btnStream.isEnabled = true

            mModelInfo?.let { modelInfo ->
                if (modelInfo.motionRegions.isNotEmpty()) {
                    val names = ArrayList<String>()
                    for (motion in modelInfo.motionRegions){
                        if (!TextUtils.isEmpty(motion.name) && "unknown" != motion.name){
                            names.add(motion.name)
                        }
                    }
                    // Named action regions
                    if (names.isNotEmpty()){
                        val motionAdapter = MotionAdapter(names, object : MotionAdapter.Callback{
                            override fun onClick(name: String, now: Boolean) {
                                applyMessage("start [${name}] motion")
                                duix?.startMotion(name, now)
                            }
                        })
                        binding.rvMotion.adapter = motionAdapter
                    }
                    if (!uiHidden) {
                        binding.btnRandomMotion.visibility = View.VISIBLE
                        binding.tvMotionTips.visibility = View.VISIBLE
                    }
                }
            }
        }
    }


    private fun stopStreaming() {
        streamer?.stop()
        streamer = null
        streaming = false
        binding.btnStream.text = "开始推流"
        binding.tvStreamStatus.text = "未连接"
    }

    override fun onDestroy() {
        streamer?.stop()
        streamer = null
        super.onDestroy()
        duix?.release()
    }

    private fun playPCMStream(){
        val thread = Thread {
            duix?.startPush()
            val inputStream = assets.open("pcm/2.pcm")
            val buffer = ByteArray(320)
            var length = 0
            while (inputStream.read(buffer).also { length = it } > 0){
                val data = buffer.copyOfRange(0, length)
                duix?.pushPcm(data)
            }
            duix?.stopPush()
            inputStream.close()
        }
        thread.start()
    }

    private fun playWAVFile(){
        val thread = Thread {
            val wavName = "1.wav"
            val wavFile = File(mContext.externalCacheDir, wavName)
            if (!wavFile.exists()){
                // copy assets -> sd card
                val inputStream = assets.open("wav/$wavName")
                if (!mContext.externalCacheDir!!.exists()){
                    mContext.externalCacheDir!!.mkdirs()
                }
                val out = FileOutputStream(wavFile)
                val buffer = ByteArray(1024)
                var length = 0
                while ((inputStream.read(buffer).also { length = it }) > 0) {
                    out.write(buffer, 0, length)
                }
                out.close()
                inputStream.close()
            }
            duix?.playAudio(wavFile.absolutePath)
        }
        thread.start()
    }

    override fun permissionsGet(get: Boolean, code: Int) {
        super.permissionsGet(get, code)
        if (get){
            showRecordDialog()
        } else {
            Toast.makeText(mContext, R.string.need_permission_continue, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showRecordDialog(){
        val audioRecordDialog = AudioRecordDialog(mContext, object : AudioRecordDialog.Listener{
            override fun onFinish(path: String) {
                val thread = Thread {
                    duix?.startPush()
                    val inputStream = FileInputStream(path)
                    val buffer = ByteArray(320)
                    var length = 0
                    while (inputStream.read(buffer).also { length = it } > 0){
                        val data = buffer.copyOfRange(0, length)
                        duix?.pushPcm(data)
                    }
                    duix?.stopPush()
                    inputStream.close()
                }
                thread.start()
            }
        })
        audioRecordDialog.show()
    }
}
