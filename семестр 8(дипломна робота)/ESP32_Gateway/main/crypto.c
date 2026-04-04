#include "crypto.h"
#include "mbedtls/aes.h"
#include "esp_log.h"
#include "nvs_flash.h"
#include "nvs.h"
#include <string.h>

static const char *TAG = "CRYPTO";
static const char *NVS_NAMESPACE = "storage";
static const char *NVS_KEY_NAME = "aes_key";

// 16-байтний ключ AES за замовчуванням
static uint8_t aes_key[16] = {
    0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 
    0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F
};

// Проста функція розрахунку контрольної суми (CRC8)
static uint8_t calculate_crc8(const uint8_t *data, size_t len) {
    uint8_t crc = 0;
    for (size_t i = 0; i < len; i++) {
        crc ^= data[i];
        for (uint8_t j = 0; j < 8; j++) {
            if (crc & 0x80) crc = (crc << 1) ^ 0x07;
            else crc <<= 1;
        }
    }
    return crc;
}

// Читання ключа з NVS при старті ---
void crypto_init(void) {
    nvs_handle_t my_handle;
    esp_err_t err = nvs_open(NVS_NAMESPACE, NVS_READONLY, &my_handle);
    
    if (err == ESP_OK) {
        size_t required_size = 16;
        err = nvs_get_blob(my_handle, NVS_KEY_NAME, aes_key, &required_size);
        
        if (err == ESP_OK) {
            ESP_LOGI(TAG, "Ключ AES успішно завантажено з NVS пам'яті!");
        } else {
            ESP_LOGW(TAG, "Ключ у пам'яті не знайдено. Використовується ключ за замовчуванням.");
        }
        nvs_close(my_handle);
    } else {
        ESP_LOGW(TAG, "Помилка відкриття NVS. Використовується ключ за замовчуванням.");
    }
}

// Запис ключа в NVS при зміні через Bluetooth ---
void crypto_set_key(const uint8_t *new_key) {
    // 1. Оновлюємо в оперативній пам'яті
    memcpy(aes_key, new_key, 16);
    
    // 2. Зберігаємо у флеш-пам'ять
    nvs_handle_t my_handle;
    esp_err_t err = nvs_open(NVS_NAMESPACE, NVS_READWRITE, &my_handle);
    if (err == ESP_OK) {
        nvs_set_blob(my_handle, NVS_KEY_NAME, aes_key, 16);
        nvs_commit(my_handle); // Робимо комміт, щоб фізично записати дані
        nvs_close(my_handle);
        ESP_LOGI(TAG, "Новий ключ AES успішно збережено у флеш-пам'ять!");
    } else {
        ESP_LOGE(TAG, "Помилка збереження ключа в NVS!");
    }
}

void encrypt_packet(LoRaPacket_t *packet) {
    packet->data.crc = calculate_crc8(packet->raw, 15);
    
    mbedtls_aes_context aes;
    mbedtls_aes_init(&aes);
    mbedtls_aes_setkey_enc(&aes, aes_key, 128);
    mbedtls_aes_crypt_ecb(&aes, MBEDTLS_AES_ENCRYPT, packet->raw, packet->raw);
    mbedtls_aes_free(&aes);
}

bool decrypt_packet(LoRaPacket_t *packet) {
    mbedtls_aes_context aes;
    mbedtls_aes_init(&aes);
    mbedtls_aes_setkey_dec(&aes, aes_key, 128);
    mbedtls_aes_crypt_ecb(&aes, MBEDTLS_AES_DECRYPT, packet->raw, packet->raw);
    mbedtls_aes_free(&aes);
    
    uint8_t calculated_crc = calculate_crc8(packet->raw, 15);
    
    if (calculated_crc == packet->data.crc) {
        return true;  
    } else {
        return false; 
    }
}