// $Id$
/*
 * This file is a part of WorldGuard.
 * Copyright (c) sk89q <http://www.sk89q.com>
 * Copyright (c) the WorldGuard team and contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY), without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>.
*/

package com.sk89q.worldguard.bukkit.commands;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import com.sk89q.worldguard.util.StringUtil;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.sk89q.minecraft.util.commands.Command;
import com.sk89q.minecraft.util.commands.CommandContext;
import com.sk89q.minecraft.util.commands.CommandException;
import com.sk89q.minecraft.util.commands.CommandPermissionsException;
import com.sk89q.worldedit.BlockVector;
import com.sk89q.worldedit.Location;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.bukkit.BukkitUtil;
import com.sk89q.worldedit.bukkit.WorldEditPlugin;
import com.sk89q.worldedit.bukkit.selections.CuboidSelection;
import com.sk89q.worldedit.bukkit.selections.Polygonal2DSelection;
import com.sk89q.worldedit.bukkit.selections.Selection;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.bukkit.RegionPermissionModel;
import com.sk89q.worldguard.bukkit.WorldConfiguration;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.databases.ProtectionDatabaseException;
import com.sk89q.worldguard.protection.databases.RegionDBUtil;
import com.sk89q.worldguard.protection.databases.migrators.AbstractDatabaseMigrator;
import com.sk89q.worldguard.protection.databases.migrators.MigrationException;
import com.sk89q.worldguard.protection.databases.migrators.MigratorKey;
import com.sk89q.worldguard.protection.flags.DefaultFlag;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.InvalidFlagFormat;
import com.sk89q.worldguard.protection.flags.RegionGroup;
import com.sk89q.worldguard.protection.flags.RegionGroupFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.GlobalProtectedRegion;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedPolygonalRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion.CircularInheritanceException;

/**
 * Implements the /region commands for WorldGuard.
 */
public final class RegionCommands {

    private final WorldGuardPlugin plugin;

    private MigratorKey migrateDBRequest;
    private Date migrateDBRequestDate;

    public RegionCommands(WorldGuardPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Get the permission model to lookup permissions.
     *
     * @param sender the sender
     * @return the permission model
     */
    private static RegionPermissionModel getPermissionModel(CommandSender sender) {
        return new RegionPermissionModel(WorldGuardPlugin.inst(), sender);
    }

    /**
     * Gets the world from the given flag, or falling back to the the current player
     * if the sender is a player, otherwise reporting an error.
     *
     * @param args the arguments
     * @param sender the sender
     * @param flag the flag (such as 'w')
     * @return a world
     * @throws com.sk89q.minecraft.util.commands.CommandException on error
     */
    private static World getWorld(CommandContext args, CommandSender sender, char flag)
            throws CommandException {
        if (args.hasFlag(flag)) {
            return WorldGuardPlugin.inst().matchWorld(sender, args.getFlag(flag));
        } else {
            if (sender instanceof Player) {
                return WorldGuardPlugin.inst().checkPlayer(sender).getWorld();
            } else {
                throw new CommandException("Please specify " +
                        "the world with -" + flag + " world_name.");
            }
        }
    }

    /**
     * Validate a region ID.
     *
     * @param id the id
     * @param allowGlobal whether __global__ is allowed
     * @return the id given
     * @throws com.sk89q.minecraft.util.commands.CommandException thrown on an error
     */
    private static String validateRegionId(String id, boolean allowGlobal)
            throws CommandException {
        if (!ProtectedRegion.isValidId(id)) {
            throw new CommandException(
                    "Название '" + id + "' содержит запрещенные символы.");
        }

        if (!allowGlobal && id.equalsIgnoreCase("__global__")) { // Sorry, no global
            throw new CommandException(
                    "Извините, но Вы не можете использовать глобальный регион.");
        }

        return id;
    }

    /**
     * Get a protected region by a given name, otherwise throw a
     * {@link com.sk89q.minecraft.util.commands.CommandException}.
     *
     * <p>This also validates the region ID.</p>
     *
     * @param regionManager the region manager
     * @param id the name to search
     * @param allowGlobal true to allow selecting __global__
     * @throws com.sk89q.minecraft.util.commands.CommandException thrown if no region is found by the given name
     */
    private static ProtectedRegion findExistingRegion(
            RegionManager regionManager, String id, boolean allowGlobal)
            throws CommandException {
        // Validate the id
        validateRegionId(id, allowGlobal);

        ProtectedRegion region = regionManager.getRegionExact(id);

        // No region found!
        if (region == null) {
            // But we want a __global__, so let's create one
            if (id.equalsIgnoreCase("__global__")) {
                region = new GlobalProtectedRegion(id);
                regionManager.addRegion(region);
                return region;
            }

            throw new CommandException(
                    "Региона под именем '" + id + "' не существует.");
        }

        return region;
    }


    /**
     * Get the region at the player's location, if possible.
     *
     * <p>If the player is standing in several regions, an error will be raised
     * and a list of regions will be provided.</p>
     *
     * @param regionManager the region manager
     * @param player the player
     * @return a region
     * @throws com.sk89q.minecraft.util.commands.CommandException thrown if no region was found
     */
    private static ProtectedRegion findRegionStandingIn(
            RegionManager regionManager, Player player) throws CommandException {
        return findRegionStandingIn(regionManager, player, false);
    }

    /**
     * Get the region at the player's location, if possible.
     *
     * <p>If the player is standing in several regions, an error will be raised
     * and a list of regions will be provided.</p>
     *
     * <p>If the player is not standing in any regions, the global region will
     * returned if allowGlobal is true and it exists.</p>
     *
     * @param regionManager the region manager
     * @param player the player
     * @param allowGlobal whether to search for a global region if no others are found
     * @return a region
     * @throws com.sk89q.minecraft.util.commands.CommandException thrown if no region was found
     */
    private static ProtectedRegion findRegionStandingIn(
            RegionManager regionManager, Player player, boolean allowGlobal) throws CommandException {
        ApplicableRegionSet set = regionManager.getApplicableRegions(
                player.getLocation());

        if (set.size() == 0) {
            if (allowGlobal) {
                 ProtectedRegion global = findExistingRegion(regionManager, "__global__", true);
                 player.sendMessage(ChatColor.GRAY + "Вы не стоите ни в одном из регионов. " +
                         "Используем глобальный регион.");
                 return global;
            }
            throw new CommandException(
                "Вы не стоите ни в одном из регионов." +
                "Укажите ID региона если хотите указать какой-то конкретный регион.");
        } else if (set.size() > 1) {
            StringBuilder builder = new StringBuilder();
            boolean first = true;

            for (ProtectedRegion region : set) {
                if (!first) {
                    builder.append(", ");
                }
                first = false;
                builder.append(region.getId());
            }

            throw new CommandException(
                    "Вы стоите в нескольких регионах и " +
                    "WorldGuard не знает какую использовать.\nВы в: " +
                            builder.toString());
        }

        return set.iterator().next();
    }

    /**
     * Get a WorldEdit selection for a player, or emit an exception if there is none
     * available.
     *
     * @param player the player
     * @return the selection
     * @throws com.sk89q.minecraft.util.commands.CommandException thrown on an error
     */
    private static Selection getSelection(Player player) throws CommandException {
        WorldEditPlugin worldEdit = WorldGuardPlugin.inst().getWorldEdit();
        Selection selection = worldEdit.getSelection(player);

        if (selection == null) {
            throw new CommandException(
                    "Сначала выделите зону. " +
                    "Используйте WorldEdit для создания выделения! " +
                    "(wiki: http://wiki.sk89q.com/wiki/WorldEdit).");
        }

        return selection;
    }

    /**
     * Create a {@link com.sk89q.worldguard.protection.regions.ProtectedRegion} from the player's selection.
     *
     * @param player the player
     * @param id the ID of the new region
     * @return a new region
     * @throws com.sk89q.minecraft.util.commands.CommandException thrown on an error
     */
    private static ProtectedRegion createRegionFromSelection(Player player, String id)
            throws CommandException {

        Selection selection = getSelection(player);

        // Detect the type of region from WorldEdit
        if (selection instanceof Polygonal2DSelection) {
            Polygonal2DSelection polySel = (Polygonal2DSelection) selection;
            int minY = polySel.getNativeMinimumPoint().getBlockY();
            int maxY = polySel.getNativeMaximumPoint().getBlockY();
            return new ProtectedPolygonalRegion(id, polySel.getNativePoints(), minY, maxY);
        } else if (selection instanceof CuboidSelection) {
            BlockVector min = selection.getNativeMinimumPoint().toBlockVector();
            BlockVector max = selection.getNativeMaximumPoint().toBlockVector();
            return new ProtectedCuboidRegion(id, min, max);
        } else {
            throw new CommandException(
                    "Извените. только кубоиды и полигоны могут быть регионами в WorldGuard.");
        }
    }

    private static void commitChanges(CommandSender sender, RegionManager regionManager)
            throws CommandException {
        commitChanges(sender, regionManager, false);
    }

    private static void reloadChanges(CommandSender sender, RegionManager regionManager)
            throws CommandException {
        reloadChanges(sender, regionManager, false);
    }

    /**
     * Save the region database.
     *
     * @param sender the sender
     * @param regionManager the region manager
     * @param silent whether to suppress messages sent to the player
     * @throws CommandException throw on an error
     */
    private static void commitChanges(CommandSender sender, RegionManager regionManager, boolean silent)
            throws CommandException {
        try {
            if (!silent && regionManager.getRegions().size() >= 500) {
                sender.sendMessage(ChatColor.GRAY +
                        "Сохраняем список регионов на диск... (Слишком долго? Работаем над исправлением)");
            }
            regionManager.save();
        } catch (ProtectionDatabaseException e) {
            throw new CommandException("Упс, регионы не сохранены: " + e.getMessage());
        }
    }

    /**
     * Load the region database.
     *
     * @param sender the sender
     * @param regionManager the region manager
     * @param silent whether to suppress messages sent to the player
     * @throws CommandException throw on an error
     */
    private static void reloadChanges(CommandSender sender, RegionManager regionManager, boolean silent)
            throws CommandException {
        try {
            if (!silent && regionManager.getRegions().size() >= 500) {
                sender.sendMessage(ChatColor.GRAY +
                        "Загружаем список регионов с диска... (Слишком долго? Работаем над исправлением)");
            }
            regionManager.load();
        } catch (ProtectionDatabaseException e) {
            throw new CommandException("Упс, регионы не сохранены: " + e.getMessage());
        }
    }

    /**
     * Set a player's selection to a given region.
     *
     * @param player the player
     * @param region the region
     * @throws com.sk89q.minecraft.util.commands.CommandException thrown on a command error
     */
    private static void setPlayerSelection(Player player, ProtectedRegion region)
            throws CommandException {
        WorldEditPlugin worldEdit = WorldGuardPlugin.inst().getWorldEdit();

        World world = player.getWorld();

        // Set selection
        if (region instanceof ProtectedCuboidRegion) {
            ProtectedCuboidRegion cuboid = (ProtectedCuboidRegion) region;
            Vector pt1 = cuboid.getMinimumPoint();
            Vector pt2 = cuboid.getMaximumPoint();
            CuboidSelection selection = new CuboidSelection(world, pt1, pt2);
            worldEdit.setSelection(player, selection);
            player.sendMessage(ChatColor.YELLOW + "Регион выделен как кубоид.");

        } else if (region instanceof ProtectedPolygonalRegion) {
            ProtectedPolygonalRegion poly2d = (ProtectedPolygonalRegion) region;
            Polygonal2DSelection selection = new Polygonal2DSelection(
                    world, poly2d.getPoints(),
                    poly2d.getMinimumPoint().getBlockY(),
                    poly2d.getMaximumPoint().getBlockY() );
            worldEdit.setSelection(player, selection);
            player.sendMessage(ChatColor.YELLOW + "Регион выделен как полигон.");

        } else if (region instanceof GlobalProtectedRegion) {
            throw new CommandException(
                    "Не могу выледитть глобальный регион! " +
                    "Он распостраняется на весь мир.");

        } else {
            throw new CommandException("Неизвестный тип региона: " +
                    region.getClass().getCanonicalName());
        }
    }

    /**
     * Utility method to set a flag.
     *
     * @param region the region
     * @param flag the flag
     * @param sender the sender
     * @param value the value
     * @throws com.sk89q.worldguard.protection.flags.InvalidFlagFormat thrown if the value is invalid
     */
    private static <V> void setFlag(ProtectedRegion region,
            Flag<V> flag, CommandSender sender, String value)
                    throws InvalidFlagFormat {
        region.setFlag(flag, flag.parseInput(WorldGuardPlugin.inst(), sender, value));
    }

    /**
     * Defines a new region.
     *
     * @param args the arguments
     * @param sender the sender
     * @throws com.sk89q.minecraft.util.commands.CommandException any error
     */
    @Command(aliases = {"define", "def", "d", "create"},
             usage = "<id> [<владелец1> [<владелец2> [<владельцы...>]]]",
             desc = "Объявляет регион",
             min = 1)
    public void define(CommandContext args, CommandSender sender)
            throws CommandException {
        Player player = plugin.checkPlayer(sender);

        // Check permissions
        if (!getPermissionModel(sender).mayDefine()) {
            throw new CommandPermissionsException();
        }

        // Get and validate the region ID
        String id = validateRegionId(args.getString(0), false);

        // Can't replace regions with this command
        RegionManager regionManager = plugin.getGlobalRegionManager().get(player.getWorld());
        if (regionManager.hasRegion(id)) {
            throw new CommandException(
                    "Данный регион уже создан. Для изменения позиции используйте " +
                    "/region redefine " + id);
        }

        // Make a region from the user's selection
        ProtectedRegion region = createRegionFromSelection(player, id);

        // Get the list of region owners
        if (args.argsLength() > 1) {
            region.setOwners(RegionDBUtil.parseDomainString(args.getSlice(1), 1));
        }

        regionManager.addRegion(region);
        commitChanges(sender, regionManager); // Save to disk

        // Issue a warning about height
        int height = region.getMaximumPoint().getBlockY() - region.getMinimumPoint().getBlockY();
        if (height <= 2) {
            sender.sendMessage(ChatColor.GOLD +
                    "(Внимание: Высота региона " + (height + 1) + " " + StringUtil.plural(height + 1, "блок", "блока", "блоков") + ".)");
        }

        // Hint
        if (regionManager.getRegions().size() <= 2) {
            sender.sendMessage(ChatColor.GRAY +
                    "(Этот регин ТЕПЕРЬ ЗАЩИЩЕН от изменения другими. " +
                    "Не хотите? используйте " +
                    ChatColor.AQUA + "/rg flag " + id + " passthrough allow" +
                    ChatColor.GRAY + ")");
        }

        // Tell the user
        sender.sendMessage(ChatColor.YELLOW + "Новый регион был назван '" + id + "'.");
    }

    /**
     * Re-defines a region with a new selection.
     *
     * @param args the arguments
     * @param sender the sender
     * @throws com.sk89q.minecraft.util.commands.CommandException any error
     */
    @Command(aliases = {"redefine", "update", "move"},
             usage = "<id>",
             desc = "Переназначает регион",
             min = 1, max = 1)
    public void redefine(CommandContext args, CommandSender sender)
            throws CommandException {
        Player player = plugin.checkPlayer(sender);
        World world = player.getWorld();

        // Get and validate the region ID
        String id = validateRegionId(args.getString(0), false);

        // Lookup the existing region
        RegionManager regionManager = plugin.getGlobalRegionManager().get(world);
        ProtectedRegion existing = findExistingRegion(regionManager, id, false);

        // Check permissions
        if (!getPermissionModel(sender).mayRedefine(existing)) {
            throw new CommandPermissionsException();
        }

        // Make a region from the user's selection
        ProtectedRegion region = createRegionFromSelection(player, id);

        // Copy details from the old region to the new one
        region.setMembers(existing.getMembers());
        region.setOwners(existing.getOwners());
        region.setFlags(existing.getFlags());
        region.setPriority(existing.getPriority());
        try {
            region.setParent(existing.getParent());
        } catch (CircularInheritanceException ignore) {
            // This should not be thrown
        }

        regionManager.addRegion(region); // Replace region
        commitChanges(sender, regionManager); // Save to disk

        // Issue a warning about height
        int height = region.getMaximumPoint().getBlockY() - region.getMinimumPoint().getBlockY();
        if (height <= 2) {
            sender.sendMessage(ChatColor.GOLD +
                    "(Предупреждение: Высота региона будет " + (height + 1) + StringUtil.plural(height + 1, " блок", " блока", " блоков") + ".)");
        }

        sender.sendMessage(ChatColor.YELLOW + "Регион '" + id + "' обновлен.");
    }

    /**
     * Claiming command for users.
     *
     * <p>This command is a joke and it needs to be rewritten. It was contributed
     * code :(</p>
     *
     * @param args the arguments
     * @param sender the sender
     * @throws com.sk89q.minecraft.util.commands.CommandException any error
     */
    @Command(aliases = {"claim"},
             usage = "<id> [<владелец1> [<владелец2> [<владельцы...>]]]",
             desc = "Создает регион",
             min = 1)
    public void claim(CommandContext args, CommandSender sender) throws CommandException {
        Player player = plugin.checkPlayer(sender);
        LocalPlayer localPlayer = plugin.wrapPlayer(player);
        RegionPermissionModel permModel = getPermissionModel(sender);

        // Check permissions
        if (!permModel.mayClaim()) {
            throw new CommandPermissionsException();
        }

        // Get and validate the region ID
        String id = validateRegionId(args.getString(0), false);

        // Can't replace existing regions
        RegionManager mgr = plugin.getGlobalRegionManager().get(player.getWorld());
        if (mgr.hasRegion(id)) {
            throw new CommandException(
                    "Данный регион уже существует.");
        }

        // Make a region from the user's selection
        ProtectedRegion region = createRegionFromSelection(player, id);

        // Get the list of region owners
        if (args.argsLength() > 1) {
            region.setOwners(RegionDBUtil.parseDomainString(args.getSlice(1), 1));
        }

        WorldConfiguration wcfg = plugin.getGlobalStateManager().get(player.getWorld());

        // Check whether the player has created too many regions
        if (!permModel.mayClaimRegionsUnbounded()) {
            int maxRegionCount = wcfg.getMaxRegionCount(player);
            if (maxRegionCount >= 0
                    && mgr.getRegionCountOfPlayer(localPlayer) >= maxRegionCount) {
                throw new CommandException(
                        "У Вас слишком много регионов, удалите ненужные.");
            }
        }

        ProtectedRegion existing = mgr.getRegionExact(id);

        // Check for an existing region
        if (existing != null) {
            if (!existing.getOwners().contains(localPlayer)) {
                throw new CommandException(
                        "Такой регион уже существует.");
            }
        }

        // We have to check whether this region violates the space of any other reion
        ApplicableRegionSet regions = mgr.getApplicableRegions(region);

        // Check if this region overlaps any other region
        if (regions.size() > 0) {
            if (!regions.isOwnerOfAll(localPlayer)) {
                throw new CommandException("Данный регион пересекается с другим.");
            }
        } else {
            if (wcfg.claimOnlyInsideExistingRegions) {
                throw new CommandException("Вы можете создавать регионы только внутри " +
                        "существующих регионов которые пренадлежат вам или вашей группе.");
            }
        }

        // Check claim volume
        if (!permModel.mayClaimRegionsUnbounded()) {
            if (region.volume() > wcfg.maxClaimVolume) {
                player.sendMessage(ChatColor.RED + "Данный регион слишком большой.");
                player.sendMessage(ChatColor.RED +
                        "Максимальный размер: " + wcfg.maxClaimVolume + ", Ваш размер: " + region.volume());
                return;
            }
        }

        /*if (plugin.getGlobalConfiguration().getiConomy() != null && wcfg.useiConomy && wcfg.buyOnClaim) {
            if (iConomy.getBank().hasAccount(player.getName())) {
                Account account = iConomy.getBank().getAccount(player.getName());
                double balance = account.getBalance();
                double regionCosts = region.countBlocks() * wcfg.buyOnClaimPrice;
                if (balance >= regionCosts) {
                    account.subtract(regionCosts);
                    player.sendMessage(ChatColor.YELLOW + "You have bought that region for "
                            + iConomy.getBank().format(regionCosts));
                    account.save();
                } else {
                    player.sendMessage(ChatColor.RED + "You have not enough money.");
                    player.sendMessage(ChatColor.RED + "The region you want to claim costs "
                            + iConomy.getBank().format(regionCosts));
                    player.sendMessage(ChatColor.RED + "You have " + iConomy.getBank().format(balance));
                    return;
                }
            } else {
                player.sendMessage(ChatColor.YELLOW + "You have not enough money.");
                return;
            }
        }*/

        region.getOwners().addPlayer(player.getName());

        mgr.addRegion(region);
        commitChanges(sender, mgr); // Save to disk
        sender.sendMessage(ChatColor.YELLOW + "Регион '" + id + "' обновлён.");
    }

    /**
     * Get a WorldEdit selection from a region.
     *
     * @param args the arguments
     * @param sender the sender
     * @throws com.sk89q.minecraft.util.commands.CommandException any error
     */
    @Command(aliases = {"select", "sel", "s"},
             usage = "[id]",
             desc = "Выделяет регион с помощью WorldEdit",
             min = 0, max = 1)
    public void select(CommandContext args, CommandSender sender) throws CommandException {
        Player player = plugin.checkPlayer(sender);
        World world = player.getWorld();
        RegionManager regionManager = plugin.getGlobalRegionManager().get(world);
        ProtectedRegion existing;

        // If no arguments were given, get the region that the player is inside
        if (args.argsLength() == 0) {
            existing = findRegionStandingIn(regionManager, player);
        } else {
            existing = findExistingRegion(regionManager, args.getString(0), false);
        }

        // Check permissions
        if (!getPermissionModel(sender).maySelect(existing)) {
            throw new CommandPermissionsException();
        }

        // Select
        setPlayerSelection(player, existing);
    }

    /**
     * Get information about a region.
     *
     * @param args the arguments
     * @param sender the sender
     * @throws com.sk89q.minecraft.util.commands.CommandException any error
     */
    @Command(aliases = {"info", "i"},
             usage = "[id]",
             flags = "sw:",
             desc = "Получает информацию об регионе",
             min = 0, max = 1)
    public void info(CommandContext args, CommandSender sender) throws CommandException {
        World world = getWorld(args, sender, 'w'); // Get the world
        RegionPermissionModel permModel = getPermissionModel(sender);

        // Lookup the existing region
        RegionManager regionManager = plugin.getGlobalRegionManager().get(world);
        ProtectedRegion existing;

        if (args.argsLength() == 0) { // Get region from where the player is
            if (!(sender instanceof Player)) {
                throw new CommandException("Пожалуйтса укажите " +
                        "регион с помощью /region info -w world_name region_name.");
            }

            existing = findRegionStandingIn(regionManager, (Player) sender, true);
        } else { // Get region from the ID
            existing = findExistingRegion(regionManager, args.getString(0), true);
        }

        // Check permissions
        if (!permModel.mayLookup(existing)) {
            throw new CommandPermissionsException();
        }

        // Print region information
        RegionPrintoutBuilder printout = new RegionPrintoutBuilder(existing);
        printout.appendRegionInfo();
        printout.send(sender);

        // Let the player also select the region
        if (args.hasFlag('s')) {
            // Check permissions
            if (!permModel.maySelect(existing)) {
                throw new CommandPermissionsException();
            }

            setPlayerSelection(plugin.checkPlayer(sender), existing);
        }
    }

    /**
     * List regions.
     *
     * @param args the arguments
     * @param sender the sender
     * @throws com.sk89q.minecraft.util.commands.CommandException any error
     */
    @Command(aliases = {"list"},
             usage = "[page]",
             desc = "Показывает список регионов",
             flags = "p:w:",
             max = 1)
    public void list(CommandContext args, CommandSender sender) throws CommandException {
        World world = getWorld(args, sender, 'w'); // Get the world
        String ownedBy;

        // Get page
        int page = args.getInteger(0, 1) - 1;
        if (page < 0) {
            page = 0;
        }

        // -p flag to lookup a player's regions
        if (args.hasFlag('p')) {
            ownedBy = args.getFlag('p');
        } else {
            ownedBy = null; // List all regions
        }

        // Check permissions
        if (!getPermissionModel(sender).mayList(ownedBy)) {
            ownedBy = sender.getName(); // assume they only want their own
            if (!getPermissionModel(sender).mayList(ownedBy)) {
                throw new CommandPermissionsException();
            }
        }

        RegionManager mgr = plugin.getGlobalRegionManager().get(world);
        Map<String, ProtectedRegion> regions = mgr.getRegions();

        // Build a list of regions to show
        List<RegionListEntry> entries = new ArrayList<RegionListEntry>();

        int index = 0;
        for (String id : regions.keySet()) {
            RegionListEntry entry = new RegionListEntry(id, index++);

            // Filtering by owner?
            if (ownedBy != null) {
                entry.isOwner = regions.get(id).isOwner(ownedBy);
                entry.isMember = regions.get(id).isMember(ownedBy);

                if (!entry.isOwner && !entry.isMember) {
                    continue; // Skip
                }
            }

            entries.add(entry);
        }

        Collections.sort(entries);

        final int totalSize = entries.size();
        final int pageSize = 10;
        final int pages = (int) Math.ceil(totalSize / (float) pageSize);

        sender.sendMessage(ChatColor.RED
                + (ownedBy == null ? "Регионы (страница " : "Регионы игрока " + ownedBy + " (страница ")
                + (page + 1) + " из " + pages + "):");

        if (page < pages) {
            // Print
            for (int i = page * pageSize; i < page * pageSize + pageSize; i++) {
                if (i >= totalSize) {
                    break;
                }

                sender.sendMessage(ChatColor.YELLOW.toString() + entries.get(i));
            }
        }
    }

    /**
     * Set a flag.
     *
     * @param args the arguments
     * @param sender the sender
     * @throws com.sk89q.minecraft.util.commands.CommandException any error
     */
    @Command(aliases = {"flag", "f"},
             usage = "<id> <flag> [-w world] [-g group] [value]",
             flags = "g:w:",
             desc = "Выставляет флаги",
             min = 2)
    public void flag(CommandContext args, CommandSender sender) throws CommandException {
        World world = getWorld(args, sender, 'w'); // Get the world
        String flagName = args.getString(1);
        String value = args.argsLength() >= 3 ? args.getJoinedStrings(2) : null;
        RegionGroup groupValue = null;
        RegionPermissionModel permModel = getPermissionModel(sender);

        // Lookup the existing region
        RegionManager regionManager = plugin.getGlobalRegionManager().get(world);
        ProtectedRegion existing = findExistingRegion(regionManager,
                args.getString(0), true);

        // Check permissions
        if (!permModel.maySetFlag(existing)) {
            throw new CommandPermissionsException();
        }

        Flag<?> foundFlag = DefaultFlag.fuzzyMatchFlag(flagName);

        // We didn't find the flag, so let's print a list of flags that the user
        // can use, and do nothing afterwards
        if (foundFlag == null) {
            StringBuilder list = new StringBuilder();

            // Need to build a list
            for (Flag<?> flag : DefaultFlag.getFlags()) {
                // Can the user set this flag?
                if (!permModel.maySetFlag(existing, flag)) {
                    continue;
                }

                if (list.length() > 0) {
                    list.append(", ");
                }

                list.append(flag.getName());
            }

            sender.sendMessage(ChatColor.RED + "Неизвестный флаг: " + flagName);
            sender.sendMessage(ChatColor.RED + "Доступные флаги: " + list);

            return;
        }

        // Also make sure that we can use this flag
        // This permission is confusing and probably should be replaced, but
        // but not here -- in the model
        if (!permModel.maySetFlag(existing, foundFlag)) {
            throw new CommandPermissionsException();
        }

        // -g for group flag
        if (args.hasFlag('g')) {
            String group = args.getFlag('g');
            RegionGroupFlag groupFlag = foundFlag.getRegionGroupFlag();

            if (groupFlag == null) {
                throw new CommandException("Region flag '" + foundFlag.getName()
                        + "' does not have a group flag!");
            }

            // Parse the [-g group] separately so entire command can abort if parsing
            // the [value] part throws an error.
            try {
                groupValue = groupFlag.parseInput(plugin, sender, group);
            } catch (InvalidFlagFormat e) {
                throw new CommandException(e.getMessage());
            }

        }

        // Set the flag value if a value was set
        if (value != null) {
            // Set the flag if [value] was given even if [-g group] was given as well
            try {
                setFlag(existing, foundFlag, sender, value);
            } catch (InvalidFlagFormat e) {
                throw new CommandException(e.getMessage());
            }

            sender.sendMessage(ChatColor.YELLOW
                    + "Флаг " + foundFlag.getName() + " в регионе '" +
                    existing.getId() + "' изменен на '" + value + "'.");

        // No value? Clear the flag, if -g isn't specified
        } else if (!args.hasFlag('g')) {
            // Clear the flag only if neither [value] nor [-g group] was given
            existing.setFlag(foundFlag, null);

            // Also clear the associated group flag if one exists
            RegionGroupFlag groupFlag = foundFlag.getRegionGroupFlag();
            if (groupFlag != null) {
                existing.setFlag(groupFlag, null);
            }

            sender.sendMessage(ChatColor.YELLOW
                    + "Флаг " + foundFlag.getName() + " удален из '" +
                    existing.getId() + "'. (Any -g(roups) were also removed.)");
        }

        // Now set the group
        if (groupValue != null) {
            RegionGroupFlag groupFlag = foundFlag.getRegionGroupFlag();

            // If group set to the default, then clear the group flag
            if (groupValue == groupFlag.getDefault()) {
                existing.setFlag(groupFlag, null);
                sender.sendMessage(ChatColor.YELLOW
                        + "Групповой флаг '" + foundFlag.getName() + "' был сброшен на значение " +
                        		"по-умолчанию.");
            } else {
                existing.setFlag(groupFlag, groupValue);
                sender.sendMessage(ChatColor.YELLOW
                        + "Групповой флаг '" + foundFlag.getName() + "' выставлен.");
            }
        }

        commitChanges(sender, regionManager); // Save to disk

        // Print region information
        RegionPrintoutBuilder printout = new RegionPrintoutBuilder(existing);
        printout.append(ChatColor.GRAY);
        printout.append("(Флаги: ");
        printout.appendFlagsList(false);
        printout.append(")");
        printout.send(sender);
    }

    /**
     * Set the priority of a region.
     *
     * @param args the arguments
     * @param sender the sender
     * @throws com.sk89q.minecraft.util.commands.CommandException any error
     */
    @Command(aliases = {"setpriority", "priority", "pri"},
             usage = "<id> <priority>",
             flags = "w:",
             desc = "Задает приоритет региону",
             min = 2, max = 2)
    public void setPriority(CommandContext args, CommandSender sender)
            throws CommandException {
        World world = getWorld(args, sender, 'w'); // Get the world
        int priority = args.getInteger(1);

        // Lookup the existing region
        RegionManager regionManager = plugin.getGlobalRegionManager().get(world);
        ProtectedRegion existing = findExistingRegion(regionManager,
                args.getString(0), false);

        // Check permissions
        if (!getPermissionModel(sender).maySetPriority(existing)) {
            throw new CommandPermissionsException();
        }

        existing.setPriority(priority);
        commitChanges(sender, regionManager); // Save to disk

        sender.sendMessage(ChatColor.YELLOW
                + "Приоритет региона '" + existing.getId() + "' был задан на "
                + priority + " (большее числа переопределены).");
    }

    /**
     * Set the parent of a region.
     *
     * @param args the arguments
     * @param sender the sender
     * @throws com.sk89q.minecraft.util.commands.CommandException any error
     */
    @Command(aliases = {"setparent", "parent", "par"},
             usage = "<id> [parent-id]",
             flags = "w:",
             desc = "Задает родительский регион",
             min = 1, max = 2)
    public void setParent(CommandContext args, CommandSender sender) throws CommandException {
        World world = getWorld(args, sender, 'w'); // Get the world
        ProtectedRegion parent;
        ProtectedRegion child;

        // Lookup the existing region
        RegionManager regionManager = plugin.getGlobalRegionManager().get(world);

        // Get parent and child
        child = findExistingRegion(regionManager, args.getString(0), false);
        if (args.argsLength() == 2) {
            parent = findExistingRegion(regionManager, args.getString(1), false);
        } else {
            parent = null;
        }

        // Check permissions
        if (!getPermissionModel(sender).maySetParent(child, parent)) {
            throw new CommandPermissionsException();
        }

        try {
            child.setParent(parent);
        } catch (CircularInheritanceException e) {
            // Tell the user what's wrong
            RegionPrintoutBuilder printout = new RegionPrintoutBuilder(parent);
            printout.append(ChatColor.RED);
            printout.append("Ох! Регион '" + parent.getId() + "' и так родитель " +
            		"региона '" + child.getId() + "', это приведет к зацикливанию.\n");
            printout.append(ChatColor.GRAY);
            printout.append("(Является дочерним регионом от '" + parent.getId() + "':\n");
            printout.appendParentTree(true);
            printout.append(ChatColor.GRAY);
            printout.append(")");
            printout.send(sender);
            return;
        }

        commitChanges(sender, regionManager); // Save to disk

        // Tell the user the current inheritance
        RegionPrintoutBuilder printout = new RegionPrintoutBuilder(child);
        printout.append(ChatColor.YELLOW);
        printout.append("Родительский регион для '" + child.getId() + "' выставлен.\n");
        if (parent != null) {
            printout.append(ChatColor.GRAY);
            printout.append("(Родительские регионы:\n");
            printout.appendParentTree(true);
            printout.append(ChatColor.GRAY);
            printout.append(")");
        }
        printout.send(sender);
        return;
    }

    /**
     * Remove a region.
     *
     * @param args the arguments
     * @param sender the sender
     * @throws com.sk89q.minecraft.util.commands.CommandException any error
     */
    @Command(aliases = {"remove", "delete", "del", "rem"},
             usage = "<id>",
             flags = "w:",
             desc = "Удаляет регион",
             min = 1, max = 1)
    public void remove(CommandContext args, CommandSender sender) throws CommandException {
        World world = getWorld(args, sender, 'w'); // Get the world

        // Lookup the existing region
        RegionManager regionManager = plugin.getGlobalRegionManager().get(world);
        ProtectedRegion existing = findExistingRegion(regionManager,
                args.getString(0), true);

        // Check permissions
        if (!getPermissionModel(sender).mayDelete(existing)) {
            throw new CommandPermissionsException();
        }

        regionManager.removeRegion(existing.getId());
        commitChanges(sender, regionManager); // Save to disk

        sender.sendMessage(ChatColor.YELLOW
                + "Регион '" + existing.getId() + "' удалён.");
    }

    /**
     * Reload the region database.
     *
     * @param args the arguments
     * @param sender the sender
     * @throws com.sk89q.minecraft.util.commands.CommandException any error
     */
    @Command(aliases = {"load", "reload"},
            usage = "[мир]",
            desc = "Перезагружает регионы из файла",
            flags = "w:")
    public void load(CommandContext args, CommandSender sender) throws CommandException {
        World world = null;
        try {
            world = getWorld(args, sender, 'w'); // Get the world
        } catch (CommandException e) {
            // assume the user wants to reload all worlds
        }

        // Check permissions
        if (!getPermissionModel(sender).mayForceLoadRegions()) {
            throw new CommandPermissionsException();
        }

        if (world != null) {
            RegionManager regionManager = plugin.getGlobalRegionManager().get(world);
            if (regionManager == null) {
                throw new CommandException("Для мира '" + world.getName() + "' нет менеджера регионов.");
            }
            reloadChanges(sender, regionManager);
        } else {
            sender.sendMessage(ChatColor.YELLOW + "Загружаем все менеджеры регионов... Это займет какое-то время.");
            for (World w : plugin.getServer().getWorlds()) {
                RegionManager regionManager = plugin.getGlobalRegionManager().get(w);
                if (regionManager == null) {
                    continue;
                }
                reloadChanges(sender, regionManager, true);
            }
        }

        sender.sendMessage(ChatColor.YELLOW + "Базы данных регионов загружены.");
    }

    /**
     * Re-save the region database.
     *
     * @param args the arguments
     * @param sender the sender
     * @throws com.sk89q.minecraft.util.commands.CommandException any error
     */
    @Command(aliases = {"save", "write"},
            usage = "[мир]",
            desc = "Пересохраняет регионы",
            flags = "w:")
    public void save(CommandContext args, CommandSender sender) throws CommandException {
        World world = null;
        try {
            world = getWorld(args, sender, 'w'); // Get the world
        } catch (CommandException e) {
            // assume user wants to save all worlds
        }

        // Check permissions
        if (!getPermissionModel(sender).mayForceSaveRegions()) {
            throw new CommandPermissionsException();
        }

        if (world != null) {
            RegionManager regionManager = plugin.getGlobalRegionManager().get(world);
            if (regionManager == null) {
                throw new CommandException("Для мира '" + world.getName() + "' нет менеджера регионов.");
            }
            commitChanges(sender, regionManager);
        } else {
            sender.sendMessage(ChatColor.YELLOW + "Сохраняем все базы... Это займет какое-то время.");
            for (World w : plugin.getServer().getWorlds()) {
                RegionManager regionManager = plugin.getGlobalRegionManager().get(w);
                if (regionManager == null) {
                    continue;
                }
                commitChanges(sender, regionManager, true);
            }
        }
        sender.sendMessage(ChatColor.YELLOW + "Базы данных сохранены.");
    }

    /**
     * Migrate the region database.
     *
     * @param args the arguments
     * @param sender the sender
     * @throws com.sk89q.minecraft.util.commands.CommandException any error
     */
    @Command(aliases = {"migratedb"}, usage = "<из> <в>",
            desc = "Мигрирование одной базы в другую.", min = 2, max = 2)
    public void migrateDB(CommandContext args, CommandSender sender) throws CommandException {
        // Check permissions
        if (!getPermissionModel(sender).mayMigrateRegionStore()) {
            throw new CommandPermissionsException();
        }

        String from = args.getString(0).toLowerCase().trim();
        String to = args.getString(1).toLowerCase().trim();

        if (from.equals(to)) {
            throw new CommandException("Источник и цель являются одной и тойже базой.");
        }

        Map<MigratorKey, Class<? extends AbstractDatabaseMigrator>> migrators =
                AbstractDatabaseMigrator.getMigrators();
        MigratorKey key = new MigratorKey(from,to);

        if (!migrators.containsKey(key)) {
            throw new CommandException("Мигратор для данных баз не найден.");
        }

        long lastRequest = 10000000;
        if (this.migrateDBRequestDate != null) {
            lastRequest = new Date().getTime() - this.migrateDBRequestDate.getTime();
        }
        if (this.migrateDBRequest == null || lastRequest > 60000) {
            this.migrateDBRequest = key;
            this.migrateDBRequestDate = new Date();

            throw new CommandException("Данная команда потенциально опасна.\n" +
                    "Пожалуйста, удостовертесь что вы сделали резервные копии перед тем как запускать миграцию.");
        }

        Class<? extends AbstractDatabaseMigrator> cls = migrators.get(key);

        try {
            AbstractDatabaseMigrator migrator = cls.getConstructor(WorldGuardPlugin.class).newInstance(plugin);

            migrator.migrate();
        } catch (IllegalArgumentException ignore) {
        } catch (SecurityException ignore) {
        } catch (InstantiationException ignore) {
        } catch (IllegalAccessException ignore) {
        } catch (InvocationTargetException ignore) {
        } catch (NoSuchMethodException ignore) {
        } catch (MigrationException e) {
            throw new CommandException("Ошибка миграции базы: " + e.getMessage());
        }

        sender.sendMessage(ChatColor.YELLOW + "Регионы успешно мигрированы.\n" +
                "Если Вы хотите использовать новую базу как основную - исправте файл настроек WorldGuard.");
    }

    /**
     * Teleport to a region
     *
     * @param args the arguments
     * @param sender the sender
     * @throws com.sk89q.minecraft.util.commands.CommandException any error
     */
    @Command(aliases = {"teleport", "tp"},
             usage = "<id>",
             flags = "s",
             desc = "Телепортирует Вас на заданную точку в регионе.",
             min = 1, max = 1)
    public void teleport(CommandContext args, CommandSender sender) throws CommandException {
        Player player = plugin.checkPlayer(sender);
        Location teleportLocation;

        // Lookup the existing region
        RegionManager regionManager = plugin.getGlobalRegionManager().get(player.getWorld());
        ProtectedRegion existing = findExistingRegion(regionManager,
                args.getString(0), false);

        // Check permissions
        if (!getPermissionModel(sender).mayTeleportTo(existing)) {
            throw new CommandPermissionsException();
        }

        // -s for spawn location
        if (args.hasFlag('s')) {
            teleportLocation = existing.getFlag(DefaultFlag.SPAWN_LOC);

            if (teleportLocation == null) {
                throw new CommandException(
                        "В данном регионе нету точки спавна.");
            }
        } else {
            teleportLocation = existing.getFlag(DefaultFlag.TELE_LOC);

            if (teleportLocation == null) {
                throw new CommandException(
                        "В данном регионе нету точки телепорта.");
            }
        }

        player.teleport(BukkitUtil.toLocation(teleportLocation));
        sender.sendMessage("Вы были телепортированы на регион '" + existing.getId() + "'.");
    }
}
