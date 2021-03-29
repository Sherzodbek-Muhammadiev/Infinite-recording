package uz.xsoft.myapplication

import android.Manifest
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.delay

class SplashScreen : Fragment(R.layout.screen_splash) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        lifecycleScope.launchWhenStarted {
            delay(2000)
            requireContext().checkPermission(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO) {
                findNavController().navigate(SplashScreenDirections.openMainScreen())
            }
        }
    }

}