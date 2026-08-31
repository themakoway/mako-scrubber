package com.mako.makoscrubber

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder

class ScrubberApplication : Application(), ImageLoaderFactory {
    val settings by lazy { MakoSettings(this) }

    // Apply the user's chosen interface language app-wide (see LocaleHelper). Activities also
    // wrap their own context via LocalizedActivity; this covers app-context lookups and sets
    // the default Locale early.
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    // Lets AsyncImage render a poster frame for scrubbed videos in the dashboard grid.
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components { add(VideoFrameDecoder.Factory()) }
            .build()
}
