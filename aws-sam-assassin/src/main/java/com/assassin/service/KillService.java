package com.assassin.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.assassin.dao.DynamoDbGameDao;
import com.assassin.dao.DynamoDbKillDao;
import com.assassin.dao.DynamoDbPlayerDao;
import com.assassin.dao.GameDao;
import com.assassin.dao.KillDao;
import com.assassin.dao.PlayerDao;
import com.assassin.exception.GameNotFoundException;
import com.assassin.exception.InvalidGameStateException;
import com.assassin.exception.KillNotFoundException;
import com.assassin.exception.PersistenceException;
import com.assassin.exception.PlayerActionNotAllowedException;
import com.assassin.exception.PlayerNotFoundException;
import com.assassin.exception.SafeZoneException;
import com.assassin.exception.ValidationException;
import com.assassin.model.Coordinate;
import com.assassin.model.Game;
import com.assassin.model.GameState;
import com.assassin.model.Kill;
import com.assassin.model.Notification;
import com.assassin.model.Player;
import com.assassin.model.PlayerStatus;
import com.assassin.service.verification.VerificationManager;
import com.assassin.service.verification.VerificationResult;
import com.assassin.util.GeoUtils;

import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;

/**
 * Service layer for handling kill reporting logic.
 */
public class KillService {

    private static final Logger logger = LoggerFactory.getLogger(KillService.class);
    private final KillDao killDao;
    private final PlayerDao playerDao; // Needed to validate players and update status/targets
    private final GameDao gameDao; // Added missing GameDao dependency
    private final NotificationService notificationService; // Added NotificationService
    private final VerificationManager verificationManager; // Add VerificationManager dependency
    private final SafeZoneService safeZoneService; // Add SafeZoneService

    // Default constructor for frameworks or testing if needed
    public KillService() {
        this(new DynamoDbKillDao(), new DynamoDbPlayerDao(), new DynamoDbGameDao(), new NotificationService(), 
             new VerificationManager(new DynamoDbPlayerDao(), new DynamoDbGameDao()), new SafeZoneService()); // Pass GameDao here too
    }

    // Constructor for dependency injection (testing)
    public KillService(KillDao killDao, PlayerDao playerDao, GameDao gameDao, NotificationService notificationService) {
        this(killDao, playerDao, gameDao, notificationService, 
             new VerificationManager(playerDao, gameDao), new SafeZoneService()); // Pass GameDao here too
    }

    // Constructor allowing explicit VerificationManager injection (for testing or different DI setups)
    public KillService(KillDao killDao, PlayerDao playerDao, GameDao gameDao, 
                       NotificationService notificationService, VerificationManager verificationManager, SafeZoneService safeZoneService) {
        this.killDao = killDao;
        this.playerDao = playerDao;
        this.gameDao = gameDao; 
        this.notificationService = notificationService;
        this.verificationManager = verificationManager; // Assign VerificationManager
        this.safeZoneService = safeZoneService; // Assign SafeZoneService
    }

    // Constructor for full dependency injection including the enhanced client for SafeZoneService
    public KillService(KillDao killDao, PlayerDao playerDao, GameDao gameDao, 
                       NotificationService notificationService, VerificationManager verificationManager, 
                       DynamoDbEnhancedClient enhancedClient) {
        this.killDao = killDao;
        this.playerDao = playerDao;
        this.gameDao = gameDao; 
        this.notificationService = notificationService;
        this.verificationManager = verificationManager;
        // Instantiate SafeZoneService with the provided client
        this.safeZoneService = new SafeZoneService(enhancedClient); 
    }

    /**
     * Reports a new kill.
     *
     * @param killerId ID of the player reporting the kill
     * @param victimId ID of the victim player
     * @param latitude Optional latitude of kill location
     * @param longitude Optional longitude of kill location
     * @param verificationMethod Method used for verification (e.g., "GPS", "NFC", "PHOTO")
     * @param verificationData Additional data for verification
     * @return The newly created Kill object
     * @throws ValidationException If validation fails
     * @throws PlayerNotFoundException If killer or victim not found
     * @throws PlayerActionNotAllowedException If player status prevents the action
     * @throws PersistenceException If database error occurs
     * @throws SafeZoneException If the kill is attempted within a safe zone
     */
    public Kill reportKill(String killerId, String victimId, Double latitude, Double longitude,
                           String verificationMethod, Map<String, String> verificationData)
            throws ValidationException, PlayerNotFoundException, PlayerActionNotAllowedException, PersistenceException, SafeZoneException {
        
        if (killerId == null || victimId == null || killerId.equals(victimId)) {
            throw new ValidationException("Invalid killer or victim ID.");
        }
        // Basic location validation (could be more sophisticated)
        if (latitude == null || longitude == null) {
            throw new ValidationException("Latitude and Longitude are required.");
        }
        if (verificationMethod == null || verificationMethod.trim().isEmpty()) {
            throw new ValidationException("Verification method cannot be empty.");
        }

        // Check if we should run in test mode (skip game rule validation)
        boolean isTestEnvironment = Boolean.parseBoolean(System.getProperty("ASSASSIN_TEST_MODE", "false"));
        
        if (!isTestEnvironment) {
            // Fetch Game first to potentially get rules (e.g., required verification method)
            // Assume Player holds gameId. This might need adjustment based on actual data model.
            Player killer = playerDao.getPlayerById(killerId)
                    .orElseThrow(() -> new ValidationException("Killer with ID " + killerId + " not found."));
            
            // Validate killer is alive
            if (!PlayerStatus.ACTIVE.name().equals(killer.getStatus())) { 
                 throw new ValidationException("Killer " + killerId + " is not active in the game.");
            }
    
            // Validate victim exists and is alive
             Player victim = playerDao.getPlayerById(victimId)
                .orElseThrow(() -> new ValidationException("Victim with ID " + victimId + " not found."));
             if (!PlayerStatus.ACTIVE.name().equals(victim.getStatus())) {
                 throw new ValidationException("Victim " + victimId + " is not active in the game.");
            }
            
            // Ensure players are in the same game
            String gameId = killer.getGameID();
            if (gameId == null || gameId.trim().isEmpty()) {
                throw new ValidationException("Killer " + killerId + " is not associated with a game.");
            }
            if (!gameId.equals(victim.getGameID())) {
                throw new ValidationException("Killer and victim are not in the same game.");
            }

            // Fetch the game to check boundaries and status
            Game game = gameDao.getGameById(gameId)
                .orElseThrow(() -> new GameNotFoundException("Game with ID " + gameId + " not found."));

            // Check if game is active
            if (!GameState.ACTIVE.name().equals(game.getStatus())) {
                throw new InvalidGameStateException("Game " + gameId + " is not active. Cannot report kill.");
            }

            // *** Boundary Check ***
            List<Coordinate> gameBoundary = game.getBoundary();
            if (gameBoundary != null && !gameBoundary.isEmpty()) {
                Coordinate killLocation = new Coordinate(latitude, longitude);
                if (!GeoUtils.isPointInBoundary(killLocation, gameBoundary)) {
                    logger.warn("Kill reported outside game boundary for game {}. Location: ({}, {}). Killer: {}. Victim: {}", 
                                gameId, latitude, longitude, killerId, victimId);
                    throw new ValidationException("Kill location is outside the defined game boundary.");
                }
                logger.debug("Kill location ({}, {}) is within game boundary for game {}", latitude, longitude, gameId);
            } else {
                logger.debug("No boundary defined for game {}, skipping boundary check.", gameId);
            }
            // *** End Boundary Check ***
    
            // Validate the victim is the killer's current target
            if (!victimId.equals(killer.getTargetID())) {
                throw new ValidationException("Reported victim " + victimId + " is not the killer's current target (" + killer.getTargetID() + ").");
            }
            
            // Check if the kill location is in a safe zone
            if (latitude != null && longitude != null) {
                Coordinate location = new Coordinate(latitude, longitude);
                if (safeZoneService.isLocationInSafeZone(gameId, location)) {
                    throw new SafeZoneException("Kill cannot be reported in a safe zone");
                }
            }
            
            // --- Kill is valid, proceed --- 
    
            // Create Kill record
            Kill kill = new Kill();
            kill.setKillerID(killerId);
            kill.setVictimID(victimId);
            kill.setTime(Instant.now().toString());
            kill.setLatitude(latitude);
            kill.setLongitude(longitude);
            // Set verification details
            kill.setVerificationMethod(verificationMethod.toUpperCase());
            kill.setVerificationStatus("PENDING"); // Default status
            kill.setVerificationData(verificationData); // Store any provided data
            
            // *** Set the KillStatusPartition based on initial status ***
            kill.setKillStatusPartition(kill.getVerificationStatus()); // Initial status is PENDING
            
            logger.info("Reporting valid kill: Killer={}, Victim={}, Time={}, Verification={}", 
                        killerId, victimId, kill.getTime(), kill.getVerificationMethod());
            killDao.saveKill(kill);
    
            // --- Update Player Statuses and Targets --- 
            // Victim is now dead
            victim.setStatus(PlayerStatus.DEAD.name());
            String victimsOldTarget = victim.getTargetID(); // Store before clearing
            victim.setTargetID(null); // Dead players have no target
            victim.setSecret(null);   // Clear secrets
            victim.setTargetSecret(null);
            playerDao.savePlayer(victim);
            logger.info("Updated victim {} status to DEAD", victimId);
    
            // Killer gets victim's old target as their new target
            killer.setTargetID(victimsOldTarget); 
            playerDao.savePlayer(killer);
            logger.info("Updated killer {} new target to {}", killerId, victimsOldTarget);
            
            // --- Increment Killer's Kill Count ---
            incrementKillerCount(killerId);
            
            return kill;
        } else {
            // Test mode - simplified validation
            logger.info("Test mode enabled, skipping game rule validation");
            
            // Create Kill record with minimal validation
            Kill kill = new Kill();
            kill.setKillerID(killerId);
            kill.setVictimID(victimId);
            kill.setTime(Instant.now().toString());
            kill.setLatitude(latitude);
            kill.setLongitude(longitude);
            kill.setVerificationMethod(verificationMethod != null ? verificationMethod.toUpperCase() : "TEST_MODE");
            kill.setVerificationStatus("PENDING");
            kill.setVerificationData(verificationData);
            
            logger.info("Reporting test kill: Killer={}, Victim={}, Time={}", killerId, victimId, kill.getTime());
            killDao.saveKill(kill);
            
            // Try to update players if they exist, but don't fail if they don't
            try {
                playerDao.getPlayerById(killerId).ifPresent(k -> {
                    // Update killer stats if needed for testing
                    playerDao.savePlayer(k);
                });
                
                playerDao.getPlayerById(victimId).ifPresent(v -> {
                    // Update victim stats if needed for testing
                    v.setStatus(PlayerStatus.DEAD.name());
                    v.setTargetID(null);
                    v.setSecret(null);
                    v.setTargetSecret(null);
                    playerDao.savePlayer(v);
                });
            } catch (Exception e) {
                logger.warn("Error updating player data in test mode (ignored): {}", e.getMessage());
            }
            
            return kill;
        }
    }

    /**
     * Verifies a pending kill based on the method and provided data.
     * Placeholder for different verification strategies.
     * 
     * @param killId The ID of the kill (or maybe killerId + time as composite key)
     * @param verifierId The ID of the player/moderator performing the verification
     * @param verificationInput Additional data required for verification (e.g., photo upload, NFC data)
     * @return The updated Kill object with status VERIFIED or REJECTED.
     * @throws KillNotFoundException
     * @throws ValidationException
     * @throws PlayerActionNotAllowedException
     */
    public Kill verifyKill(String killerId, String killTime, String verifierId, Map<String, String> verificationInput)
            throws KillNotFoundException, ValidationException, PlayerActionNotAllowedException {
                
        Kill kill = getKill(killerId, killTime); // Fetches the kill or throws KillNotFoundException

        // Allow verification only if PENDING or PENDING_REVIEW (for moderator action)
        if (!"PENDING".equals(kill.getVerificationStatus()) && !"PENDING_REVIEW".equals(kill.getVerificationStatus())) {
            throw new PlayerActionNotAllowedException("Kill is not pending verification. Current status: " + kill.getVerificationStatus());
        }
        
        // --- Delegate Verification to VerificationManager --- 
        logger.info("Delegating kill verification to VerificationManager: Killer={}, Time={}, Verifier={}, Method={}",
                    killerId, killTime, verifierId, kill.getVerificationMethod());
                    
        VerificationResult result = verificationManager.verifyKill(kill, verificationInput, verifierId);
        
        // Update Kill status and notes based on verification result
        kill.setVerificationStatus(result.toKillVerificationStatus());
        kill.setVerificationNotes(result.getNotes());
        
        // *** Update KillStatusPartition based on the final verification status ***
        kill.setKillStatusPartition(kill.getVerificationStatus());
        
        logger.info("Verification result from manager for kill (Killer: {}, Time: {}): Status={}, Notes={}", 
                    kill.getKillerID(), kill.getTime(), kill.getVerificationStatus(), kill.getVerificationNotes());
                    
        // Save the updated kill record
        killDao.saveKill(kill);
        
        // Send notification only on successful verification
        if (result.isVerified()) {
            sendKillVerifiedNotification(kill);
        }
        
        return kill;
    }
    
    // --- Placeholder Verification Methods --- 
    // These would contain the actual logic for each verification type
    /* Remove the following methods as they are now handled by IVerificationMethod implementations
    private boolean verifyGpsProximity(Kill kill, Map<String, String> verificationInput) {
        // ... implementation removed ...
    }
    
    private boolean verifyNfc(Kill kill, Map<String, String> verificationInput) {
        // ... implementation removed ...
    }
    
    private boolean verifyPhoto(Kill kill, Map<String, String> verificationInput) {
        // ... implementation removed ...
    }

    // Helper for GPS distance calculation
    private double calculateHaversineDistance(double lat1, double lon1, double lat2, double lon2) {
        // ... implementation removed ...
    }
    */

    /**
     * Gets all kills where the specified player was the killer.
     *
     * @param killerId The ID of the killer player.
     * @return A list of Kill objects.
     */
    public List<Kill> getKillsByKiller(String killerId) {
        logger.info("Getting kills by killer: {}", killerId);
        return killDao.findKillsByKiller(killerId);
    }

    /**
     * Gets all kills where the specified player was the victim.
     *
     * @param victimId The ID of the victim player.
     * @return A list of Kill objects.
     */
    public List<Kill> getKillsByVictim(String victimId) {
        logger.info("Getting kills by victim: {}", victimId);
        return killDao.findKillsByVictim(victimId);
    }

    /**
     * Gets a list of the most recent kills across all players.
     *
     * @param limit The maximum number of kills to return.
     * @return A list of Kill objects.
     */
    public List<Kill> findRecentKills(int limit) {
        logger.info("Getting recent kills with limit: {}", limit);
        return killDao.findRecentKills(limit);
    }

    /**
     * Gets all kills.
     * WARNING: This can be resource-intensive.
     *
     * @return A list of all Kill objects.
     */
    public List<Kill> getAllKills() {
        logger.info("Getting all kills");
        return killDao.getAllKills();
    }

    /**
     * Gets a specific kill by its composite key.
     *
     * @param killerId The ID of the killer.
     * @param time The timestamp of the kill.
     * @return The Kill object.
     * @throws KillNotFoundException if the kill is not found.
     */
    public Kill getKill(String killerId, String time) throws KillNotFoundException {
        logger.info("Getting kill by key: Killer={}, Time={}", killerId, time);
        return killDao.getKill(killerId, time)
                      .orElseThrow(() -> new KillNotFoundException("Kill not found with killer ID " + killerId + " and time " + time));
    }

    /**
     * Gets all kills for a specific game.
     *
     * @param gameId The ID of the game.
     * @return A list of Kill objects.
     * @throws GameNotFoundException if the game doesn't exist (optional check)
     */
    public List<Kill> getKillsByGameId(String gameId) throws GameNotFoundException {
        logger.info("Getting kills for gameID: {}", gameId);
        // Optional: Validate game exists first
        // gameDao.getGameById(gameId).orElseThrow(() -> new GameNotFoundException("Game not found: " + gameId));
        return killDao.findKillsByGameId(gameId);
    }

    // Potentially add methods like:
    // List<Kill> getKillsInGame(String gameId); // Would require gameId on Kill model + GSI

    // Added helper method to call the specific DAO implementation method
    private void incrementKillerCount(String killerId) {
         if (playerDao instanceof DynamoDbPlayerDao) {
            try {
                ((DynamoDbPlayerDao) playerDao).incrementPlayerKillCount(killerId);
            } catch (Exception e) {
                // Log error but don't fail the kill report operation
                logger.error("Failed to increment kill count for player {}: {}", killerId, e.getMessage(), e);
            }
        } else {
            logger.warn("Cannot increment kill count: PlayerDao is not an instance of DynamoDbPlayerDao.");
        }
    }

    /**
     * Confirms a player's death and sets their "last will" message
     *
     * @param gameId    the ID of the game
     * @param victimId  the ID of the player confirming their death
     * @param lastWill  the player's last will message
     * @return          the updated Kill object
     * @throws GameNotFoundException           if the game does not exist
     * @throws PlayerNotFoundException         if the player does not exist
     * @throws KillNotFoundException           if no kill record exists for this victim
     * @throws InvalidGameStateException       if the game is not in the ACTIVE state
     * @throws PlayerActionNotAllowedException if the player is not in a KILLED state
     */
    public Kill confirmDeath(String gameId, String victimId, String lastWill) 
            throws GameNotFoundException, PlayerNotFoundException, 
                   KillNotFoundException, InvalidGameStateException, 
                   PlayerActionNotAllowedException {
        
        // Get the game and validate it's active
        Optional<Game> gameOpt = gameDao.getGameById(gameId); // Corrected method name
        if (!gameOpt.isPresent()) {
            throw new GameNotFoundException("Game not found with ID: " + gameId);
        }
        
        Game game = gameOpt.get();
        // Corrected: Use getStatus() and compare with enum name
        if (!GameState.ACTIVE.name().equalsIgnoreCase(game.getStatus())) {
            throw new InvalidGameStateException("Cannot confirm death in a game that is not active");
        }
        
        // Get the player and validate it's the victim
        Optional<Player> playerOpt = playerDao.getPlayerById(victimId); // Corrected method name and removed gameId
        if (!playerOpt.isPresent()) {
            throw new PlayerNotFoundException("Player not found with ID: " + victimId);
        }
        
        Player player = playerOpt.get();
        if (!PlayerStatus.DEAD.name().equalsIgnoreCase(player.getStatus())) {
            logger.warn("Player {} attempted to confirm death in game {}, but is not DEAD (Status: {}).", victimId, gameId, player.getStatus());
            throw new PlayerActionNotAllowedException("Cannot confirm death, player status is not DEAD.");
        }
        
        // 4. Find the Kill record for this victim in this game
        // Use the renamed method returning Optional<Kill>
        Kill killRecord = killDao.findKillRecordByVictimAndGame(victimId, gameId)
                .orElseThrow(() -> {
                     logger.warn("Could not find kill record for victim {} in game {} for death confirmation.", victimId, gameId);
                     return new KillNotFoundException("No kill record found for victim " + victimId + " in game " + gameId);
                 });
        
        // Update the kill record with the last will and confirmation
        killRecord.setLastWill(lastWill);
        killRecord.setDeathConfirmed(true);
        
        // Save the updated kill record
        killDao.saveKill(killRecord); // Corrected: Use killDao
        
        return killRecord;
    }

    // --- Helper Methods ---

    /**
     * Sends a notification to the killer when their kill is verified.
     * @param kill The verified kill object.
     */
    private void sendKillVerifiedNotification(Kill kill) {
        try {
            // Fetch killer details to get name for the message (optional)
            Player killer = playerDao.getPlayerById(kill.getKillerID())
                                     .orElse(null); // Handle case where killer might not be found (unlikely)
            String killerName = (killer != null) ? killer.getPlayerName() : kill.getKillerID();

            // Fetch victim details for the message
            Player victim = playerDao.getPlayerById(kill.getVictimID())
                                    .orElse(null);
            String victimName = (victim != null) ? victim.getPlayerName() : kill.getVictimID();


            String message = String.format("Your kill of %s has been verified! Your new target is %s.",
                                           victimName,
                                           (killer != null && killer.getTargetID() != null) ? killer.getTargetID() : "being assigned"); // Include new target ID if available


            Map<String, String> data = Map.of(
                "killId", kill.getKillerID() + "_" + kill.getTime(), // Use composite key as identifier
                "killerId", kill.getKillerID(),
                "victimId", kill.getVictimID(),
                "verificationMethod", kill.getVerificationMethod(),
                "newTargetId", (killer != null && killer.getTargetID() != null) ? killer.getTargetID() : "" // Include new target ID
            );

            Notification notification = new Notification(
                kill.getKillerID(), // Recipient is the killer
                "KILL_VERIFIED",
                message,
                data
            );

            notificationService.sendNotification(notification);
            logger.info("Sent KILL_VERIFIED notification to killer: {}", kill.getKillerID());

        } catch (Exception e) {
            // Log error but don't fail the main operation
            logger.error("Failed to send KILL_VERIFIED notification for kill (Killer: {}, Time: {}): {}",
                         kill.getKillerID(), kill.getTime(), e.getMessage(), e);
        }
    }
} 