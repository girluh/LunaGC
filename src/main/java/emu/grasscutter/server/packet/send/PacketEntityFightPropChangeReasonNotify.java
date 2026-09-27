package emu.grasscutter.server.packet.send;

import emu.grasscutter.game.ability.Ability;
import emu.grasscutter.game.entity.GameEntity;
import emu.grasscutter.game.props.FightProperty;
import emu.grasscutter.net.packet.*;
import emu.grasscutter.net.proto.ChangeEnergyReasonOuterClass.ChangeEnergyReason;
import emu.grasscutter.net.proto.ChangeHpDebtsReasonOuterClass.ChangeHpDebtsReason;
import emu.grasscutter.net.proto.ChangeHpReasonOuterClass.ChangeHpReason;
import emu.grasscutter.net.proto.AbilityStringOuterClass.AbilityString;
import emu.grasscutter.net.proto.DetailAbilityInfoOuterClass.DetailAbilityInfo;
import emu.grasscutter.net.proto.EntityFightPropChangeReasonNotifyOuterClass.EntityFightPropChangeReasonNotify;
import emu.grasscutter.net.proto.PropChangeReasonOuterClass.PropChangeReason;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.UnknownFieldSet;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import lombok.Getter;
import java.util.List;

public class PacketEntityFightPropChangeReasonNotify extends BasePacket {
    @Getter private Ability ability;
    public PacketEntityFightPropChangeReasonNotify(
            GameEntity entity,
            FightProperty prop,
            Float value,
            List<Integer> param,
            PropChangeReason reason,
            ChangeHpReason changeHpReason) {
        super(PacketOpcodes.EntityFightPropChangeReasonNotify);

        EntityFightPropChangeReasonNotify.Builder proto =
                EntityFightPropChangeReasonNotify.newBuilder()
                        .setEntityId(entity.getId())
                        .setPropType(prop.getId())
                        .setPropDelta(value)
                        .setReason(reason)
                        .setChangeHpReason(changeHpReason);

        for (int p : param) {
            proto.addParamList(p);
        }

        this.setData(proto);
    }

    public PacketEntityFightPropChangeReasonNotify(
            GameEntity entity,
            FightProperty prop,
            Float value,
            PropChangeReason reason,
            ChangeHpReason changeHpReason) {
        super(PacketOpcodes.EntityFightPropChangeReasonNotify);

        var proto =
                EntityFightPropChangeReasonNotify.newBuilder()
                        .setEntityId(entity.getId())
                        .setPropType(prop.getId())
                        .setPropDelta(value)
                        .setReason(reason)
                        .setChangeHpReason(changeHpReason);

        applyDetailInfo(proto, entity);

        this.setData(proto);
    }

    public PacketEntityFightPropChangeReasonNotify(
            GameEntity entity, FightProperty prop, Float value, PropChangeReason reason) {
        super(PacketOpcodes.EntityFightPropChangeReasonNotify);

        EntityFightPropChangeReasonNotify proto =
                EntityFightPropChangeReasonNotify.newBuilder()
                        .setEntityId(entity.getId())
                        .setPropType(prop.getId())
                        .setPropDelta(value)
                        .setReason(reason)
                        .build();

        this.setData(proto);
    }

    public PacketEntityFightPropChangeReasonNotify(
            GameEntity entity, FightProperty prop, Float value, ChangeEnergyReason reason) {
        super(PacketOpcodes.EntityFightPropChangeReasonNotify);


        EntityFightPropChangeReasonNotify proto =
                EntityFightPropChangeReasonNotify.newBuilder()
                        .setEntityId(entity.getId())
                        .setPropType(prop.getId())
                        .setPropDelta(value)
                        .setChangeEnergyReson(reason)
                        .build();

        this.setData(proto);
    }

    public PacketEntityFightPropChangeReasonNotify(
            GameEntity entity,
            FightProperty prop,
            Float value,
            PropChangeReason reason,
            ChangeHpDebtsReason changeHpDebts) {
        super(PacketOpcodes.EntityFightPropChangeReasonNotify);

        var proto =
                EntityFightPropChangeReasonNotify.newBuilder()
                        .setEntityId(entity.getId())
                        .setPropType(prop.getId())
                        .setPropDelta(value)
                        .setReason(reason)
                        .setChangeHpDebtsReason(changeHpDebts);

        applyDetailInfo(proto, entity);

        // The amount of debt paid in this event (field 10, float) — only meaningful for PAY/PAY_FINISH
        if (changeHpDebts == ChangeHpDebtsReason.CHANGE_HP_DEBTS_REASON_CHANGE_HP_DEBTS_PAY
                || changeHpDebts
                        == ChangeHpDebtsReason.CHANGE_HP_DEBTS_REASON_CHANGE_HP_DEBTS_PAY_FINISH) {
            applyPaidHpDebts(proto, Math.abs(value));
        }

        this.setData(proto);
    }


    public PacketEntityFightPropChangeReasonNotify(
        GameEntity entity, FightProperty prop, Float value, PropChangeReason reason, ChangeEnergyReason energyReason) {
    super(PacketOpcodes.EntityFightPropChangeReasonNotify);

    var proto =
            EntityFightPropChangeReasonNotify.newBuilder()
                    .setEntityId(entity.getId())
                    .setPropType(prop.getId())
                    .setPropDelta(value)
                    .setReason(reason)
                    .setChangeEnergyReson(energyReason);

    applyDetailInfo(proto, entity);

    this.setData(proto);
}

    /**
     * The generated 7.0 class declares {@code detail_info} (field 7) as an obfuscated message type
     * leftover from 6.5, but in the real 7.0 descriptor field 7 wraps {@code PropChangeDetailInfo}
     * whose oneof {@code detail_ability_info} is field 11. Write the correct wire bytes as unknown
     * fields so the client parses them.
     */
    private static void applyDetailInfo(
            EntityFightPropChangeReasonNotify.Builder proto, GameEntity entity) {
        DetailAbilityInfo detail = entity.getDetailAbilityInfo();
        if (detail == null) return;

        ByteString inner;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            CodedOutputStream cos = CodedOutputStream.newInstance(bos);
            cos.writeMessage(11, detail); // PropChangeDetailInfo.detail_ability_info = 11
            cos.flush();
            inner = ByteString.copyFrom(bos.toByteArray());
        } catch (IOException e) {
            return;
        }

        proto.mergeUnknownFields(
                UnknownFieldSet.newBuilder()
                        .addField(
                                7,
                                UnknownFieldSet.Field.newBuilder()
                                        .addLengthDelimited(inner)
                                        .build())
                        .build());
    }

    /**
     * {@code _paid_hp_debts} is field 10 (float) in the 7.0 descriptor; the generated class only
     * has a misnumbered placeholder, so emit it as an unknown fixed32 field.
     */
    private static void applyPaidHpDebts(
            EntityFightPropChangeReasonNotify.Builder proto, float paid) {
        proto.mergeUnknownFields(
                UnknownFieldSet.newBuilder()
                        .addField(
                                10,
                                UnknownFieldSet.Field.newBuilder()
                                        .addFixed32(Float.floatToRawIntBits(paid))
                                        .build())
                        .build());
    }

}
