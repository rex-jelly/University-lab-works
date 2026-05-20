#include "driver/adc.h"
#include "esp_adc_cal.h"
#include "esp_log.h"
#include <stdint.h>
#include "battery.h"
#define BAT_ADC_PIN ADC1_CHANNEL_7 


//Таблиця апроксимації (відкалібрована під твої логи)
static const BatteryMap_t battery_lut[] = {
    {3310, 100}, // Твій абсолютний топ
    {3170, 90},  // Твоє середнє значення при майже повному заряді
    {2968, 75},  // Твій третій "топ замір"
    {2800, 40},  // Початок крутого спуску (типово для Li-Po)
    {2650, 15},  // Червона зона
    {2500, 0}    // Точка вимкнення
};

#define LUT_SIZE (sizeof(battery_lut) / sizeof(battery_lut[0]))
static esp_adc_cal_characteristics_t *adc_chars;

uint8_t get_battery_level() {
    if (adc_chars == NULL) {
        adc_chars = calloc(1, sizeof(esp_adc_cal_characteristics_t));
        esp_adc_cal_characterize(ADC_UNIT_1, ADC_ATTEN_DB_11, ADC_WIDTH_BIT_12, 1100, adc_chars);
    }

    // 1. Мультисемплінг для стабільності
    uint32_t raw = 0;
    for (int i = 0; i < 20; i++) { 
        raw += adc1_get_raw(BAT_ADC_PIN); 
    }
    raw /= 20;

    // 2. Отримання реальної напруги (ADC * 2 через дільник)
    uint32_t current_mv = esp_adc_cal_raw_to_voltage(raw, adc_chars) * 2;

    // 3. АЛГОРИТМ АПРОКСИМАЦІЇ (КРОК ЗА КРОКОМ)
    
    // Перевірка за межами таблиці
    if (current_mv >= battery_lut[0].voltage) return 100;
    if (current_mv <= battery_lut[LUT_SIZE - 1].voltage) return 0;

    // Пошук потрібного сегмента в таблиці
    uint8_t percentage = 0;
    for (int i = 0; i < LUT_SIZE - 1; i++) {
        if (current_mv <= battery_lut[i].voltage && current_mv > battery_lut[i+1].voltage) {
            
            // Розрахунок всередині сегмента за формулою лінійної інтерполяції:
            // $$P = P_{low} + \frac{(V_{current} - V_{low}) \times (P_{high} - P_{low})}{V_{high} - V_{low}}$$
            
            uint32_t v_high = battery_lut[i].voltage;
            uint32_t v_low = battery_lut[i+1].voltage;
            uint8_t p_high = battery_lut[i].percentage;
            uint8_t p_low = battery_lut[i+1].percentage;

            percentage = p_low + (uint8_t)((current_mv - v_low) * (p_high - p_low) / (v_high - v_low));
            break;
        }
    }

    ESP_LOGI("BATTERY", "Raw ADC: %ld | V: %ld mV -> %d%%", raw, current_mv, percentage);
    return percentage;
}