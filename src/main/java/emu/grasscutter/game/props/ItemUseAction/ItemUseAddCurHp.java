package emu.grasscutter.game.props.ItemUseAction;

import emu.grasscutter.game.props.ItemUseOp;

public class ItemUseAddCurHp extends ItemUseInt {
    private final String icon;

    public ItemUseAddCurHp(String[] useParam) {
        super(useParam);
        this.icon = useParam[1];
    }

    @Override
    public ItemUseOp getItemUseOp() {
        return ItemUseOp.ITEM_USE_ADD_CUR_HP;
    }

    @Override
    public boolean useItem(UseItemParams params) {
        var entity = params.targetAvatar.getAsEntity();
        if (entity == null) return false;
        // While heal-to-bond conversion is active (e.g. Arlecchino in combat, official
        // _ABILITY_Avatar_ForbidFoodHeal), food healing never restores HP: it converts into
        // bond at the conversion ratio (0 = fully negated).
        if (entity.isConvertToHpDebt()) {
            return entity.convertHealToHpDebt(params.count * this.i) > 0f;
        }
        return (entity.heal(params.count * this.i) > 0.01);
    }
}
