package org.chiterok.grandDuels.data;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** SQLite backend ({@code kit-ratings.db}), a database of its own next to {@code stats.db}. */
public final class SqliteKitRatingStorage implements KitRatingStorage {

    private final File dbFile;
    private Connection connection;

    public SqliteKitRatingStorage(File dataFolder) {
        this.dbFile = new File(dataFolder, "kit-ratings.db");
    }

    @Override
    public void init() throws SQLException {
        File parent = dbFile.getParentFile();
        if (parent != null) parent.mkdirs();
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS kit_ratings (uuid TEXT NOT NULL, kit TEXT NOT NULL, "
                    + "name TEXT NOT NULL, elo INTEGER NOT NULL, PRIMARY KEY (uuid, kit))");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_kit_ratings_kit_elo ON kit_ratings (kit, elo DESC)");
        }
    }

    @Override
    public Map<String, Integer> load(UUID uuid) throws SQLException {
        Map<String, Integer> ratings = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT kit, elo FROM kit_ratings WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ratings.put(rs.getString(1), rs.getInt(2));
            }
        }
        return ratings;
    }

    @Override
    public void save(UUID uuid, String name, String kitId, int elo) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO kit_ratings (uuid, kit, name, elo) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT(uuid, kit) DO UPDATE SET name = excluded.name, elo = excluded.elo")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, kitId);
            ps.setString(3, name);
            ps.setInt(4, elo);
            ps.executeUpdate();
        }
    }

    @Override
    public List<RatingEntry> top(String kitId, int limit) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT uuid, name, elo FROM kit_ratings WHERE kit = ? "
                        + "ORDER BY elo DESC, name COLLATE NOCASE ASC LIMIT ?")) {
            ps.setString(1, kitId);
            ps.setInt(2, Math.max(1, limit));
            return entries(ps);
        }
    }

    @Override
    public List<RatingEntry> topAverage(Collection<String> kitIds, int limit) throws SQLException {
        if (kitIds.isEmpty()) return List.of();
        int kits = kitIds.size();
        String marks = String.join(",", Collections.nCopies(kits, "?"));
        // unrated kits count with the default rating: (sum + missing * default) / number of kits
        String sql = "SELECT uuid, MAX(name), CAST(ROUND((SUM(elo) + (? - COUNT(*)) * ?) / ?) AS INTEGER) AS average "
                + "FROM kit_ratings WHERE kit IN (" + marks + ") "
                + "GROUP BY uuid ORDER BY average DESC, MAX(name) COLLATE NOCASE ASC LIMIT ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int index = 1;
            ps.setInt(index++, kits);
            ps.setDouble(index++, PlayerStats.DEFAULT_ELO);
            ps.setDouble(index++, kits);
            for (String kitId : kitIds) ps.setString(index++, kitId);
            ps.setInt(index, Math.max(1, limit));
            return entries(ps);
        }
    }

    private static List<RatingEntry> entries(PreparedStatement ps) throws SQLException {
        List<RatingEntry> list = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                UUID uuid;
                try {
                    uuid = UUID.fromString(rs.getString(1));
                } catch (IllegalArgumentException e) {
                    continue;
                }
                list.add(new RatingEntry(uuid, rs.getString(2), rs.getInt(3)));
            }
        }
        return list;
    }

    @Override
    public void close() {
        try {
            if (connection != null) connection.close();
        } catch (SQLException ignored) {
            // shutting down
        }
    }
}
