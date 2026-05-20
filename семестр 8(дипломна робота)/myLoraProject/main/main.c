#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/semphr.h"
#include "esp_log.h"
#include "lora.h"
#include "data_types.h"
#include "ble_server.h"
#include "radio_task.h"
#include "crypto.h"
#include "display_task.h"


QueueHandle_t tx_to_lora_queue;
QueueHandle_t rx_from_lora_queue;
SemaphoreHandle_t lora_spi_mutex;
SemaphoreHandle_t rx_interrupt_sem;

void app_main(void)
{
    ESP_LOGI("MAIN", "Запуск системи LoRa Tracker...");

    lora_spi_mutex = xSemaphoreCreateMutex();
    rx_interrupt_sem = xSemaphoreCreateBinary();

    if (lora_spi_mutex == NULL || rx_interrupt_sem == NULL)
    {
        ESP_LOGE("MAIN", "Помилка створення синхронізації!");
        return;
    }

    tx_to_lora_queue = xQueueCreate(10, sizeof(LoRaPacket_t));
    rx_from_lora_queue = xQueueCreate(10, sizeof(LoRaPacket_t));

    ble_server_init();

    crypto_init();

    display_init();

    radio_init();
}