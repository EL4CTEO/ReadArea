package com.readarea

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.readarea.data.SafeFiles
import com.readarea.reader.ReaderActivity

class OpenBookActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.data
        if (savedInstanceState == null && intent?.action == Intent.ACTION_VIEW && data != null && !SafeFiles.isPrivate(this, data)) {
            runCatching {
                startActivity(
                    Intent(this, ReaderActivity::class.java)
                        .setAction(Intent.ACTION_VIEW)
                        .setDataAndType(data, intent.type)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                )
            }
        }
        finish()
    }
}
