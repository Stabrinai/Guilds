/*
 * MIT License
 *
 * Copyright (c) 2023 Glare
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package me.glaremasters.guilds.guild;

import ch.jalu.configme.SettingsManager;
import co.aikar.commands.ACFBukkitUtil;
import co.aikar.commands.ACFUtil;
import co.aikar.commands.PaperCommandManager;
import me.glaremasters.guilds.Guilds;
import me.glaremasters.guilds.configuration.sections.GuildSettings;
import me.glaremasters.guilds.configuration.sections.GuildVaultSettings;
import me.glaremasters.guilds.configuration.sections.PluginSettings;
import me.glaremasters.guilds.configuration.sections.TicketSettings;
import me.glaremasters.guilds.exceptions.ExpectationNotMet;
import me.glaremasters.guilds.messages.Messages;
import me.glaremasters.guilds.utils.ClaimUtils;
import me.glaremasters.guilds.utils.ItemBuilder;
import me.glaremasters.guilds.utils.LoggingUtils;
import me.glaremasters.guilds.utils.Serialization;
import me.glaremasters.guilds.utils.StringUtils;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.codemc.worldguardwrapper.WorldGuardWrapper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class GuildHandler {

    private final Guilds guildsPlugin;
    private final SettingsManager settingsManager;
    private final List<Guild> guilds = new ArrayList<>();
    private final List<GuildRole> roles = new ArrayList<>();
    private final List<GuildTier> tiers = new ArrayList<>();
    private final List<Player> spies = new ArrayList<>();

    private final Map<Guild, List<Inventory>> vaults = new HashMap<>();
    private final List<Player> opened = new ArrayList<>();

    private final Map<UUID, String> lookupCache = new HashMap<>();

    private boolean migrating = false;
    public boolean papi = false;

    //as well as guild permissions from tiers using permission field and tiers list.

    public GuildHandler(Guilds guildsPlugin, SettingsManager settingsManager) {
        this.guildsPlugin = guildsPlugin;
        this.settingsManager = settingsManager;

        loadRoles();
        loadTiers();
        try {
            loadGuilds();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Loads all guilds from the database and sets the necessary data for each guild and member.
     *
     * @throws IOException if there is an error while loading guilds from the database.
     */
    private void loadGuilds() throws IOException {
        // Add to the list
        guilds.addAll(guildsPlugin.getDatabase().getGuildAdapter().getAllGuilds());
        // Loop through each guild and set the data needed
        for (Guild guild : guilds) {
            // Create the vault cache
            createVaultCache(guild);
            // Create a temp tier object for the guild
            GuildTier tier = getGuildTier(guild.getTier().getLevel());
            if (tier != null) {
                // Set the tier object
                guild.setTier(tier);
            } else {
                guild.setTier(getLowestGuildTier());
                LoggingUtils.severe("The guild (" + guild.getName() + ") had a tier level that doesn't exist on the server anymore. To prevent issues, they've been automatically set the the lowest tier level on the server.");
            }
            // Check creation date
            if (guild.getCreationDate() == 0) {
                guild.setCreationDate(System.currentTimeMillis());
            }
            // Loop through each member.
            for (GuildMember member : guild.getMembers()) {
                // Create a temp role
                GuildRole role = getGuildRole(member.getRole().getLevel());
                if (role != null) {
                    // Set each member to their role
                    member.setRole(role);
                } else {
                    member.setRole(getLowestGuildRole());
                    LoggingUtils.severe("The player (" + member.getName() + ") had a role level that doesn't exist on the server anymore. To prevent issues, they've been automatically set the the lowest role level on the server.");
                }
            }
        }
    }


    /**
     * Loads all the guild roles from the configuration file and adds them to the list of roles.
     *
     * The method uses a YAML configuration file located at 'roles.yml' within the plugin's data folder.
     *
     * Each role is defined within a configuration section named "roles". The method loops through each
     * role section and parses the role's name, permission node, and level. It then creates a new
     * {@link GuildRole} object and adds it to the list of roles.
     *
     * In addition, the method also sets the permissions for each role based on the values within the role's
     * permissions section. The permissions section should contain a boolean value for each of the
     * {@link GuildRolePerm} values.
     */
    private void loadRoles() {
        final YamlConfiguration conf = YamlConfiguration.loadConfiguration(new File(guildsPlugin.getDataFolder(), "roles.yml"));
        final ConfigurationSection roleSec = conf.getConfigurationSection("roles");

        for (String s : roleSec.getKeys(false)) {
            final String path = s + ".permissions.";
            final String name = roleSec.getString(s + ".name");
            final String perm = roleSec.getString(s + ".permission-node");
            final int level = Integer.parseInt(s);

            final GuildRole role = new GuildRole(name, perm, level);

            for (GuildRolePerm rolePerm : GuildRolePerm.values()) {
                final String valuePath = path + rolePerm.name().replace("_", "-").toLowerCase();
                if (roleSec.getBoolean(valuePath)) {
                    role.addPerm(rolePerm);
                }
            }
            this.roles.add(role);
        }
    }

    /**
     * Load all guild tiers from the tiers.yml file and populate the [tiers] list with the data.
     */
    private void loadTiers() {
        final YamlConfiguration conf = YamlConfiguration.loadConfiguration(new File(guildsPlugin.getDataFolder(), "tiers.yml"));
        final ConfigurationSection tierSec = conf.getConfigurationSection("tiers.list");

        for (String key : tierSec.getKeys(false)) {
            tiers.add(GuildTier.builder()
                    .level(tierSec.getInt(key + ".level"))
                    .name(tierSec.getString(key + ".name"))
                    .cost(tierSec.getDouble(key + ".cost", 1000))
                    .maxMembers(tierSec.getInt(key + ".max-members", 10))
                    .vaultAmount(tierSec.getInt(key + ".vault-amount", 1))
                    .mobXpMultiplier(tierSec.getDouble(key + ".mob-xp-multiplier", 1.0))
                    .damageMultiplier(tierSec.getDouble(key + ".damage-multiplier", 1.0))
                    .maxBankBalance(tierSec.getDouble(key + ".max-bank-balance", 10000))
                    .membersToRankup(tierSec.getInt(key + ".members-to-rankup", 5))
                    .maxAllies(tierSec.getInt(key + ".max-allies", 10))
                    .useBuffs(tierSec.getBoolean(key + ".use-buffs", true))
                    .permissions(tierSec.getStringList(key + ".permissions"))
                    .build());
        }
    }

    /**
     * Saves the data of all guilds to the database.
     *
     * This method saves the data of all guilds to the database, including their vault cache. The method iterates through
     * the `guilds` list and calls the `saveVaultCache` method for each guild to save their vault cache. After that, the
     * `saveGuilds` method of the database's `GuildAdapter` is called to save all guilds to the database.
     *
     * @throws IOException if an I/O error occurs during the save process.
     */
    public void saveData() throws IOException {
        guilds.forEach(this::saveVaultCache);
        guildsPlugin.getDatabase().getGuildAdapter().saveGuilds(guilds);
    }


    /**
     * Adds the specified [guild] to the list of guilds.
     *
     * @param guild the guild being added
     * @throws NullPointerException if the specified [guild] is null
     */
    public void addGuild(@NotNull Guild guild) {
        guilds.add(guild);
        createVaultCache(guild);
    }

    /**
     * Removes a [guild] from the list and deletes it from the database.
     *
     * @param guild the guild to remove
     */
    public void removeGuild(@NotNull Guild guild) {
        vaults.remove(guild);
        guilds.remove(guild);

        try {
            guildsPlugin.getDatabase().getGuildAdapter().deleteGuild(guild.getId().toString());
        } catch (IOException e) {
            LoggingUtils.warn("There was an error deleting a guild with the following uuid: " + guild.getId());
            e.printStackTrace();
        }
    }

    /**
     * Retrieves a [guild] by its name.
     *
     * @param name the name of the guild to retrieve
     * @return the guild with the given name, or {@code null} if no such guild exists
     */
    @Nullable
    public Guild getGuild(@NotNull String name) {
        return guilds.stream().filter(guild -> ACFBukkitUtil.removeColors(guild.getName()).equals(name)).findFirst().orElse(null);
    }

    /**
     * Retrieve the guild of an offline player.
     *
     * @param p the offline player whose guild is being retrieved.
     * @return the guild object of the player, or null if the player is not in a guild.
     */
    @Nullable
    public Guild getGuild(@NotNull OfflinePlayer p) {
        return getGuildByPlayerId(p.getUniqueId());
    }

    /**
     * Returns the guild object corresponding to the given UUID.
     *
     * @param uuid the UUID of the guild to retrieve
     * @return the guild object with the given UUID, or null if no guild with the given UUID is found
     */
    @Nullable
    public Guild getGuild(@NotNull UUID uuid) {
        return guilds.stream().filter(guild -> guild.getId().equals(uuid)).findFirst().orElse(null);
    }

    /**
     * Get the guild that a player belongs to by their UUID.
     *
     * @param uuid the UUID of the player
     * @return the guild the player is a member of, or null if the player is not in a guild
     */
    @Nullable
    public Guild getGuildByPlayerId(@NotNull final UUID uuid) {
        return guilds.stream().filter(guild -> guild.getMember(uuid) != null).findFirst().orElse(null);
    }

    /**
     * Retrieves the guild that has a matching invite code.
     *
     * @param code the invite code being checked
     * @return the guild that the code belongs to, or null if no such guild exists
     */
    @Nullable
    public Guild getGuildByCode(@NotNull String code) {
        return guilds.stream().filter(guild -> guild.hasInviteCode(code)).findFirst().orElse(null);
    }

    /**
     * Get an instance of the guild member object via their uuid
     *
     * @param uuid the uuid of the player
     * @return the guild member object of the player or null
     */
    @Nullable
    public GuildMember getGuildMember(@NotNull final UUID uuid) {
        final Guild guild = getGuildByPlayerId(uuid);
        return guild == null ? null : guild.getMember(uuid);
    }

    /**
     * Get a guild's name by it's IDd
     *
     * @param uuid the input ID
     * @return the output guild
     */
    public String getNameById(@NotNull UUID uuid) {
        return ACFBukkitUtil.removeColors(getGuild(uuid).getName());
    }

    /**
     * Retrieve a GuildTier object by level.
     *
     * @param level the level of the tier
     * @return the GuildTier object if found, or null if not found.
     */
    @Nullable
    public GuildTier getGuildTier(int level) {
        return tiers.stream().filter(tier -> tier.getLevel() == level).findFirst().orElse(null);
    }

    /**
     * Retrieve a guild role by level
     *
     * @param level the level of the role
     * @return the role object if found
     */
    @Nullable
    public GuildRole getGuildRole(int level) {
        return roles.stream().filter(guildRole -> guildRole.getLevel() == level).findFirst().orElse(null);
    }

    /**
     * Adds an ally to both guilds
     *
     * @param guild       the guild to ally
     * @param targetGuild the other guild to ally
     */
    public void addAlly(Guild guild, Guild targetGuild) {
        guild.addAlly(targetGuild);
        targetGuild.addAlly(guild);
        removePendingAlly(guild, targetGuild);
    }

    /**
     * Simple method to check if two guilds are allies
     *
     * @param guild  the first guild
     * @param target the second guild
     * @return allies or not
     */
    public boolean isAlly(Guild guild, Guild target) {
        return guild.getAllies().contains(target.getId());
    }

    /**
     * Check if two players are allies.
     *
     * @param player the first player
     * @param target the second player
     * @return true if the players are allies, false otherwise
     */
    public boolean isAlly(Player player, Player target) {
        Guild pGuild = getGuild(player);
        Guild tGuild = getGuild(target);
        if (pGuild == null || tGuild == null)
            return false;
        return pGuild.getAllies().contains(tGuild.getId());
    }

    /**
     * Check if two players are in the same guild or not.
     *
     * @param player the first player to check.
     * @param target the second player to check.
     * @return true if both players are in the same guild, false otherwise.
     */
    public boolean isSameGuild(@NotNull final Player player, @NotNull final Player target) {
        final Guild playerGuild = getGuild(player);
        final Guild targetGuild = getGuild(target);
        if (playerGuild == null || targetGuild == null) {
            return false;
        }
        return playerGuild.getId().equals(targetGuild.getId());
    }

    /**
     * Compare two guilds to see if they are the same.
     *
     * @param g1 the first guild to compare
     * @param g2 the second guild to compare
     * @return true if the two guilds have the same ID, false otherwise
     */
    public boolean isSameGuild(@NotNull final Guild g1, @NotNull final Guild g2) {
        return g1.getId().equals(g2.getId());
    }


    /**
     * Removes an ally of a guild.
     *
     * @param guild the guild whose ally is being removed
     * @param targetGuild the ally guild being removed
     */
    public void removeAlly(@NotNull final Guild guild, @NotNull final Guild targetGuild) {
        guild.removeAlly(targetGuild);
        targetGuild.removeAlly(guild);
    }

    /**
     * Adds a pending ally
     *
     * @param guild       the first pending guild
     * @param targetGuild the second pending guild
     */
    public void addPendingAlly(Guild guild, Guild targetGuild) {
        guild.addPendingAlly(targetGuild);
        targetGuild.addPendingAlly(guild);
    }

    /**
     * Removes a pending ally
     *
     * @param guild       the first pending guild
     * @param targetGuild the second pending guild
     */
    public void removePendingAlly(Guild guild, Guild targetGuild) {
        guild.removePendingAlly(targetGuild);
        targetGuild.removePendingAlly(guild);
    }

    /**
     * Returns the amount of guilds existing
     *
     * @return an integer of size.
     */
    public int getGuildsSize() {
        return guilds.size();
    }

    /**
     * Returns the max tier level
     *
     * @return the max tier level
     */
    public int getMaxTierLevel() {
        return tiers.size();
    }

    /**
     * Checks if a guild has reached the maximum tier level.
     *
     * @param guild the guild to check
     * @return true if the guild has reached the maximum tier level, false otherwise
     */
    public boolean isMaxTier(Guild guild) {
        return guild.getTier().getLevel() >= getMaxTierLevel();
    }

    /**
     * Retrieves the lowest role in the guild hierarchy.
     *
     * @return the lowest role in the hierarchy
     */
    public GuildRole getLowestGuildRole() {
        return roles.get(roles.size() - 1);
    }

    /**
     * Returns the lowest guild tier.
     *
     * @return The lowest guild tier object.
     */
    public GuildTier getLowestGuildTier() {
        return tiers.get(0);
    }

    /**
     * Upgrades the tier of a guild.
     *
     * @param guild the guild whose tier will be upgraded
     */
    public void upgradeTier(Guild guild) {
        guild.setTier(getGuildTier(guild.getTier().getLevel() + 1));
    }

    /**
     * Returns a list of the names of all the guilds that an offline player has been invited to.
     *
     * @param player the offline player
     * @return a list of the names of the invited guilds, or an empty list if the player has not been invited to any guilds
     */
    public List<String> getInvitedGuilds(OfflinePlayer player) {
        return guilds.stream().filter(guild -> guild.getInvitedMembers().contains(player.getUniqueId())).map(Guild::getName).collect(Collectors.toList());
    }

    /**
     * Returns a string list of the name of all guilds on the server
     *
     * @return a string list of guild names
     */
    public List<String> getGuildNames() {
        return guilds.stream().map(Guild::getName).map(ACFBukkitUtil::removeColors).collect(Collectors.toList());
    }

    /**
     * Creates a cache of the vaults of a guild
     *
     * @param guild the guild for which to create the cache
     */
    private void createVaultCache(@NotNull final Guild guild) {
        final List<Inventory> vaults = new ArrayList<>();
        if (guild.getVaults() == null) {
            return;
        }
        // Deserialize the vaults and add them to the list
        guild.getVaults().forEach(v -> {
            try {
                vaults.add(Serialization.deserializeInventory(v, settingsManager));
            } catch (InvalidConfigurationException e) {
                e.printStackTrace();
            }
        });
        // Add the guild's vaults to the cache
        this.vaults.put(guild, vaults);
    }

    /**
     * Save the cached inventories of the vaults belonging to a guild.
     *
     * @param guild The guild whose vaults are being saved.
     */
    private void saveVaultCache(@NotNull final Guild guild) {
        final List<String> vaults = new ArrayList<>();
        if (guild.getVaults() == null) return;
        // Serialize the inventory objects in the cache and add them to a list.
        this.vaults.get(guild).forEach(v -> vaults.add(Serialization.serializeInventory(v)));
        // Set the serialized inventory data to the guild's vaults list.
        guild.setVaults(vaults);
    }

    /**
     * Get the inventory of a specified guild vault.
     *
     * @param guild The guild which owns the vault.
     * @param vault The number of the vault to be opened.
     * @return The inventory of the specified guild vault.
     */
    public Inventory getGuildVault(Guild guild, int vault) {
        return vaults.get(guild).get(vault - 1);
    }

    /**
     * Checks if a player is a spy.
     *
     * @param player the player being checked
     * @return {@code true} if the player is a spy, {@code false} otherwise
     */
    private boolean isSpy(@NotNull final Player player) {
        return spies.contains(player);
    }

    /**
     * Add a player to the list of spies.
     * A spy is a player who is able to see messages and events from all guilds, regardless of their membership status.
     *
     * @param manager the PaperCommandManager instance to use for sending feedback to the player
     * @param player the player being added as a spy
     */
    private void addSpy(PaperCommandManager manager, Player player) {
        spies.add(player);
        manager.getCommandIssuer(player).sendInfo(Messages.ADMIN__SPY_ON);
    }

    /**
     * Removes a player from the list of spies.
     *
     * @param manager the PaperCommandManager instance
     * @param player the player being removed from the list of spies
     */
    public void removeSpy(PaperCommandManager manager, Player player) {
        spies.remove(player);
        manager.getCommandIssuer(player).sendInfo(Messages.ADMIN__SPY_OFF);
    }

    /**
     * Toggles the spy mode of a player, which allows them to spy on guild chats.
     *
     * @param manager the command manager for sending messages to the player
     * @param player the player whose spy mode is being toggled
     */
    public void toggleSpy(PaperCommandManager manager, Player player) {
        if (isSpy(player)) {
            removeSpy(manager, player);
        } else {
            addSpy(manager, player);
        }
    }

    /**
     * Removes a player from the list of spies and the chat map when they log out of the server.
     *
     * @param player the player being removed
     */
    public void chatLogout(Player player) {
        spies.remove(player);
        guildsPlugin.getChatListener().getPlayerChatMap().remove(player.getUniqueId());
    }

    /**
     * Clear both lists
     */
    public void chatLogout() {
        spies.clear();
        guildsPlugin.getChatListener().getPlayerChatMap().clear();
    }

    /**
     * Checks if a player has any pending guild invites and sends them a message with the list of guilds they have been invited to.
     *
     * @param manager the command manager
     * @param player  the player to check for invites
     */
    public void checkInvites(PaperCommandManager manager, Player player) {
        List<String> list = getInvitedGuilds(player);

        if (list.isEmpty()) {
            manager.getCommandIssuer(player).sendInfo(Messages.ERROR__NO_PENDING_INVITES);
            return;
        }

        manager.getCommandIssuer(player).sendInfo(Messages.PENDING__INVITES, "{number}", String.valueOf(list.size()), "{guilds}", String.join(",", list));
    }

    /**
     * Checks whether a guild satisfies the member requirement to upgrade to the next tier.
     *
     * @param guild the guild to be checked
     * @return true if the guild's member count is less than the required amount for the current tier, false otherwise
     */
    public boolean memberCheck(Guild guild) {
        GuildTier tier = guild.getTier();
        return tier.getMembersToRankup() != 0 && guild.getMembers().size() < tier.getMembersToRankup();
    }

    /**
     * Check if the input name for a guild is valid.
     *
     * @param name            the name input to be checked
     * @param settingsManager the settings manager to retrieve name requirements from
     * @return true if the name is valid, false otherwise
     */
    public boolean nameCheck(String name, SettingsManager settingsManager) {
        String regex = settingsManager.getProperty(GuildSettings.NAME_REQUIREMENTS);
        if (!settingsManager.getProperty(GuildSettings.INCLUDE_COLOR_CODES)) {
            // Remove color codes if they are not allowed
            String tmp = StringUtils.color(name);
            return ChatColor.stripColor(tmp).matches(regex);
        }
        return name.matches(regex);
    }

    /**
     * Checks if a prefix is valid or not based on guild settings.
     *
     * @param name the prefix to be checked
     * @param settingsManager the guild settings manager
     * @return true if the prefix is valid, false otherwise
     */
    public boolean prefixCheck(String name, SettingsManager settingsManager) {
        String regex = settingsManager.getProperty(GuildSettings.PREFIX_REQUIREMENTS);
        if (!settingsManager.getProperty(GuildSettings.INCLUDE_COLOR_CODES)) {
            String tmp = StringUtils.color(name);
            return ChatColor.stripColor(tmp).matches(regex);
        }
        return name.matches(regex);
    }

    /**
     * Check if a word is in the blacklist or not
     *
     * @param name            name to check
     * @param settingsManager settings manager
     * @return blacklisted or not
     */
    public boolean blacklistCheck(String name, SettingsManager settingsManager) {
        if (settingsManager.getProperty(GuildSettings.BLACKLIST_SENSITIVE))
            return settingsManager.getProperty(GuildSettings.BLACKLIST_WORDS).stream().anyMatch(s -> s.toLowerCase().contains(name));
        else
            return settingsManager.getProperty(GuildSettings.BLACKLIST_WORDS).stream().anyMatch(s -> s.equalsIgnoreCase(name));
    }

    /**
     * Check if a guild has a specific vault unlocked
     *
     * @param vault the vault being opened
     * @param guild the guild opening the vault
     * @return if they can open it or not
     */
    public boolean hasVaultUnlocked(int vault, Guild guild) {
        return vault <= getGuildTier(guild.getTier().getLevel()).getVaultAmount();
    }

    /**
     * Method to create new vault
     *
     * @param settingsManager settings manager
     * @return new vault
     */
    public Inventory createNewVault(SettingsManager settingsManager) {
        return Bukkit.createInventory(null, 54, StringUtils.color(settingsManager.getProperty(GuildVaultSettings.VAULT_NAME)));
    }

    /**
     * Get a list of the online members that can invite people
     *
     * @param guild the guild to check
     * @return list of online members
     */
    public List<Player> getOnlineInviters(Guild guild) {
        List<GuildMember> members = guild.getOnlineMembers().stream().filter(m -> m.getRole().hasPerm(GuildRolePerm.INVITE)).collect(Collectors.toList());
        return members.stream().map(GuildMember::getAsPlayer).collect(Collectors.toList());
    }

    /**
     * Simple method to inform all online inviters that someone wants to join
     *
     * @param guild          guild to be requested
     * @param commandManager command manager
     * @param player         player requesting
     */
    public void pingOnlineInviters(Guild guild, PaperCommandManager commandManager, Player player) {
        getOnlineInviters(guild).forEach(m -> commandManager.getCommandIssuer(m).sendInfo(Messages.REQUEST__INCOMING_REQUEST, "{player}", player.getName()));
    }

    /**
     * Create a guild upgrade ticket
     *
     * @param settingsManager the settings manager
     * @param amount          the amount of tickets to give
     * @return the guild upgrade ticket
     */
    public ItemStack getUpgradeTicket(SettingsManager settingsManager, int amount) {
        ItemBuilder builder = new ItemBuilder(Material.valueOf(settingsManager.getProperty(TicketSettings.TICKET_MATERIAL)));
        builder.setAmount(amount);
        builder.setName(StringUtils.color(settingsManager.getProperty(TicketSettings.TICKET_NAME)));
        builder.setLore(settingsManager.getProperty(TicketSettings.TICKET_LORE).stream().map(StringUtils::color).collect(Collectors.toList()));
        return builder.build();
    }

    /**
     * Check the guild ticket itemstack
     *
     * @param settingsManager settings manager
     * @return the itemstack
     */
    public ItemStack matchTicket(SettingsManager settingsManager) {
        ItemBuilder builder = new ItemBuilder(Material.valueOf(settingsManager.getProperty(TicketSettings.TICKET_MATERIAL)));
        builder.setAmount(1);
        builder.setName(StringUtils.color(settingsManager.getProperty(TicketSettings.TICKET_NAME)));
        builder.setLore(settingsManager.getProperty(TicketSettings.TICKET_LORE).stream().map(StringUtils::color).collect(Collectors.toList()));
        return builder.build();
    }


    /**
     * Simple method to check if a guild is full or not
     *
     * @return full or not
     */
    public boolean checkIfFull(Guild guild) {
        return guild.getSize() >= getGuildTier(guild.getTier().getLevel()).getMaxMembers();
    }

    /**
     * Removes a set of permissions from a player
     * @param permission vault permissions
     * @param offlinePlayer the player to modify
     * @param nodes the permission nodes to remove
     */
    public void removePerms(final Permission permission, final OfflinePlayer offlinePlayer, final List<String> nodes) {
        if (settingsManager.getProperty(PluginSettings.RUN_VAULT_ASYNC)) {
            Guilds.newChain().async(() -> {
                for (final String node : nodes) {
                    if (!node.equals("")) {
                        permission.playerRemove(null, offlinePlayer, node);
                    }
                }
            }).execute();
        } else {
            for (final String node : nodes) {
                if (!node.equals("")) {
                    permission.playerRemove(null, offlinePlayer, node);
                }
            }
        }
    }

    /**
     * Adds a set of permissions to a player
     * @param permission vault permissions
     * @param offlinePlayer the player to modify
     * @param nodes the permission nodes to add
     */
    public void addPerms(final Permission permission, final OfflinePlayer offlinePlayer, final List<String> nodes) {
        if (settingsManager.getProperty(PluginSettings.RUN_VAULT_ASYNC)) {
            Guilds.newChain().async(() -> {
                for (final String node : nodes) {
                    if (!node.equals("")) {
                        permission.playerAdd(null, offlinePlayer, node);
                    }
                }
            }).execute();
        } else {
            for (final String node : nodes) {
                if (!node.equals("")) {
                    permission.playerAdd(null, offlinePlayer, node);
                }
            }
        }
    }

    /**
     * Add guild perms to a specific player
     * @param permission vault permissions
     * @param player the player to modify
     */
    public void addGuildPerms(final Permission permission, final OfflinePlayer player) {
        final Guild guild = getGuild(player);
        if (guild == null) {
            return;
        }
        final GuildTier tier = getGuildTier(guild.getTier().getLevel());
        if (tier.getPermissions().isEmpty()) {
            return;
        }
        addPerms(permission, player, tier.getPermissions());
    }

    /**
     * Remove guild perms from a specific player
     * @param permission vault permissions
     * @param player the player to modify
     */
    public void removeGuildPerms(final Permission permission, final OfflinePlayer player) {
        final Guild guild = getGuild(player);
        if (guild == null) {
            return;
        }
        final GuildTier tier = getGuildTier(guild.getTier().getLevel());
        if (tier.getPermissions().isEmpty()) {
            return;
        }
        removePerms(permission, player, tier.getPermissions());
    }

    /**
     * Adds a role perm to a player
     * @param permission vault permissions
     * @param player the player to modify
     */
    public void addRolePerm(final Permission permission, final OfflinePlayer player) {
        final Guild guild = getGuild(player);
        if (guild == null) {
            return;
        }
        final GuildMember member = guild.getMember(player.getUniqueId());
        final GuildRole role = member.getRole();
        addPerms(permission, player, Collections.singletonList(role.getNode()));
    }

    /**
     * Removes a role perm from a player
     * @param permission vault permissions
     * @param player the player to modify
     */
    public void removeRolePerm(final Permission permission, final OfflinePlayer player) {
        final Guild guild = getGuild(player);
        if (guild == null) {
            return;
        }
        final GuildMember member = guild.getMember(player.getUniqueId());
        final GuildRole role = member.getRole();
        removePerms(permission, player, Collections.singletonList(role.getNode()));
    }

    /**
     * Add all guild permissions to all players in the guild
     * @param permission vault permissions
     * @param guild the guild to modify
     */
    public void addGuildPermsToAll(final Permission permission, final Guild guild) {
        final GuildTier tier = getGuildTier(guild.getTier().getLevel());
        if (tier.getPermissions().isEmpty()) {
            return;
        }
        for (final OfflinePlayer player : guild.getAllAsPlayers()) {
            addPerms(permission, player, tier.getPermissions());
        }
    }

    /**
     * Remove all guild permissions from all players in the guild
     * @param permission vault permissions
     * @param guild the guild to modify
     */
    public void removeGuildPermsFromAll(final Permission permission, final Guild guild) {
        final GuildTier tier = getGuildTier(guild.getTier().getLevel());
        if (tier.getPermissions().isEmpty()) {
            return;
        }
        for (final OfflinePlayer player : guild.getAllAsPlayers()) {
            removePerms(permission, player, tier.getPermissions());
        }
    }

    /**
     * Remove the role permission from all members when the guild is disbanded
     * @param permission the permission to remove
     * @param guild the guild to remove the perms from
     */
    public void removeRolePermsFromAll(final Permission permission, final Guild guild) {
        for (final OfflinePlayer player : guild.getAllAsPlayers()) {
            final GuildRole role = guild.getMember(player.getUniqueId()).getRole();
            removePerms(permission, player, Collections.singletonList(role.getNode()));
        }
    }

    /**
     * Handle inviting player when redeeming a code
     *
     * @param manager command manager
     * @param player  the player redeeming the code
     * @param guild   the guild they are trying to join
     * @param code    the code being redeemed
     */
    public void handleInvite(PaperCommandManager manager, Player player, Guild guild, GuildCode code) {
        if (code.getUses() <= 0)
            ACFUtil.sneaky(new ExpectationNotMet(Messages.CODES__OUT));

        code.addRedeemer(player);

        guild.addMemberByCode(new GuildMember(player.getUniqueId(), getLowestGuildRole()));

        if (ClaimUtils.isEnabled(settingsManager)) {
            WorldGuardWrapper wrapper = WorldGuardWrapper.getInstance();
            ClaimUtils.getGuildClaim(wrapper, player, guild).ifPresent(region -> ClaimUtils.addMember(region, player));
        }

        manager.getCommandIssuer(player).sendInfo(Messages.CODES__JOINED, "{guild}", guild.getName());
        guild.sendMessage(manager, Messages.CODES__GUILD_MESSAGE, "{player}", player.getName(), "{creator}", Bukkit.getOfflinePlayer(code.getCreator()).getName());
    }

    /**
     * Handle sending code list message to prevent DRY
     *
     * @param commandManager command manager
     * @param player         player to send list to
     * @param codes          list of codes
     */
    public void handleCodeList(PaperCommandManager commandManager, Player player, List<GuildCode> codes) {
        codes.forEach(c -> commandManager.getCommandIssuer(player).sendInfo(Messages.CODES__LIST_ITEM,
                "{code}", c.getId(),
                "{amount}", String.valueOf(c.getUses()),
                "{creator}", Bukkit.getOfflinePlayer(c.getCreator()).getName()));
    }

    /**
     * Remove a guild from all other guilds allies or pending allies when deleted
     *
     * @param guild the guild to check
     */
    public void removeAlliesOnDelete(Guild guild) {
        getGuilds().forEach(g -> g.getPendingAllies().removeIf(x -> x.equals(guild.getId())));
        getGuilds().forEach(g -> g.getAllies().removeIf(x -> x.equals(guild.getId())));
    }

    /**
     * Notify all allies of a guild that's being deleted.
     *
     * @param guild          the guild being deleted
     * @param commandManager the command manager
     */
    public void notifyAllies(Guild guild, PaperCommandManager commandManager) {
        guild.getAllies().forEach(g -> getGuild(g).sendMessage(commandManager, Messages.DELETE__NOTIFY_ALLIES, "{guild}", guild.getName()));
    }

    /**
     * Get a list of all public guilds on the server
     *
     * @return list of public guilds
     */
    public List<String> getPublicGuilds() {
        return guilds.stream().filter(g -> !g.isPrivate()).map(Guild::getName).collect(Collectors.toList());
    }

    /**
     * Get a total list of all joinable guilds to a player
     *
     * @param player the player to check
     * @return list of all guilds
     */
    public List<String> getJoinableGuild(Player player) {
        return Stream.concat(getInvitedGuilds(player).stream(),
                getPublicGuilds().stream())
                .map(ACFBukkitUtil::removeColors)
                .collect(Collectors.toList());
    }

    /**
     * Check if a guild name already exists
     *
     * @param name name to check
     * @return exists or not
     */
    public boolean checkGuildNames(String name) {
        return guilds.stream().anyMatch(g -> g.getName().equalsIgnoreCase(name));
    }

    /**
     * Handles sending guild chat messages to the proper locations
     *
     * @param guild   the guild of the player
     * @param player  the player sending the message
     * @param message the message the player is sending
     */
    public void handleGuildChat(final Guild guild, final Player player, final String message) {
        final String chatFormat = settingsManager.getProperty(GuildSettings.GUILD_CHAT_FORMAT);
        final String spyFormat = settingsManager.getProperty(GuildSettings.SPY_CHAT_FORMAT);
        final boolean logChat = settingsManager.getProperty(GuildSettings.LOG_GUILD_CHAT);

        guild.sendMessage(chatGenerator(guild, player, chatFormat, message));

        for (final Player spy : spies) {
            spy.sendMessage(chatGenerator(guild, player, spyFormat, message));
        }

        if (logChat) {
            LoggingUtils.info(chatGenerator(guild, player, spyFormat, message));
        }
    }

    /**
     * Handles sending a chat message to all allies of a guild
     *
     * @param guild   the guild sending the message
     * @param player  the player sending the message
     * @param message the message the player is sending
     */
    public void handleAllyChat(final Guild guild, final Player player, final String message) {
        final String chatFormat = settingsManager.getProperty(GuildSettings.ALLY_CHAT_FORMAT);
        final String spyFormat = settingsManager.getProperty(GuildSettings.SPY_CHAT_FORMAT);
        final boolean logChat = settingsManager.getProperty(GuildSettings.LOG_ALLY_CHAT);

        guild.sendMessage(chatGenerator(guild, player, chatFormat, message));

        for (final UUID ally : guild.getAllies()) {
            final Guild alliedGuild = getGuild(ally);
            alliedGuild.sendMessage(chatGenerator(guild, player, chatFormat, message));
        }

        for (final Player spy : spies) {
            spy.sendMessage(chatGenerator(guild, player, spyFormat, message));
        }

        if (logChat) {
            LoggingUtils.info(chatGenerator(guild, player, spyFormat, message));
        }
    }

    /**
     * Helper method to process guild chat input and apply replacements for output
     *
     * @param guild   the guild of the player
     * @param player  the player sending the message
     * @param format  the format of the chat
     * @param content the content of the chat
     * @return processes  chat message
     */
    private String chatGenerator(final Guild guild, final Player player, final String format, final String content) {
        final GuildRole playerRole = getGuildRole(guild.getMember(player.getUniqueId()).getRole().getLevel());
        String original = StringUtils.color(format
                .replace("{role}", playerRole.getName())
                .replace("{player}", player.getName())
                .replace("{display-name}", player.getDisplayName())
                .replace("{message}", content)
                .replace("{guild}", guild.getName()));

        if (hasPapi()) {
            original = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, original);
        }
        return original;
    }

    /**
     * Get the formatted placeholder that uses brackets
     *
     * @param player the player to check
     * @return formatted placeholder
     */
    public String getFormattedPlaceholder(Player player) {
        String leftBracket = settingsManager.getProperty(GuildSettings.FORMAT_BRACKET_LEFT);
        String content = settingsManager.getProperty(GuildSettings.FORMAT_CONTENT);
        String noGuild = settingsManager.getProperty(GuildSettings.FORMAT_NO_GUILD);
        String rightBracket = settingsManager.getProperty(GuildSettings.FORMAT_BRACKET_RIGHT);

        StringBuilder sb = new StringBuilder();
        sb.append(leftBracket).append(content).append(rightBracket);

        String combined = sb.toString();

        Guild guild = getGuild(player);
        if (guild == null) {
            return noGuild;
        }
        return StringUtils.color(combined.replace("{name}", guild.getName()).replace("{prefix}", guild.getPrefix()));
    }

    /**
     * Remove a player from the chat list
     *
     * @param uuid the uuid of the player
     */
    public void removeFromChat(final UUID uuid) {
        guildsPlugin.getChatListener().getPlayerChatMap().remove(uuid);
    }

    public Guilds getGuildsPlugin() {
        return this.guildsPlugin;
    }

    public List<Guild> getGuilds() {
        return this.guilds;
    }

    public List<Player> getSpies() {
        return this.spies;
    }

    public List<GuildTier> getTiers() {
        return this.tiers;
    }

    public List<GuildRole> getRoles() {
        return this.roles;
    }

    public Map<Guild, List<Inventory>> getVaults() {
        return this.vaults;
    }

    public List<Player> getOpened() {
        return this.opened;
    }

    public boolean isMigrating() {
        return migrating;
    }

    public void setMigrating(boolean migrating) {
        this.migrating = migrating;
    }

    public Map<UUID, String> getLookupCache() {
        return lookupCache;
    }

    public boolean hasPapi() {
        return papi;
    }

    public void setPapi(boolean papi) {
        this.papi = papi;
    }
}
