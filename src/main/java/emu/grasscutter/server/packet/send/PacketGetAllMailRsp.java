package emu.grasscutter.server.packet.send;

import emu.grasscutter.game.player.Player;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.GetAllMailRspOuterClass.GetAllMailRsp;

/** GetAllMailRsp (cmd 21948) - reply of GetAllMailReq, carries the mailbox list. */
public final class PacketGetAllMailRsp extends BasePacket {

    public PacketGetAllMailRsp(Player player, boolean collected) {
        super(PacketOpcodes.GetAllMailRsp);

        var packet = GetAllMailRsp.newBuilder().setIsCollected(collected);
        if (!collected) {
            for (var mail : player.getAllMail()) {
                packet.addMailList(mail.toProto(player));
            }
        }

        this.setData(packet.build());
    }
}
