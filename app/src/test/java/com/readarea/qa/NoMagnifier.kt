package com.readarea.qa

import android.widget.Magnifier
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@Implements(value = Magnifier::class, minSdk = 29)
class NoMagnifier {
    @Implementation
    fun show(sourceCenterX: Float, sourceCenterY: Float) {}

    @Implementation
    fun dismiss() {}
}
