package emu.grasscutter.server.packet.send;

import emu.grasscutter.game.player.Player;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.utils.ProtoEncode;

public class PacketSetPlayerHeadImageRsp extends BasePacket {

    public PacketSetPlayerHeadImageRsp(Player player) {
        super(PacketOpcodes.SetPlayerHeadImageRsp);

        this.setData(
                ProtoEncode.buildSetPlayerHeadImageRsp(
                        player.getHeadImage(),
                        player.getProfilePictureId(),
                        player.getProfileFrameId()));
    }
}
