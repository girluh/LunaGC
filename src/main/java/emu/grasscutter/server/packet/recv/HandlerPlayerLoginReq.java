package emu.grasscutter.server.packet.recv;

import emu.grasscutter.data.GameData;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.server.game.GameSession;
import emu.grasscutter.server.packet.send.*;

@Opcodes(PacketOpcodes.PlayerLoginReq)
public class HandlerPlayerLoginReq extends PacketHandler {

    @Override
    public void handle(GameSession session, byte[] header, byte[] payload) throws Exception {
        if (session.getAccount() == null) {
            session.close();
            return;
        }

        Player player = session.getPlayer();

        if (player.getAvatars().getAvatarCount() == 0) {
            // Brand new account: let the client run its character creation flow instead of
            // silently creating a traveler here. onLogin() leaves the session in
            // PICKING_CHARACTER without a world/scene, then HandlerSetPlayerBornDataReq finishes
            // the birth. Ordering matches the official flow: notify first, then login response.
            session.getPlayer().onLogin();
            session.send(new BasePacket(PacketOpcodes.DoSetPlayerBornDataNotify));
            session.send(new PacketPlayerLoginRsp(session));
            return;
        }

        if (player.getMainCharacterId() != 0
                && GameData.getAvatarDataMap().get(player.getHeadImage()) == null) {
            player.setHeadImage(player.getMainCharacterId());
        }
        session.getPlayer().onLogin();
        session.send(new PacketPlayerLoginRsp(session));
    }
}
