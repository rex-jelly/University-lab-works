#include "display_task.h"
#include <stdio.h>
#include <string.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_log.h"
#include "ssd1306.h"
#include "esp_mac.h" // Бібліотека для роботи з унікальним MAC-адресом
#include "battery.h"
extern bool is_phone_connected;
extern uint16_t global_packet_seq;

static const char *TAG = "OLED";
SSD1306_t dev;

// Ця функція static, вона використовується тільки всередині цього файлу [cite: 207]
static void display_task(void *pvParameters)
{
    char buf[20];
    uint8_t mac[6];

    // Зчитуємо заводський MAC-адрес Bluetooth (BD_ADDR)
    esp_read_mac(mac, ESP_MAC_BT);

    while (1)
    {
        // Рядок 0: Статус зв'язку [cite: 186, 211]
        if (is_phone_connected)
        {
            ssd1306_display_text(&dev, 2, "BLE: CONNECTED  ", 16, false);
        }
        else
        {
            ssd1306_display_text(&dev, 2, "BLE: Waiting... ", 16, false);
        }

        // Рядок 2: Заголовок для ID
        ssd1306_display_text(&dev, 3, "UNIQUE HW ID:", 13, false);

        // Рядок 3: Вивід твого MAC (90:15:06:f6:34:e4)
        sprintf(buf, "%02X:%02X:%02X",
                mac[0], mac[1], mac[2]);
        ssd1306_display_text(&dev, 4, buf, 9, false);
        sprintf(buf, "%02X:%02X:%02X",
                mac[3], mac[4], mac[5]);
        ssd1306_display_text(&dev, 5, buf, 9, false);
        // Рядок 5: Лічильник пакетів LoRa [cite: 202, 241]
        sprintf(buf, "Packets: %-5d", global_packet_seq);
        ssd1306_display_text(&dev, 6, buf, strlen(buf), false);
        uint8_t bat = get_battery_level();
        char bat_buf[10];
        sprintf(bat_buf, "BAT:%3d%%", bat);
        ssd1306_display_text(&dev, 7,bat_buf, strlen(bat_buf), false);
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}

void display_init(void)
{
    ESP_LOGI(TAG, "Initializing OLED...");
    i2c_master_init(&dev, 21, 22, -1);
    ssd1306_init(&dev, 128, 64);
    ssd1306_clear_screen(&dev, false);
    ssd1306_contrast(&dev, 0xFF);

    // Запуск таска на другому ядрі (Core 0), щоб не заважати LoRa на Core 1 [cite: 33, 206]
    xTaskCreatePinnedToCore(&display_task, "display", 4096, NULL, 3, NULL, 0);
}