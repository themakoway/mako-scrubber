package com.mako.makoscrubber

import android.app.Application

class ScrubberApplication : Application() {
    val settings by lazy { MakoSettings(this) }
}