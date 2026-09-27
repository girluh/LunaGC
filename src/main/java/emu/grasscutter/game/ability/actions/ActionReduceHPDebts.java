package emu.grasscutter.game.ability.actions;

import com.google.protobuf.ByteString;
import emu.grasscutter.data.binout.AbilityModifier;
import emu.grasscutter.game.ability.Ability;
import emu.grasscutter.game.entity.EntityAvatar;
import emu.grasscutter.game.entity.EntityWeapon;
import emu.grasscutter.game.entity.GameEntity;
import emu.grasscutter.game.props.FightProperty;
import emu.grasscutter.net.proto.ChangeHpDebtsReasonOuterClass;
import emu.grasscutter.net.proto.PropChangeReasonOuterClass;
import emu.grasscutter.server.packet.send.PacketEntityFightPropChangeReasonNotify;
import emu.grasscutter.Grasscutter;
import it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap;

@AbilityAction(value = AbilityModifier.AbilityModifierAction.Type.ReduceHPDebts)
public final class ActionReduceHPDebts extends AbilityActionHandler {
    @Override
    public boolean execute(Ability ability, AbilityModifier.AbilityModifierAction action, ByteString abilityData, GameEntity target) {
        var owner = ability.getOwner();
        if (owner instanceof EntityWeapon weapon) {
            owner = ability.getPlayerOwner().getTeamManager().getCurrentAvatarEntity();
        }

        // Resolve the ratio against owner fight properties and ability specials so config
        // expressions like "FIGHT_PROP_MAX_HP * 0.3" evaluate the same way as AddHPDebts.
        var properties = new Object2FloatOpenHashMap<String>();
        for (var property : FightProperty.values()) {
            properties.put(property.name(), owner.getFightProperty(property));
        }
        properties.putAll(ability.getAbilitySpecials());

        float debt = action.ratio.get(properties, 0f);

        if (target instanceof EntityAvatar) {
            float curDebt = target.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP_DEBTS);
            float newDebt = curDebt - debt;
            if (newDebt < 0) {
                newDebt = 0;
            } else if (newDebt > 2 * target.getFightProperty(FightProperty.FIGHT_PROP_MAX_HP)) {
                newDebt = 2 * target.getFightProperty(FightProperty.FIGHT_PROP_MAX_HP);
            }
            float changeDebt = newDebt - curDebt;
            target.setFightProperty(FightProperty.FIGHT_PROP_CUR_HP_DEBTS, newDebt);
            target.broadcastHpDebtPropUpdate();
            if (changeDebt != 0) {
                if (newDebt == 0) {
                    target.getWorld().broadcastPacket(new PacketEntityFightPropChangeReasonNotify(target, FightProperty.FIGHT_PROP_CUR_HP_DEBTS, changeDebt, PropChangeReasonOuterClass.PropChangeReason.PropChangeReason_PROP_CHANGE_ABILITY, ChangeHpDebtsReasonOuterClass.ChangeHpDebtsReason.CHANGE_HP_DEBTS_REASON_CHANGE_HP_DEBTS_PAY_FINISH));
                } else if (changeDebt < 0) {
                    target.getWorld().broadcastPacket(new PacketEntityFightPropChangeReasonNotify(target, FightProperty.FIGHT_PROP_CUR_HP_DEBTS, changeDebt, PropChangeReasonOuterClass.PropChangeReason.PropChangeReason_PROP_CHANGE_ABILITY, ChangeHpDebtsReasonOuterClass.ChangeHpDebtsReason.CHANGE_HP_DEBTS_REASON_CHANGE_HP_DEBTS_PAY));
                }
            }
        } else {
            Grasscutter.getLogger().warn("[ActionReduceHPDebts] CANNOT REDUCE HP DEBT TO NON AVATAR ENTITY");
            return false;
        }
        return true;
    }
}
