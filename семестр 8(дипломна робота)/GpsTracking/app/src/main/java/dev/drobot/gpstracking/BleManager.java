package dev.drobot.gpstracking;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.util.Log;

import java.util.UUID;

@SuppressLint("MissingPermission")
public class BleManager {

    private static final String TAG = "BleManager";

    private static final UUID SERVICE_UUID    = UUID.fromString("000000ff-0000-1000-8000-00805f9b34fb");
    private static final UUID RX_GPS_UUID     = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb");
    private static final UUID TX_GPS_UUID     = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb");
    private static final UUID PASS_UUID       = UUID.fromString("0000ff03-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD_UUID       = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private Context context;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private BluetoothGatt bluetoothGatt;

    private BluetoothGattCharacteristic rxCharacteristic;
    private BleCallback callback;

    // --- ОНОВЛЕНИЙ ІНТЕРФЕЙС ---
    public interface BleCallback {
        void onDeviceFound(BluetoothDevice device); // НОВЕ: викликається, коли знайдено плату
        void onDeviceConnected();
        void onDeviceDisconnected();
        void onDataReceived(LoRaPacket packet);
    }

    public BleManager(Context context, BleCallback callback) {
        this.context = context;
        this.callback = callback;
        BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            bluetoothAdapter = bluetoothManager.getAdapter();
            if (bluetoothAdapter != null) {
                bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
            }
        }
    }

    // --- 1. СКАНУВАННЯ ЕФІРУ ---
    // --- 1. СКАНУВАННЯ ЕФІРУ ---
    public void startScan() {
        // Якщо сканер порожній (наприклад, Bluetooth був вимкнений при старті) - пробуємо отримати його знову
        if (bluetoothLeScanner == null && bluetoothAdapter != null) {
            bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        }

        if (bluetoothLeScanner == null) {
            Log.e(TAG, "Неможливо запустити сканер. Bluetooth вимкнено?");
            return;
        }

        Log.i(TAG, "Пошук пристроїв LoRa_Tracker...");
        bluetoothLeScanner.startScan(scanCallback);
    }
    // Відправляє рівно 2 байти (тільки ID) для конфігурації плати
    public void sendDeviceId(int deviceId) {
        if (bluetoothGatt == null || rxCharacteristic == null) {
            Log.e("BLE", "Характеристика ще не готова!");
            return;
        }

        byte[] idBytes = new byte[2];
        idBytes[0] = (byte) (deviceId & 0xFF);          // Молодший байт
        idBytes[1] = (byte) ((deviceId >> 8) & 0xFF);   // Старший байт

        rxCharacteristic.setValue(idBytes);

        // ЗМІНЕНО: Відправляємо без очікування відповіді, щоб не обривало зв'язок!
        rxCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        bluetoothGatt.writeCharacteristic(rxCharacteristic);

        Log.i("BLE", "Базове налаштування: Відправлено ID " + deviceId + " на плату.");
    }
    public void stopScan() {
        if (bluetoothLeScanner != null) {
            bluetoothLeScanner.stopScan(scanCallback);
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String deviceName = device.getName();

            // Шукаємо плати, назва яких ПОЧИНАЄТЬСЯ з "LoRa_Tracker" (щоб ловити _1, _2 тощо)
            if( (deviceName != null )&& deviceName.startsWith("LoRa")) {
                if (callback != null) {
                    // Передаємо знайдений пристрій у MainActivity для відображення у списку
                    callback.onDeviceFound(device);
                }
            }
        }
    };

    // --- 2. ПУБЛІЧНИЙ МЕТОД ПІДКЛЮЧЕННЯ (Тепер ми викликаємо його вручну!) ---
    public void connectToDevice(BluetoothDevice device) {
        stopScan(); // Обов'язково зупиняємо сканування перед підключенням, щоб не вантажити радіомодуль телефону
        Log.i(TAG, "Підключаємось до: " + device.getName() + " (" + device.getAddress() + ")");
        bluetoothGatt = device.connectGatt(context, false, gattCallback);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Фізично підключено. Шукаємо сервіси...");
                if (callback != null) callback.onDeviceConnected();
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.w(TAG, "Відключено від плати.");
                if (callback != null) callback.onDeviceDisconnected();
                rxCharacteristic = null;
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                BluetoothGattService service = gatt.getService(SERVICE_UUID);
                if (service != null) {
                    rxCharacteristic = service.getCharacteristic(RX_GPS_UUID);
                    BluetoothGattCharacteristic txChar = service.getCharacteristic(TX_GPS_UUID);

                    if (txChar != null) {
                        gatt.setCharacteristicNotification(txChar, true);
                        BluetoothGattDescriptor descriptor = txChar.getDescriptor(CCCD_UUID);
                        if (descriptor != null) {
                            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                            gatt.writeDescriptor(descriptor);
                            Log.i(TAG, "Підписку на отримання координат активовано!");
                        }
                    }
                }
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if (TX_GPS_UUID.equals(characteristic.getUuid())) {
                byte[] data = characteristic.getValue();
                LoRaPacket packet = LoRaPacket.unpack(data);
                if (packet != null) {
                    if (callback != null) callback.onDataReceived(packet);
                }
            }
        }
    };

    // --- 3. ВІДПРАВКА ДАНИХ (WRITE) ---
    public void sendGpsData(byte[] data) {
        if (bluetoothGatt == null || rxCharacteristic == null) return;
        rxCharacteristic.setValue(data);
        rxCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        bluetoothGatt.writeCharacteristic(rxCharacteristic);
    }

    public void sendPassword(byte[] passwordBytes) {
        if (bluetoothGatt == null) return;
        BluetoothGattService service = bluetoothGatt.getService(SERVICE_UUID);
        if (service != null) {
            BluetoothGattCharacteristic passChar = service.getCharacteristic(PASS_UUID);
            if (passChar != null) {
                passChar.setValue(passwordBytes);
                bluetoothGatt.writeCharacteristic(passChar);
            }
        }
    }

    public void disconnect() {
        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
            bluetoothGatt.close();
            bluetoothGatt = null;
        }
    }
}