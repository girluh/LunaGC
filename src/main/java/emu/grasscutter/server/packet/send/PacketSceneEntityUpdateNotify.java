package emu.grasscutter.server.packet.send;

import emu.grasscutter.game.entity.GameEntity;
import emu.grasscutter.game.player.Player;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.SceneEntityUpdateNotifyOuterClass.SceneEntityUpdateNotify;
import emu.grasscutter.net.proto.VisionTypeOuterClass.VisionType;
import emu.grasscutter.utils.ProtoEncode;
import java.util.Collection;

/**
 * SceneEntityUpdateNotify (cmd 20664 in 7.0.0). Updates entity data in place without a
 * disappear/appear cycle. The generated class has stale field numbers for param/appear_type
 * (14/13 from an older version); 7.0.0 uses entity_list=10, param=8, appear_type=9.
 */
public class PacketSceneEntityUpdateNotify extends BasePacket {

    private static byte[] encode(Collection<? extends GameEntity> entities, VisionType vision, int param) {
        byte[] base =
                SceneEntityUpdateNotify.newBuilder()
                        .addAllEntityList(entities.stream().map(GameEntity::toProto).toList())
                        .build()
                        .toByteArray();
        base = ProtoEncode.appendVarint(base, 9, vision.getNumber());
        base = ProtoEncode.appendVarint(base, 8, param);
        return base;
    }

    public PacketSceneEntityUpdateNotify(GameEntity entity) {
        this(entity, VisionType.VisionType_VISION_BORN, 0);
    }

    public PacketSceneEntityUpdateNotify(GameEntity entity, VisionType vision, int param) {
        super(PacketOpcodes.SceneEntityUpdateNotify, true);
        this.setData(encode(java.util.List.of(entity), vision, param));
    }

    public PacketSceneEntityUpdateNotify(Player player) {
        this(player.getTeamManager().getCurrentAvatarEntity());
    }

    public PacketSceneEntityUpdateNotify(
            Collection<? extends GameEntity> entities, VisionType visionType) {
        super(PacketOpcodes.SceneEntityUpdateNotify, true);
        this.setData(encode(entities, visionType, 0));
    }
}
