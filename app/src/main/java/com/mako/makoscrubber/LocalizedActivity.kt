package com.mako.makoscrubber

import android.content.Context
import androidx.activity.ComponentActivity

/**
 * Base activity that applies the user's chosen interface language (see [LocaleHelper]).
 * Every activity that shows UI should extend this so the override is consistent across screens.
 */
open class LocalizedActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }
}
