package dev.drobot.gpstracking;

import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.util.Log;
import androidx.core.content.ContextCompat;
import org.mapsforge.map.rendertheme.XmlRenderTheme;
import org.mapsforge.map.rendertheme.ExternalRenderTheme;
import java.util.Set;
import java.util.HashSet;
public class GpsManager implements LocationListener {

    private Context context;
    private LocationManager locationManager;
    private LocationListenerCallback callback;

    // Конфігурація: мінімальний час (мс) та відстань (метри) для оновлення
    private static final long MIN_TIME_BW_UPDATES = 1000 * 3;

    private static final float MIN_DISTANCE_CHANGE_FOR_UPDATES = 3; // 10 метрів

    // Інтерфейс для передачі даних назад в Activity/Fragment
    public interface LocationListenerCallback {
        void onLocationChanged(Location location);
        void onStatusChanged(String provider, int status, Bundle extras);
    }

    public GpsManager(Context context, LocationListenerCallback callback) {
        this.context = context;
        this.callback = callback;
        this.locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
    }

    /**
     * Запуск відстеження координат
     */
    public void startLocationUpdates() {
        // Перевірка дозволів (обов'язкова для Android 6.0+)
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {
            Log.e("GpsManager", "Дозволи на геолокацію не надано!");
            return;
        }

        try {
            boolean isGPSEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            boolean isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);

            if (!isGPSEnabled && !isNetworkEnabled) {
                Log.e("GpsManager", "Жоден провайдер геолокації не доступний.");
                return;
            }

            // Пріоритет 1: GPS (супутники) - точніше
            if (isGPSEnabled) {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        MIN_TIME_BW_UPDATES,
                        MIN_DISTANCE_CHANGE_FOR_UPDATES,
                        this
                );
                Log.d("GpsManager", "GPS відстеження запущено");
            }
            // Пріоритет 2: Мережа (Wi-Fi/Cell) - якщо GPS недоступний або для швидкого старту
            else if (isNetworkEnabled) {
                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        MIN_TIME_BW_UPDATES,
                        MIN_DISTANCE_CHANGE_FOR_UPDATES,
                        this
                );
                Log.d("GpsManager", "Network відстеження запущено");
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Зупинка відстеження для економії батареї
     */
    public void stopLocationUpdates() {
        if (locationManager != null) {
            locationManager.removeUpdates(this);
            Log.d("GpsManager", "Відстеження зупинено");
        }
    }

    /**
     * Отримати останню відому локацію (миттєво, без очікування супутників)
     */
    public Location getLastKnownLocation() {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return null;
        }

        Location location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        if (location == null) {
            location = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        }
        return location;
    }

    // --- Методи LocationListener ---

    @Override
    public void onLocationChanged(Location location) {
        if (callback != null) {
            callback.onLocationChanged(location);
        }
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        if (callback != null) {
            callback.onStatusChanged(provider, status, extras);
        }
    }

    @Override
    public void onProviderEnabled(String provider) {}

    @Override
    public void onProviderDisabled(String provider) {}
}