package io.github.agitangst.ampulse.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import io.github.agitangst.ampulse.domain.BloodPressureGatt
import io.github.agitangst.ampulse.domain.DeviceReading
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import kotlin.math.abs

private const val TAG = "AmpulseBle"

/** Найденный тонометр: имя из рекламы (если есть), адрес и уровень сигнала. */
data class FoundDevice(val name: String?, val address: String, val rssi: Int)

/** Почему не вышло. Тексты для каждого — в `strings_tonometer.xml`. */
enum class LinkError {
    /** В телефоне нет Bluetooth LE. */
    NoBluetooth,

    /** Bluetooth выключен. */
    Disabled,

    /** Нет разрешения на поиск и соединение. */
    NoPermission,

    /** До Android 12 поиск BLE не работает при выключенной геолокации. */
    LocationOff,

    /** Система не запустила поиск (например, слишком частые запуски). */
    ScanFailed,

    /** За время поиска тонометр не нашёлся. */
    NotFound,

    /** У устройства нет службы Blood Pressure с характеристикой 0x2A35. */
    NotTonometer,

    /** Соединение не установилось или оборвалось, а замера не было. */
    Lost,

    /** Соединились, но замер не пришёл за отведённое время. */
    Timeout,
}

sealed interface LinkState {
    data object Idle : LinkState

    /** Идёт поиск ([active]) или закончился, а найденные есть и ждут выбора. */
    data class Scanning(val devices: List<FoundDevice>, val active: Boolean = true) : LinkState

    data class Connecting(val device: FoundDevice) : LinkState

    /** Соединены, индикации включены; ждём замер. [received] — сколько замеров уже пришло. */
    data class Waiting(val received: Int = 0) : LinkState

    data class Got(val reading: DeviceReading, val device: FoundDevice) : LinkState

    data class Error(val kind: LinkError) : LinkState
}

/**
 * Приём замера с тонометра Bluetooth LE со стандартной службой Blood Pressure (0x1810, характеристика 0x2A35).
 *
 * Ход: поиск по службе ([SCAN_TIMEOUT_MS]) → соединение → индикации на 0x2A35 → замеры приходят, иногда пачкой →
 * после [QUIET_MS] тишины (или разрыва связи) берётся последний ([ReadingBatch]) → соединение закрывается. Ожидание
 * замера — не дольше [MEASURE_TIMEOUT_MS]. Пакет разбирает только [BloodPressureGatt.parse].
 *
 * Все методы — с главного потока; обратные вызовы системы перекладываются на него же. [close] освобождает всё и
 * вызывается всегда, когда ссылка больше не нужна. Без Compose.
 */
class TonometerLink(context: Context) {
    private val app: Context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow<LinkState>(LinkState.Idle)
    val state: StateFlow<LinkState> = mutableState.asStateFlow()

    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var session: GattSession? = null
    private var timeout: Runnable? = null
    private var quiet: Runnable? = null
    private var preferred: String? = null
    private var chosen: FoundDevice? = null
    private var batch = ReadingBatch()
    private val found = LinkedHashMap<String, FoundDevice>()
    private val handles = HashMap<String, BluetoothDevice>()

    /**
     * Начать поиск. [preferredAddress] — запомненный тонометр: как только он найдётся, соединение идёт сразу, без
     * выбора. Уже идущая работа прерывается. Нет Bluetooth, разрешения и т. п. — состояние [LinkState.Error].
     */
    fun start(preferredAddress: String? = null) {
        release()
        preferred = preferredAddress
        chosen = null
        batch = ReadingBatch()
        found.clear()
        handles.clear()
        val problem = precheck()
        if (problem != null) {
            mutableState.value = LinkState.Error(problem)
            return
        }
        startScan()
    }

    /** Соединиться с найденным тонометром (выбор из списка). */
    fun connect(address: String) {
        val device = handles[address] ?: return
        stopScan()
        cancelTimeout()
        val info = found[address] ?: FoundDevice(null, address, 0)
        chosen = info
        batch = ReadingBatch()
        mutableState.value = LinkState.Connecting(info)
        // Соединение с тонометром может ждать связывания устройств — человеку нужно время на подтверждение.
        armTimeout(CONNECT_TIMEOUT_MS) { fail(LinkError.Lost) }
        openGatt(device)
    }

    /** Остановить поиск, закрыть соединение, снять таймеры. Состояние — [LinkState.Idle]. */
    fun close() {
        release()
        mutableState.value = LinkState.Idle
    }

    // --- Проверки ---

    /**
     * Что мешает начать: `null` — ничего. Порядок важен: включить Bluetooth с Android 12 можно только с разрешением.
     */
    @SuppressLint("MissingPermission") // isEnabled вызывается после проверки разрешений ниже
    private fun precheck(): LinkError? {
        if (!app.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) return LinkError.NoBluetooth
        val adapter = adapter() ?: return LinkError.NoBluetooth
        if (!hasPermissions(app)) return LinkError.NoPermission
        if (!adapter.isEnabled) return LinkError.Disabled
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !locationOn()) return LinkError.LocationOff
        return null
    }

    private fun adapter(): BluetoothAdapter? = app.getSystemService(BluetoothManager::class.java)?.adapter

    private fun locationOn(): Boolean = app.getSystemService(LocationManager::class.java)?.isLocationEnabled ?: true

    // --- Поиск ---

    @SuppressLint("MissingPermission") // разрешения проверены в precheck() из start(); отзыв посреди работы ловит SecurityException
    private fun startScan() {
        val sc = adapter()?.bluetoothLeScanner
        if (sc == null) {
            fail(LinkError.Disabled)
            return
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                main.post { if (scanCallback === this) onFound(result) }
            }

            override fun onBatchScanResults(results: List<ScanResult>) {
                main.post { if (scanCallback === this) results.forEach { onFound(it) } }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "Scan failed: $errorCode")
                main.post { if (scanCallback === this) fail(LinkError.ScanFailed) }
            }
        }
        scanner = sc
        scanCallback = callback
        mutableState.value = LinkState.Scanning(emptyList())
        armTimeout(SCAN_TIMEOUT_MS) { onScanTimeout() }
        try {
            sc.startScan(listOf(filter), settings, callback)
        } catch (e: SecurityException) {
            Log.w(TAG, "Scan is not permitted", e)
            fail(LinkError.NoPermission)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Scan did not start", e)
            fail(LinkError.Disabled)
        }
    }

    private fun onFound(result: ScanResult) {
        val device = result.device
        val address = device.address
        val old = found[address]
        // Имя берём из рекламы: BluetoothDevice.getName() требует BLUETOOTH_CONNECT и часто пусто.
        val name = result.scanRecord?.deviceName?.takeIf { it.isNotBlank() } ?: old?.name
        handles[address] = device
        val fresh = FoundDevice(name, address, result.rssi)
        // Сигнал дрожит от пакета к пакету — список обновляем при заметной разнице, иначе экран мигает.
        val changed = old == null || old.name != name || abs(old.rssi - fresh.rssi) >= RSSI_STEP
        if (changed) found[address] = fresh
        if (address.equals(preferred, ignoreCase = true)) {
            connect(address)
        } else if (changed) {
            mutableState.value = LinkState.Scanning(found.values.toList())
        }
    }

    private fun onScanTimeout() {
        stopScan()
        if (found.isEmpty()) {
            fail(LinkError.NotFound)
        } else {
            mutableState.value = LinkState.Scanning(found.values.toList(), active = false)
        }
    }

    @SuppressLint("MissingPermission") // см. startScan(); остановка без права — SecurityException, её ловим
    private fun stopScan() {
        val callback = scanCallback ?: return
        val sc = scanner
        scanCallback = null
        scanner = null
        try {
            sc?.stopScan(callback)
        } catch (e: SecurityException) {
            Log.w(TAG, "Scan stop is not permitted", e)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Scan stop failed", e)
        }
    }

    // --- Соединение ---

    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT проверен в precheck() из start(); отзыв ловит SecurityException
    private fun openGatt(device: BluetoothDevice) {
        val s = GattSession()
        session = s
        try {
            s.gatt = device.connectGatt(app, false, s, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            Log.w(TAG, "Connect is not permitted", e)
            fail(LinkError.NoPermission)
            return
        }
        if (s.gatt == null) fail(LinkError.Lost)
    }

    private fun onConnection(g: BluetoothGatt, status: Int, newState: Int) {
        when {
            // Тонометры часто сами отключаются сразу после передачи: если замер уже есть, это не ошибка.
            status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED -> finishOrFail(LinkError.Lost)
            newState == BluetoothProfile.STATE_CONNECTED -> discover(g)?.let { fail(it) }
        }
    }

    @SuppressLint("MissingPermission") // см. openGatt()
    private fun discover(g: BluetoothGatt): LinkError? = try {
        if (g.discoverServices()) null else LinkError.Lost
    } catch (e: SecurityException) {
        Log.w(TAG, "Service discovery is not permitted", e)
        LinkError.NoPermission
    }

    private fun onServices(g: BluetoothGatt, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            fail(LinkError.Lost)
            return
        }
        val characteristic = g.getService(SERVICE_UUID)?.getCharacteristic(MEASUREMENT_UUID)
        if (characteristic == null) {
            fail(LinkError.NotTonometer)
            return
        }
        enableIndications(g, characteristic)?.let { fail(it) }
    }

    /**
     * Подписка на 0x2A35: локально и записью в дескриптор 0x2902. Стандарт даёт индикации; если у характеристики
     * только уведомления — они. Android 13 и новее — перегрузка с значением, раньше — устаревшая через `setValue`.
     */
    @SuppressLint("MissingPermission") // см. openGatt()
    @Suppress("DEPRECATION") // ветка до Android 13
    private fun enableIndications(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic): LinkError? {
        return try {
            val cccd = characteristic.getDescriptor(CCC_UUID)
            if (cccd == null || !g.setCharacteristicNotification(characteristic, true)) return LinkError.NotTonometer
            val indicate = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
            val value = if (indicate) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
            } else {
                cccd.setValue(value)
                g.writeDescriptor(cccd)
            }
            if (started) null else LinkError.Lost
        } catch (e: SecurityException) {
            Log.w(TAG, "Subscription is not permitted", e)
            LinkError.NoPermission
        }
    }

    private fun onDescriptorWritten(descriptor: BluetoothGattDescriptor, status: Int) {
        if (descriptor.uuid != CCC_UUID) return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            fail(LinkError.Lost)
            return
        }
        mutableState.value = LinkState.Waiting(batch.size)
        armTimeout(MEASURE_TIMEOUT_MS) { finishOrFail(LinkError.Timeout) }
    }

    // --- Замеры ---

    private fun onPacket(characteristic: UUID, bytes: ByteArray) {
        if (characteristic != MEASUREMENT_UUID) return
        val reading = BloodPressureGatt.parse(bytes) ?: return
        batch.add(reading)
        mutableState.value = LinkState.Waiting(batch.size)
        // Пачка ещё идёт — ждём тишину; последний пакет + QUIET_MS — конец.
        quiet?.let { main.removeCallbacks(it) }
        quiet = Runnable { quiet = null; finishOrFail(LinkError.Timeout) }.also { main.postDelayed(it, QUIET_MS) }
    }

    private fun finishOrFail(kind: LinkError) {
        val reading = batch.latest()
        if (reading != null) {
            val device = chosen ?: FoundDevice(null, "", 0)
            release()
            mutableState.value = LinkState.Got(reading, device)
        } else {
            fail(kind)
        }
    }

    private fun fail(kind: LinkError) {
        release()
        mutableState.value = LinkState.Error(kind)
    }

    // --- Освобождение и таймеры ---

    /** Остановить поиск, закрыть gatt, снять таймеры. Опоздавшие обратные вызовы отбрасываются: сессии больше нет. */
    private fun release() {
        cancelTimeout()
        quiet?.let { main.removeCallbacks(it) }
        quiet = null
        stopScan()
        val g = session?.gatt
        session = null
        if (g != null) closeGatt(g)
    }

    @SuppressLint("MissingPermission") // закрытие освобождает ресурс; без права — SecurityException, её ловим
    private fun closeGatt(g: BluetoothGatt) {
        try {
            g.disconnect()
        } catch (e: SecurityException) {
            Log.w(TAG, "Disconnect is not permitted", e)
        }
        g.close()
    }

    private fun armTimeout(ms: Long, action: () -> Unit) {
        cancelTimeout()
        timeout = Runnable { timeout = null; action() }.also { main.postDelayed(it, ms) }
    }

    private fun cancelTimeout() {
        timeout?.let { main.removeCallbacks(it) }
        timeout = null
    }

    /** Обратные вызовы gatt приходят из потоков системы; каждый переходит на главный и отбрасывается, если сессия сменилась. */
    private inner class GattSession : BluetoothGattCallback() {
        var gatt: BluetoothGatt? = null

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            main.post { if (session === this) onConnection(gatt, status, newState) }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            main.post { if (session === this) onServices(gatt, status) }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            main.post { if (session === this) onDescriptorWritten(descriptor, status) }
        }

        /** Android 13 и новее: значение приходит в вызове. */
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val bytes = value.copyOf()
            main.post { if (session === this) onPacket(characteristic.uuid, bytes) }
        }

        /** До Android 13: значение лежит в характеристике, её содержимое перезаписывается следующим пакетом — копируем. */
        @Deprecated("Called by the system before Android 13")
        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            val bytes = characteristic.value?.copyOf() ?: return
            main.post { if (session === this) onPacket(characteristic.uuid, bytes) }
        }
    }

    companion object {
        /** Поиск: сколько искать тонометр. */
        const val SCAN_TIMEOUT_MS = 20_000L

        /** От выбора устройства до включённых индикаций; в нём может уместиться связывание. */
        const val CONNECT_TIMEOUT_MS = 30_000L

        /** Тишина после последнего пакета, по которой пачка считается законченной. */
        const val QUIET_MS = 3_000L

        /** Сколько ждать замер после соединения — человек нажимает кнопку на тонометре. */
        const val MEASURE_TIMEOUT_MS = 90_000L

        private const val RSSI_STEP = 6

        private val SERVICE_UUID: UUID = UUID.fromString(BloodPressureGatt.SERVICE)
        private val MEASUREMENT_UUID: UUID = UUID.fromString(BloodPressureGatt.MEASUREMENT)
        private val CCC_UUID: UUID = UUID.fromString(BloodPressureGatt.CCC_DESCRIPTOR)

        /** Разрешения на поиск и соединение: с Android 12 — Bluetooth, раньше — местоположение (только для поиска BLE). */
        fun permissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }

        fun hasPermissions(context: Context): Boolean =
            permissions().all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }
}
