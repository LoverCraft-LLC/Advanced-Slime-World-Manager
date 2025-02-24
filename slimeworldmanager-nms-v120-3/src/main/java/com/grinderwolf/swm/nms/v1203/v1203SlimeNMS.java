package com.grinderwolf.swm.nms.v1203;

import com.flowpowered.nbt.CompoundTag;
import com.grinderwolf.swm.api.loaders.SlimeLoader;
import com.grinderwolf.swm.api.world.SlimeChunk;
import com.grinderwolf.swm.api.world.SlimeWorld;
import com.grinderwolf.swm.api.world.properties.SlimeProperties;
import com.grinderwolf.swm.api.world.properties.SlimePropertyMap;
import com.grinderwolf.swm.nms.SlimeNMS;
import com.grinderwolf.swm.nms.world.SlimeLoadedWorld;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.Lifecycle;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import lombok.Getter;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.dedicated.DedicatedServerProperties;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.LevelVersion;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.minecraft.world.level.storage.WorldData;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.v1_20_R3.CraftServer;
import org.bukkit.craftbukkit.v1_20_R3.CraftWorld;
import org.bukkit.craftbukkit.v1_20_R3.scoreboard.CraftScoreboardManager;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Getter
public class v1203SlimeNMS implements SlimeNMS {

    private static final Logger LOGGER = LogManager.getLogger("SWM");
    public static boolean isPaperMC;

    public static LevelStorageSource CUSTOM_LEVEL_STORAGE;

    static {
        try {
            Path path = Files.createTempDirectory("swm-" + UUID.randomUUID().toString().substring(0, 5)).toAbsolutePath();
            CUSTOM_LEVEL_STORAGE = new LevelStorageSource(path, path, LevelStorageSource.parseValidator(path), DataFixers.getDataFixer());

            FileUtils.forceDeleteOnExit(path.toFile());

        } catch (IOException ex) {
            throw new IllegalStateException("Couldn't create dummy file directory.", ex);
        }
    }

    private final byte worldVersion = 0x08;

    private boolean injectFakeDimensions = false;

    private CustomWorldServer defaultWorld;
    private CustomWorldServer defaultNetherWorld;
    private CustomWorldServer defaultEndWorld;


    public v1203SlimeNMS(boolean isPaper) {
        try {
            isPaperMC = isPaper;
            CraftCLSMBridge.initialize(this);
        } catch (NoClassDefFoundError ex) {
            LOGGER.error("Failed to find ClassModifier classes. Are you sure you installed it correctly?", ex);
            Bukkit.getServer().shutdown();
        }
    }

    @Override
    public Object injectDefaultWorlds() {
        if (!injectFakeDimensions) {
            return null;
        }

        System.out.println("INJECTING: " + defaultWorld + " " + defaultNetherWorld + " " + defaultEndWorld);

        MinecraftServer server = MinecraftServer.getServer();
        server.server.scoreboardManager = new CraftScoreboardManager(server, server.getScoreboard());

        if (defaultWorld != null) {
            registerWorld(defaultWorld);
        }
        if (defaultNetherWorld != null) {
            registerWorld(defaultNetherWorld);
        }
        if (defaultEndWorld != null) {
            registerWorld(defaultEndWorld);
        }

        injectFakeDimensions = false;
        return new MappedRegistry<>(Registries.ACTIVITY, Lifecycle.stable());
    }

    @Override
    public void setDefaultWorlds(SlimeWorld normalWorld, SlimeWorld netherWorld, SlimeWorld endWorld) {
        MinecraftServer server = MinecraftServer.getServer();

        LevelSettings worldsettings;
        WorldOptions generatorOptions;

        DedicatedServerProperties dedicatedserverproperties = ((DedicatedServer) server).getProperties();

        worldsettings = new LevelSettings(
                dedicatedserverproperties.levelName,
                dedicatedserverproperties.gamemode,
                dedicatedserverproperties.hardcore,
                dedicatedserverproperties.difficulty,
                false,
                new GameRules(),
                server.getWorldData().getDataConfiguration()
        );

        generatorOptions = server.getWorldData().worldGenOptions();

        WorldData data = new PrimaryLevelData(worldsettings, generatorOptions, PrimaryLevelData.SpecialWorldProperty.NONE, Lifecycle.stable());

        server.getWorldData().setDataConfiguration(data.getDataConfiguration());

        if (normalWorld != null) {
            normalWorld.getPropertyMap().setValue(SlimeProperties.ENVIRONMENT, World.Environment.NORMAL.toString().toLowerCase());
            defaultWorld = createCustomWorld(normalWorld, Level.OVERWORLD);
            injectFakeDimensions = true;
        }

        if (netherWorld != null) {
            netherWorld.getPropertyMap().setValue(SlimeProperties.ENVIRONMENT, World.Environment.NETHER.toString().toLowerCase());
            defaultNetherWorld = createCustomWorld(netherWorld, Level.NETHER);
            injectFakeDimensions = true;
        }

        if (endWorld != null) {
            endWorld.getPropertyMap().setValue(SlimeProperties.ENVIRONMENT, World.Environment.THE_END.toString().toLowerCase());
            defaultEndWorld = createCustomWorld(endWorld, Level.END);
            injectFakeDimensions = true;
        }

    }

    @Override
    public void generateWorld(SlimeWorld world) {
        String worldName = world.getName();

        if (Bukkit.getWorld(worldName) != null) {
            throw new IllegalArgumentException("World " + worldName + " already exists! Maybe it's an outdated SlimeWorld object?");
        }

        CustomWorldServer server = createCustomWorld(world, null);
        registerWorld(server);
    }

    @Override
    public SlimeWorld getSlimeWorld(World world) {
        CraftWorld craftWorld = (CraftWorld) world;

        if (!(craftWorld.getHandle() instanceof CustomWorldServer worldServer)) {
            return null;
        }

        return worldServer.getSlimeWorld();
    }

    public void registerWorld(CustomWorldServer server) {
        MinecraftServer mcServer = MinecraftServer.getServer();
        mcServer.initWorld(server, server.serverLevelData, mcServer.getWorldData(), server.serverLevelData.worldGenOptions());

        mcServer.addLevel(server);
    }

    private CustomWorldServer createCustomWorld(SlimeWorld world, @Nullable ResourceKey<Level> dimensionOverride) {
        v1203SlimeWorld nmsWorld = (v1203SlimeWorld) world;
        String worldName = world.getName();

        PrimaryLevelData worldDataServer = createWorldData(world);
        World.Environment environment = getEnvironment(world);
        ResourceKey<LevelStem> dimension = switch (environment) {
            case NORMAL -> LevelStem.OVERWORLD;
            case NETHER -> LevelStem.NETHER;
            case THE_END -> LevelStem.END;
            default -> throw new IllegalArgumentException("Unknown dimension supplied");
        };

        Registry<LevelStem> registryMaterials = worldDataServer.customDimensions;
        LevelStem worldDimension = registryMaterials.get(dimension);


        Holder<DimensionType> type = null;
        {
            DimensionType predefinedType = Objects.requireNonNull(worldDimension).type().value();

            OptionalLong fixedTime = switch (environment) {
                case NORMAL -> OptionalLong.empty();
                case NETHER -> OptionalLong.of(18000L);
                case THE_END -> OptionalLong.of(6000L);
                case CUSTOM -> throw new UnsupportedOperationException();
            };
            double light = switch (environment) {
                case NORMAL, THE_END -> 0;
                case NETHER -> 0.1;
                case CUSTOM -> throw new UnsupportedOperationException();
            };

            TagKey<Block> infiniburn = switch (environment) {
                case NORMAL -> BlockTags.INFINIBURN_OVERWORLD;
                case NETHER -> BlockTags.INFINIBURN_NETHER;
                case THE_END -> BlockTags.INFINIBURN_END;
                case CUSTOM -> throw new UnsupportedOperationException();
            };

            type = Holder.direct(new DimensionType(fixedTime, predefinedType.hasSkyLight(), predefinedType.hasCeiling(), predefinedType.ultraWarm(),
                    predefinedType.natural(), predefinedType.coordinateScale(), predefinedType.bedWorks(), predefinedType.respawnAnchorWorks(),
                    predefinedType.minY(), predefinedType.height(), predefinedType.logicalHeight(), predefinedType.infiniburn(),
                    predefinedType.effectsLocation(), predefinedType.ambientLight(), predefinedType.monsterSettings()));
        }

        ChunkGenerator chunkGenerator = worldDimension.generator();

        ResourceKey<Level> worldKey = dimensionOverride == null ? ResourceKey.create(Registries.DIMENSION,
                new ResourceLocation(worldName.toLowerCase(Locale.ENGLISH))) : dimensionOverride;

        CustomWorldServer level;
        CraftServer server = MinecraftServer.getServer().server;

        try {
            level = new CustomWorldServer(nmsWorld, worldDataServer, worldKey, dimension, worldDimension,
                    environment, server.getGenerator(worldName), server.getBiomeProvider(worldName));
            nmsWorld.setHandle(level);
        } catch (IOException ex) {
            throw new RuntimeException(ex); // TODO do something better with this?
        }

        level.setReady(true);
        level.setSpawnSettings(world.getPropertyMap().getValue(SlimeProperties.ALLOW_MONSTERS), world.getPropertyMap().getValue(SlimeProperties.ALLOW_ANIMALS));

        return level;
    }

    private World.Environment getEnvironment(SlimeWorld world) {
        return World.Environment.valueOf(world.getPropertyMap().getValue(SlimeProperties.ENVIRONMENT).toUpperCase());
    }

    private PrimaryLevelData createWorldData(SlimeWorld world) {
        String worldName = world.getName();
        CompoundTag extraData = world.getExtraData();
        PrimaryLevelData worldDataServer;
        net.minecraft.nbt.CompoundTag extraTag = (net.minecraft.nbt.CompoundTag) Converter.convertTag(extraData);
        MinecraftServer mcServer = MinecraftServer.getServer();
        DedicatedServerProperties serverProps = ((DedicatedServer) mcServer).getProperties();

        // TODO: see about scrapping this, seems to be only used for the importer?
        if (extraTag.getTagType("LevelData") == Tag.TAG_COMPOUND) {
            net.minecraft.nbt.CompoundTag levelData = extraTag.getCompound("LevelData");
            int dataVersion = levelData.getTagType("DataVersion") == Tag.TAG_INT ? levelData.getInt("DataVersion") : -1;

            Dynamic<Tag> dynamic = mcServer.getFixerUpper().update(
                    References.LEVEL,
                    new Dynamic<>(NbtOps.INSTANCE, levelData),
                    dataVersion,
                    SharedConstants.getCurrentVersion().getDataVersion().getVersion()
            );

            LevelVersion levelVersion = LevelVersion.parse(dynamic);
            LevelSettings worldSettings = LevelSettings.parse(dynamic, mcServer.getWorldData().getDataConfiguration());

            worldDataServer = PrimaryLevelData.parse(
                    dynamic,
                    mcServer.getWorldData().getLevelSettings(),
                    PrimaryLevelData.SpecialWorldProperty.NONE,
                    mcServer.getWorldData().worldGenOptions(),
                    Lifecycle.stable()
            );
        } else {

            // Game rules
            Optional<CompoundTag> gameRules = extraData.getAsCompoundTag("gamerules");
            GameRules rules = new GameRules();

            gameRules.ifPresent(compoundTag -> {
                net.minecraft.nbt.CompoundTag compound = ((net.minecraft.nbt.CompoundTag) Converter.convertTag(compoundTag));
                Map<String, GameRules.Key<?>> gameRuleKeys = CraftWorld.getGameRulesNMS();

                compound.getAllKeys().forEach(gameRule -> {
                    if (gameRuleKeys.containsKey(gameRule)) {
                        GameRules.Value<?> gameRuleValue = rules.getRule(gameRuleKeys.get(gameRule));
                        String theValue = compound.getString(gameRule);
                        gameRuleValue.deserialize(theValue);
                        gameRuleValue.onChanged(null);
                    }
                });
            });

            LevelSettings worldSettings = new LevelSettings(worldName, serverProps.gamemode, false,
                    serverProps.difficulty, false, rules, mcServer.getWorldData().getDataConfiguration());

            worldDataServer = new PrimaryLevelData(worldSettings, mcServer.getWorldData().worldGenOptions(), PrimaryLevelData.SpecialWorldProperty.NONE, Lifecycle.stable());
        }

        worldDataServer.checkName(worldName);
        worldDataServer.setModdedInfo(mcServer.getServerModName(), mcServer.getModdedStatus().shouldReportAsModified());
        worldDataServer.setInitialized(true);

        return worldDataServer;
    }

    @Override
    public SlimeLoadedWorld createSlimeWorld(SlimeLoader loader, String worldName, Long2ObjectOpenHashMap<SlimeChunk> chunks, CompoundTag extraCompound, List<CompoundTag> mapList, byte worldVersion, SlimePropertyMap worldPropertyMap, boolean readOnly, boolean lock, Long2ObjectOpenHashMap<List<CompoundTag>> entities) {
        return new v1203SlimeWorld(this, worldVersion, loader, worldName, chunks, extraCompound, worldPropertyMap, readOnly, lock, entities);
    }
}