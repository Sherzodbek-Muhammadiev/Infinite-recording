package uz.xsoft.myapplication

import android.Manifest
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.MediaPlayer
import android.os.Bundle
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import uz.xsoft.myapplication.databinding.ScreenMainBinding
import uz.xsoft.myapplication.source.local.AppDatabase


class MainScreen : Fragment(R.layout.screen_main) {
    private var _binding: ScreenMainBinding? = null
    private val binding: ScreenMainBinding get() = _binding!!
    private val videoDao by lazy { AppDatabase.getDatabase(requireContext()).doctorDao() }
    private val mp: MediaPlayer by lazy { MediaPlayer.create(requireContext(), R.raw.click) }

    private val cameraConnector = object : CameraConnector {
        override val context: Context get() = requireContext()
        override val surfaceView: AutoFitSurfaceView? get() = _binding?.textureView
        override val scope: CoroutineScope get() = lifecycleScope
        override val textTime: TextView get() = binding.textTime
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = ScreenMainBinding.bind(view)
        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        requireContext().checkPermission(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO) {
            val camera = CameraController5()
            camera.setConnector(cameraConnector)
            lifecycle.addObserver(camera)
            binding.buttonScreen.setOnClickListener {
                mp.start()
                camera.save { videoDao.insert(it) }
            }

        }
        binding.iconRecord.animation = AnimationUtils.loadAnimation(requireContext(), R.anim.alpha_animation)
//        binding.buttonGallery.setOnClickListener { findNavController().navigate(R.id.action_mainScreen2_to_galleryScreen) }
    }


    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}