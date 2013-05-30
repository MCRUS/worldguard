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

import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import com.sk89q.minecraft.util.commands.Command;
import com.sk89q.minecraft.util.commands.CommandContext;
import com.sk89q.minecraft.util.commands.CommandException;
import com.sk89q.minecraft.util.commands.CommandPermissions;
import com.sk89q.worldguard.bukkit.BukkitUtil;
import com.sk89q.worldguard.bukkit.ConfigurationManager;
import com.sk89q.worldguard.bukkit.WorldConfiguration;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;

public class ToggleCommands {
    private final WorldGuardPlugin plugin;

    public ToggleCommands(WorldGuardPlugin plugin) {
        this.plugin = plugin;
    }

    @Command(aliases = {"stopfire"}, usage = "[<мир>]",
            desc = "Отключет распостранение огня", max = 1)
    @CommandPermissions({"worldguard.fire-toggle.stop"})
    public void stopFire(CommandContext args, CommandSender sender) throws CommandException {
        
        World world;
        
        if (args.argsLength() == 0) {
            world = plugin.checkPlayer(sender).getWorld();
        } else {
            world = plugin.matchWorld(sender, args.getString(0));
        }
        
        WorldConfiguration wcfg = plugin.getGlobalStateManager().get(world);

        if (!wcfg.fireSpreadDisableToggle) {
            plugin.getServer().broadcastMessage(
                    ChatColor.YELLOW
                    + "Распостранение огня в мире '" + world.getName() + "' было остановлено игроком "
                    + plugin.toName(sender) + ".");
        } else {
            sender.sendMessage(
                    ChatColor.YELLOW
                    + "Распостранение огня отключено во всех мирах.");
        }

        wcfg.fireSpreadDisableToggle = true;
    }

    @Command(aliases = {"allowfire"}, usage = "[<мир>]",
            desc = "Разрешает распостранение огня", max = 1)
    @CommandPermissions({"worldguard.fire-toggle.stop"})
    public void allowFire(CommandContext args, CommandSender sender) throws CommandException {
        
        World world;
        
        if (args.argsLength() == 0) {
            world = plugin.checkPlayer(sender).getWorld();
        } else {
            world = plugin.matchWorld(sender, args.getString(0));
        }
        
        WorldConfiguration wcfg = plugin.getGlobalStateManager().get(world);

        if (wcfg.fireSpreadDisableToggle) {
            plugin.getServer().broadcastMessage(ChatColor.YELLOW
                    + "Распостранение огня в мире '" + world.getName() + "' было разрешено игроком "
                    + plugin.toName(sender) + ".");
        } else {
            sender.sendMessage(ChatColor.YELLOW
                    + "Распостранение огня включено во всех мирах.");
        }

        wcfg.fireSpreadDisableToggle = false;
    }

    @Command(aliases = {"halt-activity", "stoplag", "haltactivity"},
            desc = "Разрешает и запрещает всю активность на сервере снижая этим нагрузку", flags = "c", max = 0)
    @CommandPermissions({"worldguard.halt-activity"})
    public void stopLag(CommandContext args, CommandSender sender) throws CommandException {

        ConfigurationManager configManager = plugin.getGlobalStateManager();

        configManager.activityHaltToggle = !args.hasFlag('c');

        if (configManager.activityHaltToggle) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.YELLOW
                        + "ВСЯ активность на сервере была отключена.");
            }

            plugin.getServer().broadcastMessage(ChatColor.YELLOW
                    + "ВСЯ активность на сервере была отключена игроком "
                    + plugin.toName(sender) + ".");

            for (World world : plugin.getServer().getWorlds()) {
                int removed = 0;

                for (Entity entity : world.getEntities()) {
                    if (BukkitUtil.isIntensiveEntity(entity)) {
                        entity.remove();
                        removed++;
                    }
                }

                if (removed > 10) {
                    sender.sendMessage("" + removed + " мобов (>10) было удалено в мире "
                            + world.getName());
                }
            }

        } else {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.YELLOW
                        + "ВСЯ активность на сервере возобновлена.");
            }

            plugin.getServer().broadcastMessage(ChatColor.YELLOW
                    + "ВСЯ активность на сервере возобновлена.");
        }
    }
}
