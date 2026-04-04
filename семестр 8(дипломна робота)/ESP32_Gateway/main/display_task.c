#include "display_task.h"
#include <stdio.h>
#include <string.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_log.h"
#include "ssd1306.h"

extern bool is_phone_connected;
extern uint16_t global_packet_seq;

static const char *TAG = "OLED";
SSD1306_t dev;

static void display_task(void *pvParameters) {
    char buf[20];

    while (1) {
        if (is_phone_connected) {
            // Останній параметр false - звичайний текст (не інвертований)
            ssd1306_display_text(&dev, 0, "BLE: CONNECTED  ", 16, false); 
        } else {
            ssd1306_display_text(&dev, 0, "BLE: Waiting... ", 16, false);
        }

        // Форматуємо рядок: %-5d означає вирівнювання числа по лівому краю (щоб не залишалося сміття від старих цифр)
        sprintf(buf, "TX Seq: %-5d", global_packet_seq);
        ssd1306_display_text(&dev, 2, buf, strlen(buf), false);

        ssd1306_display_text(&dev, 5, " LilyGO TTGO T3 ", 16, true); // true - інвертовані кольори (білий фон, чорні букви)

        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}

void display_init(void) {
    ESP_LOGI(TAG, "Ініціалізація OLED екрану...");
    i2c_master_init(&dev, 21, 22, -1); 

    ssd1306_init(&dev, 128, 64);

    ssd1306_clear_screen(&dev, false);
    
    ssd1306_contrast(&dev, 0xFF);

    ssd1306_display_text(&dev, 3, " LORA TRACKER  ", 15, true);
    vTaskDelay(pdMS_TO_TICKS(2000));
    ssd1306_clear_screen(&dev, false);

    xTaskCreatePinnedToCore(&display_task, "display", 4096, NULL, 3, NULL, 0);
}