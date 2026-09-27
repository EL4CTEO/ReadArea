package com.readarea.qa

import android.content.ContentResolver
import android.net.Uri
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowContentResolver

@Implements(ContentResolver::class)
class StrictContentResolver : ShadowContentResolver() {
    @Implementation
    override fun takePersistableUriPermission(uri: Uri, modeFlags: Int) {
        throw SecurityException("No persistable permission grants found for $uri")
    }
}
