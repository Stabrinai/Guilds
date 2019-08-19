package me.glaremasters.guilds.database.cooldowns;

import me.glaremasters.guilds.cooldowns.Cooldown;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface CooldownProvider {

    /**
     * Creates the container that will hold cooldowns
     * @param tablePrefix the prefix, if any, to use
     * @throws IOException
     */
    void createContainer(@Nullable String tablePrefix) throws IOException;

    /**
     * Checks if a cooldown exists in the database
     * @oaram tablePrefix the prefix, if any, to use
     * @param cooldownType the cooldown type
     * @param cooldownOwner the owner UUID of the cooldown
     * @return true or false
     */
    boolean cooldownExists(@Nullable String tablePrefix, @NotNull String cooldownType, @NotNull String cooldownOwner) throws IOException;

    /**
     * Gets all cooldowns from the database
     * @param tablePrefix the prefix, if any, to use
     * @return a list of cooldowns
     */
    List<Cooldown> getAllCooldowns(@Nullable String tablePrefix) throws IOException;

    /**
     * Create a new cooldown in the database
     * @param tablePrefix the table prefix
     * @param cooldownType the cooldown type
     * @param cooldownOwner the owner UUID of the cooldown
     * @param cooldownExpiry when the cooldown expires in milliseconds
     * @throws IOException
     */
    void createCooldown(@Nullable String tablePrefix, @NotNull String cooldownType, @NotNull String cooldownOwner, @NotNull Long cooldownExpiry) throws IOException;

    /**
     * Delete a cooldown from the database
     * @param tablePrefix the table prefix
     * @param cooldownType the type of cooldown
     * @param cooldownOwner the owner UUID of the cooldown
     * @throws IOException
     */
    void deleteCooldown(@Nullable String tablePrefix, @NotNull String cooldownType, @NotNull String cooldownOwner) throws IOException;
}
