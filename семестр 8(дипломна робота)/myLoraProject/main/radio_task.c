#include "radio_task.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/semphr.h"
#include "esp_log.h"
#include <string.h>
#include "driver/gpio.h"

#include "lora.h"
#include "data_types.h"
#include "crypto.h"

extern void lora_write_reg(int reg, int val);

static const char *TAG = "LORA_RADIO";
#define LORA_DIO0_PIN GPIO_NUM_26 


#define MAX_HOPS_ALLOWED 4          
#define MAX_TRACKED_DEVICES 20      

// Макроси для розрізання 16 біт на (3 біти hops + 13 біт counter)
#define GET_COUNTER(val) ((val) & 0x1FFF)     // Маска для нижніх 13 біт
#define GET_HOPS(val)    ((val) >> 13)        // Зсув для верхніх 3 біт
uint16_t my_device_id = 0xFFFF;
// Структура для запису в таблицю
typedef struct {
    uint16_t device_id;
    uint16_t last_counter;
    bool     is_active;
} TrackedDevice_t;

static TrackedDevice_t device_table[MAX_TRACKED_DEVICES] = {0};

// ФУНКЦІЯ ПЕРЕВІРКИ ПАКЕТУ
static bool is_packet_new(uint16_t dev_id, uint16_t counter) {
    int empty_slot = -1;
 if(my_device_id == dev_id)
 return false;
    for (int i = 0; i < MAX_TRACKED_DEVICES; i++) {
       
        if (device_table[i].is_active && device_table[i].device_id == dev_id) {
            
            // Вираховуємо різницю (з урахуванням переповнення 13-бітного лічильника на числі 8191)
            int16_t diff = counter - device_table[i].last_counter;
            if (diff < -4096) diff += 8192; 
            
            if (diff > 0) {
                // лата була поза зоною дії і повернулася.
                // Лічильник просто пішов уперед. Приймаємо без питань!
                device_table[i].last_counter = counter; 
                return true;
            } 
            else if (counter <= 5 && diff < -10) {
                // Детектор перезавантаження (Жорсткий рестарт)
                // Якщо новий лічильник дуже малий (1..5), а старий був великим (наприклад, 88), це означає, що плата втратила живлення і почала рахувати з нуля.
                ESP_LOGW(TAG, "Виявлено перезавантаження пристрою ID %d! Скидаємо лічильник на %d", dev_id, counter);
                device_table[i].last_counter = counter;
                return true;
            }

            // Якщо ми дійшли сюди, значить це реально дублікат-луна або хакерська атака старим пакетом
            return false; 
        }
        if (!device_table[i].is_active && empty_slot == -1) {
            empty_slot = i; 
        }
    }

    if (empty_slot != -1) {
        device_table[empty_slot].device_id = dev_id;
        device_table[empty_slot].last_counter = counter;
        device_table[empty_slot].is_active = true;
        ESP_LOGI(TAG, "Додано новий пристрій ID %d у базу. Лічильник: %d", dev_id, counter);
        return true;
    }

    ESP_LOGW(TAG, "Таблиця пристроїв переповнена! Пакет ігнорується.");
    return false; 
}
static void IRAM_ATTR lora_dio0_isr_handler(void* arg) {
    BaseType_t xHigherPriorityTaskWoken = pdFALSE;
    xSemaphoreGiveFromISR(rx_interrupt_sem, &xHigherPriorityTaskWoken);
    if (xHigherPriorityTaskWoken) portYIELD_FROM_ISR();
}

static void lora_rx_task(void *pvParameters) {
    uint8_t rx_buffer[16];

    while (1) {
        if (xSemaphoreTake(rx_interrupt_sem, portMAX_DELAY) == pdTRUE) {
            if (xSemaphoreTake(lora_spi_mutex, pdMS_TO_TICKS(100)) == pdTRUE) {
                
                int len = lora_receive_packet(rx_buffer, sizeof(rx_buffer));
                
                if (len == 16) {
                    LoRaPacket_t rx_packet;
                    memcpy(rx_packet.raw, rx_buffer, 16);
                    
                    if (decrypt_packet(&rx_packet)) {
                        // Розпаковуємо бітовий запис
                        uint16_t raw_val = rx_packet.data.counter_hops;
                        uint16_t seq = GET_COUNTER(raw_val);
                        uint8_t hops = GET_HOPS(raw_val);

                        if (hops >= MAX_HOPS_ALLOWED) {
                            ESP_LOGW(TAG, "Пакет від ID %d відхилено: досягнуто ліміт %d стрибків", rx_packet.data.device_id, hops);
                        } 
                        else if (is_packet_new(rx_packet.data.device_id, seq)) {
                            ESP_LOGI(TAG, "RX: Прийнято СВІЖИЙ пакет! ID: %d, Seq: %d, Hops: %d", rx_packet.data.device_id, seq, hops);
                        
                            //Віддаємо отримані координати на свій телефон
                            xQueueSend(rx_from_lora_queue, &rx_packet, 0);

                            if (hops + 1 < MAX_HOPS_ALLOWED) {
                                LoRaPacket_t relay_packet;
                                memcpy(&relay_packet, &rx_packet, sizeof(LoRaPacket_t)); 
                                    uint8_t new_hops = hops + 1;
                                relay_packet.data.counter_hops = (new_hops << 13) | (seq & 0x1FFF);

                                // Оскільки ми змінили дані (hops), треба НАНОВО ЗАШИФРУВАТИ 
                                // пакет і перерахувати CRC перед тим, як кидати в ефір!
                                encrypt_packet(&relay_packet);
                                    ESP_LOGI(TAG, "MESH: Ретрансляція пакету від ID %d. Новий Hops: %d", rx_packet.data.device_id, new_hops);
                                    // Кидаємо у чергу на передачу в ефір
                                    xQueueSend(tx_to_lora_queue, &relay_packet, 0);
                                }
                            } else {
                                ESP_LOGI(TAG, "MESH: Ретрансляція зупинена. Досягнуто ліміт стрибків (%d)", MAX_HOPS_ALLOWED);
                            }
                        }else {
                            ESP_LOGI(TAG, "RX: Дублікат пакету від ID %d. Ігноруємо.", rx_packet.data.device_id);
                        }
                    }
                }
                lora_receive(); 
                xSemaphoreGive(lora_spi_mutex);
            }
        }
    }


static void lora_tx_task(void *pvParameters) {
    LoRaPacket_t tx_packet;

    while (1) {
        if (xQueueReceive(tx_to_lora_queue, &tx_packet, portMAX_DELAY) == pdTRUE) {
            ESP_LOGI(TAG, "TX: Починаю відправку в ефір...");
            if (xSemaphoreTake(lora_spi_mutex, portMAX_DELAY) == pdTRUE) {
                lora_send_packet(tx_packet.raw, 16);
                lora_receive();
                xSemaphoreGive(lora_spi_mutex);
                ESP_LOGI(TAG, "TX: Відправка завершена.");
            }
        }
    }
}


void radio_init(void) {
    ESP_LOGI(TAG, "Ініціалізація LoRa SX1276...");
    if (!lora_init()) {
        ESP_LOGE(TAG, "Радіомодуль не знайдено!");
        return; 
    }

    lora_set_frequency(868000000); 
    lora_set_spreading_factor(9);  
    lora_set_bandwidth(125000);    
    lora_enable_crc();             
    
    gpio_config_t io_conf = {
        .intr_type = GPIO_INTR_POSEDGE, 
        .pin_bit_mask = (1ULL << LORA_DIO0_PIN), 
        .mode = GPIO_MODE_INPUT,
        .pull_up_en = 0,
        .pull_down_en = 1 
    };
    gpio_config(&io_conf);

    gpio_install_isr_service(0);
    gpio_isr_handler_add(LORA_DIO0_PIN, lora_dio0_isr_handler, NULL);
    lora_write_reg(0x40, 0x00); 

    lora_receive();                
    ESP_LOGI(TAG, "Радіомодуль готовий. Запуск задач...");

    xTaskCreatePinnedToCore(&lora_rx_task, "lora_rx", 4096, NULL, 5, NULL, 1);
    xTaskCreatePinnedToCore(&lora_tx_task, "lora_tx", 4096, NULL, 6, NULL, 1);
}