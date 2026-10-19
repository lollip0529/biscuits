package io.github.lollip0529.biscuits;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.block.entity.MobSpawnerBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructurePiece;
import net.minecraft.structure.StructureStart;
import net.minecraft.util.Util;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.FlatChunkGenerator;
import net.minecraft.world.gen.chunk.FlatChunkGeneratorConfig;
import net.minecraft.world.gen.feature.DefaultFeatureConfig;
import net.minecraft.world.gen.feature.StrongholdFeature;
import net.minecraft.world.gen.feature.StructureFeature;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

// stronghold rooms + the starter spot. singleplayer worlds have them in the server's structure starts. the practice
// map strips those out of its chunks, but practice-bank.json still has each arena's seed, start chunk and offset, so
// the rooms get regenerated with the game's own generator. other maps can put a stronghold-pieces.json next to
// level.dat instead
public final class Strongholds {
    // StrongholdTrainer's names
    private static final Map<String, String> NAMES = new HashMap<>();

    static {
        String[] pairs = {"shstart", "Start", "sh5c", "FiveWayCrossing", "shcc", "ChestCorridor", "shfc", "SmallCorridor",
                "shlt", "LeftTurn", "shli", "Library", "shpr", "PortalRoom", "shph", "PrisonHall", "shrt", "RightTurn",
                "shrc", "SquareRoom", "shsd", "SpiralStaircase", "shs", "Corridor", "shssd", "Stairs"};
        for (int i = 0; i < pairs.length; i += 2) NAMES.put(pairs[i], pairs[i + 1]);
    }

    public static final class Piece {
        public final String type;
        public final BlockBox box;
        public final Direction facing;
        public final int depth;

        Piece(String type, BlockBox box, Direction facing, int depth) {
            this.type = type;
            this.box = box;
            this.facing = facing;
            this.depth = depth;
        }

        public String name() {
            return NAMES.getOrDefault(this.type, this.type);
        }

        // StructurePiece.applyX/Y/ZTransform
        BlockPos local(int x, int y, int z) {
            BlockBox b = this.box;
            if (this.facing == null) return new BlockPos(x, y, z);
            switch (this.facing) {
                case NORTH: return new BlockPos(b.minX + x, b.minY + y, b.maxZ - z);
                case SOUTH: return new BlockPos(b.minX + x, b.minY + y, b.minZ + z);
                case WEST: return new BlockPos(b.maxX - z, b.minY + y, b.minZ + x);
                default: return new BlockPos(b.minX + z, b.minY + y, b.minZ + x);
            }
        }
    }

    public static final class Stronghold {
        // feet, null if the first piece isn't the staircase
        public final Vec3d starter;
        public final List<Piece> pieces;
        public final BlockBox bounds = BlockBox.empty();

        Stronghold(Vec3d starter, List<Piece> pieces) {
            this.starter = starter;
            this.pieces = pieces;
            for (Piece p : pieces) this.bounds.encompass(p.box);
        }

        public Vec3d eye() {
            return this.starter.add(0, 1.62, 0);
        }
    }

    private static volatile List<Stronghold> server = Collections.emptyList();
    private static volatile boolean scanning;
    private static volatile List<Stronghold> file = Collections.emptyList();
    private static volatile List<Arena> arenas = Collections.emptyList();
    private static volatile boolean reading;
    private static Path savePath;
    private static long saveStamp;
    private static long refreshed;

    // the generator only asks for the sea level, 63 same as the overworld the arenas came from
    private static final ChunkGenerator SEA_LEVEL = new FlatChunkGenerator(FlatChunkGeneratorConfig.getDefaultConfig());

    // where an arena was generated from and how far it got moved
    private static final class Arena {
        final long seed;
        final int chunkX, chunkZ, dx, dz;
        final int[] area;
        final BlockPos target;
        final Vec3d starter;
        Stronghold stronghold;
        boolean rejected;

        Arena(long seed, int chunkX, int chunkZ, int dx, int dz, int[] area, BlockPos target, Vec3d starter) {
            this.seed = seed;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.dx = dx;
            this.dz = dz;
            this.area = area;
            this.target = target;
            this.starter = starter;
        }
    }

    private Strongholds() {
    }

    public static List<Stronghold> all() {
        List<Stronghold> out = new ArrayList<>(file);
        for (Arena a : arenas) if (a.stronghold != null) out.add(a.stronghold);
        out.addAll(server);
        return out;
    }

    // StrongholdFeature.Start.init, the way worldgen runs it for a stronghold starting in that chunk
    private static List<StructurePiece> generate(long seed, int chunkX, int chunkZ) {
        StrongholdFeature.Start start = new StrongholdFeature.Start(StructureFeature.STRONGHOLD, chunkX, chunkZ, BlockBox.empty(), 0, seed);
        start.init(SEA_LEVEL, null, chunkX, chunkZ, null, DefaultFeatureConfig.INSTANCE);
        return start.getChildren();
    }

    private static List<Piece> pieces(List<StructurePiece> children, int dx, int dz) {
        List<Piece> out = new ArrayList<>();
        for (StructurePiece p : children) {
            BlockBox b = p.getBoundingBox();
            out.add(new Piece(Registry.STRUCTURE_PIECE.getId(p.getType()).getPath(), new BlockBox(b.minX + dx, b.minY, b.minZ + dz, b.maxX + dx, b.maxY, b.maxZ + dz), p.getFacing(), p.getLength()));
        }
        return out;
    }

    // gives nearby arenas their rooms. has to be on the render thread, the generator keeps state in statics
    private static void resolve(World world, Vec3d cam, double reach) {
        for (Arena a : arenas) {
            if (a.stronghold != null || a.rejected) continue;
            if (cam.x < a.area[0] - reach || cam.x > a.area[2] + 1 + reach || cam.z < a.area[1] - reach || cam.z > a.area[3] + 1 + reach) continue;
            List<Piece> pieces = pieces(generate(a.seed, a.chunkX, a.chunkZ), a.dx, a.dz);
            boolean portal = false;
            for (Piece p : pieces) portal |= p.type.equals("shpr") && p.box.contains(a.target);
            // portal room has to hold the bank's target or it's the wrong stronghold
            if (!portal) {
                a.rejected = true;
                continue;
            }
            // and the spawner has to actually be there, otherwise try again next refresh
            if (world.getBlockEntity(a.target) instanceof MobSpawnerBlockEntity) a.stronghold = new Stronghold(a.starter, pieces);
        }
    }

    public static Stronghold containing(BlockPos pos) {
        for (Stronghold s : all()) {
            if (!s.bounds.contains(pos)) continue;
            for (Piece p : s.pieces) if (p.box.contains(pos)) return s;
        }
        return null;
    }

    public static Piece room(BlockPos pos) {
        Stronghold s = containing(pos);
        if (s == null) return null;
        for (Piece p : s.pieces) if (p.box.contains(pos)) return p;
        return null;
    }

    // every 5s while esp is on, and straight away when it's turned on
    public static void refresh(MinecraftClient mc, boolean force) {
        long now = Util.getMeasuringTimeMs();
        if (!force && now - refreshed < 5000) return;
        refreshed = now;
        IntegratedServer srv = mc.getServer();
        if (srv == null || mc.player == null || mc.world == null) {
            server = Collections.emptyList();
            file = Collections.emptyList();
            arenas = Collections.emptyList();
            return;
        }
        readSave(srv.getSavePath(WorldSavePath.ROOT));
        resolve(mc.world, mc.gameRenderer.getCamera().getPos(), (mc.options.viewDistance + 1) * 16);
        if (scanning) return;
        scanning = true;
        RegistryKey<World> dim = mc.world.getRegistryKey();
        ChunkPos center = new ChunkPos(mc.player.getBlockPos());
        int r = mc.options.viewDistance;
        srv.execute(() -> {
            try {
                server = scan(srv.getWorld(dim), center, r);
            } finally {
                scanning = false;
            }
        });
    }

    // server thread. only chunks that already exist, so nothing gets generated
    private static List<Stronghold> scan(ServerWorld world, ChunkPos center, int r) {
        if (world == null) return Collections.emptyList();
        Map<StructureStart<?>, Boolean> seen = new IdentityHashMap<>();
        List<Stronghold> out = new ArrayList<>();
        for (int x = center.x - r; x <= center.x + r; x++) {
            for (int z = center.z - r; z <= center.z + r; z++) {
                Chunk chunk = world.getChunk(x, z, ChunkStatus.STRUCTURE_REFERENCES, false);
                if (chunk == null) continue;
                for (long ref : chunk.getStructureReferences(StructureFeature.STRONGHOLD)) {
                    Chunk holder = world.getChunk(ChunkPos.getPackedX(ref), ChunkPos.getPackedZ(ref), ChunkStatus.STRUCTURE_STARTS, false);
                    if (holder == null) continue;
                    StructureStart<?> start = holder.getStructureStart(StructureFeature.STRONGHOLD);
                    if (start == null || !start.hasChildren() || seen.put(start, true) != null) continue;
                    out.add(copy(world, start));
                }
            }
        }
        return out;
    }

    private static Stronghold copy(World world, StructureStart<?> start) {
        List<Piece> pieces = pieces(start.getChildren(), 0, 0);
        Piece first = pieces.get(0);
        // explainer's scan spot: out the bottom door of the staircase, 2nd block into the five way
        Vec3d starter = first.type.equals("shstart") ? stand(world, first.local(2, 1, 6)) : null;
        return new Stronghold(starter, pieces);
    }

    // caves can eat the floor under it, so find the closest place to stand in that column
    private static Vec3d stand(World world, BlockPos feet) {
        for (int dy : new int[]{0, -1, 1, -2, 2, -3, 3}) {
            BlockPos p = feet.up(dy);
            if (!opaque(world, p) && !opaque(world, p.up()) && opaque(world, p.down())) return new Vec3d(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
        }
        return new Vec3d(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
    }

    private static boolean opaque(World world, BlockPos pos) {
        return world.getBlockState(pos).isOpaqueFullCube(world, pos);
    }

    private static void readSave(Path root) {
        Path own = root.resolve("stronghold-pieces.json"), bank = root.resolve("practice-bank.json");
        long stamp = stamp(own) * 31 + stamp(bank);
        if (reading || root.equals(savePath) && stamp == saveStamp) return;
        savePath = root;
        saveStamp = stamp;
        reading = true;
        // the bank is a few MB, read it off thread
        Util.getServerWorkerExecutor().execute(() -> {
            try {
                file = Files.exists(own) ? readList(own) : Collections.emptyList();
            } catch (Exception e) {
                file = Collections.emptyList();
            }
            try {
                arenas = Files.exists(bank) ? readBank(bank) : Collections.emptyList();
            } catch (Exception e) {
                arenas = Collections.emptyList();
            } finally {
                reading = false;
            }
        });
    }

    private static long stamp(Path path) {
        try {
            return Files.exists(path) ? Files.getLastModifiedTime(path).toMillis() : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private static List<Arena> readBank(Path bank) throws Exception {
        List<Arena> out = new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(bank, StandardCharsets.UTF_8)) {
            for (JsonElement e : new JsonParser().parse(reader).getAsJsonObject().getAsJsonArray("arenas")) {
                JsonObject a = e.getAsJsonObject();
                // capture_id = seed_chunkX_chunkZ
                String[] capture = a.get("capture_id").getAsString().split("_");
                JsonArray area = a.getAsJsonArray("area"), target = a.getAsJsonArray("target"), at = a.getAsJsonArray("destination");
                out.add(new Arena(Long.parseLong(a.get("seed").getAsString()), Integer.parseInt(capture[1]), Integer.parseInt(capture[2]),
                        a.get("dx").getAsInt(), a.get("dz").getAsInt(),
                        new int[]{area.get(0).getAsInt(), area.get(1).getAsInt(), area.get(2).getAsInt(), area.get(3).getAsInt()},
                        new BlockPos(target.get(0).getAsInt(), target.get(1).getAsInt(), target.get(2).getAsInt()),
                        new Vec3d(at.get(0).getAsDouble(), at.get(1).getAsDouble(), at.get(2).getAsDouble())));
            }
        }
        return out;
    }

    // {"strongholds":[{"starter":[x,y,z],"pieces":[["sh5c",minX,minY,minZ,maxX,maxY,maxZ,facing,depth],...]}]}
    private static List<Stronghold> readList(Path path) throws Exception {
        List<Stronghold> out = new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            for (JsonElement e : new JsonParser().parse(reader).getAsJsonObject().getAsJsonArray("strongholds")) {
                JsonObject s = e.getAsJsonObject();
                JsonArray at = s.getAsJsonArray("starter");
                List<Piece> pieces = new ArrayList<>();
                for (JsonElement pe : s.getAsJsonArray("pieces")) {
                    JsonArray p = pe.getAsJsonArray();
                    int o = p.get(7).getAsInt();
                    pieces.add(new Piece(p.get(0).getAsString(), new BlockBox(p.get(1).getAsInt(), p.get(2).getAsInt(), p.get(3).getAsInt(),
                            p.get(4).getAsInt(), p.get(5).getAsInt(), p.get(6).getAsInt()), o < 0 ? null : Direction.fromHorizontal(o), p.get(8).getAsInt()));
                }
                out.add(new Stronghold(new Vec3d(at.get(0).getAsDouble(), at.get(1).getAsDouble(), at.get(2).getAsDouble()), pieces));
            }
        }
        return out;
    }
}
