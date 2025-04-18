package com.assassin.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * Represents statistics for a player in the Assassin game.
 */
@Data
@Builder
// Lombok annotations might not be processed correctly in the build environment,
// so manual constructor/setters are added as a workaround.
// @NoArgsConstructor 
// @AllArgsConstructor 
public class PlayerStats {

    private String playerId;
    private int kills;
    private int deaths;
    private int points;
    // Add other relevant stats as needed, e.g., gamesPlayed, longestKillStreak

    // Manual NoArgsConstructor
    public PlayerStats() {}

    // Manual AllArgsConstructor
    public PlayerStats(String playerId, int kills, int deaths, int points) {
        this.playerId = playerId;
        this.kills = kills;
        this.deaths = deaths;
        this.points = points;
    }

    // Manual Setters (required because Lombok might not be working)
    public void setPlayerId(String playerId) {
        this.playerId = playerId;
    }

    public void setKills(int kills) {
        this.kills = kills;
    }

    public void setDeaths(int deaths) {
        this.deaths = deaths;
    }

    public void setPoints(int points) {
        this.points = points;
    }

    // Getters are likely generated by @Data, but adding manually if necessary
    // public String getPlayerId() { return playerId; }
    // public int getKills() { return kills; }
    // public int getDeaths() { return deaths; }
    // public int getPoints() { return points; }

    // Consider adding methods for incrementing/decrementing stats if needed
} 