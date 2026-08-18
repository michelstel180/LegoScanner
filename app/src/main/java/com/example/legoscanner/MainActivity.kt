package com.example.legoscanner

import android.Manifest
import android.content.contentvalues.ContentValues.TAG
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage

// Datamodel voor een Lego Figuurtje
data class LegoFiguur(
    val id: String,
    val naam: String,
    val serie: String,
    val afbeeldingUrl: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LegoScannerApp()
                }
            }
        }
    }
}

@Composable
fun LegoScannerApp() {
    val context = LocalContext.current
    var gescandeCode by remember { mutableStateOf<String?>(null) }
    var gevondenFiguur by remember { mutableStateOf<LegoFiguur?>(null) }
    
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted -> hasCameraPermission = isGranted }
    )

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (hasCameraPermission) {
            CameraPreview(onBarcodeScanned = { rawCode ->
                if (rawCode != gescandeCode) {
                    gescandeCode = rawCode
                    gevondenFiguur = verwerkLegoCode(rawCode)
                }
            })
        } else {
            Text(
                text = "Cameratoegang is vereist om Lego-doosjes te scannen.",
                modifier = Modifier.align(Alignment.Center),
                padding = PaddingValues(16.dp)
            )
        }

        // Overlay onderin het scherm
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (gevondenFiguur != null) {
                    val figuur = gevondenFiguur!!
                    Text(text = figuur.serie, fontSize = 12.sp, color = Color.Gray)
                    Text(text = figuur.naam, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    AsyncImage(
                        model = figuur.afbeeldingUrl,
                        contentDescription = figuur.naam,
                        modifier = Modifier
                            .size(120.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Fit
                    )
                } else if (gescandeCode != null) {
                    Text(text = "Code Herkend!", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = gescandeCode!!, fontSize = 14.sp)
                    Text(
                        text = "Onbekend Lego-artikel of barcode niet in lokale database.",
                        fontSize = 12.sp,
                        color = Color.Red,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                } else {
                    Text(
                        text = "Scan de Data Matrix code op de onderkant van het Lego-doosje",
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
fun CameraPreview(onBarcodeScanned: (String) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val executor = ContextCompat.getMainExecutor(ctx)

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                val scanner = BarcodeScanning.getClient()

                imageAnalysis.setAnalyzer(executor) { imageProxy ->
                    val mediaImage = imageProxy.image
                    if (mediaImage != null) {
                        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                        scanner.process(image)
                            .addOnSuccessListener { barcodes ->
                                for (barcode in barcodes) {
                                    barcode.rawValue?.let { rawValue ->
                                        onBarcodeScanned(rawValue)
                                    }
                                }
                            }
                            .addOnFailureListener { Log.e(TAG, "Scanfout", it) }
                            .addOnCompleteListener { imageProxy.close() }
                    } else {
                        imageProxy.close()
                    }
                }

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Camera kan niet starten", e)
                }
            }, executor)

            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}

/**
 * Zet de gescande Data Matrix code van het Lego-doosje om naar een Minifiguur.
 * Lego Data Matrix op doosjes bevat een unieke 7-cijferige ID (bijv. "6471965").
 */
fun verwerkLegoCode(rawCode: String): LegoFiguur? {
    // Voorbeeld Database (Lego Minifigures Series 25/26 voorbeelden)
    val database = mapOf(
        "6471965" to LegoFiguur(
            id = "6471965",
            naam = "Film Noir Detective",
            serie = "Lego Minifigures Series 25",
            afbeeldingUrl = "https://img.bricklink.com/ItemImage/MN/0/col25-1.png"
        ),
        "6471966" to LegoFiguur(
            id = "6471966",
            naam = "E-Sports Gamer",
            serie = "Lego Minifigures Series 25",
            afbeeldingUrl = "https://img.bricklink.com/ItemImage/MN/0/col25-2.png"
        ),
        "6471967" to LegoFiguur(
            id = "6471967",
            naam = "Vampire Knight",
            serie = "Lego Minifigures Series 25",
            afbeeldingUrl = "https://img.bricklink.com/ItemImage/MN/0/col25-3.png"
        ),
        "6471968" to LegoFiguur(
            id = "6471968",
            naam = "Sprinter",
            serie = "Lego Minifigures Series 25",
            afbeeldingUrl = "https://img.bricklink.com/ItemImage/MN/0/col25-4.png"
        )
    )

    // Haal de eerste 7-cijferige code uit de gescande Data Matrix string
    val idMatch = Regex("\\b\\d{7}\\b").find(rawCode)
    val eanMatch = rawCode.trim()

    val teZoekenId = idMatch?.value ?: eanMatch

    return database[teZoekenId]
}
