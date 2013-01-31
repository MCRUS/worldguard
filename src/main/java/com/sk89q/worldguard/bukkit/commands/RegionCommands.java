// $Id$
/*
 * WorldGuard
 * Copyright (C) 2010 sk89q <http://www.sk89q.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
*/

package com.sk89q.worldguard.bukkit.commands;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.sk89q.minecraft.util.commands.Command;
import com.sk89q.minecraft.util.commands.CommandContext;
import com.sk89q.minecraft.util.commands.CommandException;
import com.sk89q.minecraft.util.commands.CommandPermissions;
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
import com.sk89q.worldguard.bukkit.WorldConfiguration;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.domains.DefaultDomain;
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

public class RegionCommands {
    private final WorldGuardPlugin plugin;

    private MigratorKey migrateDBRequest;
    private Date migrateDBRequestDate;

    public RegionCommands(WorldGuardPlugin plugin) {
        this.plugin = plugin;
    }

    @Command(aliases = {"define", "def", "d"}, usage = "<id> [<владелец 1> [<владелец 2> [<владельцы...>]]]",
            desc = "Определяет регион", min = 1)
    @CommandPermissions({"worldguard.region.define"})
    public void define(CommandContext args, CommandSender sender) throws CommandException {

        Player player = plugin.checkPlayer(sender);
        WorldEditPlugin worldEdit = plugin.getWorldEdit();
        String id = args.getString(0);

        if (!ProtectedRegion.isValidId(id)) {
            throw new CommandException("Указан не правильный ID региона!");
        }

        if (id.equalsIgnoreCase("__global__")) {
            throw new CommandException("Регион не может быть назван __global__");
        }

        // Attempt to get the player's selection from WorldEdit
        Selection sel = worldEdit.getSelection(player);

        if (sel == null) {
            throw new CommandException("Сначала выделите регион с помощью WorldEdit.");
        }

        RegionManager mgr = plugin.getGlobalRegionManager().get(sel.getWorld());
        if (mgr.hasRegion(id)) {
            throw new CommandException("Такой регион уже существует. Для переназначения используйте redefine.");
        }

        ProtectedRegion region;

        // Detect the type of region from WorldEdit
        if (sel instanceof Polygonal2DSelection) {
            Polygonal2DSelection polySel = (Polygonal2DSelection) sel;
            int minY = polySel.getNativeMinimumPoint().getBlockY();
            int maxY = polySel.getNativeMaximumPoint().getBlockY();
            region = new ProtectedPolygonalRegion(id, polySel.getNativePoints(), minY, maxY);
        } else if (sel instanceof CuboidSelection) {
            BlockVector min = sel.getNativeMinimumPoint().toBlockVector();
            BlockVector max = sel.getNativeMaximumPoint().toBlockVector();
            region = new ProtectedCuboidRegion(id, min, max);
        } else {
            throw new CommandException(
                    "Тип выделенного региона с помощью WorldEdit не поддерживается WorldGuard!");
        }

        // Get the list of region owners
        if (args.argsLength() > 1) {
            region.setOwners(RegionDBUtil.parseDomainString(args.getSlice(1), 1));
        }

        mgr.addRegion(region);

        try {
            mgr.save();
            sender.sendMessage(ChatColor.YELLOW + "Редин сохранен как " + id + ".");
        } catch (ProtectionDatabaseException e) {
            throw new CommandException("Ошибка сохранения региона: "
                    + e.getMessage());
        }
    }

    @Command(aliases = {"redefine", "update", "move"}, usage = "<id>",
            desc = "Переопределяет регион", min = 1, max = 1)
    public void redefine(CommandContext args, CommandSender sender) throws CommandException {

        Player player = plugin.checkPlayer(sender);
        World world = player.getWorld();
        WorldEditPlugin worldEdit = plugin.getWorldEdit();
        LocalPlayer localPlayer = plugin.wrapPlayer(player);
        String id = args.getString(0);

        if (id.equalsIgnoreCase("__global__")) {
            throw new CommandException("Регион __global__ нельзя переопределить");
        }

        RegionManager mgr = plugin.getGlobalRegionManager().get(world);
        ProtectedRegion existing = mgr.getRegionExact(id);

        if (existing == null) {
            throw new CommandException("Регион с таким ID не найден.");
        }

        if (existing.isOwner(localPlayer)) {
            plugin.checkPermission(sender, "worldguard.region.redefine.own");
        } else if (existing.isMember(localPlayer)) {
            plugin.checkPermission(sender, "worldguard.region.redefine.member");
        } else {
            plugin.checkPermission(sender, "worldguard.region.redefine");
        }

        // Attempt to get the player's selection from WorldEdit
        Selection sel = worldEdit.getSelection(player);

        if (sel == null) {
            throw new CommandException("Сначала выделите регион с помощью WorldEdit.");
        }

        ProtectedRegion region;

        // Detect the type of region from WorldEdit
        if (sel instanceof Polygonal2DSelection) {
            Polygonal2DSelection polySel = (Polygonal2DSelection) sel;
            int minY = polySel.getNativeMinimumPoint().getBlockY();
            int maxY = polySel.getNativeMaximumPoint().getBlockY();
            region = new ProtectedPolygonalRegion(id, polySel.getNativePoints(), minY, maxY);
        } else if (sel instanceof CuboidSelection) {
            BlockVector min = sel.getNativeMinimumPoint().toBlockVector();
            BlockVector max = sel.getNativeMaximumPoint().toBlockVector();
            region = new ProtectedCuboidRegion(id, min, max);
        } else {
            throw new CommandException(
                    "Тип выделенного региона с помощью WorldEdit не поддерживается WorldGuard!");
        }

        region.setMembers(existing.getMembers());
        region.setOwners(existing.getOwners());
        region.setFlags(existing.getFlags());
        region.setPriority(existing.getPriority());
        try {
            region.setParent(existing.getParent());
        } catch (CircularInheritanceException ignore) {
        }

        mgr.addRegion(region);

        sender.sendMessage(ChatColor.YELLOW + "Зона региона обновлена.");

        try {
            mgr.save();
        } catch (ProtectionDatabaseException e) {
            throw new CommandException("Ошибка обновления региона: "
                    + e.getMessage());
        }
    }

    @Command(aliases = {"claim"}, usage = "<id> [<владелец 1> [<владелец 2> [<владельцы...>]]]",
            desc = "Создает регион", min = 1)
    @CommandPermissions({"worldguard.region.claim"})
    public void claim(CommandContext args, CommandSender sender) throws CommandException {

        Player player = plugin.checkPlayer(sender);
        LocalPlayer localPlayer = plugin.wrapPlayer(player);
        WorldEditPlugin worldEdit = plugin.getWorldEdit();
        String id = args.getString(0);

        if (!ProtectedRegion.isValidId(id)) {
            throw new CommandException("Указан не правильный ID региона!");
        }

        if (id.equalsIgnoreCase("__global__")) {
            throw new CommandException("Регион не может называться __global__");
        }

        // Attempt to get the player's selection from WorldEdit
        Selection sel = worldEdit.getSelection(player);

        if (sel == null) {
            throw new CommandException("Сначала выбелите регион с помощью WorldEdit.");
        }

        RegionManager mgr = plugin.getGlobalRegionManager().get(sel.getWorld());

        if (mgr.hasRegion(id)) {
            throw new CommandException("Данный регион уже существует. Пожалуйста, выберите другое название.");
        }

        ProtectedRegion region;

        // Detect the type of region from WorldEdit
        if (sel instanceof Polygonal2DSelection) {
            Polygonal2DSelection polySel = (Polygonal2DSelection) sel;
            int minY = polySel.getNativeMinimumPoint().getBlockY();
            int maxY = polySel.getNativeMaximumPoint().getBlockY();
            region = new ProtectedPolygonalRegion(id, polySel.getNativePoints(), minY, maxY);
        } else if (sel instanceof CuboidSelection) {
            BlockVector min = sel.getNativeMinimumPoint().toBlockVector();
            BlockVector max = sel.getNativeMaximumPoint().toBlockVector();
            region = new ProtectedCuboidRegion(id, min, max);
        } else {
            throw new CommandException(
                    "Тип выделенного региона с помощью WorldEdit не поддерживается WorldGuard!");
        }

        // Get the list of region owners
        if (args.argsLength() > 1) {
            region.setOwners(RegionDBUtil.parseDomainString(args.getSlice(1), 1));
        }

        WorldConfiguration wcfg = plugin.getGlobalStateManager().get(player.getWorld());

        if (!plugin.hasPermission(sender, "worldguard.region.unlimited")) {
            // Check whether the player has created too many regions 
            int maxRegionCount = wcfg.getMaxRegionCount(player);
            if (maxRegionCount >= 0
                    && mgr.getRegionCountOfPlayer(localPlayer) >= maxRegionCount) {
                throw new CommandException("У Вас слишком много регионов. Чтобы создать регион, удалите старый.");
            }
        }

        ProtectedRegion existing = mgr.getRegionExact(id);

        // Check for an existing region
        if (existing != null) {
            if (!existing.getOwners().contains(localPlayer)) {
                throw new CommandException("Этот регион существует и он Вам не пренадлежит.");
            }
        }

        ApplicableRegionSet regions = mgr.getApplicableRegions(region);

        // Check if this region overlaps any other region
        if (regions.size() > 0) {
            if (!regions.isOwnerOfAll(localPlayer)) {
                throw new CommandException("Выделенная область задевает другой регион.");
            }
        } else {
            if (wcfg.claimOnlyInsideExistingRegions) {
                throw new CommandException("Мы можете создавать редины только " +
                        "внутри других регионов, владельцем которого Вы являетесь или группа в которую Вы входите.");
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

        if (!plugin.hasPermission(sender, "worldguard.region.unlimited")) {
            if (region.volume() > wcfg.maxClaimVolume) {
                player.sendMessage(ChatColor.RED + "Регион слишком большой.");
                player.sendMessage(ChatColor.RED +
                        "Раксимальный размер: " + wcfg.maxClaimVolume + ", а размер выделенной области: " + region.volume());
                return;
            }
        }

        region.getOwners().addPlayer(player.getName());
        mgr.addRegion(region);

        try {
            mgr.save();
            sender.sendMessage(ChatColor.YELLOW + "Регион сохранен как " + id + ".");
        } catch (ProtectionDatabaseException e) {
            throw new CommandException("Ошибка создания региона: "
                    + e.getMessage());
        }
    }

    @Command(aliases = {"select", "sel", "s"}, usage = "[id]",
            desc = "Выделяет заданный регион с помощью WorldEdit", min = 0, max = 1)
    public void select(CommandContext args, CommandSender sender) throws CommandException {

        final Player player = plugin.checkPlayer(sender);
        final World world = player.getWorld();
        final LocalPlayer localPlayer = plugin.wrapPlayer(player);

        final RegionManager mgr = plugin.getGlobalRegionManager().get(world);

        final String id;
        if (args.argsLength() == 0) {
            final Vector pt = localPlayer.getPosition();
            final ApplicableRegionSet set = mgr.getApplicableRegions(pt);
            if (set.size() == 0) {
                throw new CommandException("ID региона не указан, а в области где Вы стоите регион не найден!");
            }

            id = set.iterator().next().getId();
        } else {
            id = args.getString(0);
        }

        final ProtectedRegion region = mgr.getRegion(id);

        if (region == null) {
            throw new CommandException("Регион с ID не найден.");
        }

        selectRegion(player, localPlayer, region);
    }

    public void selectRegion(Player player, LocalPlayer localPlayer, ProtectedRegion region) throws CommandException, CommandPermissionsException {
        final WorldEditPlugin worldEdit = plugin.getWorldEdit();
        final String id = region.getId();

        if (region.isOwner(localPlayer)) {
            plugin.checkPermission(player, "worldguard.region.select.own." + id.toLowerCase());
        } else if (region.isMember(localPlayer)) {
            plugin.checkPermission(player, "worldguard.region.select.member." + id.toLowerCase());
        } else {
            plugin.checkPermission(player, "worldguard.region.select." + id.toLowerCase());
        }

        final World world = player.getWorld();
        if (region instanceof ProtectedCuboidRegion) {
            final ProtectedCuboidRegion cuboid = (ProtectedCuboidRegion) region;
            final Vector pt1 = cuboid.getMinimumPoint();
            final Vector pt2 = cuboid.getMaximumPoint();
            final CuboidSelection selection = new CuboidSelection(world, pt1, pt2);
            worldEdit.setSelection(player, selection);
            player.sendMessage(ChatColor.YELLOW + "Регион выделен в форме кубоида.");
        } else if (region instanceof ProtectedPolygonalRegion) {
            final ProtectedPolygonalRegion poly2d = (ProtectedPolygonalRegion) region;
            final Polygonal2DSelection selection = new Polygonal2DSelection(
                    world, poly2d.getPoints(),
                    poly2d.getMinimumPoint().getBlockY(),
                    poly2d.getMaximumPoint().getBlockY()
                    );
            worldEdit.setSelection(player, selection);
            player.sendMessage(ChatColor.YELLOW + "Регион выбрал в виде полигона.");
        } else if (region instanceof GlobalProtectedRegion) {
            throw new CommandException("Вы не можете выделить глобальные регион.");
        } else {
            throw new CommandException("Тип выделения региона не найден: " + region.getClass().getCanonicalName());
        }
    }

    @Command(aliases = {"info", "i"}, usage = "[мир] [id]", flags = "s",
            desc = "Выводит информацию про регион", min = 0, max = 2)
    public void info(CommandContext args, CommandSender sender) throws CommandException {

        final LocalPlayer localPlayer;
        final World world;
        if (sender instanceof Player) {
            final Player player = (Player) sender;
            localPlayer = plugin.wrapPlayer(player);
            world = player.getWorld();
        } else if (args.argsLength() < 2) {
            throw new CommandException("Вы должны быть игроком.");
        } else {
            localPlayer = null;
            world = plugin.matchWorld(sender, args.getString(0));
        }

        final RegionManager mgr = plugin.getGlobalRegionManager().get(world);

        final String id;

        // Get different values based on provided arguments
        switch (args.argsLength()) {
            case 0:
                if (localPlayer == null) {
                    throw new CommandException("Вы должны быть игроком.");
                }

                final Vector pt = localPlayer.getPosition();
                final ApplicableRegionSet set = mgr.getApplicableRegions(pt);
                if (set.size() == 0) {
                    throw new CommandException("ID региона не указан, а в области где Вы стоите регион не найден!");
                }

                id = set.iterator().next().getId();
                break;

            case 1:
                id = args.getString(0).toLowerCase();
                break;

            default:
                id = args.getString(1).toLowerCase();
        }

        final ProtectedRegion region = mgr.getRegion(id);

        if (region == null) {
            if (!ProtectedRegion.isValidId(id)) {
                throw new CommandException("ID региона указан не верно!");
            }
            throw new CommandException("Регион с ID '" + id + "' не существует.");
        }

        displayRegionInfo(sender, localPlayer, region);

        if (args.hasFlag('s')) {
            selectRegion(plugin.checkPlayer(sender), localPlayer, region);
        }
    }

    public void displayRegionInfo(CommandSender sender, final LocalPlayer localPlayer, ProtectedRegion region) throws CommandPermissionsException {
        if (localPlayer == null) {
            plugin.checkPermission(sender, "worldguard.region.info");
        } else if (region.isOwner(localPlayer)) {
            plugin.checkPermission(sender, "worldguard.region.info.own");
        } else if (region.isMember(localPlayer)) {
            plugin.checkPermission(sender, "worldguard.region.info.member");
        } else {
            plugin.checkPermission(sender, "worldguard.region.info");
        }

        final String id = region.getId();

        sender.sendMessage(ChatColor.YELLOW + "Регион: " + id + ChatColor.GRAY + ", Тип: " + region.getTypeName() + ", " + ChatColor.BLUE + "Приоритет: " + region.getPriority());

        boolean hasFlags = false;
        final StringBuilder s = new StringBuilder(ChatColor.BLUE + "Флаги: ");
        for (Flag<?> flag : DefaultFlag.getFlags()) {
            Object val = region.getFlag(flag), group = null;

            if (val == null) {
                continue;
            }

            if (hasFlags) {
                s.append(", ");
            }

            RegionGroupFlag groupFlag = flag.getRegionGroupFlag();
            if (groupFlag != null) {
                group = region.getFlag(groupFlag);
            }

            if (group == null) {
                s.append(flag.getName()).append(": ").append(String.valueOf(val));
            } else {
                s.append(flag.getName()).append(" -g ").append(String.valueOf(group)).append(": ").append(String.valueOf(val));
            }

            hasFlags = true;
        }
        if (hasFlags) {
            sender.sendMessage(s.toString());
        }

        if (region.getParent() != null) {
            sender.sendMessage(ChatColor.BLUE + "Родительский регион: " + region.getParent().getId());
        }

        final DefaultDomain owners = region.getOwners();
        if (owners.size() != 0) {
            sender.sendMessage(ChatColor.LIGHT_PURPLE + "Владельцы: " + owners.toUserFriendlyString());
        }

        final DefaultDomain members = region.getMembers();
        if (members.size() != 0) {
            sender.sendMessage(ChatColor.LIGHT_PURPLE + "Участники: " + members.toUserFriendlyString());
        }

        final BlockVector min = region.getMinimumPoint();
        final BlockVector max = region.getMaximumPoint();
        sender.sendMessage(ChatColor.LIGHT_PURPLE + "Координаты:"
                + " (" + min.getBlockX() + "," + min.getBlockY() + "," + min.getBlockZ() + ")"
                + " (" + max.getBlockX() + "," + max.getBlockY() + "," + max.getBlockZ() + ")"
                );
    }

    public class RegionEntry implements Comparable<RegionEntry> {
        private final String id;
        private final int index;
        private boolean isOwner;
        private boolean isMember;

        public RegionEntry(String id, int index) {
            this.id = id;
            this.index = index;
        }

        @Override
        public int compareTo(RegionEntry o) {
            if (isOwner != o.isOwner) {
                return isOwner ? 1 : -1;
            }
            if (isMember != o.isMember) {
                return isMember ? 1 : -1;
            }
            return id.compareTo(o.id);
        }

        @Override
        public String toString() {
            if (isOwner) {
                return (index + 1) + ". +" + id;
            } else if (isMember) {
                return (index + 1) + ". -" + id;
            } else {
                return (index + 1) + ". " + id;
            }
        }
    }

    @Command(aliases = {"list"}, usage = "[.игрок] [страница] [мир]",
            desc = "Вывод список регионов", max = 3)
    //@CommandPermissions({"worldguard.region.list"})
    public void list(CommandContext args, CommandSender sender) throws CommandException {

        World world;
        int page = 0;
        int argOffset = 0;
        String name = "";
        boolean own = false;
        LocalPlayer localPlayer = null;

        final String senderName = sender.getName().toLowerCase();
        if (args.argsLength() > 0 && args.getString(0).startsWith(".")) {
            name = args.getString(0).substring(1).toLowerCase();
            argOffset = 1;

            if (name.equals("me") || name.isEmpty() || name.equals(senderName)) {
                own = true;
            }
        }

        // Make /rg list default to "own" mode if the "worldguard.region.list" permission is not given
        if (!own && !plugin.hasPermission(sender, "worldguard.region.list")) {
            own = true;
        }

        if (own) {
            plugin.checkPermission(sender, "worldguard.region.list.own");
            name = senderName;
            localPlayer = plugin.wrapPlayer(plugin.checkPlayer(sender));
        }

        if (args.argsLength() > argOffset) {
            page = Math.max(0, args.getInteger(argOffset) - 1);
        }

        if (args.argsLength() > 1 + argOffset) {
            world = plugin.matchWorld(sender, args.getString(1 + argOffset));
        } else {
            world = plugin.checkPlayer(sender).getWorld();
        }

        final RegionManager mgr = plugin.getGlobalRegionManager().get(world);
        final Map<String, ProtectedRegion> regions = mgr.getRegions();

        List<RegionEntry> regionEntries = new ArrayList<RegionEntry>();
        int index = 0;
        for (String id : regions.keySet()) {
            RegionEntry entry = new RegionEntry(id, index++);
            if (!name.isEmpty()) {
                if (own) {
                    entry.isOwner = regions.get(id).isOwner(localPlayer);
                    entry.isMember = regions.get(id).isMember(localPlayer);
                } else {
                    entry.isOwner = regions.get(id).isOwner(name);
                    entry.isMember = regions.get(id).isMember(name);
                }

                if (!entry.isOwner && !entry.isMember) {
                    continue;
                }
            }

            regionEntries.add(entry);
        }

        Collections.sort(regionEntries);

        final int totalSize = regionEntries.size();
        final int pageSize = 10;
        final int pages = (int) Math.ceil(totalSize / (float) pageSize);

        sender.sendMessage(ChatColor.RED
                + (name.equals("") ? "Регионы (страница " : "Регионы игрока " + name + " (страница ")
                + (page + 1) + " из " + pages + "):");

        if (page < pages) {
            for (int i = page * pageSize; i < page * pageSize + pageSize; i++) {
                if (i >= totalSize) {
                    break;
                }
                sender.sendMessage(ChatColor.YELLOW.toString() + regionEntries.get(i));
            }
        }
    }

    @Command(aliases = {"flag", "f"}, usage = "<id> <флаг> [-g группа] [значение]", flags = "g:",
            desc = "Выставляет значение флага региона", min = 2)
    public void flag(CommandContext args, CommandSender sender) throws CommandException {

        final World world;
        Player player;
        LocalPlayer localPlayer = null;
        if (args.hasFlag('w')) {
            world = plugin.matchWorld(sender, args.getFlag('w'));
        } else {
            player = plugin.checkPlayer(sender);
            localPlayer = plugin.wrapPlayer(player);
            world = player.getWorld();
        }

        String id = args.getString(0);
        String flagName = args.getString(1);
        String value = null;
        RegionGroup groupValue = null;

        if (args.argsLength() >= 3) {
            value = args.getJoinedStrings(2);
        }

        RegionManager mgr = plugin.getGlobalRegionManager().get(world);
        ProtectedRegion region = mgr.getRegion(id);

        if (region == null) {
            if (id.equalsIgnoreCase("__global__")) {
                region = new GlobalProtectedRegion(id);
                mgr.addRegion(region);
            } else {
                throw new CommandException("Регион с таким ID не найден.");
            }
        }

        // @TODO deprecate "flag.[own./member./blank]"
        boolean hasPerm = false;

        if (localPlayer == null) {
            hasPerm = true;
        } else {
            if (region.isOwner(localPlayer)) {
                if (plugin.hasPermission(sender, "worldguard.region.flag.own." + id.toLowerCase())) hasPerm = true;
                else if (plugin.hasPermission(sender, "worldguard.region.flag.regions.own." + id.toLowerCase())) hasPerm = true;
            } else if (region.isMember(localPlayer)) {
                if (plugin.hasPermission(sender, "worldguard.region.flag.member." + id.toLowerCase())) hasPerm = true;
                else if (plugin.hasPermission(sender, "worldguard.region.flag.regions.member." + id.toLowerCase())) hasPerm = true;
            } else {
                if (plugin.hasPermission(sender, "worldguard.region.flag." + id.toLowerCase())) hasPerm = true;
                else if (plugin.hasPermission(sender, "worldguard.region.flag.regions." + id.toLowerCase())) hasPerm = true;
            }
        }
        if (!hasPerm) throw new CommandPermissionsException();

        Flag<?> foundFlag = null;

        // Now time to find the flag!
        for (Flag<?> flag : DefaultFlag.getFlags()) {
            // Try to detect the flag
            if (flag.getName().replace("-", "").equalsIgnoreCase(flagName.replace("-", ""))) {
                foundFlag = flag;
                break;
            }
        }

        if (foundFlag == null) {
            StringBuilder list = new StringBuilder();

            // Need to build a list
            for (Flag<?> flag : DefaultFlag.getFlags()) {

                // @TODO deprecate inconsistant "owner" permission
                if (localPlayer != null) {
                    if (region.isOwner(localPlayer)) {
                        if (!plugin.hasPermission(sender, "worldguard.region.flag.flags."
                                + flag.getName() + ".owner." + id.toLowerCase())
                                && !plugin.hasPermission(sender, "worldguard.region.flag.flags."
                                        + flag.getName() + ".own." + id.toLowerCase())) {
                            continue;
                        }
                    } else if (region.isMember(localPlayer)) {
                        if (!plugin.hasPermission(sender, "worldguard.region.flag.flags."
                                + flag.getName() + ".member." + id.toLowerCase())) {
                            continue;
                        }
                    } else {
                        if (!plugin.hasPermission(sender, "worldguard.region.flag.flags."
                                + flag.getName() + "." + id.toLowerCase())) {
                            continue;
                        }
                    }
                }

                if (list.length() > 0) {
                    list.append(", ");
                }
                list.append(flag.getName());
            }

            sender.sendMessage(ChatColor.RED + "Флаг с названием " + flagName + " не найден");
            sender.sendMessage(ChatColor.RED + "Доступные флаги: " + list);
            return;
        }

        if (localPlayer != null) {
            if (region.isOwner(localPlayer)) {
                plugin.checkPermission(sender, "worldguard.region.flag.flags."
                        + foundFlag.getName() + ".owner." + id.toLowerCase());
            } else if (region.isMember(localPlayer)) {
                plugin.checkPermission(sender, "worldguard.region.flag.flags."
                        + foundFlag.getName() + ".member." + id.toLowerCase());
            } else {
                plugin.checkPermission(sender, "worldguard.region.flag.flags."
                        + foundFlag.getName() + "." + id.toLowerCase());
            }
        }

        if (args.hasFlag('g')) {
            String group = args.getFlag('g');
            RegionGroupFlag groupFlag = foundFlag.getRegionGroupFlag();
            if (groupFlag == null) {
                throw new CommandException("Флаг региона '" + foundFlag.getName()
                        + "' не груповой!");
            }

            // Parse the [-g group] separately so entire command can abort if parsing
            // the [value] part throws an error.
            try {
                groupValue = groupFlag.parseInput(plugin, sender, group);
            } catch (InvalidFlagFormat e) {
                throw new CommandException(e.getMessage());
            }

        }

        if (value != null) {
            // Set the flag if [value] was given even if [-g group] was given as well
            try {
                setFlag(region, foundFlag, sender, value);
            } catch (InvalidFlagFormat e) {
                throw new CommandException(e.getMessage());
            }

            sender.sendMessage(ChatColor.YELLOW
                    + "Флаг '" + foundFlag.getName() + "' выставлен.");
        }

        if (value == null && !args.hasFlag('g')) {
            // Clear the flag only if neither [value] nor [-g group] was given
            region.setFlag(foundFlag, null);

            // Also clear the associated group flag if one exists
            RegionGroupFlag groupFlag = foundFlag.getRegionGroupFlag();
            if (groupFlag != null) {
                region.setFlag(groupFlag, null);
            }

            sender.sendMessage(ChatColor.YELLOW
                    + "Флаг '" + foundFlag.getName() + "' сброшен.");
        }

        if (groupValue != null) {
            RegionGroupFlag groupFlag = foundFlag.getRegionGroupFlag();

            // If group set to the default, then clear the group flag
            if (groupValue == groupFlag.getDefault()) {
                region.setFlag(groupFlag, null);
                sender.sendMessage(ChatColor.YELLOW
                        + "Груповой флаг '" + foundFlag.getName() + "' сброшен.");
            } else {
                region.setFlag(groupFlag, groupValue);
                sender.sendMessage(ChatColor.YELLOW
                        + "Груповой '" + foundFlag.getName() + "' выставлен.");
            }
        }

        try {
            mgr.save();
        } catch (ProtectionDatabaseException e) {
            throw new CommandException("Ошибка записи региона: "
                    + e.getMessage());
        }
    }

    public <V> void setFlag(ProtectedRegion region,
            Flag<V> flag, CommandSender sender, String value)
                    throws InvalidFlagFormat {
        region.setFlag(flag, flag.parseInput(plugin, sender, value));
    }

    @Command(aliases = {"setpriority", "priority", "pri"},
            usage = "<id> <приоритет>",
            flags = "w:",
            desc = "Выставляет приоритет региона", 
            min = 2, max = 2)
    public void setPriority(CommandContext args, CommandSender sender) throws CommandException {
        final World world;
        Player player;
        LocalPlayer localPlayer = null;
        if (args.hasFlag('w')) {
            world = plugin.matchWorld(sender, args.getFlag('w'));
        } else {
            player = plugin.checkPlayer(sender);
            localPlayer = plugin.wrapPlayer(player);
            world = player.getWorld();
        }

        String id = args.getString(0);
        int priority = args.getInteger(1);
        
        if (id.equalsIgnoreCase("__global__")) {
            throw new CommandException("Регион не может быть назван __global__");
        }
        
        RegionManager mgr = plugin.getGlobalRegionManager().get(world);
        ProtectedRegion region = mgr.getRegion(id);
        if (region == null) {
            throw new CommandException("Регион с таким ID не найден.");
        }

        id = region.getId();

        if (localPlayer != null) {
            if (region.isOwner(localPlayer)) {
                plugin.checkPermission(sender, "worldguard.region.setpriority.own." + id.toLowerCase());
            } else if (region.isMember(localPlayer)) {
                plugin.checkPermission(sender, "worldguard.region.setpriority.member." + id.toLowerCase());
            } else {
                plugin.checkPermission(sender, "worldguard.region.setpriority." + id.toLowerCase());
            }
        }

        region.setPriority(priority);

        sender.sendMessage(ChatColor.YELLOW
                + "Приоритет '" + region.getId() + "' выстален на "
                + priority + ".");
        
        try {
            mgr.save();
        } catch (ProtectionDatabaseException e) {
            throw new CommandException("Ошибка выставления приоритета: "
                    + e.getMessage());
        }
    }

    @Command(aliases = {"setparent", "parent", "par"},
            usage = "<id> [id родителя]",
            flags = "w:",
            desc = "Выставляет регион-родитель для заданого региона",
            min = 1, max = 2)
    public void setParent(CommandContext args, CommandSender sender) throws CommandException {
        final World world;
        Player player;
        LocalPlayer localPlayer = null;
        if (args.hasFlag('w')) {
            world = plugin.matchWorld(sender, args.getFlag('w'));
        } else {
            player = plugin.checkPlayer(sender);
            localPlayer = plugin.wrapPlayer(player);
            world = player.getWorld();
        }

        String id = args.getString(0);
        
        if (id.equalsIgnoreCase("__global__")) {
            throw new CommandException("Регион не может быть назван __global__");
        }
        
        RegionManager mgr = plugin.getGlobalRegionManager().get(world);
        ProtectedRegion region = mgr.getRegion(id);
        if (region == null) {
            throw new CommandException("Регион с таким ID не найден.");
        }

        id = region.getId();

        if (args.argsLength() == 1) {
            try {
                region.setParent(null);
            } catch (CircularInheritanceException ignore) {
            }
    
            sender.sendMessage(ChatColor.YELLOW
                    + "Регион-родитель для '" + region.getId() + "' удален.");
        } else {
            String parentId = args.getString(1);
            ProtectedRegion parent = mgr.getRegion(parentId);
    
            if (parent == null) {
                throw new CommandException("Регион с таким ID не найден.");
            }

            if (localPlayer != null) {
                if (region.isOwner(localPlayer)) {
                    plugin.checkPermission(sender, "worldguard.region.setparent.own." + id.toLowerCase());
                } else if (region.isMember(localPlayer)) {
                    plugin.checkPermission(sender, "worldguard.region.setparent.member." + id.toLowerCase());
                } else {
                    plugin.checkPermission(sender, "worldguard.region.setparent." + id.toLowerCase());
                } 

                if (parent.isOwner(localPlayer)) {
                    plugin.checkPermission(sender, "worldguard.region.setparent.own." + parentId.toLowerCase());
                } else if (parent.isMember(localPlayer)) {
                    plugin.checkPermission(sender, "worldguard.region.setparent.member." + parentId.toLowerCase());
                } else {
                    plugin.checkPermission(sender, "worldguard.region.setparent." + parentId.toLowerCase());
                }
            }
            
            try {
                region.setParent(parent);
            } catch (CircularInheritanceException e) {
                throw new CommandException("Обнаружен замкнутый круг!");
            }
    
            sender.sendMessage(ChatColor.YELLOW
                    + parent.getId() + " теперь регион-родитель для '" + region.getId() + ".");
        }
        
        try {
            mgr.save();
        } catch (ProtectionDatabaseException e) {
            throw new CommandException("Ошибка записи регионов: "
                    + e.getMessage());
        }
    }

    @Command(aliases = {"remove", "delete", "del", "rem"},
            usage = "<id>",
            flags = "w:",
            desc = "Удаляет регион",
            min = 1, max = 1)
    public void remove(CommandContext args, CommandSender sender) throws CommandException {
        final World world;
        Player player;
        LocalPlayer localPlayer = null;
        if (args.hasFlag('w')) {
            world = plugin.matchWorld(sender, args.getFlag('w'));
        } else {
            player = plugin.checkPlayer(sender);
            localPlayer = plugin.wrapPlayer(player);
            world = player.getWorld();
        }

        String id = args.getString(0);

        RegionManager mgr = plugin.getGlobalRegionManager().get(world);
        ProtectedRegion region = mgr.getRegionExact(id);

        if (region == null) {
            throw new CommandException("Регион с таким ID не найжен.");
        }

        if (localPlayer != null) {
            if (region.isOwner(localPlayer)) {
                plugin.checkPermission(sender, "worldguard.region.remove.own." + id.toLowerCase());
            } else if (region.isMember(localPlayer)) {
                plugin.checkPermission(sender, "worldguard.region.remove.member." + id.toLowerCase());
            } else {
                plugin.checkPermission(sender, "worldguard.region.remove." + id.toLowerCase());
            }
        }
        
        mgr.removeRegion(id);
        
        sender.sendMessage(ChatColor.YELLOW
                + "Регион '" + id + "' удален.");

        try {
            mgr.save();
        } catch (ProtectionDatabaseException e) {
            throw new CommandException("Ошибка удаления региона: "
                    + e.getMessage());
        }
    }

    @Command(aliases = {"load", "reload"}, usage = "[мир]",
            desc = "Перезагружает регионы", max = 1)
    @CommandPermissions({"worldguard.region.load"})
    public void load(CommandContext args, CommandSender sender) throws CommandException {
        
        World world = null;
        
        if (args.argsLength() > 0) {
            world = plugin.matchWorld(sender, args.getString(0));
        }

        if (world != null) {
            RegionManager mgr = plugin.getGlobalRegionManager().get(world);
            
            try {
                mgr.load();
                sender.sendMessage(ChatColor.YELLOW
                        + "Регионы для мира '" + world.getName() + "' перезагружаны.");
            } catch (ProtectionDatabaseException e) {
                throw new CommandException("Ошибка перезагрузки регионов: "
                        + e.getMessage());
            }
        } else {
            for (World w : plugin.getServer().getWorlds()) {
                RegionManager mgr = plugin.getGlobalRegionManager().get(w);
                
                try {
                    mgr.load();
                } catch (ProtectionDatabaseException e) {
                    throw new CommandException("Ошибка перезагрузки регионов: "
                            + e.getMessage());
                }
            }
            
            sender.sendMessage(ChatColor.YELLOW
                    + "База данных регионов перезагружена.");
        }
    }
    
    @Command(aliases = {"save", "write"}, usage = "[world]",
            desc = "Re-save regions to file", max = 1)
    @CommandPermissions({"worldguard.region.save"})
    public void save(CommandContext args, CommandSender sender) throws CommandException {
        
        World world = null;
        
        if (args.argsLength() > 0) {
            world = plugin.matchWorld(sender, args.getString(0));
        }

        if (world != null) {
            RegionManager mgr = plugin.getGlobalRegionManager().get(world);
            
            try {
                mgr.save();
                sender.sendMessage(ChatColor.YELLOW
                        + "Регионы мира '" + world.getName() + "' сохранены.");
            } catch (ProtectionDatabaseException e) {
                throw new CommandException("Ошибка сохранения регионов: "
                        + e.getMessage());
            }
        } else {
            for (World w : plugin.getServer().getWorlds()) {
                RegionManager mgr = plugin.getGlobalRegionManager().get(w);
                
                try {
                    mgr.save();
                } catch (ProtectionDatabaseException e) {
                    throw new CommandException("Ошибка сохранения регионов: "
                            + e.getMessage());
                }
            }
            
            sender.sendMessage(ChatColor.YELLOW
                    + "База данных регионов сохранена.");
        }
    }

    @Command(aliases = {"migratedb"}, usage = "<с> <на>",
            desc = "Мигрирование с одной базы данных регионов на другую.", min = 1)
    @CommandPermissions({"worldguard.region.migratedb"})
    public void migratedb(CommandContext args, CommandSender sender) throws CommandException {
        String from = args.getString(0).toLowerCase().trim();
        String to = args.getString(1).toLowerCase().trim();

        if (from.equals(to)) {
            throw new CommandException("Вы указали одну базу 2 раза.");
        }

        Map<MigratorKey, Class<? extends AbstractDatabaseMigrator>> migrators = AbstractDatabaseMigrator.getMigrators();
        MigratorKey key = new MigratorKey(from, to);

        if (!migrators.containsKey(key)) {
            throw new CommandException("Да данного перехода не найден мигратор.");
        }

        long lastRequest = 10000000;
        if (this.migrateDBRequestDate != null) {
            lastRequest = new Date().getTime() - this.migrateDBRequestDate.getTime();
        }
        if (this.migrateDBRequest == null || lastRequest > 60000) {
            this.migrateDBRequest = key;
            this.migrateDBRequestDate = new Date();

            throw new CommandException("Эта команда потенциально опасная.\n" +
                    "Пожалуйста, удостовертесь что Вы сделали резервные копии и перевведите данную команду.");
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
            throw new CommandException("Оишбка миграции: " + e.getMessage());
        }

        sender.sendMessage(ChatColor.YELLOW + "Регионы успешно мигрировали.\n" +
                "Если вы используйте новый формат по-умолчанию, то обновите вашу конфигурацию и перезагрузите WorldGuard.");
    }

    @Command(aliases = {"teleport", "tp"}, usage = "<id>", flags = "s",
            desc = "Телепортирует Вас взаданый регион.", min = 1, max = 1)
    @CommandPermissions({"worldguard.region.teleport"})
    public void teleport(CommandContext args, CommandSender sender) throws CommandException {
        final Player player = plugin.checkPlayer(sender);

        final RegionManager mgr = plugin.getGlobalRegionManager().get(player.getWorld());
        String id = args.getString(0);

        final ProtectedRegion region = mgr.getRegion(id);
        if (region == null) {
            if (!ProtectedRegion.isValidId(id)) {
                throw new CommandException("ID оуказан неверно!");
            }
            throw new CommandException("Регион с ID '" + id + "' не существует.");
        }

        id = region.getId();

        LocalPlayer localPlayer = plugin.wrapPlayer(player);
        if (region.isOwner(localPlayer)) {
            plugin.checkPermission(sender, "worldguard.region.teleport.own." + id.toLowerCase());
        } else if (region.isMember(localPlayer)) {
            plugin.checkPermission(sender, "worldguard.region.teleport.member." + id.toLowerCase());
        } else {
            plugin.checkPermission(sender, "worldguard.region.teleport." + id.toLowerCase());
        }

        final Location teleportLocation;
        if (args.hasFlag('s')) {
            teleportLocation = region.getFlag(DefaultFlag.SPAWN_LOC);
            if (teleportLocation == null) {
                throw new CommandException("В регионе не указана точка спавна.");
            }
        } else {
            teleportLocation = region.getFlag(DefaultFlag.TELE_LOC);
            if (teleportLocation == null) {
                throw new CommandException("В региона не указана точка телепорта.");
            }
        }

        player.teleport(BukkitUtil.toLocation(teleportLocation));

        sender.sendMessage("Вы телепортированы в регион '" + id + "'.");
    }
}
