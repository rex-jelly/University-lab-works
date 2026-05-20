package dev.drobot.gpstracking;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class LoRaPacket {
    public int deviceId;
    public double latitude;
    public double longitude;
    public int counter;
    public int hops;
    public int altitude;
    public int batteryStatus;
    public boolean isSos; // Поле для статусу тривоги
    public int  msgId;
    // --- ПАКУВАННЯ (ДЛЯ ВІДПРАВКИ НА ПЛАТУ) ---
    public static byte[] pack(int deviceId, double lat, double lon, int altitude, int batStat) {
        ByteBuffer buffer = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);

        buffer.putShort((short) deviceId);
        buffer.putInt((int) (lat * 10000000.0));
        buffer.putInt((int) (lon * 10000000.0));
        buffer.putShort((short) 0);
        buffer.putShort((short) altitude);

        // БРОНЕБІЙНИЙ ЗАХИСТ:
        // Примусово обрізаємо до 1 байта, щоб ніякі сміттєві дані не пролізли
        buffer.put((byte) (batStat & 0xFF));

        buffer.put((byte) 0); // CRC

        return buffer.array();
    }

    // --- РОЗПАКУВАННЯ (ДЛЯ ЧИТАННЯ З ПЛАТИ) ---
    public static LoRaPacket unpack(byte[] data) {
        if (data == null || data.length != 16) return null;

        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        LoRaPacket packet = new LoRaPacket();

        packet.deviceId = buffer.getShort() & 0xFFFF;
        packet.latitude = buffer.getInt() / 10000000.0;
        packet.longitude = buffer.getInt() / 10000000.0;

        short counterHops = buffer.getShort();
        packet.counter = counterHops & 0x1FFF;
        packet.hops = (counterHops >> 13) & 0x07;
        packet.altitude = buffer.getShort();

        // --- ЗАХИСТ ЧИТАННЯ SOS ---
        byte rawBatStat = buffer.get();

        // 1. Перевіряємо найстарший біт (0x80) для SOS.
        packet.isSos = (rawBatStat & 0x80) != 0;

        // 2. Очищаємо найстарший біт маскою 0x7F, щоб отримати чистий заряд батареї (0-127)
        packet.batteryStatus = rawBatStat & 0x7F;

        return packet;
    }
}