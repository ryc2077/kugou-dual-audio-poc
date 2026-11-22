package com.btsplit.poc

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * POC 界面：Hifinis 式多输出设置 v2 ——
 * 每个蓝牙输出独立选：设备、声道模式（Stereo / Left / Right / Mono）、
 * 音量（0-100%）、同步修正 trim（-400..+400ms）。
 * 播放中改声道/音量/trim 会热更新，不重建 Track；reconcile 每 500ms
 * 自动向最慢的一路对齐，日志里能看到每轮的补偿延迟。
 * 播测试音（左 440Hz / 右 660Hz）或本地文件，耳朵就能判断分没分开；
 * 每一步的 setPreferredDevice / routedDevice 结果打到日志框里，
 * 这是判断概念验证成败的唯一依据。
 *
 * 注意：不要用这个 App 播 Spotify / YouTube / 酷狗，它只能播自己生成的测试音
 * 和你选的本地文件。全局劫持所有 App 的音频不在本 POC 范围内。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var engine: SplitEngine
    private lateinit var logView: TextView
    private lateinit var leftSpinner: Spinner
    private lateinit var rightSpinner: Spinner
    private lateinit var modeSpinner1: Spinner
    private lateinit var modeSpinner2: Spinner
    private lateinit var volBar1: SeekBar
    private lateinit var volBar2: SeekBar
    private lateinit var volLabel1: TextView
    private lateinit var volLabel2: TextView
    private lateinit var delayBar1: SeekBar
    private lateinit var delayBar: SeekBar
    private lateinit var delayLabel1: TextView
    private lateinit var delayLabel: TextView
    private var devices: List<SplitEngine.BtDevice> = emptyList()
    private var pickedFile: Uri? = null

    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        pickedFile = uri
        log("Picked file: $uri")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        engine = SplitEngine(this)

        logView = findViewById(R.id.logView)
        leftSpinner = findViewById(R.id.leftSpinner)
        rightSpinner = findViewById(R.id.rightSpinner)
        modeSpinner1 = findViewById(R.id.modeSpinner1)
        modeSpinner2 = findViewById(R.id.modeSpinner2)
        volBar1 = findViewById(R.id.volBar1)
        volBar2 = findViewById(R.id.volBar2)
        volLabel1 = findViewById(R.id.volLabel1)
        volLabel2 = findViewById(R.id.volLabel2)
        delayBar1 = findViewById(R.id.delayBar1)
        delayBar = findViewById(R.id.delayBar)
        delayLabel1 = findViewById(R.id.delayLabel1)
        delayLabel = findViewById(R.id.delayLabel)

        findViewById<Button>(R.id.refreshBtn).setOnClickListener { refreshDevices() }
        findViewById<Button>(R.id.toneBtn).setOnClickListener { playTone() }
        findViewById<Button>(R.id.swapBtn).setOnClickListener { swap() }
        findViewById<Button>(R.id.stopBtn).setOnClickListener { engine.stop(); log("Stopped.") }
        findViewById<Button>(R.id.pickBtn).setOnClickListener { pickFile.launch("audio/*") }
        findViewById<Button>(R.id.fileBtn).setOnClickListener { playFile() }

        // 声道模式：默认输出 1 = 仅左声道，输出 2 = 仅右声道（经典分声道组合）。
        val modeAdapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            SplitEngine.ChannelMode.values().map { it.label }
        )
        modeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        modeSpinner1.adapter = modeAdapter
        modeSpinner2.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            SplitEngine.ChannelMode.values().map { it.label }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        modeSpinner1.setSelection(SplitEngine.ChannelMode.LEFT.ordinal)
        modeSpinner2.setSelection(SplitEngine.ChannelMode.RIGHT.ordinal)
        // 热更新（v2）：播放中改声道模式，立即生效，不重建 Track。
        // onItemSelected 在初始 setSelection 时也会触发一次，但那时还没播放，
        // updateOutput() 会直接返回 false，无副作用。
        modeSpinner1.onItemSelectedListener = modeListener(0)
        modeSpinner2.onItemSelectedListener = modeListener(1)

        setupVolumeBar(volBar1, volLabel1, "输出 1 音量", 0)
        setupVolumeBar(volBar2, volLabel2, "输出 2 音量", 1)
        setupTrimBar(delayBar1, delayLabel1, "输出 1 同步修正", 0)
        setupTrimBar(delayBar, delayLabel, "输出 2 同步修正", 1)

        requestPermissionsIfNeeded()
        refreshDevices()
        log("BTSplit POC ready. Android ${Build.VERSION.SDK_INT} (${Build.MANUFACTURER} ${Build.MODEL})")
        log("先点 Refresh，确认两个蓝牙设备都出现且类型是 TYPE_BLUETOOTH_A2DP。")
        log("如果只有一个设备出现：先在系统蓝牙里把两个都连上（Connected for audio），再点 Refresh。")
        log("默认：输出 1 = 仅左声道，输出 2 = 仅右声道。想验证 Stereo/Mono 时改上面的声道模式再播。")
    }

    /** 声道模式 Spinner 的监听：播放中改模式 → 热更新该路（不重建 Track）。 */
    private fun modeListener(index: Int) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
            if (engine.isPlaying()) applyLiveUpdate(index)
        }
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }

    /** 把界面上第 index 路的当前设置（模式 / 音量 / trim）热更新进引擎。 */
    private fun applyLiveUpdate(index: Int) {
        val mode = SplitEngine.ChannelMode.values()[
            if (index == 0) modeSpinner1.selectedItemPosition else modeSpinner2.selectedItemPosition
        ]
        val volume = (if (index == 0) volBar1.progress else volBar2.progress) / 100.0f
        val trim = (if (index == 0) delayBar1.progress else delayBar.progress) - 400
        engine.updateOutput(index, mode, volume, trim)
    }

    private fun setupVolumeBar(bar: SeekBar, label: TextView, name: String, index: Int) {
        bar.max = 100
        bar.progress = 100
        label.text = "$name：100%"
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                label.text = "$name：${p}%"
                if (fromUser && engine.isPlaying()) applyLiveUpdate(index)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    /**
     * 同步修正（trim）滑杆，复用 v1 的“延迟”滑杆（Hifinis §5：trim ±400ms）。
     * 范围 0..800，中间 400 = 0ms；trim 越大，该路总延迟越小，reconcile 给它的
     * 补偿延迟就越大，即该路出声越晚。改动时若在播放，立即热更新。
     */
    private fun setupTrimBar(bar: SeekBar, label: TextView, name: String, index: Int) {
        bar.max = 800
        bar.progress = 400
        label.text = "$name：+0ms（-400..+400，+为更晚）"
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                val trim = p - 400
                label.text = "$name：${if (trim >= 0) "+$trim" else "$trim"}ms（-400..+400，+为更晚）"
                if (fromUser && engine.isPlaying()) applyLiveUpdate(index)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    private fun requestPermissionsIfNeeded() {
        val perms = mutableListOf(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.READ_MEDIA_AUDIO)
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
        }
    }

    private fun refreshDevices() {
        engine.clearRefusedDevices()
        devices = engine.listA2dpDevices()
        log("--- A2DP devices (${devices.size}) ---")
        devices.forEachIndexed { i, d -> log("[$i] $d address=${d.address}") }
        if (devices.isEmpty()) log("⚠️ 一个 A2DP 设备都没找到。确认蓝牙已开、两个设备都已连接。")
        if (devices.size == 1) log("⚠️ 只找到 1 个。Android 经常只把当前活跃 A2DP 设备暴露出来，第二个可能要等第一个断开才出现——这本身就是要验证的点。")
        val names = devices.map { it.toString() }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        leftSpinner.adapter = adapter
        rightSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
        if (devices.size >= 2) {
            leftSpinner.setSelection(0)
            rightSpinner.setSelection(1)
        }
    }

    private fun selectedPair(): Pair<SplitEngine.BtDevice, SplitEngine.BtDevice>? {
        if (devices.size < 2) { log("需要至少 2 个 A2DP 设备"); return null }
        val l = devices[leftSpinner.selectedItemPosition]
        val r = devices[rightSpinner.selectedItemPosition]
        if (l.id == r.id) { log("左右选了同一个设备，请选两个不同的"); return null }
        return l to r
    }

    /** 按界面上的每路设置（声道模式 / 音量 / trim）组装两路输出配置。
     *  trim = 滑杆进度 − 400（滑杆 0..800，中间 400 = 0ms，范围 -400..+400）。 */
    private fun currentConfigs(): List<SplitEngine.OutputConfig>? {
        val (l, r) = selectedPair() ?: return null
        return listOf(
            SplitEngine.OutputConfig(
                device = l,
                mode = SplitEngine.ChannelMode.values()[modeSpinner1.selectedItemPosition],
                volume = volBar1.progress / 100.0f,
                trimMs = delayBar1.progress - 400
            ),
            SplitEngine.OutputConfig(
                device = r,
                mode = SplitEngine.ChannelMode.values()[modeSpinner2.selectedItemPosition],
                volume = volBar2.progress / 100.0f,
                trimMs = delayBar.progress - 400
            )
        )
    }

    private fun playTone() {
        val configs = currentConfigs() ?: return
        engine.playTestTone(configs, seconds = 10, log = ::log)
    }

    private fun playFile() {
        val uri = pickedFile ?: run { log("先点 Pick File 选一个本地音频文件（建议 48kHz WAV）"); return }
        val configs = currentConfigs() ?: return
        engine.playFile(uri, configs, log = ::log)
    }

    private fun swap() {
        if (devices.size < 2) return
        val a = leftSpinner.selectedItemPosition
        val b = rightSpinner.selectedItemPosition
        leftSpinner.setSelection(b)
        rightSpinner.setSelection(a)
        log("Swapped L/R devices.")
    }

    private fun log(msg: String) {
        runOnUiThread {
            logView.append(msg + "\n")
            // 自动滚到底
            val scroll = findViewById<android.widget.ScrollView>(R.id.logScroll)
            scroll?.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
        }
        android.util.Log.i(SplitEngine.TAG, msg)
    }

    override fun onDestroy() {
        engine.stop()
        super.onDestroy()
    }
}
