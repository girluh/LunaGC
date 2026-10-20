package emu.grasscutter.server.packet.recv;

import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.GetAllMailReqOuterClass.GetAllMailReq;
import emu.grasscutter.server.game.GameSession;
import emu.grasscutter.server.packet.send.PacketGetAllMailRsp;

@Opcodes(PacketOpcodes.GetAllMailReq)
public final class HandlerGetAllMailReq extends PacketHandler {
    @Override
    public void handle(GameSession session, byte[] header, byte[] payload) throws Exception {
        var req = GetAllMailReq.parseFrom(payload);
        session.send(new PacketGetAllMailRsp(session.getPlayer(), req.getIsCollected()));
    }
}
