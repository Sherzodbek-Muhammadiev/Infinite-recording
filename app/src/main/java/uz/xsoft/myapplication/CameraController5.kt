package uz.xsoft.myapplication

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.hardware.Camera
import android.hardware.Camera.CameraInfo
import android.hardware.camera2.*
import android.media.ImageReader
import android.media.MediaRecorder
import android.opengl.GLES20
import android.opengl.GLES20.*
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.*
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.core.graphics.scale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import uz.xsoft.*
import uz.xsoft.gles.*
import uz.xsoft.myapplication.source.local.entity.VideoData
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine


class CameraController5 : LifecycleObserver {
    private val VIDEO_WIDTH = 1920 // dimensions for 720p video
    private val VIDEO_HEIGHT = 1080
    private val DESIRED_PREVIEW_FPS = 15

    private var mEglCore: EglCore? = null
    private var mDisplaySurface: WindowSurface? = null

    // receives the output from the camera preview
    private var mCameraTexture: SurfaceTexture? = null
    private var mFullFrameBlit: FullFrameRect? = null
    private var mTmpMatrix = FloatArray(16)
    private var mTextureId = 0
    private var mFrameNum = 0
    private var mCameraPreviewThousandFps = 15
    private var mCircEncoder: CircularEncoder? = null
    private var mEncoderSurface: WindowSurface? = null
    private var mFileSaveInProgress = false
    private var mCameraSurface: Surface? = null
    private var mCamera: Camera? = null

    private var mSecondsOfVideo = 0f

    private val handlerCallback = object : HandlerCallback {
        override fun onSaveCompleted(status: Int) {

        }

        override fun onFrameDraw() {
            drawFrame()
        }

        @SuppressLint("SetTextI18n")
        override fun onUpdateBuffer(duration: Long) {
            fun Float.toTime() = if (this < 10) "0${this.toInt()}" else "${this.toInt()}"
            val time = duration / 1000000.0f
            val min = time / 60
            val sec = time % 60
            connector.textTime?.text = "${min.toTime()}:${sec.toTime()}"
        }
    }

    private var mHandler: MainHandler = MainHandler(handlerCallback).apply {
        sendEmptyMessageDelayed(MainHandler.MSG_BLINK_TEXT, 1500)
    }

    private val frameListener = SurfaceTexture.OnFrameAvailableListener { mHandler!!.sendEmptyMessage(MainHandler.MSG_FRAME_AVAILABLE) }

    /**
     * Draws a frame onto the SurfaceView and the encoder surface.
     *
     *
     * This will be called whenever we get a new preview frame from the camera.  This runs
     * on the UI thread, which ordinarily isn't a great idea -- you really want heavy work
     * to be on a different thread -- but we're really just throwing a few things at the GPU.
     * The upside is that we don't have to worry about managing state changes between threads.
     *
     *
     * If there was a pending frame available notification when we shut down, we might get
     * here after onPause().
     */
    private fun drawFrame() {
        //Log.d(TAG, "drawFrame");
        if (mEglCore == null) {
//            Log.d(com.android.grafika.ContinuousCaptureActivity.TAG, "Skipping drawFrame after shutdown")
            return
        }

        // Latch the next frame from the camera.
        mDisplaySurface!!.makeCurrent()
        mCameraTexture!!.updateTexImage()
        mCameraTexture!!.getTransformMatrix(mTmpMatrix)

        val viewWidth = connector.surfaceView?.width ?: 0
        val viewHeight = connector.surfaceView?.height ?: 0
        GLES20.glViewport(0, 0, viewWidth, viewHeight)
        mFullFrameBlit!!.drawFrame(mTextureId, mTmpMatrix)
        drawExtra(mFrameNum, viewWidth, viewHeight)
        mDisplaySurface!!.swapBuffers()

        // Send it to the video encoder.
        if (!mFileSaveInProgress) {
            mEncoderSurface!!.makeCurrent()
            GLES20.glViewport(0, 0, VIDEO_WIDTH, VIDEO_HEIGHT)
            mFullFrameBlit!!.drawFrame(mTextureId, mTmpMatrix)
            drawExtra(mFrameNum, VIDEO_WIDTH, VIDEO_HEIGHT)
            mCircEncoder!!.frameAvailableSoon()
            mEncoderSurface!!.setPresentationTime(mCameraTexture!!.timestamp)
            mEncoderSurface!!.swapBuffers()
        }
        mFrameNum++
    }

    private val bitmap by lazy { "Salom".asBitmap() }

    private fun loadTexture(): Int {
        val textureHandle = IntArray(1)
        GLES20.glGenTextures(1, textureHandle, 0)
        if (textureHandle[0] != 0) {
            // Bind to the texture in OpenGL
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandle[0])

            // Set filtering
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)

            // Load the bitmap into the bound texture.
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        }
        if (textureHandle[0] == 0) {
            throw java.lang.RuntimeException("Error loading texture.")
        }
        return textureHandle[0]
    }


    /**
     * Adds a bit of extra stuff to the display just to give it flavor.
     */
    private var textId = 0
    private val textures = IntArray(1)

    private val bufferByte by lazy {
        val size = bitmap.rowBytes * bitmap.height
        val b: ByteBuffer = ByteBuffer.allocate(size)
        bitmap.copyPixelsToBuffer(b)
        b
    }

    /* fun draw(texture: Int) {
         val program = mFullFrameBlit!!.program
         glBindFramebuffer(GL_FRAMEBUFFER, 0)
         glUseProgram(program)
         glDisable(GL_BLEND)
         val positionHandle = glGetAttribLocation(program, "aPosition")
         val textureHandle = glGetUniformLocation(program, "uTexture")
         val texturePositionHandle = glGetAttribLocation(program, "aTexPosition")
         glVertexAttribPointer(texturePositionHandle, 2, GL_FLOAT, false, 0, textureBuffer)
         glEnableVertexAttribArray(texturePositionHandle)
         glActiveTexture(GL_TEXTURE0)
         glBindTexture(GL_TEXTURE_2D, texture)
         glUniform1i(textureHandle, 0)
         glVertexAttribPointer(positionHandle, 2, GL_FLOAT, false, 0, verticesBuffer)
         glEnableVertexAttribArray(positionHandle)
         glClear(GL_COLOR_BUFFER_BIT)
         glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
     }
     */
    private val id by lazy { createAndBindTexture(bitmap) }


    private fun drawExtra(frameNum: Int, width: Int, height: Int) {
//        val mTextureUniformHandle = GLES20.glGetUniformLocation(, "u_Texture");
//        val mTextureCoordinateHandle = GLES20.glGetAttribLocation(mProgramHandle, "a_TexCoordinate");

        // Set the active texture unit to texture unit 0.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);

        // Bind the texture to this unit.
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textId);

        // Tell the texture uniform sampler to use this texture in the shader by binding to texture unit 0.
//        GLES20.glUniform1i(mTextureUniformHandle, 0);
        //DEMO 1
        /*val buffer = ByteBuffer.allocate(bitmap.byteCount)
            bitmap.copyPixelsToBuffer(buffer)
            GlUtil.createImageTexture(buffer, bitmap.width, bitmap.height, GL_RGBA)*/

        //DEMO 2


        //DEMO 3
//        GlUtil.createImageTexture(bufferByte, bitmap.width, bitmap.height, GL_RGBA)

        //DEMO 4
//        loadTexture(connector.context, R.drawable.logo)

        //DEMO 5
        /*val textureObjectIds = IntArray(1)
        glGenTextures(1, textureObjectIds, 0)
        setBitmap(bitmap,textureObjectIds[0] )*/

        //DEMO 6
//        mFullFrameBlit!!.drawFrame(id, mTmpMatrix)


        // We "draw" with the scissor rect and clear calls.  Note this uses window coordinates.
        /* when (frameNum % 3) {
             0 -> GLES20.glClearColor(1.0f, 0.0f, 0.0f, 1.0f)
             1 -> GLES20.glClearColor(0.0f, 1.0f, 0.0f, 1.0f)
             2 -> GLES20.glClearColor(0.0f, 0.0f, 1.0f, 1.0f)
         }
         val xPos = (width * (frameNum % 100 / 100.0f)).toInt()
         GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
         GLES20.glScissor(0, 0, width / 32, height / 32)
         GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
         GLES20.glDisable(GLES20.GL_SCISSOR_TEST)*/
//        connector.demoView.setImageBitmap("Salom".asBitmap(70f))
    }

    private fun createAndBindTexture(texture: Bitmap?): Int {
        if (texture == null) {
            return 0
        }
        val textureObjectIds = IntArray(1)
        glGenTextures(1, textureObjectIds, 0)
        if (textureObjectIds[0] == 0) {
            return 0
        }
        GlUtil.checkGlError("glGenTextures");
        glBindTexture(GL_TEXTURE_2D, textureObjectIds[0])

        // 纹理过滤
        // 纹理缩小的时候使用三线性过滤
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        // 纹理放大的时候使用双线性过滤
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        // 设置环绕方向S，截取纹理坐标到[1/2n,1-1/2n]。将导致永远不会与border融合
        glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE.toFloat())
        // 设置环绕方向T，截取纹理坐标到[1/2n,1-1/2n]。将导致永远不会与border融合
        glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE.toFloat())
        GlUtil.checkGlError("loadImageTexture");
        // 加载位图到opengl中
        GLUtils.texImage2D(GL_TEXTURE_2D, 0, texture, 0)
//        texture.recycle()
        GlUtil.checkGlError("loadImageTexture")
        // 生成mip贴图
        glGenerateMipmap(GL_TEXTURE_2D)

        // 既然我们已经完成了纹理的加载，现在需要和纹理解除绑定
        glBindTexture(GL_TEXTURE_2D, 0)
        return textureObjectIds[0]
    }

    fun loadTexture(context: Context, @DrawableRes resId: Int): Int {
        val textureObjectIds = IntArray(1)
        GLES20.glGenTextures(1, textureObjectIds, 0)
        if (textureObjectIds[0] == 0) {
//            Log.e(TAG, "Could not generate a new OpenGL texture object.")
            return 0
        }
        val options = BitmapFactory.Options()
        options.inScaled = false
        val bitmap = BitmapFactory.decodeResource(context.resources, resId, options)
        if (bitmap == null) {
//            Log.e(TAG, "Resource ID $resId could not be decoded.")
            GLES20.glDeleteTextures(1, textureObjectIds, 0)
            return 0
        }

        // bind
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureObjectIds[0])
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR_MIPMAP_LINEAR
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR
        )
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        // unbind
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return textureObjectIds[0]
    }


    fun setBitmap(bitmap: Bitmap?, textureId: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glEnable(GLES20.GL_BLEND) // this, and the next line
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA) // and this were key! I'm still not completely sure as to what this is doing, but it works!
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, bitmap, 0)
    }

    fun fromText(text: String?, textSize: Int, @ColorInt textColor: Int): Bitmap? {
        val paint = Paint()
        paint.textSize = textSize.toFloat()
        paint.color = Color.WHITE
        val baseline = -paint.ascent() // ascent() is negative
        val width = (paint.measureText(text) + 1.0f).toInt()
        val height = (baseline + paint.descent() + 1.0f).toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setHasAlpha(true)
        val canvas = Canvas(bitmap)
        // canvas.drawColor(Color.argb(0, 255, 255, 255));
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        canvas.drawText(text!!, 0f, baseline, paint)
        return bitmap
    }

    /**
     * ******************************************
     * */


    /** [HandlerThread] where all camera operations run */
    private val cameraThread = HandlerThread("CameraThread").apply { start() }

    /** [Handler] corresponding to [cameraThread] */
    private val cameraHandler = Handler(cameraThread.looper)

    private val cameraOpenCloseLock = Semaphore(1)

    private lateinit var rootFolder: File

    @SuppressLint("SimpleDateFormat")
    private fun createRootPath() = File(connector.context.filesDir!!, SimpleDateFormat("dd.M.yyyy hh:mm:ss").format(Date())).apply { mkdir() }


    /**
     * Camera and preview size
     * */
    private lateinit var previewSize: Size
    private lateinit var videoSize: Size


    /**
     * for recording time store
     * */
    private var startTime = AtomicLong(System.currentTimeMillis())

    /**
     * Connector connects with UI
     * */
    private var _cameraConnector: CameraConnector? = null
    private val connector get() = _cameraConnector!!

    /**
     * Selected camera id. I use 0 camera
     * */
    private val cameraId by lazy { cameraManager.cameraIdList[0] }

    /** [CameraCharacteristics] corresponding to the provided Camera ID */
    private val characteristics: CameraCharacteristics by lazy {
        cameraManager.getCameraCharacteristics(cameraId)
    }

    /** Detects, characterizes, and connects to a CameraDevice (used for all camera operations) */
    private val cameraManager: CameraManager by lazy {
        val context = connector.context.applicationContext
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    /** Captures frames from a [CameraDevice] for our video recording */
    private lateinit var captureSession: CameraCaptureSession

    /** The [CameraDevice] that will be opened in this fragment */
    private var cameraDevice: CameraDevice? = null

    @Volatile
    private var currentPart = AtomicInteger(0)

    private val isActive = AtomicBoolean()

    @OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
    fun connect() {
        Timber.d("connect()")
        startBackgroundThread()
        isActive.set(true)
        _cameraConnector?.surfaceView?.holder?.addCallback(surfaceCallback)
        if (mCamera == null) {
            // Ideally, the frames from the camera are at the same resolution as the input to
            // the video encoder so we don't have to scale.
            openCamera(VIDEO_WIDTH, VIDEO_HEIGHT, DESIRED_PREVIEW_FPS)
        }
        if (mEglCore != null) {
            startPreview()
        }
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    fun disconnect() {
        isActive.set(false)
        Timber.d("disconnect()")
        closeCamera()
        stopBackgroundThread()
        _cameraConnector?.surfaceView?.holder?.removeCallback(surfaceCallback)

        releaseCamera()

        if (mCircEncoder != null) {
            mCircEncoder!!.shutdown()
            mCircEncoder = null
        }
        if (mCameraTexture != null) {
            mCameraTexture!!.release()
            mCameraTexture = null
        }
        if (mDisplaySurface != null) {
            mDisplaySurface!!.release()
            mDisplaySurface = null
        }
        if (mFullFrameBlit != null) {
            mFullFrameBlit!!.release(false)
            mFullFrameBlit = null
        }
        if (mEglCore != null) {
            mEglCore!!.release()
            mEglCore = null
        }
    }

    fun setConnector(cameraConnector: CameraConnector) {
        this._cameraConnector = cameraConnector
        cameraConnector.surfaceView?.holder?.addCallback(surfaceCallback)

    }

    /**
     * Stops camera preview, and releases the camera to the system.
     */
    private fun releaseCamera() {
        if (mCamera != null) {
            mCamera!!.stopPreview()
            mCamera!!.release()
            mCamera = null
        }
    }

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

        override fun surfaceCreated(holder: SurfaceHolder) {
            configCameraSize()
            // Selects appropriate preview size and configures view finder
            connector.surfaceView?.setAspectRatio(previewSize.width, previewSize.height)
            // To ensure that size is set, initialize camera in the view's thread
//            connector.surfaceView?.post { initializeCamera() }

            // Set up everything that requires an EGL context.
            //
            // We had to wait until we had a surface because you can't make an EGL context current
            // without one, and creating a temporary 1x1 pbuffer is a waste of time.
            //
            // The display surface that we use for the SurfaceView, and the encoder surface we
            // use for video, use the same EGL context.

            // Set up everything that requires an EGL context.
            //
            // We had to wait until we had a surface because you can't make an EGL context current
            // without one, and creating a temporary 1x1 pbuffer is a waste of time.
            //
            // The display surface that we use for the SurfaceView, and the encoder surface we
            // use for video, use the same EGL context.


            // Try to set the frame rate to a constant value.

            // Try to set the frame rate to a constant value.
            mEglCore = EglCore(null, EglCore.FLAG_RECORDABLE)
            mDisplaySurface = WindowSurface(mEglCore, holder.surface, false)
            mDisplaySurface?.makeCurrent()
            mFullFrameBlit = FullFrameRect(
                Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_EXT)
            )
            mTextureId = mFullFrameBlit!!.createTextureObject()
//            textId = mFullFrameBlit!!.createTextureObject()
            mCameraTexture = SurfaceTexture(mTextureId)
            mCameraTexture?.setOnFrameAvailableListener(frameListener)
            mCameraSurface = Surface(mCameraTexture)
            textId = loadTexture(connector.context, R.drawable.logo)
//            mProgramHandle = ShaderHelper.createAndLinkProgram(vertexShaderHandle, fragmentShaderHandle, arrayOf("a_Position", "a_Color", "a_Normal", "a_TexCoordinate"))
//            initializeCamera()
            startPreview()
        }
    }

    private fun openCamera(desiredWidth: Int, desiredHeight: Int, desiredFps: Int) {
        if (mCamera != null) {
            throw java.lang.RuntimeException("camera already initialized")
        }
        val info = CameraInfo()

        // Try to find a front-facing camera (e.g. for videoconferencing).
        val numCameras = Camera.getNumberOfCameras()
        for (i in 0 until numCameras) {
            Camera.getCameraInfo(i, info)
            if (info.facing == CameraInfo.CAMERA_FACING_BACK) {
                mCamera = Camera.open(i)
                break
            }
        }
        if (mCamera == null) {
            mCamera = Camera.open() // opens first back-facing camera
        }
        if (mCamera == null) {
            throw java.lang.RuntimeException("Unable to open camera")
        }
        val parms = mCamera!!.parameters
        CameraUtils.choosePreviewSize(parms, desiredWidth, desiredHeight)

        // Try to set the frame rate to a constant value.
        mCameraPreviewThousandFps = CameraUtils.chooseFixedPreviewFps(parms, desiredFps * 1000)

        // Give the camera a hint that we're recording video.  This can have a big
        // impact on frame rate.
        parms.setRecordingHint(true)
        mCamera!!.parameters = parms
        val cameraPreviewSize = parms.previewSize
        val previewFacts = cameraPreviewSize.width.toString() + "x" + cameraPreviewSize.height +
                " @" + mCameraPreviewThousandFps / 1000.0f + "fps"
        val display = (connector.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        if (display.rotation == Surface.ROTATION_0) {
            mCamera!!.setDisplayOrientation(90)
            connector.surfaceView?.setAspectRatio(cameraPreviewSize.width, cameraPreviewSize.height)
//            layout.setAspectRatio(cameraPreviewSize.height.toDouble() / cameraPreviewSize.width)
        } else if (display.rotation == Surface.ROTATION_270) {
//            layout.setAspectRatio(cameraPreviewSize.height.toDouble() / cameraPreviewSize.width)
            connector.surfaceView?.setAspectRatio(cameraPreviewSize.width, cameraPreviewSize.height)
            mCamera!!.setDisplayOrientation(180)
        } else {
            // Set the preview aspect ratio.
            connector.surfaceView?.setAspectRatio(cameraPreviewSize.width, cameraPreviewSize.height)
//            layout.setAspectRatio(cameraPreviewSize.width.toDouble() / cameraPreviewSize.height)
        }
    }


    private fun startPreview() {
        if (mCamera != null) {
            try {
                mCamera?.setPreviewTexture(mCameraTexture)
            } catch (ioe: IOException) {
                throw java.lang.RuntimeException(ioe)
            }
            mCamera?.startPreview()
        }

        // TODO: adjust bit rate based on frame rate?
        // TODO: adjust video width/height based on what we're getting from the camera preview?
        //       (can we guarantee that camera preview size is compatible with AVC video encoder?)
        mCircEncoder = try {
            CircularEncoder(VIDEO_WIDTH, VIDEO_HEIGHT, 6000000, mCameraPreviewThousandFps / 1000, 60, mHandler)
        } catch (ioe: IOException) {
            throw RuntimeException(ioe)
        }
        mEncoderSurface = WindowSurface(mEglCore, mCircEncoder!!.inputSurface, true)
//        updateControls()
    }


    /** Requests used for preview only in the [CameraCaptureSession] */
    private val previewRequest: CaptureRequest by lazy {
        // Capture request holds references to target surfaces
        captureSession.device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(mCameraSurface!!)
//            set(CaptureRequest.JPEG_ORIENTATION, 1)
        }.build()
    }

    private fun initializeCamera() = connector.scope.launch(Dispatchers.Main) {
        connector.surfaceView?.holder?.surface ?: return@launch
        // Open the selected camera
        cameraDevice = openCamera(cameraManager, cameraId, cameraHandler)


        // Creates list of Surfaces where the camera will output frames
        val targets = listOf(mCameraSurface!!)

        // Start a capture session using our open camera and list of Surfaces where frames will go
        captureSession = createCaptureSession(cameraDevice!!, targets, cameraHandler)

        // Sends the capture request as frequently as possible until the session is torn down or
        //  session.stopRepeating() is called
        captureSession.setRepeatingRequest(previewRequest, null, cameraHandler)
        startPreview()
//        startRecording()
    }


    private val rootPath by lazy { connector.context.filesDir }

    @SuppressLint("SimpleDateFormat")
    fun save(block: suspend (VideoData) -> Unit) = connector.scope.launch(Dispatchers.IO) {
        val file = File(rootPath, "dash_cam_" + SimpleDateFormat("dd_M_yyyy_#_hh_mm_ss").format(Date()) + ".mp4")
        val videoData = VideoData(0, file.absolutePath, Calendar.getInstance().timeInMillis)
        mCircEncoder!!.saveVideo(file)
        block(videoData)
    }


    /** Opens the camera and returns the opened device (as the result of the suspend coroutine) */
    @SuppressLint("MissingPermission")
    private suspend fun openCamera(
        manager: CameraManager,
        cameraId: String,
        handler: Handler? = null
    ): CameraDevice = suspendCancellableCoroutine { cont ->
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) = cont.resume(device)

            override fun onDisconnected(device: CameraDevice) {
                Timber.e("Camera $cameraId has been disconnected")
//                requireActivity().finish()
            }

            override fun onError(device: CameraDevice, error: Int) {
                val msg = when (error) {
                    ERROR_CAMERA_DEVICE -> "Fatal (device)"
                    ERROR_CAMERA_DISABLED -> "Device policy"
                    ERROR_CAMERA_IN_USE -> "Camera in use"
                    ERROR_CAMERA_SERVICE -> "Fatal (service)"
                    ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                    else -> "Unknown"
                }
                val exc = RuntimeException("Camera $cameraId error: ($error) $msg")
                Timber.e(exc)
                if (cont.isActive) cont.resumeWithException(exc)
            }
        }, handler)
    }


    /**
     * It is code splitter
     * */

    private fun startBackgroundThread() {
        Timber.d("startBackgroundThread()")
        /* backgroundThread = HandlerThread("CameraBackground")
         backgroundThread?.start()
         backgroundHandler = Handler(backgroundThread?.looper!!)*/
    }

    private fun stopBackgroundThread() {
        Timber.d("stopBackgroundThread()")
        //backgroundThread?.quitSafely()
        try {
            /* backgroundThread?.join()
             backgroundThread = null
             backgroundHandler = null*/
        } catch (e: InterruptedException) {
            Timber.e(e)
        }
    }

    private fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            closePreviewSession()
            cameraDevice?.close()
            cameraDevice = null

        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.", e)
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    private fun closePreviewSession() {
        if (::captureSession.isInitialized) captureSession.close()
    }

    private fun configCameraSize() {
        videoSize = chooseVideoSize()
        previewSize = Size(videoSize.width, videoSize.height)//chooseVideoSize(sizes)
        Timber.tag("TTT").d("videoSize=$videoSize")
    }

    private fun chooseVideoSize(): Size {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return Size(1920, 1080)
        val size = map.getOutputSizes(ImageReader::class.java)
        val size1 = map.getOutputSizes(MediaRecorder::class.java)
        val size2 = map.getOutputSizes(SurfaceTexture::class.java)
        size1.forEach {
            Timber.tag("TTT").d("size1=$it")
        }
        size2.forEach {
            Timber.tag("TTT").d("size2=$it")
        }

        size.forEach {
            Timber.tag("TTT").d("size=$it")
        }
        val fSize = size.filter { it.height.toFloat() / it.width == 1080f / 1920f }.sortedBy { it.width }
        fSize.forEach {
            Timber.tag("TTT").d("fsize=$it")
        }
        return fSize.firstOrNull { it.height >= 1080 } ?: size.first { it.width == it.height * 4 / 3 && it.width <= 720 }
    }

    /**
     * Creates a [CameraCaptureSession] and returns the configured session (as the result of the
     * suspend coroutine)
     */
    private suspend fun createCaptureSession(
        device: CameraDevice,
        targets: List<Surface>,
        handler: Handler? = null
    ): CameraCaptureSession = suspendCoroutine { cont ->

        // Creates a capture session using the predefined targets, and defines a session state
        // callback which resumes the coroutine once the session is configured
        device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {

            override fun onConfigured(session: CameraCaptureSession) = cont.resume(session)

            override fun onConfigureFailed(session: CameraCaptureSession) {
                val exc = RuntimeException("Camera ${device.id} session configuration failed")
                Timber.e(exc)
                cont.resumeWithException(exc)
            }
        }, handler)
    }

    private val logoBitmap by lazy { BitmapFactory.decodeResource(connector.context.resources, R.drawable.logo).scale(72, 72) }

    private val dataPosition by lazy { PointF(videoSize.width * 0.8f, videoSize.height * 0.95f) }
    private val speedPosition by lazy { PointF(videoSize.width * 0.48f, videoSize.height * 0.95f) }


    fun Float.round(decimals: Int): Float {
        var multiplier = 1.0f
        repeat(decimals) { multiplier *= 10 }
        return kotlin.math.round(this * multiplier) / multiplier
    }
}