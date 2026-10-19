package io.github.lollip0529.biscuits;

import net.minecraft.block.BlockState;
import net.minecraft.block.MaterialColor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;

// top down terrain texture, 1 pixel per block. with a section picked it's a cave map - the first floor under open
// space in that slab, so tunnels and rooms show up underground
class Terrain implements AutoCloseable {
    static final int SURFACE = -1;

    final int originX, originZ, size;
    final Identifier id;
    private final MinecraftClient mc;
    private final NativeImageBackedTexture texture;
    private final BlockPos.Mutable pos = new BlockPos.Mutable();
    private int slice = Integer.MIN_VALUE;
    private int next;
    private int fresh;

    Terrain(MinecraftClient mc, int centerX, int centerZ, int radius) {
        this.mc = mc;
        this.size = radius * 2 + 1;
        this.originX = centerX - radius;
        this.originZ = centerZ - radius;
        this.texture = new NativeImageBackedTexture(this.size * 16, this.size * 16, true);
        this.id = new Identifier("biscuits", "terrain");
        mc.getTextureManager().registerTexture(this.id, this.texture);
    }

    void update(int slice) {
        if (slice != this.slice) {
            this.slice = slice;
            this.next = 0;
            this.fresh = 0;
        }

        // repaint fast after a slice change, then slowly keep it fresh
        int tiles = this.size * this.size;
        int budget = this.fresh < tiles ? 48 : 4;
        for (int i = 0; i < budget; i++) {
            this.paint(this.next % this.size, this.next / this.size);
            this.next = (this.next + 1) % tiles;
            this.fresh++;
        }
        this.texture.upload();
    }

    private void paint(int tx, int tz) {
        NativeImage img = this.texture.getImage();
        WorldChunk chunk = this.mc.world.getChunkManager().getWorldChunk(this.originX + tx, this.originZ + tz, false);
        if (chunk == null) {
            img.fillRect(tx * 16, tz * 16, 16, 16, 0);
            return;
        }

        int bx = (this.originX + tx) << 4, bz = (this.originZ + tz) << 4;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int rgb = this.slice == SURFACE ? this.surface(chunk, bx + lx, bz + lz) : this.cave(chunk, bx + lx, bz + lz);
                // NativeImage wants ABGR
                int abgr = 0xFF000000 | (rgb & 0xFF) << 16 | (rgb & 0xFF00) | (rgb >> 16 & 0xFF);
                img.setPixelColor(tx * 16 + lx, tz * 16 + lz, abgr);
            }
        }
    }

    private int surface(WorldChunk chunk, int x, int z) {
        int y = chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, x & 15, z & 15) - 1;
        if (y < 0) return 0x101010;
        return shade(this.color(chunk, x, y, z), 0.6f + 0.4f * y / 255f);
    }

    private int cave(WorldChunk chunk, int x, int z) {
        int bottom = this.slice << 4, top = bottom + 15, lowest = Math.max(0, bottom - 16);
        int y = top;
        // start at the top of the slab. solid there -> go down until there's air, fully solid slab is drawn dark
        if (!this.open(chunk, x, y, z)) {
            while (y >= bottom && !this.open(chunk, x, y, z)) y--;
            if (y < bottom) return shade(this.color(chunk, x, top, z), 0.3f);
        }
        // then drop to the floor, can go one section below
        while (y >= lowest && this.open(chunk, x, y, z)) y--;
        if (y < lowest) return 0x101010;
        return shade(this.color(chunk, x, y, z), 0.55f + 0.45f * (y - lowest) / (float) (top - lowest));
    }

    // air, or anything without a map colour (glass and stuff)
    private boolean open(WorldChunk chunk, int x, int y, int z) {
        BlockState state = chunk.getBlockState(this.pos.set(x, y, z));
        return state.isAir() || state.getTopMaterialColor(chunk, this.pos) == MaterialColor.CLEAR;
    }

    private int color(WorldChunk chunk, int x, int y, int z) {
        BlockState state = chunk.getBlockState(this.pos.set(x, y, z));
        return state.getTopMaterialColor(chunk, this.pos).color;
    }

    private static int shade(int rgb, float f) {
        int r = (int) ((rgb >> 16 & 255) * f), g = (int) ((rgb >> 8 & 255) * f), b = (int) ((rgb & 255) * f);
        return r << 16 | g << 8 | b;
    }

    @Override
    public void close() {
        this.texture.close();
    }
}
