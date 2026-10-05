package emu.grasscutter.server.packet.send;

import emu.grasscutter.game.player.Player;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.packet.PacketOpcodes;
import emu.grasscutter.utils.ProtoEncode;

/** BGEIAEHGKNP (cmd 20816) — owned profile picture + head frame lists. */
public class PacketBGEIAEHGKNP extends BasePacket {

    public PacketBGEIAEHGKNP(Player player) {
        super(PacketOpcodes.BGEIAEHGKNP);
        this.setData(
                ProtoEncode.buildProfileListsRsp(
                        ProtoEncode.ALL_PROFILE_FRAME_IDS,
                        ProtoEncode.ownedPictureIds(player)));
    }
}
