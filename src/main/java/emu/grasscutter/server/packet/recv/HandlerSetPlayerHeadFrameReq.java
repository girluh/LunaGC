package emu.grasscutter.server.packet.recv;

import emu.grasscutter.game.player.Player;
import emu.grasscutter.net.packet.Opcodes;
import emu.grasscutter.net.packet.PacketHandler;
import emu.grasscutter.net.packet.PacketOpcodes;
import emu.grasscutter.server.game.GameSession;
import emu.grasscutter.server.packet.send.PacketSetPlayerHeadFrameRsp;
import emu.grasscutter.utils.ProtoEncode;

@Opcodes(PacketOpcodes.SetPlayerHeadFrameReq)
public class HandlerSetPlayerHeadFrameReq extends PacketHandler {

    @Override
    public void handle(GameSession session, byte[] header, byte[] payload) throws Exception {
        int frameId = ProtoEncode.parseHeadFrameId(payload);
        Player player = session.getPlayer();
        player.setProfileFrameId(frameId);
        player.save();
        session.send(new PacketSetPlayerHeadFrameRsp(player, frameId));
    }
}
