package club.badernet.nestwerk.demo;

import club.badernet.nestwerk.member.FamilyMember;

import java.util.List;

/** The members of the demo family. */
record DemoFamily(FamilyMember parent, FamilyMember partner, FamilyMember daughter, FamilyMember son) {

    List<FamilyMember> all() {
        return List.of(parent, partner, daughter, son);
    }

    List<FamilyMember> children() {
        return List.of(daughter, son);
    }
}
