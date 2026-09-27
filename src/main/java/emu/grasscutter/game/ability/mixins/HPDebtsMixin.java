package emu.grasscutter.game.ability.mixins;

import com.google.protobuf.ByteString;
import emu.grasscutter.data.binout.AbilityMixinData;
import emu.grasscutter.game.ability.Ability;
import emu.grasscutter.game.entity.GameEntity;

/**
 * HPDebtsMixin: carries the actions reacting to bond-of-life state (e.g. Arlecchino's
 * critical-point buff, Clorinde's debt tracking). The client invokes this mixin when the
 * state changes, so run its action list server-side.
 */
@AbilityMixin(value = AbilityMixinData.Type.HPDebtsMixin)
public class HPDebtsMixin extends AbilityMixinHandler {

    @Override
    public boolean execute(
            Ability ability, AbilityMixinData mixinData, ByteString abilityData, GameEntity target) {
        if (ability == null || mixinData.IOKPLLOKGGJ == null) return false;

        boolean executed = false;
        for (var action : mixinData.IOKPLLOKGGJ) {
            if (action == null) continue;
            executed = true;
            ability.getManager().executeAction(ability, action, abilityData, target);
        }
        return executed;
    }
}
