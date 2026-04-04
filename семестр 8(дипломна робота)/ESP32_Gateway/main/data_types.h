#ifndef DATA_TYPES_H
#define DATA_TYPES_H

#include <stdint.h>
#include <stdbool.h>
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/semphr.h"

typedef union {
    struct __attribute__((packed)) {
        uint16_t device_id;
        int32_t  latitude;
        int32_t  longitude;
        uint16_t counter_hops; 
        int16_t  altitude;
        uint8_t  bat_stat;
        uint8_t  crc;
    } data;
    
    uint8_t raw[16];
} LoRaPacket_t;

extern QueueHandle_t tx_to_lora_queue;
extern QueueHandle_t rx_from_lora_queue;
extern SemaphoreHandle_t lora_spi_mutex;
extern SemaphoreHandle_t rx_interrupt_sem;

#endif