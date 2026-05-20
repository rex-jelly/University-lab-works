    package dev.drobot.gpstracking;
    
    import android.Manifest;
    import android.annotation.SuppressLint;
    import android.app.AlertDialog;
    import android.bluetooth.BluetoothDevice;
    import android.content.Intent;
    import android.content.pm.PackageManager;
    import android.graphics.Color;
    import android.location.Location;
    import android.os.Build;
    import android.os.Bundle;
    import android.os.Handler;
    import android.os.Looper;
    import android.text.InputType;
    import android.widget.ArrayAdapter;
    import android.widget.Button;
    import android.widget.EditText;
    import android.widget.TextView;
    import android.widget.Toast;
    
    import androidx.appcompat.app.AppCompatActivity;
    import androidx.core.app.ActivityCompat;
    import androidx.core.content.ContextCompat;
    
    import org.mapsforge.map.rendertheme.ExternalRenderTheme;
    import org.osmdroid.config.Configuration;
    import org.osmdroid.util.GeoPoint;
    import org.osmdroid.views.MapView;
    import org.osmdroid.views.overlay.Marker;
    
    import java.security.MessageDigest;
    import java.util.ArrayList;
    import java.util.HashMap;
    import java.util.HashSet;
    import java.util.List;
    import java.util.Map;
    import org.osmdroid.mapsforge.MapsForgeTileProvider;
    import org.osmdroid.mapsforge.MapsForgeTileSource;
    import org.osmdroid.util.BoundingBox;
    import org.mapsforge.map.android.graphics.AndroidGraphicFactory;
    import org.mapsforge.map.rendertheme.InternalRenderTheme;
    import java.io.File;
    import java.util.Set;
    
    import org.osmdroid.tileprovider.util.SimpleRegisterReceiver;
    import org.mapsforge.map.rendertheme.XmlRenderThemeMenuCallback;
    import org.mapsforge.map.rendertheme.XmlRenderThemeStyleMenu;
    
    @SuppressLint("MissingPermission")
    public class MainActivity extends AppCompatActivity {
        public int DEVICE_NUM = 1; // Твій ID
        private Button btnExit;
        private GpsManager gpsManager;
        private BleManager bleManager;
    
        // UI Елементи
        private Button btnScan, btnSos;
        private TextView tvStatus;
        private MapView map;
    
        // Змінні для карти та списків
        private List<BluetoothDevice> scannedDevices = new ArrayList<>();
        private ArrayAdapter<String> listAdapter;
        private AlertDialog scanDialog;
        private Map<Integer, Marker> deviceMarkers = new HashMap<>(); // Зберігає маркери інших плат
        private Map<Integer, Long> lastUpdateTimes = new HashMap<>();
        private int myDeviceId = DEVICE_NUM;
        private boolean isSosActive = false;
        private double latestLat = 0.0;
        private double latestLon = 0.0;
        private boolean isBleConnected = false;
        private Handler txHandler = new Handler(Looper.getMainLooper());
        private Button btnCenter;
        private int sentPacketsCount = 0;      // Скільки пакетів вже відправлено
        private long currentTxInterval = 3000; // Початковий інтервал (3 секунди)
        private int latestAlt = 0;
        private Runnable txRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isBleConnected) return;
    
                if (latestLat != 0.0 && latestLon != 0.0) {
                    int myBattery = getBatteryLevel(); // Беремо реальний заряд!
                    int batStat = myBattery & 0x7F;
                    if (isSosActive) batStat |= 0x80;
    
                    // 1. Пакуємо і відправляємо дані
                    byte[] dataForEsp32 = LoRaPacket.pack(myDeviceId, latestLat, latestLon, latestAlt, batStat);
                    bleManager.sendGpsData(dataForEsp32);
    
                    sentPacketsCount++; // Збільшуємо лічильник відправлених пакетів
                    // Передаємо 6 параметрів (включаючи батарею та лічильник):
                    updateMarkerOnMap(myDeviceId, latestLat, latestLon, isSosActive, myBattery,latestAlt);
    
                    // 2. АДАПТИВНА ЛОГІКА ІНТЕРВАЛУ (Бережемо ефір)
                    if (isSosActive) {
                        // РЕЖИМ ТРИВОГИ: Відправляємо часто!
                        currentTxInterval = 5000; // 5 секунд
                    } else if (sentPacketsCount <= 3) {
                        // ШВИДКИЙ СТАРТ: Перші 3 пакети швидко, щоб заявити про себе
                        currentTxInterval = 3000; // 3 секунди
                    } else {
                        // РЕЖИМ ТИШІ (Патруль): Спокійна відправка
                        currentTxInterval = 20000; // 20 секунд (або постав 30000 для 30 сек)
                    }
                } else {
                    // Якщо GPS ще не знайдено, перевіряємо частіше, чи він не з'явився
                    currentTxInterval = 3000;
                }
    
                // Запускаємо наступний цикл з новим, адаптивним інтервалом
                txHandler.postDelayed(this, currentTxInterval);
            }
        };

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            // Ініціалізація карти OSMdroid
            Configuration.getInstance().load(getApplicationContext(), androidx.preference.PreferenceManager.getDefaultSharedPreferences(getApplicationContext()));

            setContentView(R.layout.activity_main);
            btnExit = findViewById(R.id.btnExit);
            // 1. Ініціалізація всіх кнопок та полів (ОБОВ'ЯЗКОВО ПІСЛЯ setContentView)
            btnScan = findViewById(R.id.btnScan);
            btnSos = findViewById(R.id.btnSos);
            tvStatus = findViewById(R.id.tvStatus);
            map = findViewById(R.id.map);
            btnCenter = findViewById(R.id.btnCenter);



            // 2. Обробник кнопки ПОВНОГО ВИМКНЕННЯ
            if (btnExit != null) {
                btnExit.setOnClickListener(v -> {
                    new AlertDialog.Builder(this)
                            .setTitle("Завершення роботи")
                            .setMessage("Ви впевнені, що хочете повністю вимкнути систему та фоновий моніторинг?")
                            .setPositiveButton("Так, вийти", (dialog, which) -> shutdownApp())
                            .setNegativeButton("Скасувати", null)
                            .show();
                });
            }

            // 3. Обробник кнопки центрування
            btnCenter.setOnClickListener(v -> {
                if (latestLat != 0.0 && latestLon != 0.0) {
                    GeoPoint myPoint = new GeoPoint(latestLat, latestLon);
                    map.getController().setZoom(18.0);
                    map.getController().animateTo(myPoint);
                } else {
                    Toast.makeText(this, "Ще шукаю супутники... Зачекайте", Toast.LENGTH_SHORT).show();
                }
            });

            // Налаштування карти
            map.setMultiTouchControls(true);
            map.getController().setZoom(18.0);
            initVectorMap();
            checkPermissions();

            // 4. Обробник кнопки SOS
            btnSos.setOnClickListener(v -> {
                isSosActive = !isSosActive;
                if (isSosActive) {
                    btnSos.setText("ВИМКНУТИ SOS");
                    btnSos.setBackgroundColor(Color.parseColor("#B71C1C"));
                    Toast.makeText(this, "РЕЖИМ ТРИВОГИ УВІМКНЕНО!", Toast.LENGTH_SHORT).show();
                } else {
                    btnSos.setText("🚨 УВІМКНУТИ SOS 🚨");
                    btnSos.setBackgroundColor(Color.parseColor("#D32F2F"));
                    Toast.makeText(this, "Тривогу скасовано", Toast.LENGTH_SHORT).show();
                }
            });

            // 5. Ініціалізація менеджерів (Bluetooth та GPS)
            bleManager = new BleManager(this, new BleManager.BleCallback() {
                @Override
                public void onDeviceFound(BluetoothDevice device) {
                    runOnUiThread(() -> {
                        if (!scannedDevices.contains(device)) {
                            scannedDevices.add(device);
                            listAdapter.add(device.getName() + " (" + device.getAddress() + ")");
                            listAdapter.notifyDataSetChanged();
                        }
                    });
                }

                @Override
                public void onDeviceConnected() {
                    isBleConnected = true;
                    // ЗАПУСК ФОНОВОЇ СЛУЖБИ для безперервного моніторингу
                    Intent serviceIntent = new Intent(MainActivity.this, TrackingService.class);
                    ContextCompat.startForegroundService(MainActivity.this, serviceIntent);

                    sentPacketsCount = 0;
                    runOnUiThread(() -> {
                        tvStatus.setText("Підключено 🟢");
                        tvStatus.setTextColor(Color.parseColor("#4CAF50"));
                        btnScan.setText("Відключитись");
                    });

                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        if (isBleConnected) {
                            bleManager.sendDeviceId(myDeviceId);
                            runOnUiThread(() -> showPasswordDialog());
                            txHandler.post(txRunnable);
                        }
                    }, 1000);
                }

                @Override
                public void onDeviceDisconnected() {
                    isBleConnected = false;
                    txHandler.removeCallbacks(txRunnable);
                    runOnUiThread(() -> {
                        tvStatus.setText("Відключено 🔴");
                        tvStatus.setTextColor(Color.parseColor("#F44336"));
                        btnScan.setText("Підключити плату");
                    });
                }

                @Override
                public void onDataReceived(LoRaPacket packet) {
                    runOnUiThread(() -> updateMarkerOnMap(packet.deviceId, packet.latitude, packet.longitude, packet.isSos, packet.batteryStatus, packet.altitude));
                }
            });

            gpsManager = new GpsManager(this, new GpsManager.LocationListenerCallback() {
                @Override
                public void onLocationChanged(Location location) {
                    boolean isFirstFix = (latestLat == 0.0);
                    latestLat = location.getLatitude();
                    latestLon = location.getLongitude();
                    latestAlt = (int) location.getAltitude();
                    int myOfflineBattery = getBatteryLevel();
                    updateMarkerOnMap(myDeviceId, latestLat, latestLon, isSosActive, myOfflineBattery, latestAlt);
                    if (isFirstFix) {
                        GeoPoint myPoint = new GeoPoint(latestLat, latestLon);
                        map.getController().setZoom(18.0);
                        map.getController().animateTo(myPoint);
                        Toast.makeText(MainActivity.this, "Супутники знайдено! Навожусь...", Toast.LENGTH_SHORT).show();
                    }
                }
                @Override
                public void onStatusChanged(String provider, int status, Bundle extras) {}
            });

            btnScan.setOnClickListener(v -> {
                if (isBleConnected) {
                    txHandler.removeCallbacks(txRunnable);
                    if (bleManager != null) {
                        bleManager.disconnect();
                    }
                    isBleConnected = false;
                    tvStatus.setText("Відключено 🔴");
                    tvStatus.setTextColor(Color.parseColor("#F44336"));
                    btnScan.setText("Підключити плату");
                } else {
                    showDeviceSelectionDialog();
                }
            });
        }

        // --- ОТРИМАННЯ РЕАЛЬНОГО ЗАРЯДУ БАТАРЕЇ ---
        private int getBatteryLevel() {
            android.content.IntentFilter iFilter = new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED);
            android.content.Intent batteryStatus = registerReceiver(null, iFilter);
            if (batteryStatus != null) {
                int level = batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
                int scale = batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1);
                return (int) ((level / (float) scale) * 100);
            }
            return 100; // Якщо щось піде не так
        }
        // Додали battery та msgId у параметри
        // Замінили msgId на altitude
        private void updateMarkerOnMap(int id, double lat, double lon, boolean sos, int battery, int altitude) {
            lastUpdateTimes.put(id, System.currentTimeMillis());

            GeoPoint point = new GeoPoint(lat, lon);
            Marker marker = deviceMarkers.get(id);

            if (marker == null) {
                marker = new Marker(map);
                map.getOverlays().add(marker);
                deviceMarkers.put(id, marker);
            }

            String topText;
            int markerColor;

            if (id == myDeviceId) {
                topText = "Я";
                markerColor = Color.parseColor("#2196F3");
            } else {
                topText = "ID " + id;
                markerColor = Color.parseColor("#4CAF50");
            }

            if (sos) {
                topText += " 🚨 SOS";
                markerColor = Color.parseColor("#F44336");
            }

            marker.setIcon(createTacticalMarkerIcon(topText, markerColor));
            marker.setPosition(point);
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
            marker.setInfoWindowAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_TOP);

            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault());
            String exactTime = sdf.format(new java.util.Date(lastUpdateTimes.get(id)));

            // 1. Встановлюємо Заголовок
            marker.setTitle(((id == myDeviceId) ? "Моя рація " : "Боєць ") + id);

            // 2. Встановлюємо перший рядок в основний опис
            marker.setSnippet("🔋 Батарея: " + battery + "%");

            // 3. МАГІЯ: Решту рядків кладемо в приховане поле, яке не має лімітів!
            marker.setSubDescription("⛰ Висота: " + altitude + " м\n⏱ Оновлено: " + exactTime);



            map.invalidate();
        }
    
        // --- ДІАЛОГ ВИБОРУ ПРИСТРОЮ ---
        private void showDeviceSelectionDialog() {
            scannedDevices.clear();
            listAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
    
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Знайдені трекери:");
    
            builder.setAdapter(listAdapter, (dialog, which) -> {
                BluetoothDevice selectedDevice = scannedDevices.get(which);
                bleManager.connectToDevice(selectedDevice);
                bleManager.stopScan();
            });
    
            builder.setNegativeButton("Скасувати", (dialog, which) -> bleManager.stopScan());
    
            scanDialog = builder.create();
            scanDialog.show();
    
            bleManager.startScan();
        }
        // --- ГЕНЕРАТОР ТАКТИЧНИХ ІКОНОК ---
        private android.graphics.drawable.Drawable createTacticalMarkerIcon(String topText, int colorCode) {
            // Створюємо порожнє полотно (Bitmap) 150x150 пікселів
            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(150, 150, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
            android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    
            // 1. Малюємо текст (ID або SOS) зверху
            paint.setColor(Color.BLACK); // Колір тексту
            paint.setTextSize(26f); // Розмір тексту
            paint.setFakeBoldText(true);
            paint.setTextAlign(android.graphics.Paint.Align.CENTER);
    
            // Малюємо білу обводку тексту (щоб читалося на будь-якому фоні карти)
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(4f);
            paint.setColor(Color.WHITE);
            canvas.drawText(topText, 75, 40, paint);
    
            // Малюємо сам чорний текст поверх обводки
            paint.setStyle(android.graphics.Paint.Style.FILL);
            paint.setColor(Color.BLACK);
            canvas.drawText(topText, 75, 40, paint);
    
            // 2. Малюємо кружечок (мітку) по центру
            paint.setColor(colorCode);
            canvas.drawCircle(75, 90, 20, paint); // Радіус 20
    
            // 3. Малюємо біле кільце навколо кружечка для краси
            paint.setColor(Color.WHITE);
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(4f);
            canvas.drawCircle(75, 90, 20, paint);
    
            return new android.graphics.drawable.BitmapDrawable(getResources(), bitmap);
        }
        // --- ДІАЛОГ ВВЕДЕННЯ ПАРОЛЯ ---
        private void showPasswordDialog() {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Ключ шифрування мережі");
            builder.setMessage("Введіть пароль вашої групи (наприклад: 'AlphaTeam2026')");
    
            final EditText input = new EditText(this);
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            builder.setView(input);
    
            builder.setPositiveButton("Встановити", (dialog, which) -> {
                String password = input.getText().toString();
                sendPasswordHash(password);
            });
    
            builder.setCancelable(false); // Заборонити закривати повз кнопку
            builder.show();
        }
    
        // --- ХЕШУВАННЯ ТА ВІДПРАВКА ПАРОЛЯ ---
        private void sendPasswordHash(String password) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(password.getBytes("UTF-8"));
    
                // Відправляємо перші 16 байт хешу як ключ для AES
                byte[] aesKey = new byte[16];
                System.arraycopy(hash, 0, aesKey, 0, 16);
    
                bleManager.sendPassword(aesKey);
                Toast.makeText(this, "Ключ встановлено!", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        private void shutdownApp() {
            // Зупиняємо відправку координат
            txHandler.removeCallbacks(txRunnable);

            // Зупиняємо фонову службу
            Intent serviceIntent = new Intent(this, TrackingService.class);
            stopService(serviceIntent);

            // Відключаємо Bluetooth
            if (bleManager != null) bleManager.disconnect();

            // Зупиняємо GPS
            if (gpsManager != null) gpsManager.stopLocationUpdates();

            // Повністю закриваємо додаток
            finishAffinity();
            System.exit(0);
        }
        private void checkPermissions() {
            // ... (ТВІЙ СТАРИЙ КОД checkPermissions без змін)
            List<String> needed = new ArrayList<>();
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            needed.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                needed.add(Manifest.permission.BLUETOOTH_SCAN);
                needed.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
    
            List<String> toRequest = new ArrayList<>();
            for (String p : needed) {
                if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                    toRequest.add(p);
                }
            }
            if (!toRequest.isEmpty()) {
                ActivityCompat.requestPermissions(this, toRequest.toArray(new String[0]), 100);
            }
        }
    
        @Override
        protected void onResume() {
            super.onResume();
            map.onResume(); // Важливо для OSMdroid
            gpsManager.startLocationUpdates();
        }
    
        @Override
        protected void onPause() {
            super.onPause();
            map.onPause(); // Важливо для OSMdroid
            gpsManager.stopLocationUpdates();
            bleManager.stopScan();
    
    
        }
        private void initVectorMap() {
            AndroidGraphicFactory.createInstance(getApplication());
    
    
    // Вказуємо нашу створену підпапку
    File mapFile = new File(getExternalFilesDir(null), "Ukraine_oam.osm.map");
    File themeFile = new File(getExternalFilesDir(null), "Elevate.xml");
            if (mapFile.exists()) {
                try {
                    org.mapsforge.map.rendertheme.XmlRenderTheme theme;
    
                    if (themeFile.exists()) {
                        try {
                            // Завантажуємо тему повністю, без жодних обрізань у коді
                            theme = new org.mapsforge.map.rendertheme.ExternalRenderTheme(themeFile);
                        } catch (Exception e) {
                            theme = InternalRenderTheme.DEFAULT;
                        }
                    }else {
                        // Якщо теми немає - використовуємо стандартну (пласку)
                        theme = InternalRenderTheme.DEFAULT;
                        Toast.makeText(this, "Тему Elevate не знайдено, використовую стандартну", Toast.LENGTH_SHORT).show();
                    }
    
                    MapsForgeTileSource tileSource = MapsForgeTileSource.createFromFiles(
                            new File[]{mapFile},
                            theme,
                            "OSMARENDER"
                    );
    
                    tileSource.setUserScaleFactor(1.5f);
    
                    org.osmdroid.tileprovider.util.SimpleRegisterReceiver receiver = new org.osmdroid.tileprovider.util.SimpleRegisterReceiver(this);
                    MapsForgeTileProvider provider = new MapsForgeTileProvider(receiver, tileSource, null);
    
                    map.setTileProvider(provider);
                    Toast.makeText(this, "Векторну топографічну карту завантажено", Toast.LENGTH_SHORT).show();
    
                } catch (Exception e) {
                    e.printStackTrace();
                    Toast.makeText(this, "Помилка завантаження теми: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            } else {
                Toast.makeText(this, "Файл карти ukraine.map не знайдено", Toast.LENGTH_LONG).show();
            }
        }
    
    }