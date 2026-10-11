package org.chiterok.grandDuels.match;

/** How a duel came to be. Only RANKED duels change the ELO rating. */
public enum DuelMode {
    /** Direct /duel request between two players. */
    FRIENDLY,
    /** /unranked queue. */
    UNRANKED,
    /** /ranked queue. */
    RANKED
}
