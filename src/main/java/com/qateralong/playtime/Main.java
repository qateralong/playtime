package com.qateralong.playtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main implements ModInitializer {
    public static final String MOD_ID = "playtime";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("playtime.json");
    private static final Set<UUID> HIDDEN = new HashSet<>();

    public static class Data {
        public List<String> hidden = List.of();
    }

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> load());
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> register(dispatcher));
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("playtime")
                .executes(context -> self(context.getSource()))
                .then(Commands.literal("hide").executes(context -> hide(context.getSource(), true)))
                .then(Commands.literal("show").executes(context -> hide(context.getSource(), false)))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            MinecraftServer server = context.getSource().getServer();
                            String typed = builder.getRemainingLowerCase();
                            Set<String> names = new HashSet<>(List.of(server.getPlayerList().getPlayerNamesArray()));
                            names.addAll(List.of(server.getPlayerList().getWhiteListNames()));
                            names.stream().filter(name -> name.toLowerCase(java.util.Locale.ROOT).startsWith(typed)).forEach(builder::suggest);
                            return builder.buildFuture();
                        })
                        .executes(context -> other(context, StringArgumentType.getString(context, "player")))));
    }

    private static int self(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Укажите ник: /playtime <ник>"));
            return 0;
        }
        show(source, player.getGameProfile().getName(), ticks(player), true, false);
        return 1;
    }

    private static int other(CommandContext<CommandSourceStack> context, String name) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        UUID id = online != null ? online.getUUID() : lookup(server, name);
        if (id == null || (online == null && !Files.exists(statsFile(server, id)))) {
            source.sendFailure(Component.literal("Игрок " + name + " ещё не заходил на сервер"));
            return 0;
        }
        String shown = online != null ? online.getGameProfile().getName() : name;
        boolean own = source.getPlayer() != null && source.getPlayer().getUUID().equals(id);
        if (HIDDEN.contains(id) && !own && source.getPlayer() != null && !source.hasPermission(2)) {
            source.sendSuccess(() -> Component.literal("Игрок " + shown + " скрыл своё время игры").withStyle(ChatFormatting.GRAY), false);
            return 1;
        }
        show(source, shown, online != null ? ticks(online) : offlineTicks(server, id), online != null, HIDDEN.contains(id));
        return 1;
    }

    private static int hide(CommandSourceStack source, boolean hidden) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        if (hidden ? HIDDEN.add(player.getUUID()) : HIDDEN.remove(player.getUUID())) {
            save();
        }
        source.sendSuccess(() -> Component.literal(hidden ? "Ваше время игры скрыто от других игроков" : "Ваше время игры снова видно всем")
                .withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static void show(CommandSourceStack source, String name, long ticks, boolean online, boolean hidden) {
        Component line = Component.literal("Время игры ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(name).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(": ").withStyle(ChatFormatting.GOLD))
                .append(Component.literal(format(ticks)).withStyle(ChatFormatting.WHITE));
        if (online) {
            line = line.copy().append(Component.literal(" (в сети)").withStyle(ChatFormatting.GREEN));
        }
        if (hidden) {
            line = line.copy().append(Component.literal(" (скрыто)").withStyle(ChatFormatting.GRAY));
        }
        Component result = line;
        source.sendSuccess(() -> result, false);
    }

    static String format(long ticks) {
        long minutes = ticks / 20 / 60;
        long days = minutes / 1440;
        long hours = minutes / 60 % 24;
        minutes %= 60;
        StringBuilder text = new StringBuilder();
        if (days > 0) {
            text.append(days).append(" д ");
        }
        if (days > 0 || hours > 0) {
            text.append(hours).append(" ч ");
        }
        text.append(minutes).append(" мин");
        return ticks / 20 < 60 ? "меньше минуты" : text.toString();
    }

    private static long ticks(ServerPlayer player) {
        return player.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME));
    }

    private static Path statsFile(MinecraftServer server, UUID id) {
        return server.getWorldPath(LevelResource.PLAYER_STATS_DIR).resolve(id + ".json");
    }

    private static long offlineTicks(MinecraftServer server, UUID id) {
        try (Reader reader = Files.newBufferedReader(statsFile(server, id))) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            JsonObject custom = root.getAsJsonObject("stats").getAsJsonObject("minecraft:custom");
            return custom.get("minecraft:play_time").getAsLong();
        } catch (Exception e) {
            return 0L;
        }
    }

    private static UUID lookup(MinecraftServer server, String name) {
        Optional<GameProfile> cached = server.getProfileCache().get(name);
        if (cached.isPresent()) {
            return cached.get().getId();
        }
        return server.usesAuthentication() ? null : net.minecraft.core.UUIDUtil.createOfflinePlayerUUID(name);
    }

    private static void load() {
        HIDDEN.clear();
        try {
            if (Files.exists(FILE)) {
                try (Reader reader = Files.newBufferedReader(FILE)) {
                    Data data = GSON.fromJson(reader, Data.class);
                    if (data != null && data.hidden != null) {
                        data.hidden.forEach(id -> HIDDEN.add(UUID.fromString(id)));
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to read playtime.json", e);
        }
    }

    private static void save() {
        Data data = new Data();
        data.hidden = HIDDEN.stream().map(UUID::toString).toList();
        try (Writer writer = Files.newBufferedWriter(FILE)) {
            GSON.toJson(data, writer);
        } catch (Exception e) {
            LOGGER.error("Failed to write playtime.json", e);
        }
    }
}
