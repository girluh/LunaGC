package emu.grasscutter.server.packet.recv;

import emu.grasscutter.game.gacha.GachaBanner;
import emu.grasscutter.game.gacha.PlayerGachaBannerInfo;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.GachaWishReqOuterClass.GachaWishReq;
import emu.grasscutter.server.game.GameSession;
import emu.grasscutter.server.packet.send.PacketGachaWishRsp;
import java.util.Arrays;

@Opcodes(PacketOpcodes.GachaWishReq)
public class HandlerGachaWishReq extends PacketHandler {

    private static final int RET_NO_ACTIVE_SCHEDULE = 1401;
    private static final int RET_SAME_WISH_ITEM = 1407;
    private static final int RET_ITEM_NOT_IN_UP_LIST = 1408;

    @Override
    public void handle(GameSession session, byte[] header, byte[] payload) throws Exception {
        GachaWishReq req = GachaWishReq.parseFrom(payload);

        GachaBanner banner =
                session.getServer().getGachaSystem().getGachaBanners().get(req.getGachaScheduleId());
        int itemId = req.getItemId();
        int retcode = 0;
        int progress = 0;
        int maxProgress = 0;

        if (banner == null || !banner.hasEpitomized()) {
            retcode = RET_NO_ACTIVE_SCHEDULE;
        } else {
            PlayerGachaBannerInfo gachaInfo =
                    session.getPlayer().getGachaInfo().getBannerInfo(banner);
            maxProgress = banner.getWishMaxProgress();
            progress = gachaInfo.getFailedChosenItemPulls();

            boolean itemAllowed =
                    itemId == 0
                            || Arrays.stream(banner.getRateUpItems5())
                                    .anyMatch(i -> i == itemId);
            if (!itemAllowed) {
                retcode = RET_ITEM_NOT_IN_UP_LIST;
            } else if (gachaInfo.getWishItemId() == itemId) {
                retcode = RET_SAME_WISH_ITEM;
            } else {
                gachaInfo.setWishItemId(itemId);
                gachaInfo.setFailedChosenItemPulls(0);
                progress = 0;
            }

            if (retcode != 0) {
                progress = 0;
                maxProgress = 0;
            }
        }

        session.send(
                new PacketGachaWishRsp(
                        req.getGachaType(),
                        req.getGachaScheduleId(),
                        itemId,
                        progress,
                        maxProgress,
                        retcode));
    }
}
