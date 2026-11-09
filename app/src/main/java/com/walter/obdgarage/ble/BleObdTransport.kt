package com.walter.obdgarage.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import com.walter.obdgarage.obd.ObdException
import com.walter.obdgarage.obd.ObdTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

private const val TAG = "DashBle"
private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/** Comandos que o ELM327 demora mais para responder (reset e busca de protocolo). */
private val SLOW_COMMANDS = setOf("ATZ", "ATSP0", "0100", "0120", "0140", "0160", "0902")

/**
 * Transporte para adaptadores ELM327 por Bluetooth Low Energy. Descobre sozinho as
 * características de comunicação (ver [GattProfile.pick]) e registra no logcat (tag `DashBle`)
 * todos os serviços, para conferência com o nRF Connect.
 */
@SuppressLint("MissingPermission")
class BleObdTransport(
    private val context: Context,
    private val device: BleDevice,
) : ObdTransport {

    override val name: String = device.name ?: device.address

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

    private val assembler = ResponseAssembler()
    private val responses = Channel<String>(Channel.UNLIMITED)
    private val mutex = Mutex()

    /** Operação GATT em andamento; o callback correspondente completa este deferred com o status. */
    @Volatile private var waiter: CompletableDeferred<Int>? = null
    @Volatile private var lostReason: String? = null

    /** Rastreio gravado em arquivo, para diagnosticar uma sessão sem o PC conectado. */
    private val traceFile = (context.getExternalFilesDir(null) ?: context.filesDir).resolve("ble_trace.txt")
    private val traceClock = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.ROOT)

    private fun trace(message: String) {
        Log.d(TAG, message)
        try {
            traceFile.appendText("${traceClock.format(java.util.Date())} $message\n")
        } catch (_: Exception) {
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            trace("connectionState status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> waiter?.complete(status)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val reason = "Bluetooth desconectado (status $status)"
                    lostReason = reason
                    waiter?.completeExceptionally(ObdException(reason))
                    responses.close(ObdException(reason))
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            waiter?.complete(status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            waiter?.complete(status)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            waiter?.complete(status)
        }

        // Android 13+ chama esta; versões anteriores chamam a de baixo.
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            onData(value)
        }

        @Deprecated("Usada no Android 12 e anteriores")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            c.value?.let(::onData)
        }
    }

    private fun onData(bytes: ByteArray) {
        val text = String(bytes, Charsets.US_ASCII)
        trace("RX chunk ${text.replace("\r", "\\r")}")
        assembler.append(text)?.let { responses.trySend(it) }
    }

    override suspend fun open() {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw ObdException("Este aparelho não tem Bluetooth")
        if (!adapter.isEnabled) throw ObdException("O Bluetooth está desligado")
        try {
            // As tentativas se acumulam no arquivo; só recomeça se ele ficar grande.
            if (traceFile.length() > 300_000) traceFile.writeText("")
            trace("---- nova tentativa ----")
            trace("conectando a ${device.name} ${device.address} (Android ${android.os.Build.VERSION.SDK_INT}, ${android.os.Build.MODEL})")
            val remote = adapter.getRemoteDevice(device.address)
            val connectStatus = step(15_000, "conexão com o adaptador") {
                gatt = remote.connectGatt(context, false, callback, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
                gatt != null
            }
            if (connectStatus != BluetoothGatt.GATT_SUCCESS) throw ObdException("Falha ao conectar (status $connectStatus)")

            delay(500) // alguns aparelhos precisam de uma pausa antes de descobrir serviços
            val g = gatt ?: throw ObdException("Conexão perdida")
            val discoverStatus = step(10_000, "descoberta de serviços") { g.discoverServices() }
            if (discoverStatus != BluetoothGatt.GATT_SUCCESS) throw ObdException("Falha ao listar serviços (status $discoverStatus)")

            val services = g.services.map { s ->
                ServiceInfo(s.uuid, s.characteristics.map { CharInfo(it.uuid, it.properties) })
            }
            services.forEach { s ->
                trace("serviço ${s.uuid}")
                s.characteristics.forEach { trace("   característica ${it.uuid} props=0x${it.properties.toString(16)}") }
            }
            val pick = GattProfile.pick(services)
                ?: throw ObdException(
                    "Não achei o canal de comunicação neste dispositivo. Serviços: " +
                        services.joinToString { GattProfile.shortId(it.uuid) ?: it.uuid.toString() },
                )
            trace("usando serviço=${pick.service} notify=${pick.notify} write=${pick.write} noResponse=${pick.writeNoResponse}")

            val service = g.getService(pick.service)
            val notifyChar = service.getCharacteristic(pick.notify)
            writeChar = service.getCharacteristic(pick.write)
            writeType = if (pick.writeNoResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

            g.setCharacteristicNotification(notifyChar, true)
            val cccd = notifyChar.getDescriptor(CCCD) ?: throw ObdException("Característica sem descritor de notificação")
            val notifyStatus = step(5_000, "ativação das notificações") {
                @Suppress("DEPRECATION")
                cccd.value = if (pick.indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
            if (notifyStatus != BluetoothGatt.GATT_SUCCESS) throw ObdException("Falha ao ativar notificações (status $notifyStatus)")
        } catch (e: SecurityException) {
            release()
            throw ObdException("Sem permissão de Bluetooth")
        } catch (e: Exception) {
            release()
            throw e
        }
    }

    override suspend fun request(command: String): String = mutex.withLock {
        val g = gatt
        val c = writeChar
        if (g == null || c == null) throw ObdException(lostReason ?: "Bluetooth não está aberto")

        assembler.reset()
        while (responses.tryReceive().isSuccess) { /* descarta sobras de uma resposta anterior */ }

        trace("TX $command")
        val status = step(3_000, "envio de $command") {
            @Suppress("DEPRECATION")
            c.value = (command + "\r").toByteArray(Charsets.US_ASCII)
            c.writeType = writeType
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }
        if (status != BluetoothGatt.GATT_SUCCESS) throw ObdException("Falha ao enviar $command (status $status)")

        val timeout = if (command in SLOW_COMMANDS) 12_000L else 3_000L
        val response = withTimeoutOrNull(timeout) { responses.receive() }
            ?: throw ObdException("O adaptador não respondeu a $command")
        trace("RX $command -> ${response.replace("\n", " | ")}")
        response
    }

    override suspend fun close() {
        release()
    }

    private fun release() {
        responses.close()
        gatt?.let {
            try {
                it.disconnect()
                it.close()
            } catch (_: SecurityException) {
            }
        }
        gatt = null
        writeChar = null
    }

    /** Dispara uma operação GATT e espera o callback correspondente, com limite de tempo. */
    private suspend fun step(timeoutMs: Long, what: String, start: () -> Boolean): Int {
        val deferred = CompletableDeferred<Int>()
        waiter = deferred
        lostReason?.let { throw ObdException(it) }
        if (!start()) throw ObdException("Não consegui iniciar: $what")
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            throw ObdException("Tempo esgotado: $what")
        }
    }
}
