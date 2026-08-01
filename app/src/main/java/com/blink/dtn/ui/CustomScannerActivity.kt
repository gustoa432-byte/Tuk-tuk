package com.blink.dtn.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import com.journeyapps.barcodescanner.CaptureManager
import com.journeyapps.barcodescanner.DecoratedBarcodeView

/**
 * QR capture with a centered viewport. Avoids top-heavy Spacer layouts that
 * push the preview off-screen on short / cutout / gesture-nav devices.
 */
class CustomScannerActivity : ComponentActivity() {
    private lateinit var captureManager: CaptureManager
    private lateinit var barcodeScannerView: DecoratedBarcodeView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        barcodeScannerView = DecoratedBarcodeView(this).apply {
            // Hide the library's own status / torch chrome — we draw our own.
            setStatusText("")
        }
        captureManager = CaptureManager(this, barcodeScannerView)
        captureManager.initializeFromIntent(intent, savedInstanceState)
        captureManager.decode()

        setContent {
            DisposableEffect(Unit) {
                onDispose { /* CaptureManager lifecycle via Activity callbacks */ }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0A0B0D))
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.78f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(20.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        AndroidView(
                            factory = { barcodeScannerView },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = "Наведите камеру на QR код",
                        color = Color.White,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        captureManager.onResume()
    }

    override fun onPause() {
        super.onPause()
        captureManager.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        captureManager.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        captureManager.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        captureManager.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
}
