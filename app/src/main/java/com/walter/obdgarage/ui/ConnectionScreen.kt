package com.walter.obdgarage.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.walter.obdgarage.ConnectionState
import com.walter.obdgarage.ble.BleDevice

private val bluetoothPermissions: Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

/** Pedidos juntos na primeira vez: Bluetooth (obrigatório) e notificações (Android 13+, para o aviso de leitura ativa). */
private val requestedPermissions: Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) bluetoothPermissions + Manifest.permission.POST_NOTIFICATIONS
    else bluetoothPermissions

@Composable
fun ConnectionScreen(
    state: ConnectionState,
    logCount: Int,
    lastDevice: BleDevice?,
    onConnectSimulated: () -> Unit,
    onOpenScan: () -> Unit,
    onReconnect: (BleDevice) -> Unit,
    onOpenLogs: () -> Unit,
    onDismissError: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    var permissionDenied by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        // A notificação do serviço é opcional; só o Bluetooth é obrigatório.
        val granted = bluetoothPermissions.all { result[it] == true }
        permissionDenied = !granted
        if (granted) pending?.invoke()
        pending = null
    }

    /** Roda [action] já com as permissões de Bluetooth concedidas, pedindo se for preciso. */
    fun withPermission(action: () -> Unit) {
        val granted = bluetoothPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (granted) action() else {
            pending = action
            launcher.launch(requestedPermissions)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val titleSize = (minOf(maxWidth, maxHeight).value * 0.13f).coerceIn(36f, 60f).sp
        val busy = state is ConnectionState.Connecting

        // Altura dos botões: em paisagem a tela é baixa (~360 dp), então eles se encolhem para caber todos sem rolar.
        val buttonCount = (if (lastDevice != null) 2 else 1) + 2 + (if (logCount > 0) 1 else 0)
        val pad = if (landscape) 16.dp else 24.dp
        val gap = if (landscape) 10.dp else 14.dp
        val btnH = if (landscape) ((maxHeight - pad * 2 - gap * (buttonCount - 1)) / buttonCount).coerceIn(48.dp, BigButtonHeight) else BigButtonHeight

        val header: @Composable ColumnScope.() -> Unit = {
            Text("OBD Garage", fontSize = titleSize, fontWeight = FontWeight.Bold, color = DashColors.TextPrimary)
            Text("Só leitura · não escreve nada no carro", color = DashColors.TextSecondary, fontSize = 18.sp, textAlign = TextAlign.Center)
            StatusArea(state, permissionDenied, onDismissError)
        }
        val buttons: @Composable ColumnScope.() -> Unit = {
            if (lastDevice != null) {
                BigButton("Reconectar a ${lastDevice.name ?: lastDevice.address}", { withPermission { onReconnect(lastDevice) } }, enabled = !busy, height = btnH)
                BigOutlinedButton("Escolher outro adaptador", { withPermission(onOpenScan) }, enabled = !busy, height = btnH)
            } else {
                BigButton("Conectar ao adaptador", { withPermission(onOpenScan) }, enabled = !busy, height = btnH)
            }
            BigOutlinedButton("Usar simulador (sem o carro)", onConnectSimulated, enabled = !busy, height = btnH)
            if (logCount > 0) BigOutlinedButton("Logs gravados ($logCount)", onOpenLogs, height = btnH)
            BigOutlinedButton("Configurações", onOpenSettings, height = btnH)
        }

        if (landscape) {
            Row(Modifier.fillMaxSize().padding(pad), horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), content = header)
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(gap, Alignment.CenterVertically), content = buttons,
                )
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
            ) {
                header()
                Spacer(Modifier.height(10.dp))
                buttons()
            }
        }
    }
}

@Composable
private fun ColumnScope.StatusArea(state: ConnectionState, permissionDenied: Boolean, onDismissError: () -> Unit) {
    when (state) {
        is ConnectionState.Connecting -> {
            CircularProgressIndicator(Modifier.size(48.dp))
            Text("Conectando a ${state.transport}…", color = DashColors.TextSecondary, fontSize = 20.sp, textAlign = TextAlign.Center)
        }
        is ConnectionState.Failed -> {
            Text("Falha: ${state.message}", color = DashColors.Warn, fontSize = 20.sp, textAlign = TextAlign.Center)
            BigOutlinedButton("Ok", onDismissError)
        }
        else -> if (permissionDenied) {
            Text(
                "Sem permissão de Bluetooth, a conexão com o adaptador não funciona.",
                color = DashColors.Warn, fontSize = 20.sp, textAlign = TextAlign.Center,
            )
        }
    }
}
