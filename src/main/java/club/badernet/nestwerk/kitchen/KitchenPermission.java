package club.badernet.nestwerk.kitchen;

import club.badernet.nestwerk.security.Permission;

public enum KitchenPermission implements Permission {
    KITCHEN_VIEW,
    KITCHEN_RECIPES_EDIT,
    KITCHEN_PLAN,
    KITCHEN_SHOPPING,
    KITCHEN_SETTINGS
}
