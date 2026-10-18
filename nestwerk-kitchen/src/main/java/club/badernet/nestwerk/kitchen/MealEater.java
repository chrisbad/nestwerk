package club.badernet.nestwerk.kitchen;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** Wer mitisst und wie viele Portionen. */
@Embeddable
public class MealEater {

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(nullable = false)
    private double portions;

    protected MealEater() {
    }

    public MealEater(Long memberId, double portions) {
        this.memberId = memberId;
        this.portions = portions;
    }

    public Long getMemberId() {
        return memberId;
    }

    public double getPortions() {
        return portions;
    }
}
