package club.badernet.nestwerk.school;

import club.badernet.nestwerk.security.Permission;

public enum SchoolPermission implements Permission {
    SCHOOL_VIEW,
    SCHOOL_EDIT,
    SCHOOL_HOMEWORK,
    SCHOOL_GRADES,
    SCHOOL_GRADES_OWN,
    SCHOOL_SETTINGS
}
