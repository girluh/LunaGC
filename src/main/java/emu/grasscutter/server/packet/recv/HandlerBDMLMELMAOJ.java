package emu.grasscutter.server.packet.recv;

import emu.grasscutter.net.packet.*;
import emu.grasscutter.server.game.GameSession;

@Opcodes(PacketOpcodes.BDMLMELMAOJ)
public class HandlerBDMLMELMAOJ extends PacketHandler {

    @Override
    public void handle(GameSession session, byte[] header, byte[] payload) throws Exception {
        int index = 0;
        int oldLevel = -1;
        long guid = 0;
        boolean hasGuid = false;

        while (index < payload.length) {
            long tag = 0;
            int shift = 0;
            while (index < payload.length) {
                long b = payload[index++] & 0xFFL;
                tag |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    break;
                }
                shift += 7;
                if (shift > 63) {
                    return;
                }
            }

            int field = (int) (tag >> 3);
            int wireType = (int) (tag & 7);
            long value = 0;

            if (wireType == 0) {
                shift = 0;
                while (index < payload.length) {
                    long b = payload[index++] & 0xFFL;
                    value |= (b & 0x7F) << shift;
                    if ((b & 0x80) == 0) {
                        break;
                    }
                    shift += 7;
                    if (shift > 63) {
                        return;
                    }
                }
                if (field == 9) {
                    oldLevel = (int) value;
                } else if (field == 13) {
                    guid = value;
                    hasGuid = true;
                }
            } else if (wireType == 2) {
                int length = 0;
                shift = 0;
                while (index < payload.length) {
                    long b = payload[index++] & 0xFFL;
                    length |= (int) ((b & 0x7F) << shift);
                    if ((b & 0x80) == 0) {
                        break;
                    }
                    shift += 7;
                }
                if (length < 0 || index + length > payload.length) {
                    return;
                }
                index += length;
            } else {
                return;
            }
        }

        if (!hasGuid) {
            return;
        }
        HandlerCHLFLNMHFIE.upgradeAvatar(session, guid, oldLevel);
    }
}
