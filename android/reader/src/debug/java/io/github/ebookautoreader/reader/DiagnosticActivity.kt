package io.github.ebookautoreader.reader

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Debug-only ADB entry point; the manifest requires android.permission.DUMP. */
class DiagnosticActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent.getStringExtra("test")) {
            "fixture" -> {
                val page = intent.getStringExtra("page") ?: "join-a"
                if (page in setOf("join-a", "join-b", "short-a", "short-b", "short-c",
                        "body-narrow", "body-medium", "body-dense", "body-bottom", "chapter")) {
                    startActivity(Intent(this, FixtureActivity::class.java).putExtra("page", page))
                } else ReaderDiagnostics.event("diagnostic rejected reason=invalid_fixture")
            }
            "voice" -> {
                VoiceDiagnosticRequest.arm()
                startActivity(Intent(this, ReaderActivity::class.java))
            }
            else -> ReaderDiagnostics.event("diagnostic rejected reason=invalid_test")
        }
        finish()
    }
}
