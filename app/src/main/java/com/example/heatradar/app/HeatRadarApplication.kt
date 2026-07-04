package com.example.heatradar.app

import android.app.Application
import com.example.heatradar.core.monitor.RealSampler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class HeatRadarApplication : Application() {

    @Inject
    lateinit var realSampler: RealSampler

    override fun onCreate() {
        super.onCreate()
        realSampler.start()
    }
}
