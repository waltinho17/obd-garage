package com.walter.obdgarage

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.walter.obdgarage.ble.BleDevice
import com.walter.obdgarage.ble.BleObdTransport
import com.walter.obdgarage.ble.BleScanner
import com.walter.obdgarage.obd.ObdException
import com.walter.obdgarage.log.LogEntry
import com.walter.obdgarage.log.LogRecorder
import com.walter.obdgarage.log.LogSession
import com.walter.obdgarage.log.LogStore
import com.walter.obdgarage.log.Sample
import com.walter.obdgarage.obd.Elm327Client
import com.walter.obdgarage.obd.ObdTransport
import com.walter.obdgarage.obd.PressureUnit
import com.walter.obdgarage.obd.ReadMode
import com.walter.obdgarage.obd.ReadScheduler
import com.walter.obdgarage.obd.Sensors
import com.walter.obdgarage.obd.SimulatedObdTransport
import com.walter.obdgarage.obd.Vehicle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val transport: String) : ConnectionState
    data class Connected(val transport: String, val adapterInfo: String) : ConnectionState
    data class Failed(val message: String) : ConnectionState
}

/** Tipo de motor quanto à alimentação; decide se a aba Turbo faz sentido. */
enum class EngineKind {
    UNKNOWN, TURBO, ASPIRADO;

    companion object {
        fun parse(name: String?): EngineKind = entries.firstOrNull { it.name == name } ?: UNKNOWN
    }
}

/** Maior boost visto desde que a sessão começou (ou desde o último "zerar"), com o RPM do momento. */
data class PeakBoost(val value: Double, val rpm: Double?)

/** Qual tela de logs está aberta. */
sealed interface LogView {
    data object Closed : LogView
    data object List : LogView
    data class Detail(val entry: LogEntry, val session: LogSession, val path: String) : LogView
}

class DashViewModel(application: Application) : AndroidViewModel(application) {
    private val store = LogStore(
        (application.getExternalFilesDir(null) ?: application.filesDir).resolve("logs"),
    )

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state

    private val _values = MutableStateFlow<Map<String, Double>>(emptyMap())
    val values: StateFlow<Map<String, Double>> = _values

    private val _updatesPerSecond = MutableStateFlow(0.0)
    val updatesPerSecond: StateFlow<Double> = _updatesPerSecond

    private val _readMode = MutableStateFlow(ReadMode.MULTI)
    val readMode: StateFlow<ReadMode> = _readMode

    private val _peakBoost = MutableStateFlow<PeakBoost?>(null)
    val peakBoost: StateFlow<PeakBoost?> = _peakBoost

    /** Últimas [HISTORY_SIZE] amostras (10 por segundo), para o gráfico ao vivo. */
    private val _history = MutableStateFlow<List<Sample>>(emptyList())
    val history: StateFlow<List<Sample>> = _history
    private val historyBuffer = ArrayDeque<Sample>()

    private val recorder = LogRecorder()
    private val _recordingSince = MutableStateFlow<Long?>(null)
    val recordingSince: StateFlow<Long?> = _recordingSince

    private val _logEntries = MutableStateFlow<List<LogEntry>>(emptyList())
    val logEntries: StateFlow<List<LogEntry>> = _logEntries

    private val _logView = MutableStateFlow<LogView>(LogView.Closed)
    val logView: StateFlow<LogView> = _logView

    private var job: Job? = null

    private val prefs = application.getSharedPreferences("dash", Context.MODE_PRIVATE)
    private val scanner = BleScanner(application)
    val devices: StateFlow<List<BleDevice>> = scanner.devices

    private val _scanOpen = MutableStateFlow(false)
    val scanOpen: StateFlow<Boolean> = _scanOpen

    private val _scanError = MutableStateFlow<String?>(null)
    val scanError: StateFlow<String?> = _scanError

    /** Último adaptador conectado com sucesso, para reconectar com um toque. */
    private val _lastDevice = MutableStateFlow(
        prefs.getString("ble_address", null)?.let { BleDevice(prefs.getString("ble_name", null), it) },
    )
    val lastDevice: StateFlow<BleDevice?> = _lastDevice

    /** Unidade da pressão do turbo na tela (só exibição; o log guarda sempre bar). */
    private val _boostUnit = MutableStateFlow(PressureUnit.parse(prefs.getString("boost_unit", null)))
    val boostUnit: StateFlow<PressureUnit> = _boostUnit

    fun cycleBoostUnit() {
        val next = _boostUnit.value.next()
        _boostUnit.value = next
        prefs.edit().putString("boost_unit", next.name).apply()
    }

    /** PIDs que o carro conectado suporta, convertidos em ids de sensor; null enquanto não conectou. */
    private val _supportedIds = MutableStateFlow<Set<String>?>(null)
    val supportedIds: StateFlow<Set<String>?> = _supportedIds

    private val _engineKind = MutableStateFlow(EngineKind.UNKNOWN)
    val engineKind: StateFlow<EngineKind> = _engineKind

    /** Nome do carro mostrado na tela (maiúsculas): o que o usuário digitou, ou marca e ano lidos do VIN. */
    private val _vehicleName = MutableStateFlow("")
    val vehicleName: StateFlow<String> = _vehicleName

    private var vehicleKey = ""
    private var currentVin = ""
    private var vinDefaultName: String? = null
    private var fullThrottleSamples = 0

    fun setVehicleName(name: String) {
        val clean = name.trim().uppercase().take(24)
        prefs.edit().putString("vehicle_name_$vehicleKey", clean).apply()
        _vehicleName.value = clean.ifEmpty { vinDefaultName ?: "" }
    }

    /** Qual o nome em edição (o digitado, sem o padrão do VIN). */
    fun editableVehicleName(): String = prefs.getString("vehicle_name_$vehicleKey", "") ?: ""

    private fun setEngineKind(kind: EngineKind, persist: Boolean) {
        _engineKind.value = kind
        if (persist) prefs.edit().putString("engine_$vehicleKey", kind.name).apply()
    }

    private fun loadSettings() = Settings(
        theme = parseEnum(prefs.getString("set_theme", null), ThemeMode.DARK),
        textSize = parseEnum(prefs.getString("set_text", null), TextSize.NORMAL),
        orientation = parseEnum(prefs.getString("set_orient", null), OrientationMode.AUTO),
        startDash = prefs.getString("set_start", "sport") ?: "sport",
        keepScreenOn = prefs.getBoolean("set_keep_on", true),
    )

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<Settings> = _settings

    fun updateSettings(change: (Settings) -> Settings) {
        val s = change(_settings.value)
        _settings.value = s
        prefs.edit()
            .putString("set_theme", s.theme.name).putString("set_text", s.textSize.name)
            .putString("set_orient", s.orientation.name).putString("set_start", s.startDash)
            .putBoolean("set_keep_on", s.keepScreenOn).apply()
    }

    fun setBoostUnit(unit: PressureUnit) {
        _boostUnit.value = unit
        prefs.edit().putString("boost_unit", unit.name).apply()
    }

    private val _settingsOpen = MutableStateFlow(false)
    val settingsOpen: StateFlow<Boolean> = _settingsOpen
    fun openSettings() { _settingsOpen.value = true }
    fun closeSettings() { _settingsOpen.value = false }

    init {
        refreshEntries()
    }

    fun connectSimulated() = connect(SimulatedObdTransport())

    fun openScan() {
        _scanOpen.value = true
    }

    fun closeScan() {
        scanner.stop()
        _scanOpen.value = false
    }

    fun startScan() {
        _scanError.value = scanner.start()
    }

    fun stopScan() = scanner.stop()

    fun connectBle(device: BleDevice) {
        if (job?.isActive == true) return
        scanner.stop()
        connect(BleObdTransport(getApplication(), device)) {
            prefs.edit().putString("ble_address", device.address).putString("ble_name", device.name).apply()
            _lastDevice.value = device
        }
    }

    private fun connect(transport: ObdTransport, onConnected: () -> Unit = {}) {
        if (job?.isActive == true) return
        _state.value = ConnectionState.Connecting(transport.name)
        _values.value = emptyMap()
        _peakBoost.value = null
        historyBuffer.clear()
        _history.value = emptyList()
        job = viewModelScope.launch {
            try {
                transport.open()
                val client = Elm327Client(transport)
                val info = client.initialize()
                // Só lê o que o carro diz suportar; o resto fica "--" nos widgets.
                val sensors = Sensors.all.filter { it.pid in info.supportedPids }
                if (sensors.isEmpty()) throw ObdException("O carro não suporta nenhum dos parâmetros conhecidos")
                // boost e MAP são o mesmo PID; o boost só existe se o MAP existir.
                _supportedIds.value = sensors.map { it.id }.toSet()

                // Identifica o carro pelo VIN (opcional: se não vier, usa o endereço do adaptador).
                val vin = try {
                    client.readVin()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                currentVin = vin.orEmpty()
                vehicleKey = vin ?: transport.name
                vinDefaultName = Vehicle.defaultName(vin)
                _vehicleName.value = prefs.getString("vehicle_name_$vehicleKey", "").orEmpty().ifEmpty { vinDefaultName ?: "" }
                fullThrottleSamples = 0
                _engineKind.value = when {
                    Sensors.map.id !in _supportedIds.value!! -> EngineKind.ASPIRADO
                    else -> EngineKind.parse(prefs.getString("engine_$vehicleKey", null))
                }
                val scheduler = ReadScheduler(client, sensors)
                _state.value = ConnectionState.Connected(transport.name, info.description)
                KeepAliveService.start(getApplication())
                _scanOpen.value = false
                onConnected()

                val mirror = launch {
                    launch {
                        scheduler.values.collect {
                            _values.value = it
                            updatePeak(it)
                        }
                    }
                    launch { scheduler.updatesPerSecond.collect { _updatesPerSecond.value = it } }
                    launch { scheduler.mode.collect { _readMode.value = it } }
                    launch {
                        while (isActive) {
                            delay(SAMPLE_INTERVAL_MS)
                            sample()
                        }
                    }
                }
                try {
                    scheduler.run()
                } finally {
                    mirror.cancel()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = ConnectionState.Failed(e.message ?: "Erro desconhecido")
            } finally {
                // Se a conexão cair no meio de uma gravação, o que foi gravado não se perde.
                finishRecording()
                KeepAliveService.stop(getApplication())
                withContext(NonCancellable) { transport.close() }
            }
        }
    }

    private fun updatePeak(v: Map<String, Double>) {
        val boost = v[Sensors.boost.id] ?: return
        detectEngineKind(v, boost)
        val current = _peakBoost.value
        if (current == null || boost > current.value) _peakBoost.value = PeakBoost(boost, v[Sensors.rpm.id])
    }

    /**
     * Turbo: o MAP passa da pressão atmosférica (boost acima de 0,15 bar). Aspirado: com acelerador a fundo
     * (80%+) e giro alto (3000+ rpm) por cerca de 15 s, o boost não passa de 0,1 bar. Vale para o carro todo
     * e fica salvo; enquanto não se sabe, a aba Turbo continua visível.
     */
    private fun detectEngineKind(v: Map<String, Double>, boost: Double) {
        if (_engineKind.value != EngineKind.UNKNOWN) return
        if (boost > TURBO_BOOST_BAR) {
            setEngineKind(EngineKind.TURBO, persist = true)
            return
        }
        val throttle = v[Sensors.throttle.id] ?: v[Sensors.pedal.id] ?: return
        val rpm = v[Sensors.rpm.id] ?: return
        if (throttle >= 80.0 && rpm >= 3000.0) fullThrottleSamples++
        if (fullThrottleSamples >= ASPIRATED_SAMPLES && (_peakBoost.value?.value ?: 0.0) < ASPIRATED_MAX_BAR) {
            setEngineKind(EngineKind.ASPIRADO, persist = true)
        }
    }

    fun resetPeak() {
        _peakBoost.value = null
    }

    private fun sample() {
        val v = _values.value
        if (v.isEmpty()) return
        historyBuffer.addLast(Sample(android.os.SystemClock.elapsedRealtime(), v))
        while (historyBuffer.size > HISTORY_SIZE) historyBuffer.removeFirst()
        _history.value = historyBuffer.toList()
        recorder.add(v)
    }

    fun toggleRecording() {
        if (recorder.isRecording) finishRecording()
        else if (_state.value is ConnectionState.Connected) _recordingSince.value = recorder.start()
    }

    /** Para a gravação, salva o log em arquivo e abre o resultado. */
    private fun finishRecording() {
        val log = recorder.stop()?.withVehicle(_vehicleName.value, currentVin)
        _recordingSince.value = null
        if (log == null) return
        viewModelScope.launch {
            val entry = withContext(Dispatchers.IO) { store.save(log) }
            _logEntries.value = withContext(Dispatchers.IO) { store.list() }
            _logView.value = LogView.Detail(entry, log, store.path(entry.id))
        }
    }

    private fun refreshEntries() {
        viewModelScope.launch { _logEntries.value = withContext(Dispatchers.IO) { store.list() } }
    }

    fun openLogList() {
        _logView.value = LogView.List
        refreshEntries()
    }

    fun openLog(entry: LogEntry) {
        viewModelScope.launch {
            val session = withContext(Dispatchers.IO) { store.load(entry.id) } ?: return@launch
            _logView.value = LogView.Detail(entry, session, store.path(entry.id))
        }
    }

    /** Voltar: do detalhe para a lista, e da lista para a tela anterior. */
    fun backFromLogs() {
        _logView.value = if (_logView.value is LogView.Detail) LogView.List else LogView.Closed
    }

    fun closeLogs() {
        _logView.value = LogView.Closed
    }

    fun editLog(entry: LogEntry, name: String, note: String) {
        viewModelScope.launch {
            val updated = withContext(Dispatchers.IO) { store.update(entry.id, name, note) } ?: return@launch
            val session = withContext(Dispatchers.IO) { store.load(entry.id) } ?: return@launch
            _logEntries.value = withContext(Dispatchers.IO) { store.list() }
            _logView.value = LogView.Detail(updated, session, store.path(entry.id))
        }
    }

    fun deleteLog(entry: LogEntry) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.delete(entry.id) }
            _logEntries.value = withContext(Dispatchers.IO) { store.list() }
            _logView.value = LogView.List
        }
    }

    fun disconnect() {
        job?.cancel()
        job = null
        _state.value = ConnectionState.Disconnected
    }

    fun dismissError() {
        if (_state.value is ConnectionState.Failed) _state.value = ConnectionState.Disconnected
    }

    override fun onCleared() {
        scanner.stop()
        job?.cancel()
    }

    private companion object {
        const val TURBO_BOOST_BAR = 0.15
        const val ASPIRATED_MAX_BAR = 0.1
        const val ASPIRATED_SAMPLES = 40
        const val SAMPLE_INTERVAL_MS = 100L
        const val HISTORY_SIZE = 600
    }
}
