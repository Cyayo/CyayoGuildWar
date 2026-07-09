package id.cyayo.guildwar.manager;

import id.cyayo.guildwar.CyayoGuildWar;
import org.bukkit.Bukkit;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;

public class WebhookManager {

    private final CyayoGuildWar plugin;

    public WebhookManager(CyayoGuildWar plugin) {
        this.plugin = plugin;
    }

    public void sendWinWebhook(String territoryName, String attackerName, String defenderName, int cooldownMinutes, String participants) {
        sendWebhook("on-war-end-win", Map.of(
                "{territory}", territoryName,
                "{attacker}", attackerName,
                "{defender}", defenderName,
                "{cooldown}", String.valueOf(cooldownMinutes),
                "{participants}", participants,
                "{time}", getFormattedTime()
        ));
    }

    public void sendFailWebhook(String territoryName, String attackerName, String defenderName, int cooldownMinutes, String participants) {
        sendWebhook("on-war-end-fail", Map.of(
                "{territory}", territoryName,
                "{attacker}", attackerName,
                "{defender}", defenderName,
                "{cooldown}", String.valueOf(cooldownMinutes),
                "{participants}", participants,
                "{time}", getFormattedTime()
        ));
    }

    public void sendStartWebhook(String territoryName, String attackerName, String defenderName, String defenderTier) {
        sendWebhook("on-war-start", Map.of(
                "{territory}", territoryName,
                "{attacker}", attackerName,
                "{defender}", defenderName,
                "{defender_tier}", defenderTier,
                "{time}", getFormattedTime()
        ));
    }

    @SuppressWarnings("unchecked")
    private void sendWebhook(String sectionName, Map<String, String> placeholders) {
        if (!plugin.getConfig().getBoolean("discord-webhook.enabled", false)) return;

        String webhookUrl = plugin.getConfig().getString("discord-webhook.url", "");
        if (webhookUrl == null || webhookUrl.isEmpty() || webhookUrl.contains("YOUR_WEBHOOK_URL_HERE")) return;

        // Check if individual sub-section is enabled
        if (sectionName.equals("on-war-start") && !plugin.getConfig().getBoolean("discord-webhook.on-war-start.enabled", false)) {
            return;
        }
        if (sectionName.equals("on-war-end-win") && !plugin.getConfig().getBoolean("discord-webhook.on-war-end-win.enabled", true)) {
            return;
        }
        if (sectionName.equals("on-war-end-fail") && !plugin.getConfig().getBoolean("discord-webhook.on-war-end-fail.enabled", true)) {
            return;
        }

        String username = plugin.getConfig().getString("discord-webhook.username", "Cyayo Guild War");
        String avatarUrl = plugin.getConfig().getString("discord-webhook.avatar-url", "");

        String mode = plugin.getConfig().getString("discord-webhook.mode", "embed");

        String title = plugin.getConfig().getString("discord-webhook." + sectionName + ".title", "");
        String colorHex = plugin.getConfig().getString("discord-webhook." + sectionName + ".color", "#ffffff");
        String description = plugin.getConfig().getString("discord-webhook." + sectionName + ".description", "");
        String footer = plugin.getConfig().getString("discord-webhook." + sectionName + ".footer", "");
        String thumbnail = plugin.getConfig().getString("discord-webhook." + sectionName + ".thumbnail", "");

        // Replace placeholders
        title = replace(title, placeholders);
        description = replace(description, placeholders);
        footer = replace(footer, placeholders);
        thumbnail = replace(thumbnail, placeholders);

        // Convert HEX to Integer Color
        int color = 16777215; // default white
        try {
            color = Integer.parseInt(colorHex.replace("#", ""), 16);
        } catch (NumberFormatException ignored) {}

        final String finalWebhookUrl = webhookUrl;
        final String finalUsername = username;
        final String finalAvatarUrl = avatarUrl;
        final String finalMode = mode;
        final String finalTitle = title;
        final int finalColor = color;
        final String finalDescription = description;
        final String finalFooter = footer;
        final String finalThumbnail = thumbnail;

        // Run asynchronously
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                JSONObject json = new JSONObject();
                json.put("username", finalUsername);
                if (finalAvatarUrl != null && !finalAvatarUrl.isEmpty()) {
                    json.put("avatar_url", finalAvatarUrl);
                }

                if (finalMode.equalsIgnoreCase("text")) {
                    if (finalDescription != null && !finalDescription.isEmpty()) {
                        json.put("content", finalDescription);
                    }
                } else {
                    // Embed mode
                    JSONArray embeds = new JSONArray();
                    JSONObject embed = new JSONObject();
                    
                    if (finalTitle != null && !finalTitle.isEmpty()) {
                        embed.put("title", finalTitle);
                    }
                    embed.put("color", finalColor);
                    if (finalDescription != null && !finalDescription.isEmpty()) {
                        embed.put("description", finalDescription);
                    }
                    
                    if (finalFooter != null && !finalFooter.isEmpty()) {
                        JSONObject footObj = new JSONObject();
                        footObj.put("text", finalFooter);
                        embed.put("footer", footObj);
                    }
                    
                    if (finalThumbnail != null && !finalThumbnail.isEmpty()) {
                        JSONObject thumbObj = new JSONObject();
                        thumbObj.put("url", finalThumbnail);
                        embed.put("thumbnail", thumbObj);
                    }

                    embeds.add(embed);
                    json.put("embeds", embeds);
                }

                URL url = new URL(finalWebhookUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);

                try (OutputStream os = connection.getOutputStream()) {
                    byte[] input = json.toJSONString().getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

                int code = connection.getResponseCode();
                if (code < 200 || code >= 300) {
                    plugin.getLogger().warning("Gagal mengirim notifikasi Discord! Server mengembalikan kode respon: " + code);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Error saat mengirim Discord Webhook: " + e.getMessage());
            }
        });
    }

    private String replace(String source, Map<String, String> placeholders) {
        if (source == null) return "";
        String result = source;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            String rawVal = org.bukkit.ChatColor.stripColor(plugin.color(entry.getValue()));
            result = result.replace(entry.getKey(), rawVal);
        }
        return org.bukkit.ChatColor.stripColor(plugin.color(result));
    }

    private String getFormattedTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss");
        return sdf.format(new Date());
    }
}
