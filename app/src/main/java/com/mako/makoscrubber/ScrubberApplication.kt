package com.mako.makoscrubber

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder

class ScrubberApplication : Application(), ImageLoaderFactory {
    val settings by lazy { MakoSettings(this) }

    // Lets AsyncImage render a poster frame for scrubbed videos in the dashboard grid.
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components { add(VideoFrameDecoder.Factory()) }
            .build()
}
