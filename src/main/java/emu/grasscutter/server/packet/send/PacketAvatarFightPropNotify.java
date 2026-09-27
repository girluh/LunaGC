package emu.grasscutter.server.packet.send;

import emu.grasscutter.game.avatar.Avatar;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.AvatarFightPropNotifyOuterClass.AvatarFightPropNotify;
import java.util.HashMap;

public class PacketAvatarFightPropNotify extends BasePacket {

    public PacketAvatarFightPropNotify(Avatar avatar) {
        super(PacketOpcodes.AvatarFightPropNotify);

        var props = new HashMap<>(avatar.getFightProperties());

        AvatarFightPropNotify proto =
                AvatarFightPropNotify.newBuilder()
                        .setAvatarGuid(avatar.getGuid())
                        .putAllFightPropMap(props)
                        .build();

        this.setData(proto);
    }
}
