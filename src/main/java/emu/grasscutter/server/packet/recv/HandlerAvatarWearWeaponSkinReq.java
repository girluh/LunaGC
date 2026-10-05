package emu.grasscutter.server.packet.recv;

import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.AvatarWearWeaponSkinReqOuterClass.AvatarWearWeaponSkinReq;
import emu.grasscutter.server.game.GameSession;
import emu.grasscutter.server.packet.send.PacketAvatarWearWeaponSkinRsp;

@Opcodes(PacketOpcodes.AvatarWearWeaponSkinReq)
public class HandlerAvatarWearWeaponSkinReq extends PacketHandler {

    @Override
    public void handle(GameSession session, byte[] header, byte[] payload) throws Exception {
        AvatarWearWeaponSkinReq req = AvatarWearWeaponSkinReq.parseFrom(payload);

        var player = session.getPlayer();
        var guids = req.getAvatarGuidListList();
        int skinId = req.getWeaponSkinId();

        boolean success = !guids.isEmpty() && player.hasWeaponSkin(skinId);
        session.send(
                success
                        ? new PacketAvatarWearWeaponSkinRsp(guids, skinId)
                        : new PacketAvatarWearWeaponSkinRsp());

        // Apply after the Rsp so the client processes the skin state before the entity updates.
        if (success) {
            player.getAvatars().changeWeaponSkin(guids, skinId);
        }
    }
}
