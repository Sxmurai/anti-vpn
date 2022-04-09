package me.sxmurai.antivpn;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Prevents VPN connections
 *
 * Technically mc servers already have an option for this, but this is just an extra layer
 *
 * @author aesthetical
 * @since 4/9/22
 */
public class Main extends JavaPlugin implements Listener, CommandExecutor {
    /**
     * Cache players IP addresses
     *
     * This is because the API I'm using does have a rate-limit + it'll save an API request to check
     */
    private final Map<UUID, Set<IP>> cachedIps = new ConcurrentHashMap<>();

    private boolean banAllCached = false;
    private boolean exemptOps = false;

    @Override
    public void onEnable() {

        // load configs
        saveConfig();
        reloadConfig();

        // cache our configurations
        if (getConfig() != null) {

            // if we should ban all cached IP addresses
            if (getConfig().contains("ban-all-cached")) {
                banAllCached = getConfig().getBoolean("ban-all-cached");
            }

            // if we should exempt players with operator status
            if (getConfig().contains("exempt-ops")) {
                exemptOps = getConfig().getBoolean("exempt-ops");
            }
        }

        // register join/leave listener
        getServer().getPluginManager().registerEvents(this, this);

        // we're done c:
        getLogger().info("Loaded plugin, ban-all-cached: " + banAllCached + ", exempt-ops: " + exemptOps);
    }

    @Override
    public void onDisable() {

        // clear our cache
        cachedIps.clear();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {

        // get our joining player
        Player player = event.getPlayer();

        // if the player has operator status and we should not check operators, return
        if (player.isOp() && exemptOps) {
            return;
        }

        // for some reason this can be null so...
        if (player.getAddress() == null) {
            player.kickPlayer("Somehow your address is null. Try rejoining maybe?");
            return;
        }

        // get the players IP and check if their ip is even valid
        String ip = player.getAddress().getHostName();
        if (ip == null || ip.isEmpty()) {
            player.kickPlayer("Your ip is null/empty (????)");
            return;
        }

        // check if they have already connected on this IP address
        IP cached = getCachedIP(player.getUniqueId(), ip);
        if (cached != null) {
            // if the previously connected IP was flagged as a proxy, kick
            if (cached.proxy()) {
                player.kickPlayer("Please disconnect from your VPN/Proxy before joining.");
            }

            // else, we stop the check
            return;
        }

        // check if the player is on a VPN
        boolean isVPN = isVpn(ip);
        if (isVPN) {
            getLogger().info("Player " + player.getName() + " attempted to join on proxy " + ip);
            player.kickPlayer("Please disconnect from your VPN/Proxy before joining.");
        }

        // cache the players IP
        cacheIP(player.getUniqueId(), ip, isVPN);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {

        // get the player
        Player player = event.getPlayer();

        // if the player is banned and we should ban all the cached ip addresses
        if (player.isBanned() && banAllCached) {
            Set<IP> ips = cachedIps.getOrDefault(player.getUniqueId(), null);
            if (ips != null && !ips.isEmpty()) {
                ips.forEach((ip) -> getServer().banIP(ip.address()));
                getLogger().info("Banned " + player.getName() + "'s " + ips.size() + " cached IP addresses because they were banned.");

                // clear cache
                cachedIps.remove(player.getUniqueId());
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        if (label.equalsIgnoreCase("cachedips")) {
            // if they are not an operator, deny access to this command
            if (!sender.isOp()) {
                sender.sendMessage("who r u LOL!");
                return false;
            }

            // if no arguments provided
            if (args.length == 0) {
                sender.sendMessage("Please provide a players username.");
                return true;
            }

            // get our player from args, and null check
            Player player = getServer().getPlayer(args[0]);
            if (player == null) {
                sender.sendMessage("Could not find player");
                return true;
            }

            // get all ips and if none/null, exception thrown
            Set<IP> ips = cachedIps.getOrDefault(player.getUniqueId(), null);
            if (ips == null || ips.isEmpty()) {
                sender.sendMessage("There are no cached IP addresses this player has joined on");
                return false;
            }

            // send ips
            sender.sendMessage("Joined IP addresses (" + ips.size() + "): " + ips.stream().map(IP::address).collect(Collectors.joining(", ")));
        }

        return true;
    }

    /**
     * Gets the players cached IP address
     * @param uuid the players UUID
     * @param address the players address
     * @return the IP object
     */
    private IP getCachedIP(UUID uuid, String address) {
        Set<IP> ips = cachedIps.computeIfAbsent(uuid, (i) -> new HashSet<>());
        return ips.stream()
                .filter((ip) -> ip.address().equalsIgnoreCase(address))
                .findFirst().orElse(null);
    }

    /**
     * Cache an IP
     * @param uuid the players UUID
     * @param address the address
     * @param proxy if the address was flagged as a proxy/vpn
     */
    private void cacheIP(UUID uuid, String address, boolean proxy) {
        Set<IP> ips = cachedIps.computeIfAbsent(uuid, (i) -> new HashSet<>());
        ips.add(new IP(address, proxy));
        cachedIps.put(uuid, ips);
    }

    /**
     * Checks if the player has connected with a VPN
     * @param ip the players IP
     * @return if they are on a VPN or not
     */
    private boolean isVpn(String ip) {
        // create our HTTP client
        HttpClient client = HttpClient.newHttpClient();

        try {
            // build our request
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(new URI("https://proxycheck.io/v2/" + ip + "?vpn=1&asn=1")) // request URL, this will return a JSON
                    .header("Content-Type", "application/json") // make sure we specify we want JSON
                    .build();

            // send it off
            HttpResponse<String> resp = client.send(req, BodyHandlers.ofString());

            // if we had made the request successfully
            if (resp.statusCode() == 200) {

                // parse our JSON
                JsonObject obj = new Gson().fromJson(resp.body(), JsonObject.class);

                // reeee
                if (obj.has("status")) {
                    String status = obj.get("status").getAsString();
                    if (status.equalsIgnoreCase("error")) {
                        return false;
                    }

                    if (obj.has(ip)) {
                        JsonObject data = obj.getAsJsonObject(ip);

                        String proxy = data.get("proxy").getAsString();
                        return !proxy.equalsIgnoreCase("no");
                    }
                }
            }
        } catch (URISyntaxException | IOException | InterruptedException e) {
            e.printStackTrace();
        }

        return false;
    }

    private record IP(String address, boolean proxy) { }
}
