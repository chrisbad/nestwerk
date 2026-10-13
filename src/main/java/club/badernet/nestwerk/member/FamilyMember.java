package club.badernet.nestwerk.member;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "family_members")
public class FamilyMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 7)
    private String color;

    @Column(name = "user_id", unique = true)
    private Long userId;

    protected FamilyMember() {
    }

    public FamilyMember(String name, String color, Long userId) {
        this.name = name;
        this.color = color;
        this.userId = userId;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getColor() {
        return color;
    }

    public Long getUserId() {
        return userId;
    }

    public void change(String name, String color, Long userId) {
        this.name = name;
        this.color = color;
        this.userId = userId;
    }
}
