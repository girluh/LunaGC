package emu.grasscutter.server.packet.recv;

import emu.grasscutter.game.player.Player;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.SetPlayerHeadImageReqOuterClass.SetPlayerHeadImageReq;
import emu.grasscutter.server.game.GameSession;
import emu.grasscutter.server.packet.send.PacketSetPlayerHeadImageRsp;
import emu.grasscutter.utils.ProtoEncode;

@Opcodes(PacketOpcodes.SetPlayerHeadImageReq)
public class HandlerSetPlayerHeadImageReq extends PacketHandler {
    @Override
    public void handle(GameSession session, byte[] header, byte[] payload) throws Exception {
        SetPlayerHeadImageReq req = SetPlayerHeadImageReq.parseFrom(payload);

        int pictureId = req.getProfilePictureId();
        Player player = session.getPlayer();

        int avatarId = ProtoEncode.pictureToAvatar(pictureId);
        if (avatarId == 0) {
            avatarId = player.getMainCharacterId();
        }
        if (avatarId == 0) {
            avatarId = player.getHeadImage();
        }

        player.setProfilePictureId(pictureId);
        player.setHeadImage(avatarId);
        session.send(new PacketSetPlayerHeadImageRsp(player));
    }
}
