package de.moekadu.tuner

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.lang.ref.WeakReference
import kotlin.math.pow

class MainActivity : AppCompatActivity() {

    companion object {
        const val sampleRate = 44100
        const val REQUEST_AUDIO_RECORD_PERMISSION = 1001

        //const val processingBufferSize = 16384
        const val processingBufferSize = 4096
        //const val processingBufferSize = 8192
        const val numBufferForPostprocessing = 2
    }

    private var record: AudioRecord? = null
    private var recordReader: RecordReaderThread? = null
    private val dummyAudioBuffer = FloatArray(processingBufferSize)
    // val audioTimestamp = AudioTimestamp()

    /// Buffer, where we store the audio data
    private var audioData: CircularRecordData? = null

    private var preprocessor: PreprocessorThread? = null
    private var preprocessingResults: ProcessingResultBuffer<PreprocessorThread.PreprocessingResults>? =
        null

    private var postprocessor: PostprocessorThread? = null
    private var postprocessingResults: ProcessingResultBuffer<PostprocessorThread.PostprocessingResults>? =
        null

    //val overlapFraction = 4
    private val overlapFraction = 2

    private var volumeMeter: VolumeMeter? = null

    private var spectrumPlot: PlotView? = null
    private val spectrumPlotXMarks = FloatArray(1)

    private var frequencyText: TextView? = null

    private val frequencies = FloatArray(RealFFT.nFrequencies(processingBufferSize))

    private var pitchPlot: PlotView? = null
    private val pitchHistory = FloatArray(200) { 0.0f }

    class UiHandler(private val activity: WeakReference<MainActivity>) : Handler() {

        override fun handleMessage(msg: Message) {
            super.handleMessage(msg)
            activity.get()?.let {
                //Log.v("Tuner", "something finished " + it.i)
//                it.i = it.i + 1

                val obj = msg.obj
                //Log.v("Tuner", "MainActivity:UiHandler:handleMessage : message num="+msg.what)
                when (msg.what) {
                    RecordReaderThread.FINISHWRITE -> {
                        //Log.v("Tuner", "MainActivity:UiHandler:handleMessage : write finished")
                        when (obj) {
                            is CircularRecordData.WriteBuffer -> it.doPreprocessing(obj)
                        }
                    }
                    PreprocessorThread.PREPROCESSING_FINISHED -> {
                        //Log.v("Tuner", "MainActivity:UiHandler:handleMessage : preprocessing finished")
                        when (obj) {
                            is PreprocessorThread.ReadBufferAndProcessingResults -> it.doPostprocessing(
                                obj
                            )
                        }
                    }
                    PostprocessorThread.POSTPROCESSING_FINISHED -> {
                        //Log.v("Tuner", "MainActivity:UiHandler:handleMessage : postprocessing finished")
                        when (obj) {
                            is PostprocessorThread.PreprocessingDataAndPostprocessingResults -> it.doVisualize(
                                obj
                            )
                        }
                    }
                }
            }
        }
    }

    private val uiHandler = UiHandler(WeakReference(this))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        volumeMeter = findViewById(R.id.volume_meter)
        //volumeMeter?.startDelay = kotlin.math.max((2 * 1000 * processingBufferSize / overlapFraction / sampleRate).toLong(), 200L)
        spectrumPlot = findViewById(R.id.spectrum_plot)
        //spectrumPlot?.xRange(0f, 880f)
        //spectrumPlot?.setXTicks(floatArrayOf(0f,200f,400f,600f,800f), true)
        spectrumPlot?.xRange(0f, 1760f, false)
        spectrumPlot?.setXTicks(floatArrayOf(0f,200f, 400f, 600f, 800f, 1000f,1200f, 1400f, 1600f), true)
        spectrumPlot?.setXMarkTextFormat { i -> getString(R.string.hertz, i) }
        spectrumPlot?.setXTickTextFormat { i -> getString(R.string.hertz, i) }
        frequencyText = findViewById(R.id.frequency_text)

        for(i in 0 until processingBufferSize/2)
            frequencies[i] = RealFFT.getFreq(i, processingBufferSize, 1.0f/ sampleRate)

        pitchPlot = findViewById(R.id.pitch_plot)
        pitchPlot?.xRange(0f, 1.1f*pitchHistory.size.toFloat(), false)
        pitchPlot?.yRange(0f, 1760f, false)
        pitchPlot?.plot(pitchHistory)
    }

    override fun onStart() {
        super.onStart()
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_AUDIO_RECORD_PERMISSION
            )
        } else {
            startAudioRecorder()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            REQUEST_AUDIO_RECORD_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startAudioRecorder()
                } else {
                    Toast.makeText(
                        this,
                        "No audio recording permission is granted",
                        Toast.LENGTH_LONG
                    ).show()
                    Log.v(
                        "Tuner",
                        "MainActivity:onRequestPermissionsResult: No audio recording permission is granted."
                    )
                }
            }
        }
    }

    private fun startAudioRecorder() {
        Log.v("Tuner", "MainActivity::startAudioRecorder")

        if (preprocessor == null) {
            preprocessor = PreprocessorThread(processingBufferSize, uiHandler)
            preprocessingResults = ProcessingResultBuffer(3 + numBufferForPostprocessing) {
                PreprocessorThread.PreprocessingResults(processingBufferSize)
            }
            preprocessor?.start()
        }

        if (postprocessor == null) {
            postprocessor = PostprocessorThread(processingBufferSize, uiHandler)
            postprocessingResults =
                ProcessingResultBuffer(3) { PostprocessorThread.PostprocessingResults() }
            postprocessor?.start()
        }

        if (recordReader == null) {
            recordReader = RecordReaderThread(uiHandler)
            recordReader?.start()
        }

        if (record == null) {
            val processingInterval = getProcessingInterval()

            //val sampleRate = AudioFormat.SAMPLE_RATE_UNSPECIFIED
            val minBufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_FLOAT
            )

            // Times four because size is given in bytes, but we are reading floats
            val audioRecordBufferSize = kotlin.math.max(2 * processingInterval * 4, minBufferSize)

            val localRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_FLOAT,
                audioRecordBufferSize
            )
            if (localRecord.state == AudioRecord.STATE_UNINITIALIZED) {
                Log.v(
                    "Tuner",
                    "MainActivity::startAudioRecorder: Not able to acquire audio resource"
                )
                Toast.makeText(this, "Not able to acquire audio resource", Toast.LENGTH_LONG).show()
            }

            var circularBufferSize = 3 * kotlin.math.max(
                processingBufferSize,
                processingInterval
            )  // Smaller size might be enough?
            if (circularBufferSize % processingInterval > 0)  // Make sure, the circularBufferSize is a multiple of the processingInterval
                circularBufferSize =
                    (circularBufferSize / processingInterval + 1) * processingInterval

            audioData = CircularRecordData(circularBufferSize)

            val posNotificationState = localRecord.setPositionNotificationPeriod(processingInterval)

            if (posNotificationState != AudioRecord.SUCCESS) {
                Log.v(
                    "Tuner",
                    "MainActivity::startAudioRecord: Not able to set position notification period"
                )
                Toast.makeText(
                    this,
                    "Not able to set position notification period",
                    Toast.LENGTH_LONG
                ).show()
            }

            Log.v("Tuner", "MainActivity::startAudioRecorder: minBufferSize = " + minBufferSize)
            Log.v(
                "Tuner",
                "MainActivity::startAudioRecorder: circularBufferSize = " + circularBufferSize
            )
            Log.v(
                "Tuner",
                "MainActivity::startAudioRecorder: processingInterval = " + processingInterval
            )
            Log.v(
                "Tuner",
                "MainActivity::startAudioRecorder: audioRecordBufferSize = " + audioRecordBufferSize
            )

            localRecord.setRecordPositionUpdateListener(object :
                AudioRecord.OnRecordPositionUpdateListener {
                override fun onMarkerReached(recorder: AudioRecord?) {
                    Log.v("Tuner", "MainActivity:onMarkerReached")
                }

                //@RequiresApi(Build.VERSION_CODES.N)
                override fun onPeriodicNotification(recorder: AudioRecord?) {
                    //Log.v("Tuner", "MainActivity:onPeriodicNotification")
                    //recorder?.getTimestamp(audioTimestamp, TIMEBASE_MONOTONIC)
                    //Log.v("Tuner", "MainActivity:onPeriodicNotification : timestamp=" +audioTimestamp.framePosition)
                    readAudioData(recorder)
                }
            })

            record = localRecord
//            if(record?.recordingState == AudioRecord.RECORDSTATE_RECORDING)
//                Log.v("Tuner", "MainActivity:startRecorder:recordingState = recording")
        }

        record?.startRecording()
    }

    override fun onStop() {

        record?.stop()
        record?.release()
        record = null

        preprocessor?.quit()
        preprocessor = null

        recordReader?.quit()
        recordReader = null

        super.onStop()
    }

    override fun onDestroy() {
        record?.release()
        super.onDestroy()
    }

    private fun readAudioData(recorder: AudioRecord?) {
        //Log.v("Tuner", "MainActivity:readAudioData")
        if(recorder != null) {
            if (recorder.state == AudioRecord.STATE_UNINITIALIZED) {
                return
            }

            val writeBuffer = audioData?.lockWrite(recorder.positionNotificationPeriod)

            var readToDummy = false

            if (audioData == null) {
                Log.v("Tuner", "MainActivity::onPeriodicNotification: recordBuffer does not exist")
                readToDummy = true
            }
            else if (writeBuffer == null) {
                Log.v("Tuner", "MainActivity::onPeriodicNotification: cannot acquire write buffer")
                readToDummy = true
            }
            else {
                val recordAndData = RecordReaderThread.RecordAndData(recorder, writeBuffer)
                val handler = recordReader?.handler
                val message = handler?.obtainMessage(RecordReaderThread.READDATA, recordAndData)
                if (message != null){
                    handler.sendMessage(message)
                }
                else  {
                    audioData?.unlockWrite(writeBuffer)
                }
            }

            if(readToDummy)
                recorder.read(dummyAudioBuffer, 0, recorder.positionNotificationPeriod, AudioRecord.READ_BLOCKING)
        }
    }

    /// Inhere we trigger the processing of the audio data we just read
    private fun doPreprocessing(writeBuffer: CircularRecordData.WriteBuffer) {
        //Log.v("Tuner", "MainActivity:doPreprocessing")
        val startWrite = writeBuffer.startWrite
        val endWrite = startWrite + writeBuffer.size

        audioData?.unlockWrite(writeBuffer)

        val processingInterval = getProcessingInterval()
        // Log.v("Tuner", "MainActivity:onFinishedReadRecordData " + startWrite + " " + endWrite)

        // We might want to process audio on more than one thread in future
        for (i in startWrite + processingInterval..endWrite step processingInterval) {
            //Log.v("Tuner", "MainActivity:onFinishReadRecordData:loop: " + i)
            val startProcessingIndex = i - processingBufferSize

            if (startProcessingIndex >= 0) {

                val readBuffer = audioData?.lockRead(startProcessingIndex, processingBufferSize)
                if (readBuffer == null) {
                    Log.v(
                        "Tuner",
                        "MainActivity:doPreprocessing: Not able to get read access to recordBuffer"
                    )
                }
                else {
                    val results = preprocessingResults?.lockWrite()
                    if (results == null) {
                        Log.v(
                            "Tuner",
                            "MainActivity:doPreprocessing: Not able to get write access to the processingResults"
                        )
                        audioData?.unlockRead(readBuffer)
                    }
                    else {
                        //Log.v("Tuner", "MainActivity:doPreprocessing: Sending data to preprocessing thread")

                        val sendObject =
                            PreprocessorThread.ReadBufferAndProcessingResults(readBuffer, results)
                        val handler = preprocessor?.handler
                        val message =
                            handler?.obtainMessage(PreprocessorThread.PREPROCESS_AUDIO, sendObject)
                        if(message != null) {
                            handler.sendMessage(message)
                        }
                        else {
                            audioData?.unlockRead(readBuffer)
                            preprocessingResults?.unlockWrite(results)
                        }
                    }
                }
            }
            else {
                Log.v("Tuner", "MainActivity:doPreprocessing: Not enough data yet to preprocess")
            }
        }
    }

    private fun doPostprocessing(readBufferAndProcessingResults: PreprocessorThread.ReadBufferAndProcessingResults) {
        //Log.v("Tuner", "MainActivity:doPostprocessing")
        audioData?.unlockRead(readBufferAndProcessingResults.readBuffer)
        val numUnlocked =
            preprocessingResults?.unlockWrite(readBufferAndProcessingResults.preprocessingResults)
                ?: 0

        for (j in numUnlocked downTo 1) {
            val prepArray =
                Array(numBufferForPostprocessing) { i -> preprocessingResults?.lockRead(i - numBufferForPostprocessing + 1 - j) }

            if (prepArray.contains(null)) {
                for (pA in prepArray)
                    preprocessingResults?.unlockRead(pA)
                continue
            }

            val post = postprocessingResults?.lockWrite()

            // Make sure, that prepArray has only non-null entries and post is not null, otherwise unlock and go on
            if (post == null) {
                for (pA in prepArray)
                    preprocessingResults?.unlockRead(pA)
                continue
            }
            //Log.v("Tuner", "MainActivity:doPostprocessing : sending data to postprocessing thread")
            val sendObject =
                PostprocessorThread.PreprocessingDataAndPostprocessingResults(prepArray, post, getProcessingInterval())
            val handler = postprocessor?.handler
            val message = handler?.obtainMessage(PostprocessorThread.POSTPROCESS_AUDIO, sendObject)
            if(message != null) {
                handler.sendMessage(message)
            }
            else {
                for (pA in prepArray)
                    preprocessingResults?.unlockRead(pA)
                postprocessingResults?.unlockWrite(post)
            }
        }
    }

    private fun doVisualize(preprocessingDataAndPostprocessingResults: PostprocessorThread.PreprocessingDataAndPostprocessingResults) {
        //Log.v("Tuner", "MainActivity:doVisualize")
        val prepArray = preprocessingDataAndPostprocessingResults.preprocessingResults
        val post = preprocessingDataAndPostprocessingResults.postprocessingResults

        val numUnlocked = postprocessingResults?.unlockWrite(post) ?: 0
        if(numUnlocked == 0) {
            for (pA in prepArray)
                preprocessingResults?.unlockRead(pA)
            return
        }

        val postResults = postprocessingResults?.lockRead(-1)

        prepArray.last()?.let {result ->
            //Log.v("Tuner", "Max level: " + result.maxValue)
            volumeMeter?.let {
                val minAllowedVal = 10.0f.pow(it.minValue)
                val value = kotlin.math.max(minAllowedVal, result.maxValue)
                val spl = kotlin.math.log10(value)
                //Log.v("Tuner", "spl: " + spl)
                it.volume = spl

                val freq = result.idxMaxFreq * sampleRate / result.spectrum.size
                val pitchFreq = result.idxMaxPitch * sampleRate / result.spectrum.size
                Log.v("Tuner", "freq=" + freq + "   pitch="+pitchFreq)
            }
            spectrumPlotXMarks[0] = postResults?.frequency ?: 0f
            spectrumPlot?.setXMarks(spectrumPlotXMarks, false)
            spectrumPlot?.plot(frequencies, result.ampSpec)
        }

        postResults?.let {
            Log.v("Tuner", "freqcorr=" + it.frequency)
            frequencyText?.text = "frequency: " + it.frequency + "Hz"
            pitchHistory.copyInto(pitchHistory, 0, 1)
            pitchHistory[pitchHistory.lastIndex] = it.frequency
            pitchPlot?.plot(pitchHistory)
        }

        for (pA in prepArray)
            preprocessingResults?.unlockRead(pA)
        postprocessingResults?.unlockRead(postResults)
    }

    private fun getProcessingInterval() : Int {
        // overlapFraction=1 -> no overlap, overlapFraction=2 -> 50% overlap, overlapFraction=4 -> 25% overlap, ...
        return processingBufferSize / overlapFraction
    }
}
