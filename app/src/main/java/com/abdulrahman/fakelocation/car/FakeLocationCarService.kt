package com.abdulrahman.fakelocation.car

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.abdulrahman.fakelocation.nav.NavSession

/** Android Auto entry point: real-location navigation on the car screen. */
class FakeLocationCarService : CarAppService() {

    // A personal, sideloaded app: any Android Auto host may connect.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = CarSession()
}

class CarSession : Session() {

    override fun onCreateScreen(intent: Intent): Screen {
        val granted = ContextCompat.checkSelfPermission(carContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return CarMessageScreen(carContext)

        val renderer = CarMapRenderer(carContext, this)
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) = NavSession.acquire()
            override fun onDestroy(owner: LifecycleOwner) = NavSession.release()
        })
        return CarMapScreen(carContext, renderer)
    }
}
