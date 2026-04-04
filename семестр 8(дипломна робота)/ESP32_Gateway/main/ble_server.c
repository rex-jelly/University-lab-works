/*
 * Локальний BLE-шлюз для LoRa Tracker (16-byte AES payload)
 * Версія 2.0: Розподіл потоків по Характеристиках (GPS та Пароль)
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/queue.h"
#include "esp_system.h"
#include "esp_log.h"
#include "nvs_flash.h"
#include "esp_bt.h"
#include "esp_gap_ble_api.h"
#include "esp_gatts_api.h"
#include "esp_bt_defs.h"
#include "esp_bt_main.h"

#include "ble_server.h"
#include "data_types.h"
#include "crypto.h"
#define GATTS_TAG "BLE_TRACKER"

#define PROFILE_NUM 1
#define PROFILE_APP_ID 0


#define TRACKER_SVC_UUID       0x00FF
#define TRACKER_RX_GPS_UUID    0xFF01 // Сюди телефон шле свої координати
#define TRACKER_TX_GPS_UUID    0xFF02 // Звідси телефон читає координати інших (через Notify)
#define TRACKER_PASS_UUID      0xFF03 // Сюди телефон шле 16-байтний ключ AES

uint16_t global_packet_seq = 0; // Глобальний лічильник для пакетів з цього пристрою

uint16_t tracker_svc_handle;
uint16_t rx_gps_char_handle;
uint16_t tx_gps_char_handle;
uint16_t pass_char_handle;
uint16_t tx_descr_handle;

bool is_phone_connected = false;
bool is_notify_enabled = false;


struct gatts_profile_inst {
    esp_gatts_cb_t gatts_cb;
    uint16_t gatts_if;
    uint16_t app_id;
    uint16_t conn_id;
};

static void gatts_profile_event_handler(esp_gatts_cb_event_t event, esp_gatt_if_t gatts_if, esp_ble_gatts_cb_param_t *param);

static struct gatts_profile_inst gl_profile_tab[PROFILE_NUM] = {
    [PROFILE_APP_ID] = {
        .gatts_cb = gatts_profile_event_handler,
        .gatts_if = ESP_GATT_IF_NONE,
    },
};

static esp_ble_adv_data_t adv_data = {
    .set_scan_rsp = false,
    .include_name = true,
    .include_txpower = false,
    .min_interval = 0x0006,
    .max_interval = 0x0010,
    .appearance = 0x00,
    .flag = (ESP_BLE_ADV_FLAG_GEN_DISC | ESP_BLE_ADV_FLAG_BREDR_NOT_SPT),
};

static esp_ble_adv_params_t adv_params = {
    .adv_int_min        = 0x20,
    .adv_int_max        = 0x40,
    .adv_type           = ADV_TYPE_IND,
    .own_addr_type      = BLE_ADDR_TYPE_PUBLIC,
    .channel_map        = ADV_CHNL_ALL,
    .adv_filter_policy  = ADV_FILTER_ALLOW_SCAN_ANY_CON_ANY,
};

static void ble_notify_task(void* param)
{
    ESP_LOGI(GATTS_TAG, "BLE Notify Task Started");
    LoRaPacket_t rx_packet;

    while (1) {
        if (is_phone_connected && is_notify_enabled) {
            
            if (xQueueReceive(rx_from_lora_queue, &rx_packet, portMAX_DELAY) == pdTRUE) {
                
                esp_ble_gatts_send_indicate(
                    gl_profile_tab[PROFILE_APP_ID].gatts_if, 
                    gl_profile_tab[PROFILE_APP_ID].conn_id, 
                    tx_gps_char_handle, 
                    16, rx_packet.raw, false); // Відправляємо розпаковані 16 байт
                    
                ESP_LOGI(GATTS_TAG, "Відправлено 16 байт (GPS Data) на телефон!");
            }
            
        } else {
            vTaskDelay(pdMS_TO_TICKS(1000)); 
        }
    }
}

static void gap_event_handler(esp_gap_ble_cb_event_t event, esp_ble_gap_cb_param_t *param)
{
    switch (event) {
    case ESP_GAP_BLE_ADV_DATA_SET_COMPLETE_EVT:
        esp_ble_gap_start_advertising(&adv_params);
        break;
    default:
        break;
    }
}


static void gatts_profile_event_handler(esp_gatts_cb_event_t event, esp_gatt_if_t gatts_if, esp_ble_gatts_cb_param_t *param)
{
    switch (event) {
    case ESP_GATTS_REG_EVT: {
        esp_ble_gap_set_device_name("LoRa_Tracker");
        esp_ble_gap_config_adv_data(&adv_data);

        esp_gatt_srvc_id_t service_id = {
            .is_primary = true,
            .id.inst_id = 0x00,
            .id.uuid.len = ESP_UUID_LEN_16,
            .id.uuid.uuid.uuid16 = TRACKER_SVC_UUID,
        };
        esp_ble_gatts_create_service(gatts_if, &service_id, 12); 
        break;
    }
    case ESP_GATTS_CREATE_EVT: {
        tracker_svc_handle = param->create.service_handle;
        esp_ble_gatts_start_service(tracker_svc_handle);

        esp_bt_uuid_t rx_gps_uuid = {.len = ESP_UUID_LEN_16, .uuid.uuid16 = TRACKER_RX_GPS_UUID};
        esp_ble_gatts_add_char(tracker_svc_handle, &rx_gps_uuid, ESP_GATT_PERM_WRITE, ESP_GATT_CHAR_PROP_BIT_WRITE, NULL, NULL);

        esp_bt_uuid_t tx_gps_uuid = {.len = ESP_UUID_LEN_16, .uuid.uuid16 = TRACKER_TX_GPS_UUID};
        esp_ble_gatts_add_char(tracker_svc_handle, &tx_gps_uuid, ESP_GATT_PERM_READ, ESP_GATT_CHAR_PROP_BIT_NOTIFY, NULL, NULL);

        esp_bt_uuid_t pass_uuid = {.len = ESP_UUID_LEN_16, .uuid.uuid16 = TRACKER_PASS_UUID};
        esp_ble_gatts_add_char(tracker_svc_handle, &pass_uuid, ESP_GATT_PERM_WRITE, ESP_GATT_CHAR_PROP_BIT_WRITE, NULL, NULL);
        break;
    }
    case ESP_GATTS_ADD_CHAR_EVT: {
        if (param->add_char.char_uuid.uuid.uuid16 == TRACKER_RX_GPS_UUID) {
            rx_gps_char_handle = param->add_char.attr_handle;
            ESP_LOGI(GATTS_TAG, "Created RX_GPS Char");
        } 
        else if (param->add_char.char_uuid.uuid.uuid16 == TRACKER_TX_GPS_UUID) {
            tx_gps_char_handle = param->add_char.attr_handle;
            ESP_LOGI(GATTS_TAG, "Created TX_GPS Char");
            
            esp_bt_uuid_t descr_uuid = {.len = ESP_UUID_LEN_16, .uuid.uuid16 = ESP_GATT_UUID_CHAR_CLIENT_CONFIG};
            esp_ble_gatts_add_char_descr(tracker_svc_handle, &descr_uuid, ESP_GATT_PERM_READ | ESP_GATT_PERM_WRITE, NULL, NULL);
        }
        else if (param->add_char.char_uuid.uuid.uuid16 == TRACKER_PASS_UUID) {
            pass_char_handle = param->add_char.attr_handle;
            ESP_LOGI(GATTS_TAG, "Created PASSWORD Char");
        }
        break;
    }
    case ESP_GATTS_ADD_CHAR_DESCR_EVT:
        tx_descr_handle = param->add_char_descr.attr_handle;
        break;

    case ESP_GATTS_CONNECT_EVT:
        ESP_LOGI(GATTS_TAG, "Smartphone Connected!");
        gl_profile_tab[PROFILE_APP_ID].conn_id = param->connect.conn_id;
        is_phone_connected = true;
        break;

    case ESP_GATTS_DISCONNECT_EVT:
        ESP_LOGI(GATTS_TAG, "Smartphone Disconnected!");
        is_phone_connected = false;
        is_notify_enabled = false;
        esp_ble_gap_start_advertising(&adv_params);
        break;

    case ESP_GATTS_WRITE_EVT: {
        // ВАРІАНТ 1: Телефон увімкнув підписку (Notify)
        if (param->write.handle == tx_descr_handle && param->write.len == 2) {
            uint16_t descr_value = param->write.value[1] << 8 | param->write.value[0];
            is_notify_enabled = (descr_value == 0x0001);
            ESP_LOGI(GATTS_TAG, "Notify state changed to: %d", is_notify_enabled);
        }
        
        // ВАРІАНТ 2: Телефон прислав свої GPS КООРДИНАТИ
        else if (param->write.handle == rx_gps_char_handle) {
            if (param->write.len == 16) {
                LoRaPacket_t tx_packet;
                
                memcpy(tx_packet.raw, param->write.value, 16); 

                // ПАКУВАННЯ ЛІЧИЛЬНИКА ТА СТРИБКІВ
                uint8_t current_hops = 0; 
                global_packet_seq++;      
                tx_packet.data.counter_hops = (current_hops << 13) | (global_packet_seq & 0x1FFF);

                encrypt_packet(&tx_packet);
                xQueueSend(tx_to_lora_queue, &tx_packet, portMAX_DELAY);
                
                ESP_LOGI(GATTS_TAG, "Координати запаковано (Seq: %d), зашифровано і передано в LoRa!", global_packet_seq);
            } else {
                ESP_LOGW(GATTS_TAG, "GPS Packet must be 16 bytes!");
            }
        }
        
        // ВАРІАНТ 3: Телефон прислав ПАРОЛЬ AES
        else if (param->write.handle == pass_char_handle) {
            if (param->write.len == 16) {
                crypto_set_key(param->write.value);
                ESP_LOGI(GATTS_TAG, "Новий пароль AES отримано з телефону і застосовано!");
            } else {
                ESP_LOGE(GATTS_TAG, "Security Error: AES key MUST be 16 bytes!");
            }
        }

        if (param->write.need_rsp) {
            esp_ble_gatts_send_response(gatts_if, param->write.conn_id, param->write.trans_id, ESP_GATT_OK, NULL);
        }
        break;
    }
    default:
        break;
    }
}

static void gatts_event_handler(esp_gatts_cb_event_t event, esp_gatt_if_t gatts_if, esp_ble_gatts_cb_param_t *param)
{
    if (event == ESP_GATTS_REG_EVT) {
        if (param->reg.status == ESP_GATT_OK) {
            gl_profile_tab[param->reg.app_id].gatts_if = gatts_if;
        } else {
            return;
        }
    }
    if (gatts_if == ESP_GATT_IF_NONE || gatts_if == gl_profile_tab[PROFILE_APP_ID].gatts_if) {
        if (gl_profile_tab[PROFILE_APP_ID].gatts_cb) {
            gl_profile_tab[PROFILE_APP_ID].gatts_cb(event, gatts_if, param);
        }
    }
}

void ble_server_init(void)
{
    ESP_LOGI(GATTS_TAG, "Initializing LoRa Tracker System...");

    esp_err_t ret = nvs_flash_init();
    if (ret == ESP_ERR_NVS_NO_FREE_PAGES || ret == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        ret = nvs_flash_init();
    }
    ESP_ERROR_CHECK(ret);

    ESP_ERROR_CHECK(esp_bt_controller_mem_release(ESP_BT_MODE_CLASSIC_BT));

    esp_bt_controller_config_t bt_cfg = BT_CONTROLLER_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_bt_controller_init(&bt_cfg));
    ESP_ERROR_CHECK(esp_bt_controller_enable(ESP_BT_MODE_BLE));
    ESP_ERROR_CHECK(esp_bluedroid_init());
    ESP_ERROR_CHECK(esp_bluedroid_enable());

    ESP_ERROR_CHECK(esp_ble_gap_register_callback(gap_event_handler));
    ESP_ERROR_CHECK(esp_ble_gatts_register_callback(gatts_event_handler));
    ESP_ERROR_CHECK(esp_ble_gatts_app_register(PROFILE_APP_ID));

    xTaskCreatePinnedToCore(ble_notify_task, "ble_notify", 4096, NULL, 5, NULL, 0); 
}