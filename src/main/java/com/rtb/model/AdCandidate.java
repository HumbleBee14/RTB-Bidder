package com.rtb.model;

/** A campaign that matched targeting, with a computed score for ranking. */
public final class AdCandidate {

    private final Campaign campaign;
    private double score;

    public AdCandidate(Campaign campaign) {
        this.campaign = campaign;
    }

    public Campaign getCampaign() {
        return campaign;
    }

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
    }
}
