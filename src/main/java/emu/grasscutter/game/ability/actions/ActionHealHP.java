package emu.grasscutter.game.ability.actions;

import com.google.protobuf.ByteString;
import emu.grasscutter.data.binout.AbilityModifier.AbilityModifierAction;
import emu.grasscutter.game.ability.Ability;
import emu.grasscutter.game.entity.*;
import emu.grasscutter.*;
import emu.grasscutter.net.proto.ChangeHpDebtsReasonOuterClass;
import emu.grasscutter.net.proto.PropChangeReasonOuterClass;
import emu.grasscutter.server.packet.send.PacketEntityFightPropUpdateNotify;
import emu.grasscutter.server.packet.send.PacketEntityFightPropChangeReasonNotify;
import emu.grasscutter.server.packet.send.PacketEvtBeingHealedNotify;
import emu.grasscutter.server.packet.send.PacketServerGlobalValueChangeNotify;
import emu.grasscutter.game.props.FightProperty;
import it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap;

@AbilityAction(AbilityModifierAction.Type.HealHP)
public final class ActionHealHP extends AbilityActionHandler {
    @Override
    public boolean execute(
            Ability ability, AbilityModifierAction action, ByteString abilityData, GameEntity target) {
        var owner = ability.getOwner();

        if (owner instanceof EntityClientGadget ownerGadget) {
            owner =
                    ownerGadget
                            .getScene()
                            .getEntityById(ownerGadget.getOwnerEntityId());
            if (DebugConstants.LOG_ABILITIES) {
                Grasscutter.getLogger()
                        .debug(
                                "Owner {} has top owner {}: {}",
                                ability.getOwner(),
                                ownerGadget.getOwnerEntityId(),
                                owner);
            }
        }
        if (owner instanceof EntityClientGadget ownerGadget) {
                owner = ownerGadget.getScene().getEntityById(ownerGadget.getOwnerEntityId());

                if (ownerGadget.gadgetId == 41089013 || ownerGadget.gadgetId == 41089012 || ownerGadget.gadgetId == 41089011) {
                    if (owner == null) {
                        owner = ability.getPlayerOwner().getTeamManager().getCurrentAvatarEntity();
                    }
                }
            }

        if (owner == null) return false;

        var properties = new Object2FloatOpenHashMap<String>();

        for (var property : FightProperty.values()) {
            var name = property.name();
            var value = owner.getFightProperty(property);
            properties.put(name, value);
        }

        for (var e : ability.getAbilitySpecials().object2FloatEntrySet()) {
            properties.put(e.getKey(), e.getFloatValue());
        }

        var amountByCasterMaxHPRatio = action.amountByCasterMaxHPRatio.get(properties, 0);
        var amountByCasterAttackRatio = action.amountByCasterAttackRatio.get(properties, 0);
        var amountByCasterCurrentHPRatio = action.amountByCasterCurrentHPRatio.get(properties, 0);
        var amountByCasterDefRatio = action.amountByCasterDefRatio.get(properties, 0);
        var amountByTargetCurrentHPRatio = action.amountByTargetCurrentHPRatio.get(properties, 0);
        var amountByTargetMaxHPRatio = action.amountByTargetMaxHPRatio.get(properties, 0);
        var amountToRegenerate = action.amount.get(properties, 0);

        if (action.amount.get(ability) != 0 &&
            (amountByCasterMaxHPRatio != 0 ||
            amountByCasterAttackRatio != 0 ||
            amountByCasterCurrentHPRatio != 0 ||
            amountByCasterDefRatio != 0 ||
            amountByTargetCurrentHPRatio != 0 ||
            amountByTargetMaxHPRatio != 0)) {
            amountToRegenerate += action.amount.get(ability);
        }

        amountToRegenerate +=
                amountByCasterMaxHPRatio * owner.getFightProperty(FightProperty.FIGHT_PROP_MAX_HP);
        amountToRegenerate +=
                amountByCasterAttackRatio * owner.getFightProperty(FightProperty.FIGHT_PROP_CUR_ATTACK);
        amountToRegenerate +=
                amountByCasterCurrentHPRatio * owner.getFightProperty(FightProperty.FIGHT_PROP_CUR_HP);
        amountToRegenerate +=
                amountByCasterDefRatio * owner.getFightProperty(FightProperty.FIGHT_PROP_CUR_DEFENSE);

        var abilityRatio = 1.0f;
        if (!action.ignoreAbilityProperty)
            abilityRatio +=
                    target.getFightProperty(FightProperty.FIGHT_PROP_HEAL_ADD)
                            + target.getFightProperty(FightProperty.FIGHT_PROP_HEALED_ADD);

        amountToRegenerate +=
                amountByTargetCurrentHPRatio * target.getFightProperty(FightProperty.FIGHT_PROP_MAX_HP);
        amountToRegenerate +=
                amountByTargetMaxHPRatio * target.getFightProperty(FightProperty.FIGHT_PROP_MAX_HP);

        String healTag = action.healTag;
        float dodgeHealFlag = "Clorinde_ElementalArt_Heal".equals(healTag) ? 0f : 1f;
        target.getGlobalAbilityValues().put("_ABILITY_Clorinde_Dodge_HealFlag", dodgeHealFlag);
        target.getWorld().broadcastPacket(new PacketServerGlobalValueChangeNotify(target, "_ABILITY_Clorinde_Dodge_HealFlag", dodgeHealFlag));

        // Arlecchino burst: officially her bond is consumed when the burst damage lands, not at
        // cast. Hold the heal until a hit from this avatar arrives, or 0.5s timeout (HealDelay
        // duration) so it still fires when the burst hits nothing.
        if (ability.getData() != null
                && "Avatar_Arlecchino_ElementalBurst_Attack".equals(ability.getData().abilityName)) {
            var mgr = ability.getManager();
            if (mgr != null) {
                float queuedAmount = amountToRegenerate * abilityRatio * action.healRatio.get(ability, 1f);
                boolean mute = action.muteHealEffect;
                final GameEntity healOwner = owner;
                final GameEntity healTarget = target;
                mgr.queueArlecchinoBurstHeal(
                        healTarget.getId(),
                        () -> performHeal(ability, healOwner, healTarget, queuedAmount, mute));
                return true;
            }
        }

    // Heal-to-bond conversion (SwitchHealToHPDebtsMixin): active while the mixin's modifier is
    // attached; heals whose tag is excluded bypass it. Official semantics: the healing does not
    // restore HP; the bond instead grows by ratio * heal (ratio 0 = healing fully negated, e.g.
    // Arlecchino in combat can only be healed by her own burst).
    if (target instanceof EntityAvatar) {
        boolean excluded = healTag != null && target.getHpDebtConvertExcludedHealTags().contains(healTag);
        if (!excluded && target.isConvertToHpDebt()) {
            float healAmount = amountToRegenerate * abilityRatio * action.healRatio.get(ability, 1f);
            target.convertHealToHpDebt(healAmount);
            return true;
        }
    }

        if ("MizukiBurstSelf".equals(healTag)) {
            amountToRegenerate *= 2.0f;
            Grasscutter.getLogger().debug("Healing increased by 100% for target {}", target);
        }

        float finalAmount = amountToRegenerate * abilityRatio * action.healRatio.get(ability, 1f);

        performHeal(ability, owner, target, finalAmount, action.muteHealEffect);

        if (finalAmount > 0
                && "Avatar_Furina_Constellation_2".equals(ability.getData().abilityName)
                && target instanceof EntityAvatar furinaTarget
                && furinaTarget.getAvatar().getAvatarId() == 10000089) {
            var player = ability.getPlayerOwner();
            if (player != null) {
                float baseAmount = finalAmount / abilityRatio;
                var seen = new java.util.HashSet<Long>();
                seen.add(furinaTarget.getAvatar().getGuid());
                for (var member : player.getTeamManager().getActiveTeam()) {
                    if (!seen.add(member.getAvatar().getGuid())) continue;
                    if (member.isConvertToHpDebt()) continue;
                    float memberRatio = 1.0f;
                    if (!action.ignoreAbilityProperty)
                        memberRatio += member.getFightProperty(FightProperty.FIGHT_PROP_HEAL_ADD)
                            + member.getFightProperty(FightProperty.FIGHT_PROP_HEALED_ADD);
                    float spreadAmount = baseAmount * memberRatio;
                    float spreadReal = member.heal(spreadAmount, action.muteHealEffect);
                    if (spreadReal > 0 && !action.muteHealEffect) {
                        member.getWorld().broadcastPacket(new PacketEvtBeingHealedNotify(owner, member, spreadAmount, spreadReal));
                    }
                }
            }
        }

        return true;
    }

    /** Applies a heal to the target (bond payment first, then HP) and fires heal hooks. */
    private static void performHeal(
            Ability ability, GameEntity owner, GameEntity target, float finalAmount, boolean mute) {
        float realHeal = target.heal(finalAmount, mute);
        if (realHeal > 0 && !mute) {
            target.getWorld().broadcastPacket(new PacketEvtBeingHealedNotify(owner, target, finalAmount, realHeal));
        }

        if (finalAmount > 0) {
            var healOwner = owner;
            for (var mod : healOwner.getInstancedModifiers().values()) {
                var modData = mod.getModifierData();
                var modAbility = mod.getAbility();
                if (modData != null && modData.onHeal != null && modAbility != null) {
                    for (var healEvt : modData.onHeal) {
                        ability.getManager().executeAction(modAbility, healEvt, ByteString.EMPTY, healOwner);
                    }
                }
            }
        }
    }
}
