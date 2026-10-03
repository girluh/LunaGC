package emu.grasscutter.server.packet.send;

import emu.grasscutter.game.player.Player;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.packet.PacketOpcodes;
import emu.grasscutter.utils.ProtoEncode;

/** SetPlayerHeadFrameRsp (cmd 29314) — echoes the equipped profile picture + frame. */
public class PacketSetPlayerHeadFrameRsp extends BasePacket {

    public PacketSetPlayerHeadFrameRsp(Player player, int frameId) {
        super(PacketOpcodes.SetPlayerHeadFrameRsp);
        this.setData(
                ProtoEncode.buildSetPlayerHeadFrameRsp(
                        player.getHeadImage(), player.getProfilePictureId(), frameId));
    }
}
