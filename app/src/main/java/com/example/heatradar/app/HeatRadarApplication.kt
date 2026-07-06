package com.example.heatradar.app

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.heatradar.core.monitor.RealSampler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class HeatRadarApplication : Application(), DefaultLifecycleObserver {

    @Inject
    lateinit var realSampler: RealSampler

    override fun onCreate() {
        super<Application>.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        realSampler.start()
    }

    override fun onStart(owner: LifecycleOwner) {
        realSampler.onAppForeground()
    }

    override fun onStop(owner: LifecycleOwner) {
        realSampler.onAppBackground()
    }
}
