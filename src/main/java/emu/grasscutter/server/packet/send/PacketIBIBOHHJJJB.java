package emu.grasscutter.server.packet.send;

import emu.grasscutter.game.player.Player;
import emu.grasscutter.net.packet.BasePacket;
import emu.grasscutter.net.packet.PacketOpcodes;
import emu.grasscutter.utils.ProtoEncode;

/** IBIBOHHJJJB (cmd 22090) — notify with owned profile picture + head frame lists. */
public class PacketIBIBOHHJJJB extends BasePacket {

    public PacketIBIBOHHJJJB(Player player) {
        super(PacketOpcodes.IBIBOHHJJJB);
        this.setData(
                ProtoEncode.buildProfileListsNotify(
                        ProtoEncode.ownedPictureIds(player),
                        ProtoEncode.ALL_PROFILE_FRAME_IDS));
    }
}
