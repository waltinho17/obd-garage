package com.walter.dashobd.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.location.LocationManagerCompat
import com.walter.dashobd.ConnectionState
import com.walter.dashobd.ble.BleDevice

private val LIKELY_ADAPTER = Regex("(?i)(vlink|v-link|icar|vgate|obd|elm)")

private fun BleDevice.likelyAdapter() = name?.let { LIKELY_ADAPTER.containsMatchIn(it) } == true

@Composable
fun ScanScreen(
    devices: List<BleDevice>,
    state: ConnectionState,
    scanError: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPick: (BleDevice) -> Unit,
    onDismissError: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    DisposableEffect(Unit) {
        onStart()
        onDispose { onStop() }
    }

    // No Android 11 e anterior, a busca BLE só retorna resultados com a localização ligada.
    val context = LocalContext.current
    val locationOff = Build.VERSION.SDK_INT <= Build.VERSION_CODES.R &&
        !LocationManagerCompat.isLocationEnabled(context.getSystemService(android.location.LocationManager::class.java))

    // Adaptadores prováveis primeiro, depois por sinal; sem nome por último.
    val sorted = devices.sortedWith(
        compareByDescending<BleDevice> { it.likelyAdapter() }.thenByDescending { it.name != null }.thenByDescending { it.rssi },
    )
    val busy = state is ConnectionState.Connecting

    Column(Modifier.fillMaxSize().background(DashColors.Background).padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().height(84.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigOutlinedButton("Voltar", onBack, Modifier.width(130.dp), enabled = !busy)
            Column(Modifier.weight(1f)) {
                FitText("Escolher adaptador", 28.sp, Modifier.fillMaxWidth(), fontWeight = FontWeight.SemiBold)
                FitText(
                    if (busy) "Conectando…" else "Ligue a ignição e deixe o adaptador no conector OBD",
                    17.sp, Modifier.fillMaxWidth(), color = DashColors.TextSecondary,
                )
            }
            if (busy) CircularProgressIndicator(Modifier.height(28.dp))
        }

        if (locationOff) {
            Notice("Ligue a localização do aparelho: o Android exige isso para buscar dispositivos Bluetooth.")
        }
        scanError?.let { Notice(it) }
        if (state is ConnectionState.Failed) {
            Notice("Falha ao conectar: ${state.message}") {
                BigOutlinedButton("Ok", onDismissError, Modifier.width(110.dp))
            }
        }

        if (sorted.isEmpty() && scanError == null) {
            Text("Procurando dispositivos…", color = DashColors.TextSecondary, fontSize = 22.sp, modifier = Modifier.padding(top = 16.dp))
        }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sorted, key = { it.address }) { d ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(DashColors.Surface)
                        .clickable(enabled = !busy) { onPick(d) }
                        .padding(horizontal = 18.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    FitText(d.name ?: "Sem nome", 26.sp, Modifier.fillMaxWidth(), fontWeight = FontWeight.SemiBold)
                    FitText(
                        "${d.address} · sinal ${d.rssi} dBm" + if (d.likelyAdapter()) " · provável adaptador" else "",
                        17.sp, Modifier.fillMaxWidth(),
                        color = if (d.likelyAdapter()) DashColors.Accent else DashColors.TextSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun Notice(text: String, action: @Composable (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(DashColors.Warn.copy(alpha = 0.15f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, color = DashColors.TextPrimary, fontSize = 18.sp, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}
