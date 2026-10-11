package org.chiterok.grandDuels.data;

import java.util.UUID;

/** One leaderboard row of the kit rating system. */
public record RatingEntry(UUID uuid, String name, int elo) {}
