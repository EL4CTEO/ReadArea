package com.readarea

import androidx.lifecycle.ViewModelProvider

object TestIsolation {
    fun reset() {
        ViewModelProvider.AndroidViewModelFactory::class.java.getDeclaredField("_instance").apply { isAccessible = true }.set(null, null)
    }
}
