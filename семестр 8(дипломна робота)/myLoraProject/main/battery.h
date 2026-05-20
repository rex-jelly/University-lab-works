#ifndef BAT_H
#define BAT_H
#include <stdint.h>
uint8_t get_battery_level(void);
uint8_t get_battery_level_approx(uint32_t current_mv);
typedef struct {
    uint32_t voltage;
    uint8_t percentage;
} BatteryMap_t;

#endif