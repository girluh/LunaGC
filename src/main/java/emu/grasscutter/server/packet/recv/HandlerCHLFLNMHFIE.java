package emu.grasscutter.server.packet.recv;

import emu.grasscutter.Grasscutter;
import emu.grasscutter.game.avatar.Avatar;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.server.game.GameSession;
import emu.grasscutter.server.packet.send.PacketAvatarPropNotify;
import java.io.ByteArrayOutputStream;

@Opcodes(PacketOpcodes.CHLFLNMHFIE)
public class HandlerCHLFLNMHFIE extends PacketHandler {

    private static final int MASTERLESS_FATE_STAR = 104300;

    @Override
    public void handle(GameSession session, byte[] header, byte[] payload) throws Exception {
        if (payload.length < 2 || (payload[0] & 0xFF) != 0x58) {
            return;
        }

        int index = 1;
        long guid = 0;
        int shift = 0;
        while (index < payload.length) {
            long b = payload[index++] & 0xFFL;
            guid |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
            shift += 7;
            if (shift > 63) {
                return;
            }
        }

        upgradeAvatar(session, guid, -1);
    }

    static void upgradeAvatar(GameSession session, long guid, int reqOldLevel) {
        Avatar avatar = session.getPlayer().getAvatars().getAvatarByGuid(guid);
        if (avatar == null) {
            sendRsp(session, guid, 0, 0, 1);
            return;
        }

        int oldLevel = avatar.getLevel();
        int curLevel =
                switch (oldLevel) {
                    case 90 -> 95;
                    case 95 -> 100;
                    default -> 0;
                };
        if (curLevel == 0 || (reqOldLevel > 0 && reqOldLevel != oldLevel)) {
            Grasscutter.getLogger()
                    .info(
                            "ExtraLevelUpgrade rejected: guid={} level={} reqOldLevel={}",
                            guid,
                            oldLevel,
                            reqOldLevel);
            sendRsp(session, guid, oldLevel, oldLevel, 1);
            return;
        }

        int cost = oldLevel == 90 ? 1 : 2;
        if (!session.getPlayer().getInventory().payItem(MASTERLESS_FATE_STAR, cost)) {
            Grasscutter.getLogger()
                    .info("ExtraLevelUpgrade denied: guid={} item={} count={}", guid, MASTERLESS_FATE_STAR, cost);
            sendRsp(session, guid, oldLevel, oldLevel, 1);
            return;
        }

        avatar.setLevel(curLevel);
        avatar.recalcStats(true);
        avatar.save();

        Grasscutter.getLogger().info("ExtraLevelUpgrade: guid={} level {} -> {}", guid, oldLevel, curLevel);
        session.getPlayer().sendPacket(new PacketAvatarPropNotify(avatar));
        sendRsp(session, guid, oldLevel, curLevel, 0);
    }

    static void sendRsp(GameSession session, long guid, int oldLevel, int curLevel, int retcode) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarintField(out, 2, retcode);
        writeVarintField(out, 6, oldLevel);
        writeVarintField(out, 10, guid);
        writeVarintField(out, 14, curLevel);

        BasePacket packet = new BasePacket(PacketOpcodes.AvatarExtraLevelUpgradeRsp);
        packet.setData(out.toByteArray());
        session.send(packet);
    }

    private static void writeVarintField(ByteArrayOutputStream out, int fieldNumber, long value) {
        long tag = (long) (fieldNumber << 3);
        while (tag >= 0x80) {
            out.write((int) ((tag & 0x7F) | 0x80));
            tag >>>= 7;
        }
        out.write((int) tag);

        while (value >= 0x80) {
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.write((int) value);
    }
}
