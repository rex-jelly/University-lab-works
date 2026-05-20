#ifndef CRYPTO_H
#define CRYPTO_H

#include <stdint.h>
#include <stdbool.h>
#include "data_types.h"

void crypto_init(void);

void crypto_set_key(const uint8_t *new_key);

void encrypt_packet(LoRaPacket_t *packet);
bool decrypt_packet(LoRaPacket_t *packet); 

#endif